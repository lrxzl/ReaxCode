package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import cn.net.xiangxiang.seeker.TermuxManager;

public class TermuxStartupHelper {

    private static final String LOG_TAG = "TermuxStartup";
    private static final String PREF_NAME = "termux_startup";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    public static void start(Context context) {
        new Thread(() -> {
            try {
                // 1. 前2次启动等待8秒，确保系统初始化完成
                SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                int startupCount = prefs.getInt("startup_count", 0);
                if (startupCount < 2) {
                    Thread.sleep(1000 * 8);
                    prefs.edit().putInt("startup_count", startupCount + 1).apply();
                }

                File homeDir = new File(TERMUX_HOME);
                
                // 2. 释放 init_env.sh
                File initScript = copyAssetToFile(context, "sh/init_env.sh", new File(homeDir, "init_env.sh"));
                
                // 3. 释放 nodeTools 目录下的文件
                File nodeDir = new File(homeDir, "nodeTools");
                copyAssetToFile(context, "nodeTools/server.js", new File(nodeDir, "server.js"));
                copyAssetToFile(context, "nodeTools/nodeTools.js", new File(nodeDir, "nodeTools.js"));

                // 4. 初始化 Termux 并执行 init_env.sh
                TermuxManager termuxManager = TermuxManager.getInstance();
                termuxManager.init(context);
                
                TermuxManager.CommandResult initResult = termuxManager.executeCommandSync("bash " + initScript.getAbsolutePath());
                Logger.logInfo(LOG_TAG, "init_env.sh 执行结果: exitCode=" + initResult.exitCode + " stderr=" + initResult.stderr);

                // 5. 在 init_env 之后，后台启动 server.js
                // 使用 nohup 和 & 让其在后台持续运行，日志输出到 server.log
                String startServerCmd = "nohup node " + new File(nodeDir, "server.js").getAbsolutePath() + 
                                        " > " + new File(homeDir, "server.log").getAbsolutePath() + " 2>&1 &";
                
                TermuxManager.CommandResult serverResult = termuxManager.executeCommandSync(startServerCmd);
                Logger.logInfo(LOG_TAG, "启动 Node Server 结果: exitCode=" + serverResult.exitCode);

            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Termux 启动初始化异常", e);
            }
        }, "termux-startup-thread").start();
    }

    /**
     * 辅助方法：将 assets 中的文件复制到物理文件系统，并设置可执行权限
     */
    private static File copyAssetToFile(Context context, String assetPath, File destFile) throws IOException {
        File parentDir = destFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        // 赋予可执行权限
        destFile.setExecutable(true);
        return destFile;
    }
}
