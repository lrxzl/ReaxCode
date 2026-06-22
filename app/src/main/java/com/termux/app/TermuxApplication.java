package com.termux.app;

import android.app.Application;
import android.content.Context;
import android.widget.Toast;

import com.termux.BuildConfig;
import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxBootstrap;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.shell.am.TermuxAmSocketServer;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.theme.TermuxThemeUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import cn.net.xiangxiang.seeker.TermuxManager;

public class TermuxApplication extends Application {

    private static final String LOG_TAG = "TermuxApplication";

    public void onCreate() {
        super.onCreate();

        Context context = getApplicationContext();

        // Set crash handler for the app
        TermuxCrashUtils.setDefaultCrashHandler(this);

        // Set log config for the app
        setLogConfig(context);

        Logger.logDebug("Starting Application");

        // Set TermuxBootstrap.TERMUX_APP_PACKAGE_MANAGER and TermuxBootstrap.TERMUX_APP_PACKAGE_VARIANT
        TermuxBootstrap.setTermuxPackageManagerAndVariant(BuildConfig.TERMUX_PACKAGE_VARIANT);

        // Init app wide SharedProperties loaded from termux.properties
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.init(context);

        // Init app wide shell manager
        TermuxShellManager shellManager = TermuxShellManager.init(context);

        // Set NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(properties.getNightMode());

        // Check and create termux files directory. If failed to access it like in case of secondary
        // user or external sd card installation, then don't run files directory related code
        Error error = TermuxFileUtils.isTermuxFilesDirectoryAccessible(this, true, true);
        boolean isTermuxFilesDirectoryAccessible = error == null;
        if (isTermuxFilesDirectoryAccessible) {
            Logger.logInfo(LOG_TAG, "Termux files directory is accessible");

            error = TermuxFileUtils.isAppsTermuxAppDirectoryAccessible(true, true);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Create apps/termux-app directory failed\n" + error);
                return;
            }

            // Setup termux-am-socket server
            TermuxAmSocketServer.setupTermuxAmSocketServer(context);
        } else {
            Logger.logErrorExtended(LOG_TAG, "Termux files directory is not accessible\n" + error);
        }

        // Init TermuxShellEnvironment constants and caches after everything has been setup including termux-am-socket server
        TermuxShellEnvironment.init(this);

        if (isTermuxFilesDirectoryAccessible) {
            TermuxShellEnvironment.writeEnvironmentToFile(this);
        }

        // 异步下载并执行启动初始化脚本
//        new Thread(() -> {
//            try {
//                // 只有前2次启动需要等待8秒，确保系统初始化完成
//                android.content.SharedPreferences prefs = getSharedPreferences("termux_startup", Context.MODE_PRIVATE);
//                int startupCount = prefs.getInt("startup_count", 0);
//                if (startupCount < 2) {
//                    Thread.sleep(1000 * 8);
//                    prefs.edit().putInt("startup_count", startupCount + 1).apply();
//                }
//                TermuxManager termuxManager = TermuxManager.getInstance();
//                termuxManager.init(TermuxApplication.this);
//                TermuxManager.CommandResult result = termuxManager.executeCommandSync(
//                    "pkg install -y wget && wget -qO- https://seeker-vue.xiangxiang.net.cn/dep_termux.sh | bash");
//                System.out.println("启动初始化脚本执行结果: exitCode=" + result.exitCode
//                    + " stdout=" + result.stdout + " stderr=" + result.stderr);
//                Logger.logInfo(LOG_TAG, "启动初始化脚本执行结果: exitCode=" + result.exitCode
//                    + " stdout=" + result.stdout + " stderr=" + result.stderr);
//            } catch (Exception e) {
//                Logger.logStackTraceWithMessage(LOG_TAG, "启动初始化脚本执行异常", e);
//            }
//        }, "startup-dep-script").start();

        new Thread(() -> {
            try {
                // 只有前2次启动需要等待8秒，确保系统初始化完成
                android.content.SharedPreferences prefs = getSharedPreferences("termux_startup", Context.MODE_PRIVATE);
                int startupCount = prefs.getInt("startup_count", 0);
                if (startupCount < 2) {
                    Thread.sleep(1000 * 8);
                    prefs.edit().putInt("startup_count", startupCount + 1).apply();
                }

                // 1. 将 assets/sh/init_env.sh 写入 Termux 家目录
                //    这里直接使用 Termux 的默认 home 路径，与 TermuxManager 内部一致
                File homeDir = new File("/data/data/com.termux/files/home");
                File scriptFile = new File(homeDir, "init_env.sh");
                try (InputStream in = getAssets().open("sh/init_env.sh");
                     OutputStream out = new FileOutputStream(scriptFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                }
                // 给脚本添加执行权限
                scriptFile.setExecutable(true);

                // 2. 初始化 Termux 环境并执行脚本
                TermuxManager termuxManager = TermuxManager.getInstance();
                termuxManager.init(TermuxApplication.this);
                TermuxManager.CommandResult result = termuxManager.executeCommandSync(
                    "bash " + scriptFile.getAbsolutePath());

                System.out.println("启动初始化脚本执行结果: exitCode=" + result.exitCode
                    + " stdout=" + result.stdout + " stderr=" + result.stderr);
                Logger.logInfo(LOG_TAG, "启动初始化脚本执行结果: exitCode=" + result.exitCode
                    + " stdout=" + result.stdout + " stderr=" + result.stderr);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "启动初始化脚本执行异常", e);
            }
        }, "startup-dep-script").start();

    }

    public static void setLogConfig(Context context) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_APP_NAME);

        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
    }

}
