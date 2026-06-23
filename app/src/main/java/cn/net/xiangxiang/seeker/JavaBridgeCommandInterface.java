package cn.net.xiangxiang.seeker;

import android.webkit.JavascriptInterface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.net.xiangxiang.reaction.frontend.tools.file.FileContentOperator;
import cn.net.xiangxiang.reaction.frontend.tools.file.IFileContentOperator;

/**
 * Java与WebView之间的JS桥接类
 * <p>
 * 提供JavaScript可调用的接口，用于与Termux命令行交互。
 * 通过 addJavascriptInterface 注入到WebView中，前端通过 window.JavaBridge 调用。
 * </p>
 * 
 * 前端调用示例：
 * <pre>
 * // 执行命令（异步回调方式）
 * window.JavaBridge.exec("ls -la", function(result) {
 *     var obj = JSON.parse(result);
 *     console.log("输出:", obj.stdout);
 *     console.log("退出码:", obj.exitCode);
 * });
 * 
 * // 获取session数量
 * var count = window.JavaBridge.getSessionCount();
 * </pre>
 */
public class JavaBridgeCommandInterface {

    private static final String LOG_TAG = "JavaBridgeCommandInterface";

    private final TermuxManager mTermuxManager;

    /**
     * 构造函数
     * @param termuxManager TermuxManager实例
     */
    public JavaBridgeCommandInterface(TermuxManager termuxManager) {
        this.mTermuxManager = termuxManager;
    }

