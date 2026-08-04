package cn.net.xiangxiang.seeker.tools;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import cn.net.xiangxiang.seeker.tools.file.FileContentOperator;
import cn.net.xiangxiang.seeker.tools.file.IFileContentOperator;
import cn.net.xiangxiang.seeker.TermuxManager;

public class FrontendJavaTools {
    private static final Logger log = Logger.getLogger(FrontendJavaTools.class.getName());

    // ==================== exec ====================

    /**
     * 执行系统终端命令（始终在同一个终端会话中，历史执行状态会保留）
     * 耗时阻塞任务建议异步进行，并隔时间看下进度
     * @param command 命令字符串
     * @param options {
     *  timeoutMs: int, // 命令执行超时（毫秒，不填时默认=30_000s，最大30min）
     *  maxResultChars: int, // 最大输出字符（不填时默认=3_000，最大100_000），数值应尽量小
     *  async: boolean, // 是否异步执行，默认async=false，为false时会阻塞并返回结果，为true时会返回PID信息
     * }
     * @return 命令输出结果
     */
    public String exec(String command, Map<String, Object> options) {
        if (options == null) options = new HashMap<>();
        Integer timeoutMs = (Integer) options.getOrDefault("timeoutMs", 30_000);
        Integer maxResultChars = (Integer) options.getOrDefault("maxResultChars", 3_000);
        boolean async = (boolean) options.getOrDefault("async", false);

        String result = runTerminalCmd(command, async, timeoutMs);

        if (result.length() > maxResultChars) {
            result = result.substring(0, maxResultChars)
                + String.format("...[已截断，共计%d个字符]...", result.length());
        }
        System.out.println("[exec] " + command + " → " + result);
        return result;
    }

    /**
     * 通过 TermuxManager 执行终端命令
     *
     * @param command   命令字符串
     * @param async     是否异步：true 时用 nohup 后台执行并立即返回 PID 信息
     * @param timeoutMs 超时毫秒数（同步模式下生效）
     * @return 命令执行结果字符串
     */
    private String runTerminalCmd(String command, boolean async, int timeoutMs) {
        TermuxManager termuxManager = TermuxManager.getInstance();

        if (async) {
            // 异步模式：将命令包装为后台执行，返回 PID
            // 用 nohup + & 后台运行，echo $! 拿到 PID
            String asyncCommand = "nohup sh -c " + shellEscape(command) + " > /dev/null 2>&1 & echo $!";
            TermuxManager.CommandResult result = termuxManager.executeCommandSync(asyncCommand);
            if (!result.error.isEmpty()) {
                return "error: " + result.error;
            }
            String pid = result.stdout.trim();
            return "{\"async\": true, \"pid\": \"" + pid + "\", \"message\": \"命令已在后台启动\"}";
        }

        // 同步模式：用 timeout 命令包裹，防止命令执行超时挂死
        // Android/Termux 的 timeout 命令来自 coreutils
        // 转换 ms → s，至少 1 秒
        int timeoutSec = Math.max(1, timeoutMs / 1000);
        // 限制最大 30 分钟
        timeoutSec = Math.min(timeoutSec, 30 * 60);

        // 用 timeout 包裹执行，合并 stderr 到 stdout 方便统一获取
        String wrappedCommand = "timeout " + timeoutSec + " sh -c " + shellEscape(command) + " 2>&1";
        TermuxManager.CommandResult cmdResult = termuxManager.executeCommandSync(wrappedCommand);

        if (!cmdResult.error.isEmpty()) {
            return "error: " + cmdResult.error;
        }

        // 组合 stdout 和 stderr
        StringBuilder sb = new StringBuilder();
        if (cmdResult.stdout != null && !cmdResult.stdout.isEmpty()) {
            sb.append(cmdResult.stdout);
        }
        // 如果 stderr 有额外内容（虽然上面 2>&1 已合并，但保险起见）
        if (cmdResult.stderr != null && !cmdResult.stderr.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[stderr] ").append(cmdResult.stderr);
        }

        // 如果退出码是 124，说明 timeout 超时了
        if (cmdResult.exitCode == 124) {
            sb.append("\n[超时] 命令执行超过 ").append(timeoutSec).append(" 秒，已被终止");
        } else if (cmdResult.exitCode != 0) {
            sb.append("\n[exitCode=").append(cmdResult.exitCode).append("]");
        }

        return sb.toString();
    }

    /**
     * 对命令字符串进行 shell 安全转义，用单引号包裹
     */
    private String shellEscape(String command) {
        // 将命令中的单引号替换为 '\'' ，然后整体用单引号包裹
        return "'" + command.replace("'", "'\\''") + "'";
    }

    // ==================== runPythonCode ====================

