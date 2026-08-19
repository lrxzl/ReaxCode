package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.shared.logger.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import cn.net.xiangxiang.seeker.TermuxManager;

public class TermuxStartupHelper {

    /**
     * 启动流程状态监听器
     * 用于通知 UI 层显示/隐藏初始化进度对话框
     */
    public interface OnStartupListener {
        /** 初始化开始（即将执行 init_env.sh） */
        void onStartupStart();
        /** 初始化结束（reax/startup.sh 执行完成） */
        void onStartupComplete();
        /** 初始化进度更新 */
        void onStartupProgress(String message);
    }

    private static final List<OnStartupListener> sListeners = new CopyOnWriteArrayList<>();

    /** 当前是否正在执行启动初始化流程 */
    private static volatile boolean sIsStartupRunning = false;

    /** 服务保活定时器 */
    private static volatile ScheduledExecutorService sResumeScheduler = null;

    /** 查询启动初始化是否正在进行中 */
    public static boolean isStartupRunning() {
        return sIsStartupRunning;
    }

    /** 注册启动监听器 */
    public static void addStartupListener(OnStartupListener listener) {
        if (listener != null && !sListeners.contains(listener)) {
            sListeners.add(listener);
        }
    }

    /** 移除启动监听器 */
    public static void removeStartupListener(OnStartupListener listener) {
        sListeners.remove(listener);
    }

    private static final String LOG_TAG = "TermuxStartup";
    private static final String PREF_NAME = "termux_startup";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    /** 修改此版本号会强制重新释放 reax 资源 */
    private static final int ASSETS_VERSION = 13;

    public static void start(Context context) {
        new Thread(() -> {
            try {
                // 通知 UI：初始化开始，显示旋转等待
                notifyStartupStart();
                SharedPreferences prefs =
                    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                int startupCount = prefs.getInt("startup_count", 0);

                // 前 2 次启动等待 8 秒，确保 Termux 系统初始化完成
                if (startupCount < 2) {
                    Thread.sleep(1000 * 8);
                    prefs.edit().putInt("startup_count", startupCount + 1).apply();
                }

                File homeDir = new File(TERMUX_HOME);

                // ---------- 1. 释放 reax/** ----------
                int deployed = prefs.getInt("assets_version", 0);
                if (deployed != ASSETS_VERSION) {
                    Logger.logInfo(LOG_TAG, "释放 reax 资源 (version=" + ASSETS_VERSION + ")...");

                    // [修正] 明确指定目标目录为 homeDir/reax
                    copyAssetsDir(context, "reax", new File(homeDir, "reax"));

                    prefs.edit().putInt("assets_version", ASSETS_VERSION).apply();
                    Logger.logInfo(LOG_TAG, "reax 释放完成");
                } else {
                    Logger.logInfo(LOG_TAG, "reax 已是最新版本，跳过释放");
                }

                // ---------- 2. 释放 sh/ 下所有脚本（整体递归拷贝，保证最新） ----------
                copyAssetsDir(context, "sh", new File(homeDir, "sh"));

                File shDir = new File(homeDir, "sh");

                // ---------- 3. 初始化 Termux ----------
                TermuxManager termuxManager = TermuxManager.getInstance();
                termuxManager.init(context);

                // ---------- 4. 分步执行环境初始化 ----------
                notifyStartupProgress("步骤 1/4: 配置镜像源...");
                File initMirrors = new File(shDir, "init_mirrors.sh");
                TermuxManager.CommandResult mirrorsResult = termuxManager.executeCommandWithTimeout("bash " + initMirrors.getAbsolutePath(), 180_000); // 3分钟超时
                Logger.logInfo(LOG_TAG, "init_mirrors.sh exitCode=" + mirrorsResult.exitCode);
                if (mirrorsResult.exitCode != 0) {
                    throw new RuntimeException("镜像源配置失败 (exit=" + mirrorsResult.exitCode + ") " + mirrorsResult.stderr);
                }

                notifyStartupProgress("步骤 2/4: 初始化 Node.js（首次约需1分钟）...");
                File initNode = new File(shDir, "init_node.sh");
                TermuxManager.CommandResult nodeResult = termuxManager.executeCommandWithTimeout("bash " + initNode.getAbsolutePath(), 360_000); // 6分钟超时
                Logger.logInfo(LOG_TAG, "init_node.sh exitCode=" + nodeResult.exitCode + " stderr=" + nodeResult.stderr);
                if (nodeResult.exitCode != 0) {
                    throw new RuntimeException("Node.js 初始化失败 (exit=" + nodeResult.exitCode + ") " + nodeResult.stderr);
                }

                notifyStartupProgress("步骤 3/4: 初始化 Git（后台运行）...");
                File initGit = new File(shDir, "init_git.sh");
                termuxManager.executeCommandSync("nohup bash " + initGit.getAbsolutePath() + " < /dev/null &");
                Logger.logInfo(LOG_TAG, "init_git.sh 已派发后台");

                // ---------- 4. 启动 reax（统一启动 node-tools + pro-manager） ----------
                notifyStartupProgress("步骤 4/4: 启动 ReaX 服务...");
                File reaxStartup = new File(homeDir, "reax/startup.sh");
                TermuxManager.CommandResult reaxResult = termuxManager.executeCommandWithTimeout("bash " + reaxStartup.getAbsolutePath(), 60_000); // 60秒超时
                Logger.logInfo(LOG_TAG, "reax startup exitCode=" + reaxResult.exitCode + " stdout=" + reaxResult.stdout + " stderr=" + reaxResult.stderr);
                // 回显启动日志中的 echo 信息
                if (reaxResult.stdout != null && !reaxResult.stdout.trim().isEmpty()) {
                    notifyStartupProgress(reaxResult.stdout.trim());
                }

                // 通知 UI：初始化完成，隐藏旋转等待
                notifyStartupComplete();
                Logger.logInfo(LOG_TAG, "Termux 启动流程已派发完毕");

                // ---------- 7. 更新 pro-manager ----------
//                File updateProManager = new File(homeDir, "sh/update.sh");
//                termuxManager.executeCommandSync("bash " + updateProManager.getAbsolutePath() + " < /dev/null > "
//                    + new File(homeDir, "pro-manager-update.log").getAbsolutePath() + " 2>&1 &");

            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Termux 启动初始化异常", e);
                notifyStartupComplete();
            }
        }, "termux-startup-thread").start();

        // 启动服务保活监视器（每10秒检查 reaxcode/pro-manager 服务状态）
        startResumeMonitor(context);
    }


