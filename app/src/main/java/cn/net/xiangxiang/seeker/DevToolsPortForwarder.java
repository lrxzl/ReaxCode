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
            // 同时监听 IPv4 和 IPv6，解决 Node.js 解析 localhost 优先使用 IPv6 的问题
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

        // 获取客户端访问的目标 IP，用于动态重写 webSocketDebuggerUrl
        String targetIp = tcpSocket.getLocalAddress().getHostAddress();
        if (targetIp.contains(":")) {
            targetIp = "[" + targetIp + "]"; // IPv6 地址在 URL 中需要加方括号
        }
        final String dynamicHost = targetIp + ":" + LOCAL_TCP_PORT;

        // TCP -> Local (上行)：重写 Host 头，绕过 WebView 安全校验
        bridgePool.submit(() -> {
            try {
                HeaderRewriter rewriter = new HeaderRewriter(true, "localhost:" + LOCAL_TCP_PORT, dynamicHost);
                transfer(tcpSocket.getInputStream(), localSocket.getOutputStream(), rewriter, cleanup);
            } catch (IOException e) {
                cleanup.run();
            }
        });

        // Local -> TCP (下行)：重写响应体中的 ws:// 地址
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

    /**
     * 轻量级 HTTP 头部及内容重写器
     */
    private static class HeaderRewriter {
        private enum Mode { REQUEST, RESPONSE_HTTP, RESPONSE_WS, PASSTHROUGH }
        private Mode currentMode;
        private final String replaceHost;
        private final String replaceWsUrl;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        HeaderRewriter(boolean isRequest, String replaceHost, String replaceWsUrl) {
            this.currentMode = isRequest ? Mode.REQUEST : Mode.RESPONSE_HTTP;
            this.replaceHost = replaceHost;
            this.replaceWsUrl = replaceWsUrl;
        }

        byte[] consume(byte[] data, int len) {
            // WebSocket 升级后或非 HTTP 数据，直接透传，防止破坏二进制帧
            if (currentMode == Mode.PASSTHROUGH || currentMode == Mode.RESPONSE_WS) {
                return data.length == len ? data : Arrays.copyOf(data, len);
            }

            buffer.write(data, 0, len);
            byte[] current = buffer.toByteArray();
            int idx = indexOf(current, 0, current.length, HEADER_END);

            if (idx < 0) {
                // 还没读完头部，继续等待
                return new byte[0];
            }

            String headerStr = new String(current, 0, idx + 4, StandardCharsets.US_ASCII);
            byte[] remainingBytes = Arrays.copyOfRange(current, idx + 4, current.length);

            ByteArrayOutputStream out = new ByteArrayOutputStream();

            if (currentMode == Mode.REQUEST) {
                // 请求方向：强制重写 Host 头
                String rewritten = headerStr.replaceAll("(?i)Host: [^\\r\\n]+", "Host: " + replaceHost);
                out.write(rewritten.getBytes(StandardCharsets.US_ASCII), 0, rewritten.length());
                // 请求头处理完，后续 body 透传
                currentMode = Mode.PASSTHROUGH;
                out.write(remainingBytes, 0, remainingBytes.length);
            } else {
                // 响应方向：重写头里的 ws:// 地址
                String rewrittenHeader = headerStr.replaceAll("ws://[^/\"\\s]+:\\d+", "ws://" + replaceWsUrl);
                out.write(rewrittenHeader.getBytes(StandardCharsets.US_ASCII), 0, rewrittenHeader.length());

                String lowerHeader = headerStr.toLowerCase();
                boolean isWsUpgrade = lowerHeader.contains("upgrade: websocket") || lowerHeader.contains("101 switching protocols");

                if (isWsUpgrade) {
                    // WebSocket 握手响应，后续是二进制帧，直接透传
                    currentMode = Mode.RESPONSE_WS;
                    out.write(remainingBytes, 0, remainingBytes.length);
                } else {
                    // 普通 HTTP 响应(如 /json/version)，body 通常是 JSON 且在同一个包里
                    // 对当前包剩余部分做替换，然后进入透传模式
                    String remainingStr = new String(remainingBytes, StandardCharsets.US_ASCII);
                    String rewrittenRemaining = remainingStr.replaceAll("ws://[^/\"\\s]+:\\d+", "ws://" + replaceWsUrl);
                    out.write(rewrittenRemaining.getBytes(StandardCharsets.US_ASCII), 0, rewrittenRemaining.length());
                    currentMode = Mode.PASSTHROUGH;
                }
            }

            buffer.reset();
            return out.toByteArray();
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