    /**
     * 执行 Python 代码
     */
    public String runPythonCode(String code, Map<String, Object> options) {
        if (options == null) options = new HashMap<>();
        Integer timeoutMs = (Integer) options.getOrDefault("timeoutMs", 30_000);
        Integer maxResultChars = (Integer) options.getOrDefault("maxResultChars", 1_000);

        try {
            // 将 Python 代码通过 python3 -c 执行
            // 用 heredoc 方式传递代码，避免复杂转义问题
            String result = runPythonCodeInternal(code, timeoutMs);

            if (result.length() > maxResultChars) {
                result = result.substring(0, maxResultChars)
                    + String.format("...[已截断，共计%d个字符]...", result.length());
            }
            log.info("[runPythonCode] result=" + result);
            return result;
        } catch (Exception e) {
            log.severe("[runPythonCode] Error: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    /**
     * 内部方法：执行 Python 代码
     * 通过将代码写入临时文件再执行的方式，避免复杂的 shell 转义问题
     *
     * @param code      Python 代码
     * @param timeoutMs 超时毫秒
     * @return 执行结果
     */
    private String runPythonCodeInternal(String code, int timeoutMs) {
        TermuxManager termuxManager = TermuxManager.getInstance();
        int timeoutSec = Math.max(1, Math.min(timeoutMs / 1000, 30 * 60));

        // 阈值：base64 后超过 100KB 就走临时文件方案
        final int BASE64_THRESHOLD = 75_000; // 原文 75KB，base64 后约 100KB

        if (code.length() <= BASE64_THRESHOLD) {
            // ===== 小代码：base64 内联方案（快，无文件 IO）=====
            String base64Code = android.util.Base64.encodeToString(
                code.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP
            );
            String cmd = "timeout " + timeoutSec
                + " python3 -c \"import base64;exec(base64.b64decode('" + base64Code + "').decode())\" 2>&1";
            return executeAndFormat(termuxManager, cmd, timeoutSec);
        } else {
            // ===== 大代码：写临时文件方案（无参数长度限制）=====
            return runPythonViaFile(termuxManager, code, timeoutSec);
        }
    }

    /**
     * 大代码方案：将 Python 代码写入临时文件后执行
     */
    private String runPythonViaFile(TermuxManager termuxManager, String code, int timeoutSec) {
        // 1. 生成唯一临时文件路径 TODO 注意这个包路径后续可能要改
        String tmpFile = "/data/data/com.termux/cache/_py_"
            + System.currentTimeMillis() + "_" + Thread.currentThread().getId() + ".py";

        try {
            // 2. 用 Java IO 直接写文件（不经过 shell，无长度限制）
            java.io.File file = new java.io.File(tmpFile);
            file.getParentFile().mkdirs();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                 java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write(code);
                writer.flush();
            }
            // 设置可执行权限
            file.setReadable(true, false);

            // 3. 执行临时文件
            String cmd = "timeout " + timeoutSec + " python3 " + tmpFile + " 2>&1";
            return executeAndFormat(termuxManager, cmd, timeoutSec);

        } catch (IOException e) {
            return "error: 写入临时Python文件失败: " + e.getMessage();
        } finally {
            // 4. 清理临时文件
            try {
                new java.io.File(tmpFile).delete();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 执行命令并格式化结果（公共方法，避免重复代码）
     */
    private String executeAndFormat(TermuxManager termuxManager, String cmd, int timeoutSec) {
        TermuxManager.CommandResult cmdResult = termuxManager.executeCommandSync(cmd);

        if (!cmdResult.error.isEmpty()) {
            return "error: " + cmdResult.error;
        }

        StringBuilder sb = new StringBuilder();
        if (cmdResult.stdout != null && !cmdResult.stdout.isEmpty()) {
            sb.append(cmdResult.stdout);
        }
        if (cmdResult.stderr != null && !cmdResult.stderr.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[stderr] ").append(cmdResult.stderr);
        }
        if (cmdResult.exitCode == 124) {
            sb.append("\n[超时] 执行超过 ").append(timeoutSec).append(" 秒，已被终止");
        } else if (cmdResult.exitCode != 0) {
            sb.append("\n[exitCode=").append(cmdResult.exitCode).append("]");
        }
        return sb.toString();
    }
    // ==================== 文件操作 ====================
    // 下面的内容不要动了

    private final FileContentOperator fileContentOperator = new FileContentOperator();

    public List<IFileContentOperator.SearchResult> search(String rootDir, String filePattern, String contentRegex, int contextLineCount) throws IOException {
        return fileContentOperator.search(rootDir, filePattern, contentRegex, contextLineCount);
    }

    public IFileContentOperator.ReadResult readLines(String filePath, int startRow, int endRow) throws IOException {
        return fileContentOperator.readLines(filePath, startRow, endRow);
    }

    public List<String> listFiles(String directory, String filePattern, int maxDepth) throws IOException {
        return fileContentOperator.listFiles(directory, filePattern, maxDepth);
    }

    public IFileContentOperator.WriteResult replaceLines(String filePath, String newContent, String startLineText, String endLineText,
                                                         int startRow, int endRow) throws IOException {
        return fileContentOperator.replaceLines(filePath, newContent, startLineText, endLineText, startRow, endRow);
    }

    public IFileContentOperator.WriteResult insertLines(String filePath, int afterRow, String newContent) throws IOException {
        return fileContentOperator.insertLines(filePath, afterRow, newContent);
    }

    public IFileContentOperator.WriteResult replaceByAnchor(String filePath, String anchorText, int beforeCount, int afterCount,
                                                            String newContent) throws IOException {
        return fileContentOperator.replaceByAnchor(filePath, anchorText, beforeCount, afterCount, newContent);
    }

    public IFileContentOperator.WriteResult insertAfterAnchor(String filePath, String anchorText, String newContent) throws IOException {
        return fileContentOperator.insertAfterAnchor(filePath, anchorText, newContent);
    }

    public IFileContentOperator.WriteResult insertBeforeAnchor(String filePath, String anchorText, String newContent) throws IOException {
        return fileContentOperator.insertBeforeAnchor(filePath, anchorText, newContent);
    }

}
