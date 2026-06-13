package cn.net.xiangxiang.seeker;

import android.app.Activity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import cn.net.xiangxiang.reaction.frontend.tools.FrontendJavaTools;
import cn.net.xiangxiang.reaction.frontend.tools.file.IFileContentOperator;

public class JavaBridge {
    private static final Logger log = Logger.getLogger(JavaBridge.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();
    private final FrontendJavaTools tools;
    private final Activity activity;
    private final WebView homeWebView;

    public JavaBridge() {
        this(null, new FrontendJavaTools(), null);
    }

    public JavaBridge(FrontendJavaTools tools) {
        this(null, tools, null);
    }

    public JavaBridge(Activity activity, FrontendJavaTools tools, WebView homeWebView) {
        this.tools = tools;
        this.activity = activity;
        this.homeWebView = homeWebView;
    }

    // ==================== 统一入口 ====================

    @JavascriptInterface
    public String invokeMethod(String methodName, String jsonParams) {
        ObjectNode response = mapper.createObjectNode();
        try {
            JsonNode argsNode = (jsonParams == null || jsonParams.isEmpty())
                ? mapper.createArrayNode() : mapper.readTree(jsonParams);

            Object result = dispatch(methodName, argsNode);
            response.set("data", toJackson(result));
            log.info("[invokeMethod] " + methodName + " ok");
        } catch (Exception e) {
            log.severe("[invokeMethod] " + methodName + " error: " + e.getMessage());
            response.putNull("data");
            response.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            response.put("errorType", e.getClass().getSimpleName());
            attachErrorDetails(response, e);
        }
        return response.toString();
    }

    // ==================== 方法分发 ====================

    private Object dispatch(String methodName, JsonNode args) throws Exception {
        switch (methodName) {

            case "exec": {
                String command = args.get(0).asText();
                Map<String, Object> options = toMap(args.get(1));
                return tools.exec(command, options);
            }

            case "runPythonCode": {
                String code = args.get(0).asText();
                Map<String, Object> options = toMap(args.get(1));
                return tools.runPythonCode(code, options);
            }

            case "search": {
                String rootDir = args.get(0).asText();
                String filePattern = optNullableText(args, 1);
                String contentRegex = args.get(2).asText();
                int contextLineCount = args.get(3).asInt();
                return searchViaExec(rootDir, filePattern, contentRegex, contextLineCount);
            }

            case "readLines": {
                String filePath = args.get(0).asText();
                int startRow = args.get(1).asInt();
                int endRow = args.get(2).asInt();
                return readLinesViaExec(filePath, startRow, endRow);
            }

            case "listFiles": {
                String directory = args.get(0).asText();
                String filePattern = optNullableText(args, 1);
                int maxDepth = args.get(2).asInt();
                return listFilesViaExec(directory, filePattern, maxDepth);
            }

            case "replaceLines": {
                String filePath = args.get(0).asText();
                String newContent = optNullableText(args, 1);
                String startLineText = args.get(2).asText();
                String endLineText = args.get(3).asText();
                int startRow = args.get(4).asInt();
                int endRow = args.get(5).asInt();
                return replaceLinesViaExec(filePath, newContent, startLineText, endLineText, startRow, endRow);
            }

            case "insertLines": {
                String filePath = args.get(0).asText();
                int afterRow = args.get(1).asInt();
                String newContent = args.get(2).asText();
                return insertLinesViaExec(filePath, afterRow, newContent);
            }

            case "replaceByAnchor": {
                String filePath = args.get(0).asText();
                String anchorText = args.get(1).asText();
                int beforeCount = args.get(2).asInt();
                int afterCount = args.get(3).asInt();
                String newContent = optNullableText(args, 4);
                return replaceByAnchorViaExec(filePath, anchorText, beforeCount, afterCount, newContent);
            }

            case "insertAfterAnchor": {
                String filePath = args.get(0).asText();
                String anchorText = args.get(1).asText();
                String newContent = args.get(2).asText();
                return insertAfterAnchorViaExec(filePath, anchorText, newContent);
            }

            case "insertBeforeAnchor": {
                String filePath = args.get(0).asText();
                String anchorText = args.get(1).asText();
                String newContent = args.get(2).asText();
                return insertBeforeAnchorViaExec(filePath, anchorText, newContent);
            }

            // 修改 dispatch 中的 case 分支
            case "openAndroidWebView": {
                String url = args.get(0).asText();
                return openFloatingWebView(url);
            }

            // 修改 dispatch 中的 case 分支
            case "evalJavascriptOnWebView": {
                String webViewId = args.get(0).asText();
                String script = args.get(1).asText();
                FloatingWebView webView = floatingWebViewMap.get(webViewId);
                if (webView == null) {
                    throw new IllegalArgumentException("WebView with id " + webViewId + " not found");
                }
                // 【关键改变】：这里你可以安全地使用你最开始写的那个 CountDownLatch 的 evaluateJavascriptSync 了！
                // 因为此时 dispatch 是运行在上面的 new Thread() 子线程里的！主线程是空闲的！
                return evaluateJavascriptSync(webView, script);
            }

            default:
                throw new IllegalArgumentException("Unknown method: " + methodName);
        }
    }


    static Map<String, FloatingWebView> floatingWebViewMap = new HashMap<>();
    static int floatingWebViewId = 0;

    // openFloatingWebView 的同步等待版本
    public String openFloatingWebView(String url) {
        final String finalUrl = url;
        final String[] idHolder = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);

        activity.runOnUiThread(() -> {
            try {
                FloatingWebView floating = new FloatingWebView(activity);
                floating.loadUrl(finalUrl);
                ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
                decorView.addView(floating);
                String newId = String.valueOf(++ floatingWebViewId);
                floatingWebViewMap.put(newId, floating);
                idHolder[0] = newId;
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for WebView creation", e);
        }
        return idHolder[0];
    }

    // 同步执行 JavaScript 并获取返回值
    private String evaluateJavascriptSync(FloatingWebView webView, String script) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] resultHolder = new String[1];

        activity.runOnUiThread(() -> {
            webView.getWebView().evaluateJavascript(script, value -> {
                resultHolder[0] = value;
                latch.countDown(); // 拿到结果后解除阻塞
            });
        });

        // 阻塞当前线程，最多等待 10 秒
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new RuntimeException("JavaScript execution timeout after 10 seconds");
        }
        return resultHolder[0];
    }


    // ==================== 文件操作 via exec ====================

    /** 执行 exec 并返回输出，options 固定为 30s 超时，最大输出 100_000 字符 */
    private String exec(String command) throws Exception {
        Map<String, Object> opts = new HashMap<>();
        opts.put("timeoutMs", 30_000);
        opts.put("maxResultChars", 100_000);
        return (String) tools.exec(command, opts);
    }

    /** 执行 exec 并忽略结果，用于写操作（options 固定 30s 超时） */
    private void execWrite(String command) throws Exception {
        Map<String, Object> opts = new HashMap<>();
        opts.put("timeoutMs", 30_000);
        opts.put("maxResultChars", 1_000);
        tools.exec(command, opts);
    }

    /** 获取文件总行数 */
    private int fileLineCount(String filePath) throws Exception {
        String out = exec("wc -l < " + shellEscapePath(filePath));
        return Integer.parseInt(out.trim());
    }

    /** 对文件路径做简单的 shell 转义（用单引号包裹，内部单引号替换为 '\\\\''） */
    private String shellEscapePath(String path) {
        return "'" + path.replace("'", "'\\\\''") + "'";
    }

    // --- search ---

    private Object searchViaExec(String rootDir, String filePattern, String contentRegex, int contextLineCount) throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        // grep: -r 递归, -n 行号, -H 文件名（强制）, -E 扩展正则
        StringBuilder cmd = new StringBuilder("grep -rnH -E ");
        if (contextLineCount > 0) {
            cmd.append("-C ").append(contextLineCount).append(" ");
        }
        cmd.append(shellEscapePath(contentRegex)).append(" ").append(shellEscapePath(rootDir));
        if (filePattern != null && !filePattern.isEmpty()) {
            cmd.append(" --include=").append(shellEscapePath(filePattern));
        }
        String out = exec(cmd.toString());
        // 解析 grep 输出，格式: path:row:text
        // 有 context 时，context 行格式: path-row-text 或 path:row:text（取决于 grep）
        // busybox grep -C 输出与普通不同，这里做兼容处理
        if (out == null || out.trim().isEmpty()) return results;

        // 将输出按 "\\n--\\n" 或直接按空行分组（grep -C 用 "--" 分隔匹配组）
        String[] groups = out.split("\\n--\\n|\\n--$");
        for (String group : groups) {
            if (group.trim().isEmpty()) continue;
            String[] lines = group.split("\\n");
            // 找到匹配行（不以 "-" 开头，且格式为 path:row:text）
            int matchIdx = -1;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.matches("^[^:]+:[0-9]+:.*")) {
                    // 可能是匹配行
                    if (!line.contains(":")) continue;
                    int firstColon = line.indexOf(':');
                    int secondColon = line.indexOf(':', firstColon + 1);
                    if (secondColon > firstColon) {
                        matchIdx = i;
                        break;
                    }
                }
            }
            if (matchIdx < 0) continue;
            String matchLine = lines[matchIdx];
            int firstColon = matchLine.indexOf(':');
            int secondColon = matchLine.indexOf(':', firstColon + 1);
            String path = matchLine.substring(0, firstColon);
            int row = Integer.parseInt(matchLine.substring(firstColon + 1, secondColon));
            String matchText = matchLine.substring(secondColon + 1);

            List<Map<String, Object>> context = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                int contextRow;
                String text;
                if (line.startsWith(path + "-")) {
                    // busybox 格式: path-row-text
                    String rest = line.substring(path.length() + 1);
                    int dash = rest.indexOf('-');
                    if (dash < 0) continue;
                    contextRow = Integer.parseInt(rest.substring(0, dash));
                    text = rest.substring(dash + 1);
                } else if (line.startsWith(path + ":")) {
                    String rest = line.substring(path.length() + 1);
                    int colon = rest.indexOf(':');
                    if (colon < 0) continue;
                    contextRow = Integer.parseInt(rest.substring(0, colon));
                    text = rest.substring(colon + 1);
                } else {
                    continue;
                }
                Map<String, Object> nl = new HashMap<>();
                nl.put("row", contextRow);
                nl.put("text", text);
                context.add(nl);
            }

            Map<String, Object> sr = new HashMap<>();
            sr.put("path", path);
            sr.put("row", row);
            sr.put("regex", contentRegex);
            sr.put("match", matchText);
            sr.put("context", context);
            results.add(sr);
        }
        return results;
    }

    // --- readLines ---

    private Object readLinesViaExec(String filePath, int startRow, int endRow) throws Exception {
        int totalLines = fileLineCount(filePath);
        // 钳位
        int s = Math.max(1, startRow);
        int e = Math.min(totalLines, endRow);
        if (s > e) {
            // 返回空
            Map<String, Object> rr = new HashMap<>();
            rr.put("filePath", filePath);
            rr.put("startRow", s);
            rr.put("endRow", e);
            rr.put("totalLines", totalLines);
            rr.put("lines", new ArrayList<>());
            return rr;
        }
        String out = exec("sed -n '" + s + "," + e + "p' " + shellEscapePath(filePath));
        String[] lineTexts = out.split("\\n", -1);
        // sed 输出末尾无换行时不会多出空行，需要处理
        List<Map<String, Object>> lineList = new ArrayList<>();
        for (int i = 0; i < lineTexts.length; i++) {
            Map<String, Object> nl = new HashMap<>();
            nl.put("row", s + i);
            nl.put("text", lineTexts[i]);
            lineList.add(nl);
        }
        Map<String, Object> rr = new HashMap<>();
        rr.put("filePath", filePath);
        rr.put("startRow", s);
        rr.put("endRow", e);
        rr.put("totalLines", totalLines);
        rr.put("lines", lineList);
        return rr;
    }

    // --- listFiles ---


    // --- listFiles ---

    private Object listFilesViaExec(String directory, String filePattern, int maxDepth) throws Exception {
        // 深 0 视为 1（仅当前目录）
        if (maxDepth <= 0) maxDepth = 1;
        // 检查目录是否存在
        String testCmd = "test -d " + shellEscapePath(directory);
        String testOut = exec(testCmd);
        // 若 test 失败，exec 返回非零退出码，需抛异常
        // 实际上 exec 在非零退出码时已抛出异常，这里只做冗余检查

        StringBuilder cmd = new StringBuilder("find ");
        cmd.append(shellEscapePath(directory));
        cmd.append(" -maxdepth ").append(maxDepth).append(" -type f");
        if (filePattern != null && !filePattern.isEmpty()) {
            // 使用 -regex 支持正则过滤（而非 -name 通配符）
            cmd.append(" -regex ").append(shellEscapePath(filePattern));
        }
        String out = exec(cmd.toString());
        if (out == null || out.trim().isEmpty()) return new ArrayList<String>();
        String[] files = out.split("\\n");
        List<String> fileList = new ArrayList<>();
        for (String f : files) {
            if (!f.trim().isEmpty()) fileList.add(f.trim());
        }
        return fileList;
    }

    // --- replaceLines ---

    private Object replaceLinesViaExec(String filePath, String newContent, String startLineText, String endLineText, int startRow, int endRow) throws Exception {
        // 1. 找到 startLineText 所在行号
        int actualStartRow = findLineByText(filePath, startLineText, startRow);
        // 2. 找到 endLineText 所在行号
        int actualEndRow = findLineByText(filePath, endLineText, endRow);
        if (actualStartRow > actualEndRow) {
            throw new IllegalArgumentException("startRow(" + actualStartRow + ") > endRow(" + actualEndRow + ")");
        }
        int totalLines = fileLineCount(filePath);
        // 3. 读取旧内容
        String oldContent = exec("sed -n '" + actualStartRow + "," + actualEndRow + "p' " + shellEscapePath(filePath));

        // 4. 用 sed 做替换
        if (newContent == null || newContent.isEmpty()) {
            // 删除
            execWrite("sed -i '" + actualStartRow + "," + actualEndRow + "d' " + shellEscapePath(filePath));
        } else {
            // 写入临时文件，然后用 sed r 命令
            String tmpFile = filePath + ".tmp";
            execWrite("printf '%s' " + shellEscapePath(newContent + "\\n") + " > " + shellEscapePath(tmpFile));
            execWrite("sed -i '" + actualStartRow + "," + actualEndRow + "d;" + actualEndRow + "r " + shellEscapePath(tmpFile) + "' " + shellEscapePath(filePath));
            execWrite("rm -f " + shellEscapePath(tmpFile));
        }

        int newTotalLines = fileLineCount(filePath);
        int deletedCount = actualEndRow - actualStartRow + 1;
        int insertedCount = (newContent == null || newContent.isEmpty()) ? 0 : newContent.split("\\n", -1).length;

        Map<String, Object> wr = new HashMap<>();
        wr.put("filePath", filePath);
        wr.put("originalStartRow", actualStartRow);
        wr.put("originalEndRow", actualEndRow);
        wr.put("deletedLineCount", deletedCount);
        wr.put("insertedLineCount", insertedCount);
        wr.put("oldContent", oldContent);
        wr.put("newContent", newContent);
        wr.put("newStartRow", actualStartRow);
        wr.put("newEndRow", actualStartRow + insertedCount - 1);
        wr.put("totalLinesAfter", newTotalLines);
        return wr;
    }

    /** 在文件中查找包含 text 的行号，以 nearRow 为参考选择最近的匹配 */
    private int findLineByText(String filePath, String text, int nearRow) throws Exception {
        // 用 grep -n 查找所有匹配行
        String out = exec("grep -n -F " + shellEscapePath(text) + " " + shellEscapePath(filePath));
        if (out == null || out.trim().isEmpty()) {
            throw new IllegalArgumentException("Text not found: " + text);
        }
        String[] lines = out.split("\\n");
        int bestRow = -1;
        int bestDist = Integer.MAX_VALUE;
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            int row = Integer.parseInt(line.substring(0, colon));
            int dist = Math.abs(row - nearRow);
            if (dist < bestDist) {
                bestDist = dist;
                bestRow = row;
            }
        }
        return bestRow;
    }

    // --- insertLines ---

    private Object insertLinesViaExec(String filePath, int afterRow, String newContent) throws Exception {
        int totalLines = fileLineCount(filePath);
        if (afterRow < 1) afterRow = 0;
        if (afterRow > totalLines) afterRow = totalLines;
        if (newContent == null || newContent.isEmpty()) {
            Map<String, Object> wr = new HashMap<>();
            wr.put("filePath", filePath);
            wr.put("originalStartRow", afterRow + 1);
            wr.put("originalEndRow", afterRow);
            wr.put("deletedLineCount", 0);
            wr.put("insertedLineCount", 0);
            wr.put("oldContent", "");
            wr.put("newContent", "");
            wr.put("newStartRow", afterRow + 1);
            wr.put("newEndRow", afterRow);
            wr.put("totalLinesAfter", totalLines);
            return wr;
        }
        String tmpFile = filePath + ".tmp";
        // 将新内容写入临时文件，确保末尾有换行
        execWrite("printf '%s\\n' " + shellEscapePath(newContent) + " > " + shellEscapePath(tmpFile));
        if (afterRow == 0) {
            // 插入到文件开头
            execWrite("cat " + shellEscapePath(tmpFile) + " " + shellEscapePath(filePath) + " > " + shellEscapePath(filePath + ".tmp2") + " && mv " + shellEscapePath(filePath + ".tmp2") + " " + shellEscapePath(filePath));
        } else {
            execWrite("sed -i '" + afterRow + "r " + shellEscapePath(tmpFile) + "' " + shellEscapePath(filePath));
        }
        execWrite("rm -f " + shellEscapePath(tmpFile) + " " + shellEscapePath(filePath + ".tmp2"));

        int newTotalLines = fileLineCount(filePath);
        int insertedCount = newContent.split("\\n", -1).length;

        Map<String, Object> wr = new HashMap<>();
        wr.put("filePath", filePath);
        wr.put("originalStartRow", afterRow + 1);
        wr.put("originalEndRow", afterRow);
        wr.put("deletedLineCount", 0);
        wr.put("insertedLineCount", insertedCount);
        wr.put("oldContent", "");
        wr.put("newContent", newContent);
        wr.put("newStartRow", afterRow + 1);
        wr.put("newEndRow", afterRow + insertedCount);
        wr.put("totalLinesAfter", newTotalLines);
        return wr;
    }

    // --- replaceByAnchor ---

    private Object replaceByAnchorViaExec(String filePath, String anchorText, int beforeCount, int afterCount, String newContent) throws Exception {
        // 1. 找到唯一的锚点行
        int anchorRow = findUniqueLine(filePath, anchorText);
        int startRow = anchorRow - beforeCount;
        int endRow = anchorRow + afterCount;
        int totalLines = fileLineCount(filePath);
        if (startRow < 1) startRow = 1;
        if (endRow > totalLines) endRow = totalLines;

        String oldContent = exec("sed -n '" + startRow + "," + endRow + "p' " + shellEscapePath(filePath));

        if (newContent == null || newContent.isEmpty()) {
            execWrite("sed -i '" + startRow + "," + endRow + "d' " + shellEscapePath(filePath));
        } else {
            String tmpFile = filePath + ".tmp";
            execWrite("printf '%s\\n' " + shellEscapePath(newContent) + " > " + shellEscapePath(tmpFile));
            execWrite("sed -i '" + startRow + "," + endRow + "d;" + startRow + "r " + shellEscapePath(tmpFile) + "' " + shellEscapePath(filePath));
            execWrite("rm -f " + shellEscapePath(tmpFile));
        }

        int newTotalLines = fileLineCount(filePath);
        int deletedCount = endRow - startRow + 1;
        int insertedCount = (newContent == null || newContent.isEmpty()) ? 0 : newContent.split("\\n", -1).length;

        Map<String, Object> wr = new HashMap<>();
        wr.put("filePath", filePath);
        wr.put("originalStartRow", startRow);
        wr.put("originalEndRow", endRow);
        wr.put("deletedLineCount", deletedCount);
        wr.put("insertedLineCount", insertedCount);
        wr.put("oldContent", oldContent);
        wr.put("newContent", newContent);
        wr.put("newStartRow", startRow);
        wr.put("newEndRow", startRow + insertedCount - 1);
        wr.put("totalLinesAfter", newTotalLines);
        return wr;
    }

    // --- insertAfterAnchor ---

    private Object insertAfterAnchorViaExec(String filePath, String anchorText, String newContent) throws Exception {
        int anchorRow = findUniqueLine(filePath, anchorText);
        return insertLinesViaExec(filePath, anchorRow, newContent);
    }

    // --- insertBeforeAnchor ---

    private Object insertBeforeAnchorViaExec(String filePath, String anchorText, String newContent) throws Exception {
        int anchorRow = findUniqueLine(filePath, anchorText);
        return insertLinesViaExec(filePath, anchorRow - 1, newContent);
    }

    /** 查找唯一匹配的行号，若匹配多行则抛异常 */
    private int findUniqueLine(String filePath, String text) throws Exception {
        String out = exec("grep -n -F " + shellEscapePath(text) + " " + shellEscapePath(filePath));
        if (out == null || out.trim().isEmpty()) {
            throw new IllegalArgumentException("Anchor text not found: " + text);
        }
        String[] lines = out.split("\\n");
        if (lines.length > 1) {
            throw new IllegalArgumentException("Ambiguous anchor: " + lines.length + " matches for: " + text);
        }
        int colon = lines[0].indexOf(':');
        return Integer.parseInt(lines[0].substring(0, colon));
    }

    private String optNullableText(JsonNode arr, int index) {
        JsonNode node = index < arr.size() ? arr.get(index) : null;
        if (node == null || node.isNull()) return null;
        return node.asText(null);
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) return new HashMap<>();
        Map<String, Object> map = new HashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode v = entry.getValue();
            if (v.isNull()) continue;
            if (v.isNumber()) {
                map.put(entry.getKey(), v.intValue());
            } else if (v.isBoolean()) {
                map.put(entry.getKey(), v.booleanValue());
            } else {
                map.put(entry.getKey(), v.asText());
            }
        }
        return map;
    }

    // ==================== 错误详情 ====================

    private void attachErrorDetails(ObjectNode response, Exception e) {
        if (e instanceof IFileContentOperator.RowCheckFailedException) {
            IFileContentOperator.RowCheckFailedException ex = (IFileContentOperator.RowCheckFailedException) e;
            ObjectNode d = mapper.createObjectNode();
            d.put("filePath", ex.getFilePath());
            d.put("checkedRow", ex.getCheckedRow());
            d.put("expectedText", ex.getExpectedText());
            d.put("actualContent", ex.getActualContent());
            d.put("suggestedRow", ex.getSuggestedRow());
            response.set("errorDetails", d);
        } else if (e instanceof IFileContentOperator.AmbiguousAnchorException) {
            IFileContentOperator.AmbiguousAnchorException ex = (IFileContentOperator.AmbiguousAnchorException) e;
            ObjectNode d = mapper.createObjectNode();
            ArrayNode rows = mapper.createArrayNode();
            if (ex.getMatchedRows() != null) {
                for (Integer r : ex.getMatchedRows()) rows.add((int) r);
            }
            d.set("matchedRows", rows);
            response.set("errorDetails", d);
        }
    }

    // ==================== Java 对象 → JsonNode ====================

    private JsonNode toJackson(Object obj) {
        if (obj == null) return mapper.nullNode();
        if (obj instanceof String || obj instanceof Number || obj instanceof Boolean) {
            return mapper.valueToTree(obj);
        }
        // 所有 IFileContentOperator 数据类和 List 都能被 ObjectMapper 自动序列化
        return mapper.valueToTree(obj);
    }
}
