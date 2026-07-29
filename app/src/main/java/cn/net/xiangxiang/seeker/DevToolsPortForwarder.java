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
import java.util.Arrays;
import java.util.List;

public class DevToolsPortForwarder {

    private static final String TAG = "PortForwarder";
    private static final int LOCAL_TCP_PORT = 9222;

    // 可能的 Socket 前缀
    private static final String SOCKET_PREFIX = "webview_devtools_remote";
    private final Context acitivity;

    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private Context context;

    public DevToolsPortForwarder(Context context) {
        this.acitivity = context.getApplicationContext();
        this.context = context;
    }

    public void start() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(LOCAL_TCP_PORT);
                isRunning = true;
                Log.i(TAG, "TCP 代理服务已启动，监听端口: " + LOCAL_TCP_PORT);
                ((Activity) this.context).runOnUiThread(() -> {
                    Toast.makeText(this.context, "TCP 代理服务已启动，监听端口: " + LOCAL_TCP_PORT, Toast.LENGTH_LONG).show();
                });

                while (isRunning) {
                    Socket tcpClient = serverSocket.accept();
                    Log.i(TAG, "Termux 已连接，准备桥接...");
                    ((Activity) this.context).runOnUiThread(() -> {
                        Toast.makeText(this.context, "Termux 已连接，准备桥接...", Toast.LENGTH_LONG).show();
                    });
                    // 获取所有可能的 Socket 名称
                    List<String> possibleSocketNames = getPossibleSocketNames();
                    LocalSocket localSocket = null;
                    ((Activity) this.context).runOnUiThread(() -> {
                        Toast.makeText(this.context, "names" + Arrays.toString(possibleSocketNames.toArray()), Toast.LENGTH_LONG).show();
                    });
                    // 依次尝试连接
                    for (String socketName : possibleSocketNames) {
                        try {
                            localSocket = new LocalSocket();
                            localSocket.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
                            Log.i(TAG, "成功连接到 WebView Socket: " + socketName);

                            ((Activity) this.context).runOnUiThread(() -> {
                                Toast.makeText(this.context, "成功连接到 WebView Socket: " + socketName, Toast.LENGTH_LONG).show();
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
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (IOException e) {
            Log.d(TAG, "连接已断开");
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            try { out.close(); } catch (IOException ignored) {}
        }
    }
}
