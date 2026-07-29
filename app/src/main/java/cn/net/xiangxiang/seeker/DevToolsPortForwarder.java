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
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class DevToolsPortForwarder {

    private static final String TAG = "PortForwarder";
    private static final int LOCAL_TCP_PORT = 9222;
    private static final String SOCKET_PREFIX = "webview_devtools_remote";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int RETRY_INTERVAL_MS = 300;
    private static final byte[] HEADER_END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private final Context context;
    private final List<ServerSocket> serverSockets = new ArrayList<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private final ExecutorService acceptPool = Executors.newFixedThreadPool(2);
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
            tryBind("0.0.0.0");
            tryBind("::");

            if (serverSockets.isEmpty()) {
                throw new IOException("无法绑定任何网络接口");
            }

            Log.i(TAG, "TCP 代理服务已启动，监听端口: " + LOCAL_TCP_PORT);

            for (ServerSocket ss : serverSockets) {
                acceptPool.submit(() -> acceptLoop(ss));
            }
        } catch (IOException e) {
            if (isRunning.get()) {
                Log.e(TAG, "代理服务异常: " + e.getMessage(), e);
            }
            isRunning.set(false);
        }
    }

    private void tryBind(String host) {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(host, LOCAL_TCP_PORT));
            serverSockets.add(ss);
            Log.i(TAG, "成功监听: " + host + ":" + LOCAL_TCP_PORT);
        } catch (IOException e) {
            Log.w(TAG, "监听 " + host + " 失败: " + e.getMessage());
        }
    }

    private void acceptLoop(ServerSocket serverSocket) {
        while (isRunning.get()) {
            try {
                Socket tcpClient = serverSocket.accept();
                tcpClient.setKeepAlive(true);
                tcpClient.setTcpNoDelay(true);
                tcpClient.setSoTimeout(0);
                Log.i(TAG, "客户端已连接: " + tcpClient.getRemoteSocketAddress());
                bridgePool.submit(() -> handleClient(tcpClient));
            } catch (IOException e) {
                if (isRunning.get()) {
                    Log.e(TAG, "Accept 异常: " + e.getMessage());
                }
                break;
            }
        }
    }

    private void handleClient(Socket tcpClient) {
        LocalSocket localSocket = null;
        try {
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
                Log.e(TAG, "无法连接到任何 WebView Socket."
                    + (lastError != null ? " 最后错误: " + lastError.getMessage() : ""));
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

    private void bridge(Socket tcpSocket, LocalSocket localSocket) {
        final AtomicBoolean closed = new AtomicBoolean(false);
        final Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                Log.d(TAG, "桥接关闭: " + tcpSocket.getRemoteSocketAddress());
                closeQuietly(tcpSocket);
                closeQuietly(localSocket);
            }
        };

        String targetIp = tcpSocket.getLocalAddress().getHostAddress();
        if (targetIp.contains(":")) {
            targetIp = "[" + targetIp + "]";
        }
        final String dynamicHost = targetIp + ":" + LOCAL_TCP_PORT;

        bridgePool.submit(() -> {
            try {
                HeaderRewriter rewriter = new HeaderRewriter(true, "localhost:" + LOCAL_TCP_PORT, dynamicHost);
                transfer(tcpSocket.getInputStream(), localSocket.getOutputStream(), rewriter, cleanup);
            } catch (IOException e) {
                cleanup.run();
            }
        });

        bridgePool.submit(() -> {
            try {
                HeaderRewriter rewriter = new HeaderRewriter(false, "localhost:" + LOCAL_TCP_PORT, dynamicHost);
                transfer(localSocket.getInputStream(), tcpSocket.getOutputStream(), rewriter, cleanup);
            } catch (IOException e) {
                cleanup.run();
            }
        });
    }

    private void transfer(InputStream in, OutputStream out, HeaderRewriter rewriter, Runnable cleanup) {
        byte[] buffer = new byte[8192];
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
            // 连接断开
        } finally {
            cleanup.run();
        }
    }

    private static class HeaderRewriter {
        private enum Mode { REQUEST, RESPONSE_HEADER, RESPONSE_BODY, PASSTHROUGH }
        private Mode mode;
        private final boolean isRequest;
        private final String replaceHost;
        private final String replaceWsUrl;

        private final ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        private final ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream();
        private String savedHeaderStr = "";
        private int contentLength = -1;

        HeaderRewriter(boolean isRequest, String replaceHost, String replaceWsUrl) {
            this.isRequest = isRequest;
            this.replaceHost = replaceHost;
            this.replaceWsUrl = replaceWsUrl;
            this.mode = isRequest ? Mode.REQUEST : Mode.RESPONSE_HEADER;
        }

        byte[] consume(byte[] data, int len) {
            if (mode == Mode.PASSTHROUGH) {
                return data.length == len ? data : Arrays.copyOf(data, len);
            }

            if (mode == Mode.REQUEST) {
                headerBuf.write(data, 0, len);
                byte[] current = headerBuf.toByteArray();
                int idx = indexOf(current, 0, current.length, HEADER_END);
                if (idx >= 0) {
                    String headerStr = new String(current, 0, idx + 4, StandardCharsets.US_ASCII);
                    byte[] leftover = Arrays.copyOfRange(current, idx + 4, current.length);

                    String rewritten = headerStr.replaceAll("(?i)Host: [^\\r\\n]+", "Host: " + replaceHost);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    out.write(rewritten.getBytes(StandardCharsets.US_ASCII), 0, rewritten.length());
                    out.write(leftover, 0, leftover.length);

                    mode = Mode.PASSTHROUGH;
                    headerBuf.reset();
                    return out.toByteArray();
                }
                return new byte[0];
            }

            // Response 处理
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int offset = 0;
            while (offset < len) {
                if (mode == Mode.RESPONSE_HEADER) {
                    int available = len - offset;
                    headerBuf.write(data, offset, available);
                    byte[] current = headerBuf.toByteArray();
                    int idx = indexOf(current, 0, current.length, HEADER_END);

                    if (idx >= 0) {
                        savedHeaderStr = new String(current, 0, idx + 4, StandardCharsets.US_ASCII);
                        byte[] leftover = Arrays.copyOfRange(current, idx + 4, current.length);

                        String lower = savedHeaderStr.toLowerCase();
                        boolean isWsUpgrade = lower.contains("upgrade: websocket") || lower.contains("101 switching protocols");
                        boolean isChunked = lower.contains("transfer-encoding: chunked");

                        contentLength = -1;
                        int clIdx = lower.indexOf("content-length:");
                        if (clIdx >= 0) {
                            int eol = lower.indexOf('\n', clIdx);
                            String val = lower.substring(clIdx + 15, eol).trim();
                            try { contentLength = Integer.parseInt(val); } catch (Exception ignored) {}
                        }

                        headerBuf.reset();

                        if (isWsUpgrade) {
                            String rewrittenHeader = savedHeaderStr.replaceAll("ws://[^/\"\\s]+:\\d+", "ws://" + replaceWsUrl);
                            out.write(rewrittenHeader.getBytes(StandardCharsets.US_ASCII), 0, rewrittenHeader.length());
                            out.write(leftover, 0, leftover.length);
                            mode = Mode.PASSTHROUGH;
                            offset = len; // WebSocket 升级后，后续全是二进制帧，直接退出循环透传
                        } else if (contentLength >= 0) {
                            mode = Mode.RESPONSE_BODY;
                            // 把 leftover 当作 body 数据处理，并更新 offset
                            int bodyOffset = 0;
                            while (bodyOffset < leftover.length && mode == Mode.RESPONSE_BODY) {
                                int availableBody = leftover.length - bodyOffset;
                                int needed = contentLength - bodyBuf.size();
                                int take = Math.min(availableBody, needed);
                                bodyBuf.write(leftover, bodyOffset, take);
                                bodyOffset += take;

                                if (bodyBuf.size() == contentLength) {
                                    processCompleteBody(out);
                                    // processCompleteBody 会将 mode 设为 RESPONSE_HEADER，循环继续处理剩余的 leftover
                                }
                            }
                            offset += bodyOffset;
                        } else if (isChunked) {
                            String rewrittenHeader = savedHeaderStr.replaceAll("ws://[^/\"\\s]+:\\d+", "ws://" + replaceWsUrl);
                            out.write(rewrittenHeader.getBytes(StandardCharsets.US_ASCII), 0, rewrittenHeader.length());
                            out.write(leftover, 0, leftover.length);
                            mode = Mode.PASSTHROUGH;
                            offset = len;
                        } else {
                            String rewrittenHeader = savedHeaderStr.replaceAll("ws://[^/\"\\s]+:\\d+", "ws://" + replaceWsUrl);
                            out.write(rewrittenHeader.getBytes(StandardCharsets.US_ASCII), 0, rewrittenHeader.length());
                            out.write(leftover, 0, leftover.length);
                            mode = Mode.PASSTHROUGH;
                            offset = len;
                        }
                    } else {
                        // 没找到完整的 header，等待下一个 TCP 包
                        offset = len;
                    }
                } else if (mode == Mode.RESPONSE_BODY) {
                    int available = len - offset;
                    int needed = contentLength - bodyBuf.size();
                    int take = Math.min(available, needed);
                    bodyBuf.write(data, offset, take);
                    offset += take;

                    if (bodyBuf.size() == contentLength) {
                        processCompleteBody(out);
                        // mode 变为 RESPONSE_HEADER，while 循环继续处理剩余的 data
                    }
                } else {
                    out.write(data, offset, len - offset);
                    offset = len;
                }
            }
            return out.toByteArray();
        }

        private void processCompleteBody(ByteArrayOutputStream out) {
            String bodyStr = new String(bodyBuf.toByteArray(), StandardCharsets.UTF_8);
            String rewrittenBody = bodyStr.replaceAll("ws://[^/\"\\s]+:\\d+", "ws://" + replaceWsUrl);
            byte[] rewrittenBodyBytes = rewrittenBody.getBytes(StandardCharsets.UTF_8);

            String rewrittenHeader = savedHeaderStr
                .replaceAll("(?i)Content-Length: \\d+", "Content-Length: " + rewrittenBodyBytes.length)
                .replaceAll("ws://[^/\"\\s]+:\\d+", "ws://" + replaceWsUrl);

            out.write(rewrittenHeader.getBytes(StandardCharsets.US_ASCII), 0, rewrittenHeader.length());
            out.write(rewrittenBodyBytes, 0, rewrittenBodyBytes.length);

            bodyBuf.reset();
            mode = Mode.RESPONSE_HEADER; // 恢复状态，准备处理 Keep-Alive 连接上的下一个响应
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
        for (ServerSocket ss : serverSockets) {
            try {
                if (!ss.isClosed()) {
                    ss.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "关闭服务异常", e);
            }
        }
        serverSockets.clear();
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