    /**
     * 执行shell命令（同步模式）
     * <p>
     * JavaScript调用此方法执行命令并直接返回结果JSON字符串。
     * 注意：此方法会阻塞UI线程直到命令执行完成，请勿执行耗时命令。
     * </p>
     * 
     * @param command 要执行的shell命令
     * @return 命令执行结果的JSON字符串
     *         { "command": "xxx", "stdout": "xxx", "stderr": "xxx", "exitCode": 0 }
     *         或 { "error": "错误信息" }
     */
    @JavascriptInterface
    public String execSync(String command) {
        Logger.logDebug(LOG_TAG, "execSync 收到命令: " + command);
        
        if (mTermuxManager == null) {
            return "{\"error\": \"TermuxManager未初始化\"}";
        }
        
        if (command == null || command.trim().isEmpty()) {
            return "{\"error\": \"命令不能为空\"}";
        }

        try {
            TermuxManager.CommandResult result = mTermuxManager.executeCommandSync(command);
            Logger.logDebug(LOG_TAG, "execSync 命令完成: " + command + 
                " exitCode=" + result.exitCode);
            return result.toString();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "execSync 异常", e);
            return "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}";
        }
    }


    /**
     * 获取当前活跃session数量
     * @return session数量
     */
    @JavascriptInterface
    public int getSessionCount() {
        if (mTermuxManager == null) return 0;
        return mTermuxManager.getSessionCount();
    }

    /**
     * 获取当前后台任务数量
     * @return 任务数量
     */
    @JavascriptInterface
    public int getTaskCount() {
        if (mTermuxManager == null) return 0;
        return mTermuxManager.getTaskCount();
    }

    /**
     * 获取系统信息
     * @return 系统信息JSON字符串
     */
    @JavascriptInterface
    public String getSystemInfo() {
        TermuxManager.CommandResult result = mTermuxManager.executeCommandSync("uname -a");
        String uname = result.stdout.trim();
        
        return "{\"system\": \"Termux\", \"uname\": \"" + escapeJson(uname) + 
               "\", \"sessionCount\": " + getSessionCount() + 
               ", \"taskCount\": " + getTaskCount() + "}";
    }

    /**
     * 执行命令并将标准输出回显到HTML页面
     * @param command 要执行的命令
     * @return 标准输出字符串
     */
    @JavascriptInterface
    public String execAndGetOutput(String command) {
        TermuxManager.CommandResult result = mTermuxManager.executeCommandSync(command);
        if (!result.error.isEmpty()) {
            return "错误: " + result.error;
        }
        if (result.exitCode != 0 && !result.stderr.isEmpty()) {
            return result.stderr;
        }
        return result.stdout;
    }

    // ============= Start JavaTools ===================

    /**
     * 通用方法调用接口
     * <p>
     * 前端通过此单一入口调用所有方法，避免 JavascriptInterface 的类型限制问题。
     * 参数和返回值均为 JSON 字符串。
     * </p>
     *
     * 前端调用示例：
     * <pre>
     * // 调用 execSync
     * var result = JSON.parse(window.JavaBridge.invokeMethod("execSync", '["ls -la"]'));
     *
     * // 调用 readLines
     * var result = JSON.parse(window.JavaBridge.invokeMethod("readLines", '["/path/to/file", 1, 10]'));
     *
     * // 调用 getSessionCount（无参数）
     * var result = JSON.parse(window.JavaBridge.invokeMethod("getSessionCount", '[]'));
     *
     * // 调用 search
     * var result = JSON.parse(window.JavaBridge.invokeMethod("search", '["/root", "*.java", "TODO", 3]'));
     *
     * // 调用 replaceLines
     * var result = JSON.parse(window.JavaBridge.invokeMethod("replaceLines",
     *     '["/path/file.txt", "new content", "startLine", "endLine", 1, 10]'));
     * </pre>
     *
     * @param methodName 要调用的方法名
     * @param argsJson   参数的 JSON 数组字符串，如 '["arg1", 2, true]'
     * @return 执行结果的 JSON 字符串 {"success": true, "data": ...} 或 {"success": false, "error": "..."}
     */
    @JavascriptInterface
    public String invokeMethod(String methodName, String argsJson) {
        Logger.logDebug(LOG_TAG, "invokeMethod: " + methodName + " args=" + argsJson);

        try {
            if (methodName == null || methodName.trim().isEmpty()) {
                return errorJson("方法名不能为空");
            }

            // 解析参数数组
            org.json.JSONArray jsonArgs;
            if (argsJson == null || argsJson.trim().isEmpty() || argsJson.trim().equals("null")) {
                jsonArgs = new org.json.JSONArray();
            } else {
                jsonArgs = new org.json.JSONArray(argsJson);
            }

            // 查找匹配的方法
            java.lang.reflect.Method targetMethod = null;
            java.lang.reflect.Method[] methods = this.getClass().getMethods();

            for (java.lang.reflect.Method method : methods) {
                // 只匹配带 @JavascriptInterface 注解的方法，排除 invokeMethod 自身
                if (method.getName().equals(methodName)
                    && method.isAnnotationPresent(JavascriptInterface.class)
                    && !methodName.equals("invokeMethod")) {

                    Class<?>[] paramTypes = method.getParameterTypes();

                    // 参数个数匹配（对于有 Map/Object options 重载的 exec，优先匹配单参数版本）
                    if (paramTypes.length == jsonArgs.length()) {
                        targetMethod = method;
                        break;
                    }

                    // 如果找到参数更少的版本（比如 exec(String) vs exec(String, Map)），
                    // 也可以在参数多于方法形参时尝试匹配
                    if (targetMethod == null && paramTypes.length <= jsonArgs.length()) {
                        targetMethod = method;
                    }
                }
            }

            if (targetMethod == null) {
                return errorJson("未找到方法: " + methodName + "，参数个数: " + jsonArgs.length());
            }

            // 转换参数
            Class<?>[] paramTypes = targetMethod.getParameterTypes();
            Object[] convertedArgs = new Object[paramTypes.length];

            for (int i = 0; i < paramTypes.length; i++) {
                convertedArgs[i] = convertArg(jsonArgs, i, paramTypes[i]);
            }

            // 调用方法
            Object result = targetMethod.invoke(this, convertedArgs);

            // 包装返回值
            return successJson(result);

        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Logger.logStackTraceWithMessage(LOG_TAG, "invokeMethod 目标方法异常: " + methodName, cause);
            return errorJson(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "invokeMethod 异常: " + methodName, e);
            return errorJson(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 将 JSONArray 中的元素转换为目标类型
     */
    private Object convertArg(org.json.JSONArray jsonArgs, int index, Class<?> targetType) throws Exception {
        if (index >= jsonArgs.length() || jsonArgs.isNull(index)) {
            // 基本类型给默认值，引用类型给 null
            if (targetType == int.class) return 0;
            if (targetType == long.class) return 0L;
            if (targetType == double.class) return 0.0;
            if (targetType == float.class) return 0.0f;
            if (targetType == boolean.class) return false;
            return null;
        }

        Object value = jsonArgs.get(index);

        // String
        if (targetType == String.class) {
            return value.toString();
        }

        // int / Integer
        if (targetType == int.class || targetType == Integer.class) {
            if (value instanceof Number) return ((Number) value).intValue();
            return Integer.parseInt(value.toString());
        }

        // long / Long
        if (targetType == long.class || targetType == Long.class) {
            if (value instanceof Number) return ((Number) value).longValue();
            return Long.parseLong(value.toString());
        }

        // double / Double
        if (targetType == double.class || targetType == Double.class) {
            if (value instanceof Number) return ((Number) value).doubleValue();
            return Double.parseDouble(value.toString());
        }

        // float / Float
        if (targetType == float.class || targetType == Float.class) {
            if (value instanceof Number) return ((Number) value).floatValue();
            return Float.parseFloat(value.toString());
        }

        // boolean / Boolean
        if (targetType == boolean.class || targetType == Boolean.class) {
            if (value instanceof Boolean) return value;
            return Boolean.parseBoolean(value.toString());
        }

        // Map<String, Object> — 从 JSONObject 转换
        if (Map.class.isAssignableFrom(targetType)) {
            if (value instanceof org.json.JSONObject) {
                return jsonObjectToMap((org.json.JSONObject) value);
            }
            // 如果传入的是字符串形式的 JSON
            if (value instanceof String) {
                return jsonObjectToMap(new org.json.JSONObject((String) value));
            }
            return null;
        }

        // Object 类型 — 尝试智能转换
        if (targetType == Object.class) {
            if (value instanceof org.json.JSONObject) {
                return jsonObjectToMap((org.json.JSONObject) value);
            }
            return value;
        }

        // 兜底：尝试直接返回
        return value;
    }

    /**
     * JSONObject 转 Map
     */
    private Map<String, Object> jsonObjectToMap(org.json.JSONObject jsonObject) throws Exception {
        Map<String, Object> map = new HashMap<>();
        java.util.Iterator<String> keys = jsonObject.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object val = jsonObject.get(key);
            if (val instanceof org.json.JSONObject) {
                map.put(key, jsonObjectToMap((org.json.JSONObject) val));
            } else if (val instanceof org.json.JSONArray) {
                map.put(key, jsonArrayToList((org.json.JSONArray) val));
            } else if (val == org.json.JSONObject.NULL) {
                map.put(key, null);
            } else {
                map.put(key, val);
            }
        }
        return map;
    }

    /**
     * JSONArray 转 List
     */
    private List<Object> jsonArrayToList(org.json.JSONArray jsonArray) throws Exception {
        List<Object> list = new java.util.ArrayList<>();
        for (int i = 0; i < jsonArray.length(); i++) {
            Object val = jsonArray.get(i);
            if (val instanceof org.json.JSONObject) {
                list.add(jsonObjectToMap((org.json.JSONObject) val));
            } else if (val instanceof org.json.JSONArray) {
                list.add(jsonArrayToList((org.json.JSONArray) val));
            } else if (val == org.json.JSONObject.NULL) {
                list.add(null);
            } else {
                list.add(val);
            }
        }
        return list;
    }

    /**
     * 构造成功响应 JSON
     */
    private String successJson(@Nullable Object data) {
        try {
            JSONObject json = new JSONObject();
            json.put("success", true);

            if (data == null) {
                json.put("data", JSONObject.NULL);
            } else if (data instanceof String) {
                // 尝试解析为JSON，如果本身就是JSON字符串则嵌入为对象
                String str = (String) data;
                try {
                    if (str.startsWith("{")) {
                        json.put("data", new JSONObject(str));
                    } else if (str.startsWith("[")) {
                        json.put("data", new org.json.JSONArray(str));
                    } else {
                        json.put("data", str);
                    }
                } catch (Exception e) {
                    json.put("data", str);
                }
            } else if (data instanceof Number || data instanceof Boolean) {
                json.put("data", data);
            } else if (data instanceof List) {
                json.put("data", data.toString());
            } else {
                // 对于复杂对象（如 ReadResult, WriteResult, SearchResult），用 Gson 序列化
                /*try {
                    String objJson = new com.google.gson.Gson().toJson(data);
                    if (objJson.startsWith("{")) {
                        json.put("data", new JSONObject(objJson));
                    } else if (objJson.startsWith("[")) {
                        json.put("data", new org.json.JSONArray(objJson));
                    } else {
                        json.put("data", objJson);
                    }
                } catch (Exception e) {
                    json.put("data", data.toString());
                }*/
                json.put("data", data.toString());
            }

            return json.toString();
        } catch (Exception e) {
            return "{\"success\":true,\"data\":\"" + escapeJson(String.valueOf(data)) + "\"}";
        }
    }

    /**
     * 构造错误响应 JSON
     */
    private String errorJson(String message) {
        try {
            JSONObject json = new JSONObject();
            json.put("success", false);
            json.put("error", message != null ? message : "未知错误");
            return json.toString();
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + escapeJson(message) + "\"}";
        }
    }
    /**
     * 执行shell命令
     * <p>
     * JavaScript调用此方法执行命令。由于Android WebView的JavascriptInterface
     * 天然支持同步返回，此方法直接返回结果。
     * 如需异步支持，前端可用setTimeout包裹调用。
     * </p>
     *
     * @param command 要执行的shell命令
     * @return 命令执行结果的JSON字符串
     */
    @JavascriptInterface
    public String exec(String command) {
        return execSync(command);
    }

    /**
     * 带选项的命令执行接口 - 兼容高层调用
     * <p>
     * 支持的options参数：
     * <ul>
     *   <li>"timeoutMs" (Integer) - 命令超时时间，单位毫秒，默认30000</li>
     *   <li>"maxResultChars" (Integer) - 结果最大字符数，超出将截断，默认3000</li>
     *   <li>"async" (Boolean) - 是否异步执行，默认false</li>
     * </ul>
     *
     * @param command 要执行的命令
     * @param options 执行选项，可为null（使用默认值）
     * @return 命令执行结果字符串
     */
    @JavascriptInterface
    public String exec(@NonNull String command, @Nullable Object options) {
        if (options instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) options;
            return exec(command, map);
        }
        return exec(command);
    }
    @JavascriptInterface
    public String exec(@NonNull String command, @Nullable Map<String, Object> options) {
        if (options == null) options = new HashMap<>();

        int timeoutMs = options.containsKey("timeoutMs") && options.get("timeoutMs") instanceof Integer
            ? (Integer) options.get("timeoutMs") : 30_000;
        int maxResultChars = options.containsKey("maxResultChars") && options.get("maxResultChars") instanceof Integer
            ? (Integer) options.get("maxResultChars") : 3_000;
        boolean async = options.containsKey("async") && options.get("async") instanceof Boolean
            ? (Boolean) options.get("async") : false;

        String result;
        if (async) {
            // 异步模式：在后台线程执行，立即返回提示
            final String cmd = command;
            final int timeout = timeoutMs;
            new Thread(() -> {
                TermuxManager.CommandResult cmdResult = mTermuxManager.executeCommandWithTimeout(cmd, timeout);
                Logger.logDebug(LOG_TAG, "[exec async] " + cmd + " → exitCode=" + cmdResult.exitCode);
            }, "exec-async-" + System.currentTimeMillis()).start();
            result = "{\"status\": \"async_submitted\", \"command\": \"" +
                TermuxManager.CommandResult.escapeJsonStatic(command) + "\"}";
        } else {
            // 同步模式：带超时执行
            TermuxManager.CommandResult cmdResult = mTermuxManager.executeCommandWithTimeout(command, timeoutMs);
            if (!cmdResult.error.isEmpty()) {
                result = cmdResult.error;
            } else if (!cmdResult.stderr.isEmpty() && cmdResult.exitCode != 0) {
                result = cmdResult.stderr;
            } else {
                result = cmdResult.stdout;
            }
        }

        // 截断超长结果
        if (result.length() > maxResultChars) {
            int totalLen = result.length();
            result = result.substring(0, maxResultChars) + "...[已截断，共计" + totalLen + "个字符]...";
        }

        Logger.logDebug(LOG_TAG, "[exec] " + command + " → " + result);
        return result;
    }

    private final FileContentOperator fileContentOperator = new FileContentOperator();

    @JavascriptInterface
    public List<IFileContentOperator.SearchResult> search(String rootDir, String filePattern, String contentRegex, int contextLineCount) throws IOException {
        return fileContentOperator.search(rootDir, filePattern, contentRegex, contextLineCount);
    }

    @JavascriptInterface
    public IFileContentOperator.ReadResult readLines(String filePath, int startRow, int endRow) throws IOException {
        return fileContentOperator.readLines(filePath, startRow, endRow);
    }

    @JavascriptInterface
    public List<String> listFiles(String directory, String filePattern, int maxDepth) throws IOException {
        return fileContentOperator.listFiles(directory, filePattern, maxDepth);
    }

    @JavascriptInterface
    public IFileContentOperator.WriteResult replaceLines(String filePath, String newContent, String startLineText, String endLineText,
                                                         int startRow, int endRow) throws IOException {
        return fileContentOperator.replaceLines(filePath, newContent, startLineText, endLineText, startRow, endRow);
    }

    @JavascriptInterface
    public IFileContentOperator.WriteResult insertLines(String filePath, int afterRow, String newContent) throws IOException {
        return fileContentOperator.insertLines(filePath, afterRow, newContent);
    }

    @JavascriptInterface
    public IFileContentOperator.WriteResult replaceByAnchor(String filePath, String anchorText, int beforeCount, int afterCount,
                                                            String newContent) throws IOException {
        return fileContentOperator.replaceByAnchor(filePath, anchorText, beforeCount, afterCount, newContent);
    }

    @JavascriptInterface
    public IFileContentOperator.WriteResult insertAfterAnchor(String filePath, String anchorText, String newContent) throws IOException {
        return fileContentOperator.insertAfterAnchor(filePath, anchorText, newContent);
    }

    @JavascriptInterface
    public IFileContentOperator.WriteResult insertBeforeAnchor(String filePath, String anchorText, String newContent) throws IOException {
        return fileContentOperator.insertBeforeAnchor(filePath, anchorText, newContent);
    }

    // ============= End JavaTools ===================

    /**
     * JSON字符串转义辅助方法
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