    /**
     * 启动定时检查服务（每10秒执行一次 resume.sh）
     * 由 start() 调用，确保App启动后持续保活
     */
    public static void startResumeMonitor(Context context) {
        if (sResumeScheduler != null) return;
        sResumeScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "resume-monitor");
            t.setDaemon(true);
            return t;
        });
        sResumeScheduler.scheduleWithFixedDelay(() -> {
            try {
                checkServicesNow();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "定时检查服务异常", e);
            }
        }, 10, 10, TimeUnit.SECONDS);
        Logger.logInfo(LOG_TAG, "已启动服务保活定时器（每10秒检查）");
    }

    /**
     * 立即执行一次 resume.sh，检查服务状态并恢复
     */
    public static void checkServicesNow() {
        try {
            File resumeSh = new File(TERMUX_HOME, "reax/resume.sh");
            if (!resumeSh.exists()) {
                Logger.logInfo(LOG_TAG, "resume.sh 不存在，跳过检查: " + resumeSh.getAbsolutePath());
                return;
            }
            Logger.logInfo(LOG_TAG, "执行 resume.sh 检查服务...");
            TermuxManager.getInstance().executeCommandSync(
                "nohup bash " + resumeSh.getAbsolutePath() + " < /dev/null >> " + TERMUX_HOME + "/reax/resume-monitor.log 2>&1 &");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "执行 resume.sh 异常", e);
        }
    }


    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * 递归拷贝 assets 目录到目标目录
     * @param assetPath  assets 下的相对路径，如 "reax"
     * @param destDir    对应要在文件系统中创建的目标目录，如 ".../home/reax"
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

    // ============================================================
    // 启动状态通知
    // ============================================================

    private static void notifyStartupStart() {
        sIsStartupRunning = true;
        for (OnStartupListener listener : sListeners) {
            try {
                listener.onStartupStart();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "通知启动开始异常", e);
            }
        }
    }

    private static void notifyStartupProgress(String message) {
        for (OnStartupListener listener : sListeners) {
            try {
                listener.onStartupProgress(message);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "通知启动进度异常", e);
            }
        }
    }

    private static void notifyStartupComplete() {
        sIsStartupRunning = false;
        for (OnStartupListener listener : sListeners) {
            try {
                listener.onStartupComplete();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "通知启动完成异常", e);
            }
        }
    }
}
