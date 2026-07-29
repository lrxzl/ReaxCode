package cn.net.xiangxiang.seeker;

import android.app.ActivityManager;
import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class DevToolsPortForwarder {

    private static final String TAG = "PortForwarder";
    private static final int LOCAL_TCP_PORT = 9222;
    private static final String SOCKET_PREFIX = "webview_devtools_remote";

    // 连接 WebView socket 时的最大重试窗口
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int RETRY_INTERVAL_MS = 300;

    // HTTP 响应头与 chunked/body 边界检测用
    private static final byte[] HEADER_END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private final Context context;
    private ServerSocket serverSocket;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // 接受 TCP 客户端的线程池（一般 1~2 个足够）
    private final ExecutorService acceptPool = Executors.newSingleThreadExecutor();
    // 处理每条连接的桥接线程池
    private final ExecutorService bridgePool = Executors.newCachedThreadPool();

    public DevToolsPortForwarder(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "代理服务已在运行");
            return;
        }
        acceptPool.submit(this::runAcceptLoop);
    }

    private void runAcceptLoop() {
        try {
            serverSocket = new ServerSocket(LOCAL_TCP_PORT);
            Log.i(TAG, "TCP 代理服务已启动，监听端口: " + LOCAL_TCP_PORT
                + "，可用 playwright-cli attach --cdp http://localhost:" + LOCAL_TCP_PORT);

            while (isRunning.get()) {
                Socket tcpClient = serverSocket.accept();
                tcpClient.setKeepAlive(true);
                tcpClient.setTcpNoDelay(true);   // CDP 消息小且频繁，禁用 Nagle
                tcpClient.setSoTimeout(0);
                Log.i(TAG, "客户端已连接: " + tcpClient.getRemoteSocketAddress());
                bridgePool.submit(() -> handleClient(tcpClient));
            }
        } catch (IOException e) {
            if (isRunning.get()) {
                Log.e(TAG, "代理服务异常: " + e.getMessage(), e);
            }
        } finally {
            isRunning.set(false);
        }
    }

    private void handleClient(Socket tcpClient) {
        LocalSocket localSocket = null;
        try {
            // WebView socket 可能晚于 Playwright 连接创建，重试一段时间
            long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
            IOException lastError = null;

            while (System.currentTimeMillis() < deadline) {
                List<String> names = getPossibleSocketNames();
                for (String name : names) {
                    LocalSocket candidate = new LocalSocket();
                    try {
                        candidate.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
                        Log.i(TAG, "成功连接到 WebView Socket: " + name);
                        localSocket = candidate;
                        break;
                    } catch (IOException e) {
                        lastError = e;
                        closeQuietly(candidate);
                    }
                }
                if (localSocket != null) break;

                try {
                    Thread.sleep(RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (localSocket == null) {
                Log.e(TAG, "无法连接到任何 WebView Socket，请确保 WebView 调试已开启。"
                    + (lastError != null ? " 最后错误: " + lastError.getMessage() : ""));
                // 给客户端一个友好的 HTTP 502 错误，便于 Playwright 报错时排查
                writeBadGateway(tcpClient);
                closeQuietly(tcpClient);
                return;
            }

            bridge(tcpClient, localSocket);
        } catch (Exception e) {
            Log.e(TAG, "处理客户端异常", e);
            closeQuietly(tcpClient);
        }
    }

    /**
     * 桥接 TCP 与 LocalSocket。
     * 对 Playwright 的 /json/version 等 HTTP 请求，会重写 webSocketDebuggerUrl 的 host，
     * 使其指向 localhost:9222，避免 WebView 返回 0.0.0.0 之类的地址导致 Playwright 连接失败。
     */
    private void bridge(Socket tcpSocket, LocalSocket localSocket) {
        final AtomicBoolean closed = new AtomicBoolean(false);
        final Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                Log.d(TAG, "桥接关闭: " + tcpSocket.getRemoteSocketAddress());
                closeQuietly(tcpSocket);
                closeQuietly(localSocket);
            }
        };

        // TCP -> Local (上行)：Playwright 发出的请求，原样透传
        bridgePool.submit(() -> {
            try {
                transfer(tcpSocket.getInputStream(), localSocket.getOutputStream(), null, cleanup);
            } catch (IOException e) {
                Log.d(TAG, "上行流打开失败: " + e.getMessage());
                cleanup.run();
            }
        });

        // Local -> TCP (下行)：WebView 返回的响应，对 /json/* 的 JSON 响应做 host 重写
        bridgePool.submit(() -> {
            try {
                OutputStream tcpOut = tcpSocket.getOutputStream();
                InputStream localIn = localSocket.getInputStream();
                transfer(localIn, tcpOut, tcpSocket, cleanup);
            } catch (IOException e) {
                Log.d(TAG, "下行流打开失败: " + e.getMessage());
                cleanup.run();
            }
        });
    }

    /**
     * @param rewriteTcpOut 非 null 时，对 HTTP 响应体中的 ws://xxx:port/... 做重写为 ws://localhost:9222/...
     *                      仅用于下行（WebView -> Playwright）方向
     */
    private void transfer(InputStream in, OutputStream out, Socket rewriteTcpOut, Runnable cleanup) {
        byte[] buffer = new byte[8192];
        // 如果要重写，需要一个状态机解析 HTTP 响应头与 chunked 编码
        HttpRewriter rewriter = (rewriteTcpOut != null) ? new HttpRewriter(LOCAL_TCP_PORT) : null;

        try {
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                if (rewriter != null) {
                    byte[] rewritten = rewriter.consume(buffer, bytesRead);
                    if (rewritten != null && rewritten.length > 0) {
                        out.write(rewritten, 0, rewritten.length);
                    }
                } else {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            }
        } catch (IOException e) {
            // 连接断开（正常关闭或对端崩溃）
        } finally {
            cleanup.run();
        }
    }

    /**
     * 一个轻量的 HTTP 响应重写器：
     * - 解析 status line + headers
     * - 若 Content-Length 存在，重写 body 中的 "ws://host:port/" -> "ws://localhost:9222/"
     * - 若 Transfer-Encoding: chunked，重写每个 chunk 的 body
     * - 其他情况（如 WebSocket 升级后的二进制帧）原样透传
     * <p>
     * 这是为了修复 WebView 有时返回 webSocketDebuggerUrl: "ws://0.0.0.0:9222/..." 的问题，
     * Playwright 会试图连到 0.0.0.0 导致失败。
     */
    private static class HttpRewriter {
        private enum State { HEADER, BODY_FIXED, BODY_CHUNKED, BODY_EOF, PASSTHROUGH }

        private final int targetPort;
        private State state = State.HEADER;
        private final ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        private int remainingBody = -1;
        private int chunkRemaining = 0;
        private boolean chunkHeaderPending = true;

        HttpRewriter(int targetPort) {
            this.targetPort = targetPort;
        }

        byte[] consume(byte[] data, int len) throws IOException {
            if (state == State.PASSTHROUGH) {
                return data.length == len ? data : Arrays.copyOf(data, len);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int offset = 0;
            while (offset < len) {
                if (state == State.HEADER) {
                    int consumed = feedHeader(data, offset, len, out);
                    offset += consumed;
                } else if (state == State.BODY_FIXED) {
                    offset += feedFixedBody(data, offset, len, out);
                    if (remainingBody == 0) state = State.PASSTHROUGH;
                } else if (state == State.BODY_CHUNKED) {
                    offset += feedChunkedBody(data, offset, len, out);
                } else if (state == State.BODY_EOF) {
                    // 直接透传到连接关闭
                    out.write(data, offset, len - offset);
                    offset = len;
                } else { // PASSTHROUGH
                    out.write(data, offset, len - offset);
                    offset = len;
                }
            }
            return out.toByteArray();
        }

        private int feedHeader(byte[] data, int offset, int len, ByteArrayOutputStream out) throws IOException {
            int available = len - offset;
            int copyLen = Math.min(available, HEADER_END.length - headerBuf.size());
            if (copyLen > 0) headerBuf.write(data, offset, copyLen);

            // 检查是否已读到 \r\n\r\n
            byte[] hb = headerBuf.toByteArray();
            int idx = indexOf(hb, 0, hb.length, HEADER_END);
            if (idx < 0) {
                // 还没读完头部
                return copyLen;
            }

            // 找到头部结尾，重写头部
            String headers = new String(hb, 0, idx + 4, StandardCharsets.US_ASCII);
            String rewrittenHeaders = headers;

            // 解析 Content-Length 与 Transfer-Encoding
            String lower = headers.toLowerCase();
            int contentLength = -1;
            boolean chunked = false;
            int clIdx = lower.indexOf("content-length:");
            if (clIdx >= 0) {
                int eol = lower.indexOf('\n', clIdx);
                String val = lower.substring(clIdx + 15, eol).trim();
                try { contentLength = Integer.parseInt(val); } catch (NumberFormatException ignored) {}
            }
            if (lower.contains("transfer-encoding: chunked")) {
                chunked = true;
            }
            boolean isWebSocketUpgrade = lower.contains("upgrade: websocket");

            out.write(rewrittenHeaders.getBytes(StandardCharsets.US_ASCII));

            // 处理头部后可能还有部分 body
            int headerConsumed = (idx + 4);
            int alreadyInBuffer = hb.length - headerConsumed;
            int totalConsumed = copyLen;
            // 把 headerBuf 中超出头部的部分当作 body 处理
            if (alreadyInBuffer > 0) {
                byte[] leftover = Arrays.copyOfRange(hb, headerConsumed, hb.length);
                if (isWebSocketUpgrade) {
                    // WebSocket 升级响应，之后走二进制，不重写
                    out.write(leftover, 0, leftover.length);
                    state = State.PASSTHROUGH;
                } else if (chunked) {
                    state = State.BODY_CHUNKED;
                    chunkHeaderPending = true;
                    chunkRemaining = 0;
                    feedChunkedBody(leftover, 0, leftover.length, out);
                } else if (contentLength >= 0) {
                    state = State.BODY_FIXED;
                    remainingBody = contentLength;
                    feedFixedBody(leftover, 0, leftover.length, out);
                    if (remainingBody == 0) state = State.PASSTHROUGH;
                } else {
                    // 没有 Content-Length 也没有 chunked，body 直到 EOF
                    state = State.BODY_EOF;
                    out.write(leftover, 0, leftover.length);
                }
            } else {
                if (isWebSocketUpgrade) {
                    state = State.PASSTHROUGH;
                } else if (chunked) {
                    state = State.BODY_CHUNKED;
                    chunkHeaderPending = true;
                    chunkRemaining = 0;
                } else if (contentLength >= 0) {
                    state = State.BODY_FIXED;
                    remainingBody = contentLength;
                    if (remainingBody == 0) state = State.PASSTHROUGH;
                } else {
                    state = State.BODY_EOF;
                }
            }
            return totalConsumed;
        }

        private int feedFixedBody(byte[] data, int offset, int len, ByteArrayOutputStream out) {
            int available = len - offset;
            int take = Math.min(available, remainingBody);
            byte[] rewritten = rewriteHost(data, offset, take);
            out.write(rewritten, 0, rewritten.length);
            remainingBody -= take;
            return take;
        }

        private int feedChunkedBody(byte[] data, int offset, int len, ByteArrayOutputStream out) {
            int consumed = 0;
            while (consumed < (len - offset)) {
                int remaining = len - offset - consumed;
                if (chunkHeaderPending) {
                    // 读取 chunk 大小行：XXXX\r\n
                    int eol = indexOf(data, offset + consumed, offset + consumed + remaining, (byte) '\n');
                    if (eol < 0) {
                        // 需要更多数据 - 简单起见透传并等待
                        out.write(data, offset + consumed, remaining);
                        consumed += remaining;
                        break;
                    }
                    int lineLen = eol - (offset + consumed) + 1;
                    String sizeLine = new String(data, offset + consumed, lineLen, StandardCharsets.US_ASCII).trim();
                    int semi = sizeLine.indexOf(';');
                    if (semi >= 0) sizeLine = sizeLine.substring(0, semi);
                    try {
                        chunkRemaining = Integer.parseInt(sizeLine.trim(), 16);
                    } catch (NumberFormatException e) {
                        chunkRemaining = 0;
                    }
                    // 透传 chunk 头
                    out.write(data, offset + consumed, lineLen);
                    consumed += lineLen;
                    chunkHeaderPending = false;
                    if (chunkRemaining == 0) {
                        // 最后一个 chunk，后续 trailer 直到 \r\n\r\n，简单起见切到 PASSTHROUGH
                        state = State.PASSTHROUGH;
                        // 写入剩余
                        out.write(data, offset + consumed, len - offset - consumed);
                        consumed = len - offset;
                        break;
                    }
                } else {
                    int take = Math.min(remaining, chunkRemaining);
                    byte[] rewritten = rewriteHost(data, offset + consumed, take);
                    out.write(rewritten, 0, rewritten.length);
                    consumed += take;
                    chunkRemaining -= take;
                    if (chunkRemaining == 0) {
                        // 期望接下来是 \r\n，然后下一个 chunk 头
                        // 读取 \r\n
                        if (remaining - take >= 2 && data[offset + consumed] == '\r' && data[offset + consumed + 1] == '\n') {
                            out.write(data, offset + consumed, 2);
                            consumed += 2;
                        }
                        chunkHeaderPending = true;
                    }
                }
            }
            return consumed;
        }

        private byte[] rewriteHost(byte[] data, int offset, int len) {
            // 简单粗暴：将 "ws://host:port/" 替换为 "ws://localhost:9222/"
            // host 可能是 0.0.0.0、127.0.0.1、设备 IP 等
            String s = new String(data, offset, len, StandardCharsets.US_ASCII);
            String rewritten = s.replaceAll("ws://[^/\"]+:\\d+/", "ws://localhost:" + targetPort + "/");
            return rewritten.getBytes(StandardCharsets.US_ASCII);
        }

        private static int indexOf(byte[] haystack, int from, int to, byte[] needle) {
            outer:
            for (int i = from; i <= to - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (haystack[i + j] != needle[j]) continue outer;
                }
                return i;
            }
            return -1;
        }

        private static int indexOf(byte[] haystack, int from, int to, byte b) {
            for (int i = from; i < to; i++) {
                if (haystack[i] == b) return i;
            }
            return -1;
        }
    }

    private void writeBadGateway(Socket tcpClient) {
        try {
            OutputStream os = tcpClient.getOutputStream();
            String body = "{\"error\":\"No WebView devtools socket available\"}";
            String resp = "HTTP/1.1 502 Bad Gateway\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "Connection: close\r\n\r\n" + body;
            os.write(resp.getBytes(StandardCharsets.US_ASCII));
            os.flush();
        } catch (IOException ignored) {}
    }

    private List<String> getPossibleSocketNames() {
        List<String> names = new ArrayList<>();
        names.add(SOCKET_PREFIX);

        int currentPid = android.os.Process.myPid();
        names.add(SOCKET_PREFIX + "_" + currentPid);

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process.pid != currentPid) {
                        names.add(SOCKET_PREFIX + "_" + process.pid);
                    }
                }
            }
        }
        return names;
    }

    public void stop() {
        isRunning.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "关闭服务异常", e);
        }
        bridgePool.shutdownNow();
        acceptPool.shutdownNow();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (IOException ignored) {}
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static void closeQuietly(LocalSocket s) {
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }
}
