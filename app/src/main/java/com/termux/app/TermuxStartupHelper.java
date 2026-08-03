package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.shared.logger.Logger;

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

    /** 修改此版本号会强制重新释放 node-servers 资源 */
    private static final int ASSETS_VERSION = 2; // [修正] 版本号+1，强制重新释放

    public static void start(Context context) {
        new Thread(() -> {
            try {
                SharedPreferences prefs =
                    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                int startupCount = prefs.getInt("startup_count", 0);

                // 前 2 次启动等待 8 秒，确保 Termux 系统初始化完成
                if (startupCount < 2) {
                    Thread.sleep(1000 * 8);
                    prefs.edit().putInt("startup_count", startupCount + 1).apply();
                }

                File homeDir = new File(TERMUX_HOME);

                // ---------- 1. 释放 node-servers/** ----------
                int deployed = prefs.getInt("assets_version", 0);
                if (deployed != ASSETS_VERSION) {
                    Logger.logInfo(LOG_TAG, "释放 node-servers 资源 (version=" + ASSETS_VERSION + ")...");

                    // [修正] 明确指定目标目录为 homeDir/node-servers
                    copyAssetsDir(context, "node-servers", new File(homeDir, "node-servers"));

                    prefs.edit().putInt("assets_version", ASSETS_VERSION).apply();
                    Logger.logInfo(LOG_TAG, "node-servers 释放完成");
                } else {
                    Logger.logInfo(LOG_TAG, "node-servers 已是最新版本，跳过释放");
                }

                // ---------- 2. 释放 sh/ 下脚本（小文件，每次覆盖保证最新） ----------
                File shDir = new File(homeDir, "sh");
                copyAssetToFile(context, "sh/init_env.sh",      new File(shDir, "init_env.sh"));
                copyAssetToFile(context, "sh/init_node.sh",     new File(shDir, "init_node.sh"));
                copyAssetToFile(context, "sh/init_git.sh",      new File(shDir, "init_git.sh"));
                copyAssetToFile(context, "sh/init_mirrors.sh",  new File(shDir, "init_mirrors.sh"));
                copyAssetToFile(context, "sh/update.sh",        new File(shDir, "update.sh"));

                // ---------- 3. 初始化 Termux ----------
                TermuxManager termuxManager = TermuxManager.getInstance();
                termuxManager.init(context);

                // ---------- 4. 同步执行 init_env.sh ----------
                File initEnv = new File(shDir, "init_env.sh");
                TermuxManager.CommandResult initResult =
                    termuxManager.executeCommandSync("bash " + initEnv.getAbsolutePath());
                Logger.logInfo(LOG_TAG,
                    "init_env.sh exitCode=" + initResult.exitCode
                        + " stderr=" + initResult.stderr);

                // ---------- 5. 后台启动 node-tools ----------
                File nodeToolsStartup =
                    new File(homeDir, "node-servers/node-tools/startup.sh");
                termuxManager.executeCommandSync(
                    "bash " + nodeToolsStartup.getAbsolutePath()
                        + " < /dev/null > " + new File(homeDir, "node-tools-startup.log").getAbsolutePath()
                        + " 2>&1 &");

                // ---------- 6. 后台启动 pro-manager ----------
                File proManagerStartup =
                    new File(homeDir, "node-servers/pro-manager/startup.sh");
                termuxManager.executeCommandSync(
                    "bash " + proManagerStartup.getAbsolutePath()
                        + " < /dev/null > " + new File(homeDir, "pro-manager-startup.log").getAbsolutePath()
                        + " 2>&1 &");

                Logger.logInfo(LOG_TAG, "Termux 启动流程已派发完毕");

            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Termux 启动初始化异常", e);
            }
        }, "termux-startup-thread").start();
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * 递归拷贝 assets 目录到目标目录
     * @param assetPath  assets 下的相对路径，如 "node-servers"
     * @param destDir    对应要在文件系统中创建的目标目录，如 ".../home/node-servers"
     */
    private static void copyAssetsDir(Context context, String assetPath, File destDir) throws IOException {
        String[] children = context.getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            // 叶子节点：拷贝文件（此时 destDir 实际是目标文件路径）
            copyAssetToFile(context, assetPath, destDir);
            return;
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("无法创建目录: " + destDir.getAbsolutePath());
        }
        for (String child : children) {
            copyAssetsDir(context, assetPath + "/" + child, new File(destDir, child));
        }
    }

    /** 拷贝单个 asset 文件到目标路径，并赋予可执行权限 */
    private static File copyAssetToFile(Context context, String assetPath, File destFile) throws IOException {
        File parentDir = destFile.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("无法创建父目录: " + parentDir.getAbsolutePath());
        }
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        destFile.setExecutable(true, false);
        destFile.setReadable(true, false);
        return destFile;
    }
}
