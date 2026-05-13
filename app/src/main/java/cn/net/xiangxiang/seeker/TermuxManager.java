package cn.net.xiangxiang.seeker;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.shell.command.environment.IShellEnvironment;
import com.termux.shared.shell.command.result.ResultData;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.shared.logger.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * 当前管理的TermuxSession列表
     */
    private final List<TermuxSession> mSessions = new ArrayList<>();

    /**
     * 后台执行的任务列表
     */
    private final List<AppShell> mTasks = new ArrayList<>();

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
     * @param context 应用上下文
     */
    public void init(@NonNull Context context) {
        this.mContext = context.getApplicationContext();
        this.mShellManager = TermuxShellManager.init(mContext);
        Logger.logDebug(LOG_TAG, "TermuxManager初始化完成");
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
     * 销毁所有session
     */
    public void destroyAllSessions() {
        if (mShellManager != null) {
            for (TermuxSession session : new ArrayList<>(mSessions)) {
                mShellManager.mTermuxSessions.remove(session);
            }
        }
        mSessions.clear();
        Logger.logDebug(LOG_TAG, "所有Session已销毁");
    }

    /**
     * 执行后台命令（同步模式 - 等待命令执行完成并返回结果）
     * 使用AppShell后台执行命令，支持shell命令
     *
     * @param command 要执行的命令
     * @return 命令执行结果（标准输出+标准错误）
     */
    @NonNull
    public CommandResult executeCommandSync(@NonNull String command) {
        CommandResult result = new CommandResult();
        result.command = command;

        try {
            // 构建ExecutionCommand
            ExecutionCommand executionCommand = new ExecutionCommand(
                TermuxShellManager.getNextShellId(),
                "/system/bin/sh",  // 使用系统shell
                new String[]{"-c", command},  // -c 表示执行后面跟着的命令字符串
                null,  // stdin
                null,  // workingDirectory
                ExecutionCommand.Runner.APP_SHELL.getName(),
                false
            );
            executionCommand.shellName = "web-cmd";
            executionCommand.commandLabel = command;

            // 同步执行
            AppShell appShell = AppShell.execute(
                mContext,
                executionCommand,
                null,  // appShellClient
                new TermuxShellEnvironment(),
                null,  // additionalEnvironment
                true   // 同步模式
            );

            if (appShell != null) {
                ResultData resultData = appShell.getExecutionCommand().resultData;
                if (resultData != null) {
                    result.stdout = resultData.stdout.toString();
                    result.stderr = resultData.stderr.toString();
                    result.exitCode = resultData.exitCode != null ? resultData.exitCode : -1;
                }
                mTasks.add(appShell);
                Logger.logDebug(LOG_TAG, "命令执行完成: " + command +
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
