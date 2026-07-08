package cn.net.xiangxiang.seeker;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxService;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.shell.command.environment.IShellEnvironment;
import com.termux.shared.shell.command.result.ResultData;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.shared.logger.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Termux管理类 - 用于管理Termux的session和后台命令行交互
 * <p>
 * 职责：
 * 1. 管理所有TermuxSession的生命周期
 * 2. 提供后台命令执行能力（同步/异步）
 * 3. 桥接WebView与底层shell系统
 * 4. 获取session输出
 * </p>
 */
public class TermuxManager {

    private static final String LOG_TAG = "TermuxManager";
    private static TermuxManager instance;

    private Context mContext;
    private TermuxShellManager mShellManager;

    /** TermuxService绑定相关 */
    private TermuxService mTermuxService;
    private boolean mIsBound = false;
    private final List<Runnable> mServiceReadyCallbacks = new ArrayList<>();

    /** WakeLock相关 - 保持CPU运行，防止网络被冻结 */
    private PowerManager.WakeLock mWakeLock;
    private static final String WAKE_LOCK_TAG = "TermuxManager:background-keepalive";

    /**
     * 当前管理的TermuxSession列表
     */
    private final List<TermuxSession> mSessions = new ArrayList<>();

    /**
     * 后台执行的任务列表
     */
    private final List<AppShell> mTasks = new ArrayList<>();

    /** ServiceConnection实现 - 绑定TermuxService以保持前台服务运行 */
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mTermuxService = ((TermuxService.LocalBinder) service).service;
            mIsBound = true;
            Logger.logDebug(LOG_TAG, "TermuxService已绑定");

