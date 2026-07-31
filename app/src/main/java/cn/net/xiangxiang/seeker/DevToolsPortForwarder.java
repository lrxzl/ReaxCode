package cn.net.xiangxiang.seeker;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class DevToolsPortForwarder {

    private static final String TAG = "PortForwarder";
    private static final int LOCAL_TCP_PORT = 9222;

    // 可能的 Socket 前缀
    private static final String SOCKET_PREFIX = "webview_devtools_remote";
    private final Context activity;

    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private Context context;

    public DevToolsPortForwarder(Context context) {
        this.activity = context.getApplicationContext();
        this.context = context;
    }

    public void start() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(LOCAL_TCP_PORT);
                isRunning = true;
                Log.i(TAG, "TCP 代理服务已启动，监听端口: " + LOCAL_TCP_PORT);

                while (isRunning) {
                    Socket tcpClient = serverSocket.accept();
                    // 获取所有可能的 Socket 名称
                    List<String> possibleSocketNames = getPossibleSocketNames();
                    LocalSocket localSocket = null;
                    // 依次尝试连接
                    for (String socketName : possibleSocketNames) {
                        try {
                            localSocket = new LocalSocket();
                            localSocket.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
                            Log.i(TAG, "成功连接到 WebView Socket: " + socketName);

                            ((Activity) this.context).runOnUiThread(() -> {
                                Toast.makeText(this.context, "CDP success" + socketName, Toast.LENGTH_LONG).show();
                            });
                            break; // 连接成功，跳出循环
                        } catch (IOException e) {
                            // 当前名字连接失败，尝试下一个
                            if (localSocket != null) {
                                try { localSocket.close(); } catch (IOException ignored) {}
                            }
                        }
                    }

                    if (localSocket != null && localSocket.isConnected()) {
                        pipeStreams(tcpClient, localSocket);
                    } else {
                        Log.e(TAG, "无法连接到任何 WebView Socket，请确保 WebView 调试已开启");
                        tcpClient.close();
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "代理服务异常: " + e.getMessage());
            }
        }).start();
    }

    // 核心逻辑：生成可能的 Socket 名称列表
    private List<String> getPossibleSocketNames() {
        List<String> names = new ArrayList<>();

        // 1. 最基础的名字，优先尝试
        names.add(SOCKET_PREFIX);

        // 2. 获取当前 App 进程的 PID
        int currentPid = android.os.Process.myPid();
        names.add(SOCKET_PREFIX + "_" + currentPid);

        // 3. 如果你的 WebView 运行在多进程 (比如 :remote 进程)
        // 需要获取该 App 所有进程的 PID
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    // 过滤掉主进程，因为主进程上面已经加过了
                    if (process.pid != currentPid) {
                        names.add(SOCKET_PREFIX + "_" + process.pid);
                    }
                }
            }
        }

        return names;
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "关闭服务异常", e);
        }
    }

    private void pipeStreams(Socket tcpSocket, LocalSocket localSocket) {
        try {
            InputStream tcpIn = tcpSocket.getInputStream();
            OutputStream tcpOut = tcpSocket.getOutputStream();
            InputStream localIn = localSocket.getInputStream();
            OutputStream localOut = localSocket.getOutputStream();

            new Thread(() -> transfer(tcpIn, localOut)).start();
            new Thread(() -> transfer(localIn, tcpOut)).start();

        } catch (IOException e) {
            Log.e(TAG, "建立桥接流失败", e);
        }
    }

    private void transfer(InputStream in, OutputStream out) {
        byte[] buffer = new byte[4096];
        int bytesRead;
        try {
            while ((bytesRead = in.read(buffer)) != -1) {
                // 1. 原有的字节传输
                out.write(buffer, 0, bytesRead);
                out.flush();
                // 2. 新增：将本次读取的字节转换为 UTF-8 字符串并输出
                // 注意：如果传输的是二进制数据（如图片），转为字符串可能产生乱码，建议仅用于文本传输场景
                String chunk = new String(buffer, 0, bytesRead, java.nio.charset.StandardCharsets.UTF_8);
                // 为避免日志过长，只输出前 200 个字符，并追加长度信息
                int maxLogLen = 1000;
                String logContent = chunk.length() > maxLogLen ? chunk.substring(0, maxLogLen) + "..." : chunk;
                Log.d(TAG, "传输数据块 (长度=" + bytesRead + "): " + logContent);
            }
        } catch (IOException e) {
            Log.d(TAG, "连接已断开");
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            try { out.close(); } catch (IOException ignored) {}
        }
    }
}