            // 通知所有等待Service就绪的回调
            synchronized (mServiceReadyCallbacks) {
                for (Runnable callback : mServiceReadyCallbacks) {
                    callback.run();
                }
                mServiceReadyCallbacks.clear();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mTermuxService = null;
            mIsBound = false;
            Logger.logWarn(LOG_TAG, "TermuxService已断开");
        }
    };

    private TermuxManager() {}

    /**
     * 获取单例实例
     */
    public static synchronized TermuxManager getInstance() {
        if (instance == null) {
            instance = new TermuxManager();
        }
        return instance;
    }

    /**
     * 初始化管理器 - 需要在Application或Activity中调用
     * 同时绑定到TermuxService以确保后台执行能力
     * @param context 应用上下文
     */
    public void init(@NonNull Context context) {
        this.mContext = context.getApplicationContext();
        this.mShellManager = TermuxShellManager.init(mContext);
        Logger.logDebug(LOG_TAG, "TermuxManager初始化完成");

        // 绑定到TermuxService，确保前台服务运行
        bindToTermuxService();

        // 获取WakeLock，保持CPU运行，防止后台网络被冻结
        acquireWakeLock();

        // 请求电池优化豁免，防止Doze模式限制网络
        requestBatteryOptimizationExemption();
    }

    /**
     * 获取WakeLock，保持CPU运行
     * 防止Android在后台休眠CPU导致网络栈冻结
     */
    private void acquireWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            return;
        }
        try {
            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                mWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG
                );
                mWakeLock.acquire();
                Logger.logDebug(LOG_TAG, "WakeLock已获取，CPU将保持运行");
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "获取WakeLock失败: " + e.getMessage());
        }
    }

    /**
     * 释放WakeLock
     */
    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            mWakeLock = null;
            Logger.logDebug(LOG_TAG, "WakeLock已释放");
        }
    }

    /**
     * 请求电池优化豁免
     * 防止Doze模式限制后台网络访问
     */
    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(mContext.getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + mContext.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(intent);
                    Logger.logDebug(LOG_TAG, "已请求电池优化豁免");
                }
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "请求电池优化豁免失败: " + e.getMessage());
            }
        }
    }

    /**
     * 绑定到TermuxService
     */
    private void bindToTermuxService() {
        if (mIsBound && mTermuxService != null) {
            Logger.logDebug(LOG_TAG, "TermuxService已绑定，跳过");
            return;
        }

        try {
            Intent serviceIntent = new Intent(mContext, TermuxService.class);
            // 先startService确保服务启动（即使所有binding都断开服务也会继续运行）
            mContext.startService(serviceIntent);
            // 再bindService获取引用
            boolean bound = mContext.bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
            if (bound) {
                Logger.logDebug(LOG_TAG, "正在绑定TermuxService...");
            } else {
                Logger.logError(LOG_TAG, "绑定TermuxService失败");
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "绑定TermuxService异常", e);
        }
    }

    /**
     * 确保TermuxService已绑定，如果未绑定则触发绑定并等待就绪
     * @param onReady Service就绪后的回调
     */
    public void ensureServiceReady(@Nullable Runnable onReady) {
        if (mIsBound && mTermuxService != null) {
            if (onReady != null) onReady.run();
            return;
        }

        bindToTermuxService();

        if (onReady != null) {
            synchronized (mServiceReadyCallbacks) {
                mServiceReadyCallbacks.add(onReady);
            }
        }
    }

    /**
     * 获取已绑定的TermuxService实例
     * @return TermuxService或null（未绑定时）
     */
    @Nullable
    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    /**
     * 解绑TermuxService并释放WakeLock
     */
    public void unbindService() {
        if (mIsBound && mContext != null) {
            try {
                mContext.unbindService(mServiceConnection);
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "解绑TermuxService异常: " + e.getMessage());
            }
            mIsBound = false;
            mTermuxService = null;
        }
        releaseWakeLock();
    }

    /**
     * 创建新的TerminalSession
     * @param terminalSessionClient TerminalSession客户端接口实现
     * @param sessionName session名称
     * @return 创建的TermuxSession，失败返回null
     */
    @Nullable
    public TermuxSession createSession(@NonNull TerminalSessionClient terminalSessionClient,
                                       @Nullable String sessionName) {
        if (mShellManager == null) {
            Logger.logError(LOG_TAG, "TermuxShellManager未初始化");
            return null;
        }

        try {
            // 构建执行命令 - 默认启动shell
            ExecutionCommand executionCommand = new ExecutionCommand(
                TermuxShellManager.getNextShellId(),
                null,  // executable为null则自动选择默认shell
                null,  // arguments
                null,  // stdin
                null,  // workingDirectory
                ExecutionCommand.Runner.TERMINAL_SESSION.getName(),
                false  // isFailsafe
            );

            if (sessionName != null && !sessionName.isEmpty()) {
                executionCommand.shellName = sessionName;
            }

            // 使用TermuxSession静态方法创建session
            TermuxSession session = TermuxSession.execute(
                mContext,
                executionCommand,
                terminalSessionClient,
                null,  // termuxSessionClient 可以为null
                new TermuxShellEnvironment(),
                null,  // additionalEnvironment
                false  // setStdoutOnExit
            );

            if (session != null) {
                mSessions.add(session);
                Logger.logDebug(LOG_TAG, "Session创建成功: " +
                    (sessionName != null ? sessionName : "unnamed"));
            }

            return session;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "创建Session失败", e);
            return null;
        }
    }

    /**
     * 销毁指定的session
     * @param session 要销毁的session
     */
    public void destroySession(@NonNull TermuxSession session) {
        if (mShellManager != null) {
            mShellManager.mTermuxSessions.remove(session);
        }
        mSessions.remove(session);
        Logger.logDebug(LOG_TAG, "Session已销毁");
    }

    /**
     * 销毁所有session并解绑Service
     */
    public void destroyAllSessions() {
        if (mShellManager != null) {
            for (TermuxSession session : new ArrayList<>(mSessions)) {
                mShellManager.mTermuxSessions.remove(session);
            }
        }
        mSessions.clear();
        unbindService();
        Logger.logDebug(LOG_TAG, "所有Session已销毁，Service已解绑");
    }

    /**
     * 执行后台命令（同步模式 - 等待命令执行完成并返回结果）
     * 优先通过TermuxService执行（确保前台服务运行，进程不被杀死），
     * 如果Service未绑定则等待绑定完成或回退到直接执行。
     *
     * @param command 要执行的命令
     * @return 命令执行结果（标准输出+标准错误）
     */
    @NonNull
    public CommandResult executeCommandSync(@NonNull String command) {
        CommandResult result = new CommandResult();
        result.command = command;

        // 如果Service还未绑定，等待绑定完成（最多5秒）
        if (mTermuxService == null && mIsBound == false) {
            Logger.logDebug(LOG_TAG, "TermuxService未绑定，等待绑定...");
            final CountDownLatch bindLatch = new CountDownLatch(1);
            synchronized (mServiceReadyCallbacks) {
                mServiceReadyCallbacks.add(bindLatch::countDown);
            }
            // 重新触发绑定
            bindToTermuxService();
            try {
                bindLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 优先通过TermuxService执行（保持前台服务）
        if (mTermuxService != null) {
            return executeViaTermuxService(command, result);
        }

        // 回退：直接执行（Service未绑定时）
        Logger.logWarn(LOG_TAG, "TermuxService未就绪，回退到直接执行");
        return executeDirectly(command, result);
    }

    /**
     * 通过TermuxService执行命令
     * TermuxService是前台服务，确保进程不会被系统杀死
     */
    private CommandResult executeViaTermuxService(@NonNull String command, @NonNull CommandResult result) {
        try {
            // 通过TermuxService创建后台任务（前台服务管理）
            AppShell appShell = mTermuxService.createTermuxTask(
                TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh",
                new String[]{"-c", command},
                null,  // stdin
                TermuxConstants.TERMUX_HOME_DIR_PATH
            );

            if (appShell != null) {
                // 等待进程完成
                Process process = appShell.getProcess();
                if (process != null) {
                    long startTime = System.currentTimeMillis();
                    long timeoutMs = 60_000;

                    // 轮询等待进程退出
                    while (process.isAlive()) {
                        if (System.currentTimeMillis() - startTime > timeoutMs) {
                            result.error = "命令执行超时（60s）: " + command;
                            Logger.logWarn(LOG_TAG, result.error);
                            return result;
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            result.error = "等待命令完成时被中断: " + command;
                            return result;
                        }
                    }

                    // 进程已完成，等待StreamGobbler线程完成（它们会在进程退出后很快完成）
                    // 给一点时间让exitCode被设置
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    // 读取结果
                    ResultData resultData = appShell.getExecutionCommand().resultData;
                    if (resultData != null) {
                        result.stdout = resultData.stdout.toString();
                        result.stderr = resultData.stderr.toString();
                        result.exitCode = resultData.exitCode != null ? resultData.exitCode : -1;
                    }
                    mTasks.add(appShell);
                    Logger.logDebug(LOG_TAG, "[via Service] 命令执行完成: " + command +
                        " (exitCode=" + result.exitCode + ")");
                } else {
                    result.error = "无法获取进程引用";
                    Logger.logError(LOG_TAG, result.error);
                }
            } else {
                // Service创建任务失败，回退到直接执行
                Logger.logWarn(LOG_TAG, "TermuxService创建任务失败，回退到直接执行");
                return executeDirectly(command, result);
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "通过Service执行命令异常，回退到直接执行: " + e.getMessage());
            return executeDirectly(command, result);
        }

        return result;
    }

    /**
     * 直接执行命令（不通过Service，进程可能被杀死）
     */
    private CommandResult executeDirectly(@NonNull String command, @NonNull CommandResult result) {
        try {
            ExecutionCommand executionCommand = new ExecutionCommand(
                TermuxShellManager.getNextShellId(),
                TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh",
                new String[]{"-c", command},
                null,
                TermuxConstants.TERMUX_HOME_DIR_PATH,
                ExecutionCommand.Runner.APP_SHELL.getName(),
                false
            );
            executionCommand.shellName = "web-cmd";
            executionCommand.commandLabel = command;

            AppShell appShell = AppShell.execute(
                mContext,
                executionCommand,
                null,
                new TermuxShellEnvironment(),
                null,
                true
            );

            if (appShell != null) {
                ResultData resultData = appShell.getExecutionCommand().resultData;
                if (resultData != null) {
                    result.stdout = resultData.stdout.toString();
                    result.stderr = resultData.stderr.toString();
                    result.exitCode = resultData.exitCode != null ? resultData.exitCode : -1;
                }
                mTasks.add(appShell);
                Logger.logDebug(LOG_TAG, "[direct] 命令执行完成: " + command +
                    " (exitCode=" + result.exitCode + ")");
            } else {
                result.error = "命令执行失败：无法创建AppShell";
                Logger.logError(LOG_TAG, result.error);
            }
        } catch (Exception e) {
            result.error = "命令执行异常: " + e.getMessage();
            Logger.logStackTraceWithMessage(LOG_TAG, "命令执行异常", e);
        }

        return result;
    }


    /**
     * 带超时的命令执行
     *
     * @param command   命令
     * @param timeoutMs 超时时间（毫秒）
     * @return 命令执行结果
     */
    @NonNull
    public CommandResult executeCommandWithTimeout(@NonNull String command, int timeoutMs) {
        final CommandResult[] holder = new CommandResult[1];
        Thread execThread = new Thread(() -> {
            holder[0] = executeCommandSync(command);
        }, "exec-timeout-" + System.currentTimeMillis());

        execThread.start();
        try {
            execThread.join(timeoutMs);
            if (execThread.isAlive()) {
                execThread.interrupt();
                CommandResult timeoutResult = new CommandResult();
                timeoutResult.command = command;
                timeoutResult.exitCode = -1;
                timeoutResult.error = "命令执行超时（" + timeoutMs + "ms）: " + command;
                Logger.logWarn(LOG_TAG, timeoutResult.error);
                return timeoutResult;
            }
        } catch (InterruptedException e) {
            CommandResult interruptResult = new CommandResult();
            interruptResult.command = command;
            interruptResult.exitCode = -1;
            interruptResult.error = "命令执行被中断: " + command;
            Thread.currentThread().interrupt();
            return interruptResult;
        }

        return holder[0] != null ? holder[0] : new CommandResult();
    }

    /**
     * 获取当前所有session的数量
     */
    public int getSessionCount() {
        return mSessions.size();
    }

    /**
     * 获取当前所有后台任务数量
     */
    public int getTaskCount() {
        return mTasks.size();
    }

    /**
     * 通过索引获取session
     * @param index 索引
     * @return TermuxSession或null
     */
    @Nullable
    public TermuxSession getSession(int index) {
        if (index >= 0 && index < mSessions.size()) {
            return mSessions.get(index);
        }
        return null;
    }

    /**
     * 命令执行结果封装类
     */
    public static class CommandResult {
        /** 执行的命令 */
        public String command = "";
        /** 标准输出 */
        public String stdout = "";
        /** 标准错误 */
        public String stderr = "";
        /** 退出码 */
        public int exitCode = -1;
        /** 错误信息 */
        public String error = "";

        /**
         * 判断命令是否执行成功
         */
        public boolean isSuccess() {
            return exitCode == 0 && error.isEmpty();
        }

        @Override
        public String toString() {
            if (!error.isEmpty()) {
                return "{\"error\": \"" + escapeJson(error) + "\"}";
            }
            return "{\"command\": \"" + escapeJson(command) +
                "\", \"stdout\": \"" + escapeJson(stdout) +
                "\", \"stderr\": \"" + escapeJson(stderr) +
                "\", \"exitCode\": " + exitCode + "}";
        }

        private String escapeJson(String str) {
            return escapeJsonStatic(str);
        }

        static String escapeJsonStatic(String str) {
            if (str == null) return "";
            return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        }
    }
}
