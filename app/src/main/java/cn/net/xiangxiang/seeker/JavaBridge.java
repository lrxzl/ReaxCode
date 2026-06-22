package cn.net.xiangxiang.seeker;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebResourceRequest;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import cn.net.xiangxiang.reaction.frontend.tools.FrontendJavaTools;
import cn.net.xiangxiang.reaction.frontend.tools.file.IFileContentOperator;

public class JavaBridge {
    private static final Logger log = Logger.getLogger(JavaBridge.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();
    public static final int FILE_CHOOSER_REQUEST_CODE_JBRIDGE = 10011;
    private final FrontendJavaTools tools;
    private final Activity activity;
    private final WebView homeWebView;
    private volatile CountDownLatch fileChooserLatch;
    private volatile Uri fileChooserResultUri;


    /** 浮动 WebView 条目，包含 WebView 实例和 URL */
    private static class WebViewEntry {
        final FloatingWebView floatingWebView;
        final String url;
        WebViewEntry(FloatingWebView floatingWebView, String url) {
            this.floatingWebView = floatingWebView;
            this.url = url;
        }
    }

    /**
     * LinkedHashMap 保持插入顺序：
     * - 第一个 entry 就是最老的（最先被淘汰）
     * - 最后一个 entry 就是最新的
     * 所有读写均通过 synchronized(floatingWebViewMap) 保护线程安全
     */
    private final LinkedHashMap<String, WebViewEntry> floatingWebViewMap = new LinkedHashMap<>();

    public JavaBridge(Activity activity, FrontendJavaTools tools, WebView homeWebView) {
        this.tools = tools;
        this.activity = activity;
        this.homeWebView = homeWebView;
    }

    // ==================== 简单接口（无需回调） ====================

    @JavascriptInterface
    public void openWebView(String url) {
        log.info("[openWebView] url=" + url);
        openFloatingWebView(url);
    }

    // ==================== 同步入口（向后兼容，仅适用于快速操作） ====================

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

    // ==================== 异步入口（Promise + 回调） ====================

    @JavascriptInterface
    public void invokeMethodAsync(String methodName, String jsonParams, String callbackId) {
        log.info("[invokeMethodAsync] queued: " + methodName + ", callbackId=" + callbackId);
        new Thread(() -> {
            ObjectNode response = mapper.createObjectNode();
            try {
                JsonNode argsNode = (jsonParams == null || jsonParams.isEmpty())
                    ? mapper.createArrayNode() : mapper.readTree(jsonParams);
                Object result = dispatch(methodName, argsNode);
                response.set("data", toJackson(result));
                response.put("success", true);
                log.info("[invokeMethodAsync] " + methodName + " ok");
            } catch (Exception e) {
                log.severe("[invokeMethodAsync] " + methodName + " error: " + e.getMessage());
                response.putNull("data");
                response.put("success", false);
                response.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
                response.put("errorType", e.getClass().getSimpleName());
                attachErrorDetails(response, e);
            }
            deliverCallback(callbackId, response.toString());
        }, "JB-" + methodName).start();
    }

    /** 将结果回调到 JS 端 */
    private void deliverCallback(String callbackId, String resultJson) {
        if (homeWebView == null || activity == null) {
            log.warning("[deliverCallback] homeWebView or activity is null, cannot deliver");
            return;
        }
        String escaped = escapeForJsString(resultJson);
        /*String js = "if(window.__handleJavaBridgeCallback){"
            + "window.__handleJavaBridgeCallback('" + callbackId + "',JSON.parse('" + escaped + "'));"
            + "}";*/
        String js = "if(window.__handleJavaBridgeCallback){" +
            "window.__handleJavaBridgeCallback('" + callbackId + "'," + resultJson + ");" +
            "}";
        activity.runOnUiThread(() -> {
            try {
                homeWebView.evaluateJavascript(js, null);
            } catch (Exception e) {
                log.severe("[deliverCallback] evaluateJavascript failed: " + e.getMessage());
            }
        });
    }

    private String escapeForJsString(String s) {
        if (s == null) return "";
        return s
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029");
    }

    // ==================== 注入 JS 桥接辅助代码 ====================

    public static String getBridgeJsCode() {
        return JavaBridgeConstants.BRIDGE_JS;
    }

    public void injectBridgeJs() {
        if (homeWebView != null) {
            homeWebView.evaluateJavascript(JavaBridgeConstants.BRIDGE_JS, null);
        }
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

            case "openOrGetWebViewByUrl": {
                String url = args.get(0).asText();
                return openOrGetWebViewByUrl(url);
            }

            case "getCurrentWebViewListInfos": {
                return getCurrentWebViewListInfos();
            }

            case "runJavaScriptOnWebView": {
                String webViewId = args.get(0).asText();
                String script = args.get(1).asText();
                FloatingWebView webView;
                synchronized (floatingWebViewMap) {
                    WebViewEntry entry = floatingWebViewMap.get(webViewId);
                    if (entry == null) {
                        throw new IllegalArgumentException("WebView with id " + webViewId + " not found");
                    }
                    webView = entry.floatingWebView;
                }
                return evaluateJavascriptSync(webView, script);
            }

            case "closeWebViews": {
                JsonNode idNode = args.get(0);
                if (idNode.isArray()) {
                    for (JsonNode n : idNode) {
                        closeWebView(n.asText());
                    }
                } else {
                    String id = idNode.asText();
                    if ("all".equalsIgnoreCase(id)) {
                        closeAllWebViews();
                    } else {
                        closeWebView(id);
                    }
                }
                Map<String, Object> result = new HashMap<>();
                result.put("closed", true);
                return result;
            }

            case "openFileChooser": {
                String acceptType = optNullableText(args, 0);
                return openFileChooserInternal(acceptType);
            }

            default:
                throw new IllegalArgumentException("Unknown method: " + methodName);
        }
    }

    // ==================== 浮动 WebView 管理 ====================

    /**
     * 打开或获取已有 WebView：
     * 1. 若 URL 已有对应 WebView，直接返回其 id
     * 2. 若达到 MAX_WEB_VIEW_COUNT 上限，自动关闭最老的（LinkedHashMap 第一个 entry）
     * 3. 创建新的浮动 WebView 并返回 id
     */
    public String openOrGetWebViewByUrl(String url) throws Exception {
        synchronized (floatingWebViewMap) {
            // 1. 查找是否已有相同 URL 的 WebView
            /*for (Map.Entry<String, WebViewEntry> entry : floatingWebViewMap.entrySet()) {
                if (url.equals(entry.getValue().url)) {
                    String existId = entry.getKey();
                    log.info("[openOrGetWebViewByUrl] Found existing WebView: id=" + existId + ", url=" + url);
                    return existId;
                }
            }*/

            // 2. 超过上限则关闭最老的 — LinkedHashMap 第一个 entry 就是最老的
            while (floatingWebViewMap.size() >= JavaBridgeConstants.MAX_WEB_VIEW_COUNT) {
                String oldestId = floatingWebViewMap.keySet().iterator().next();
                log.info("[openOrGetWebViewByUrl] Max count reached, closing oldest: id=" + oldestId);
                closeWebViewInternal(oldestId);
            }
        }

        // 3. 创建新的浮动 WebView
        String newId = openFloatingWebView(url);
        log.info("[openOrGetWebViewByUrl] Created new WebView: id=" + newId + ", url=" + url);
        return newId;
    }

    /** 打开浮动 WebView，同步返回其 id */
    public String openFloatingWebView(String url) {
        final String finalUrl = url;
        final String[] idHolder = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);

        activity.runOnUiThread(() -> {
            try {
                FloatingWebView floating = new FloatingWebView(activity);
                floating.setOnCloseListener(this::removeFromMap);
                floating.getWebView().addJavascriptInterface(JavaBridge.this, "SubWebViewBridge");
                floating.getWebView().setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, String url) {
                        if (PaymentSchemeHandler.handlePaymentUrl(activity, view, url)) {
                            return true;
                        }
                        return super.shouldOverrideUrlLoading(view, url);
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        if (request != null && request.getUrl() != null) {
                            String url = request.getUrl().toString();
                            if (PaymentSchemeHandler.handlePaymentUrl(activity, view, url)) {
                                return true;
                            }
                        }
                        return super.shouldOverrideUrlLoading(view, request);
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        view.evaluateJavascript(JavaBridgeConstants.BRIDGE_JS, null);
                    }
                });
                floating.loadUrl(finalUrl);
                ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
                decorView.addView(floating);
                String newId = String.valueOf(JavaBridgeConstants.floatingWebViewIdSeq.incrementAndGet());
                synchronized (floatingWebViewMap) {
                    floatingWebViewMap.put(newId, new WebViewEntry(floating, finalUrl));
                }
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

    /**
     * 获取所有浮动 WebView 的信息列表
     * 每项包含：id、url、title、innerText（截取前 200 字符）
     */
    public List<Map<String, Object>> getCurrentWebViewListInfos() throws Exception {
        List<Map.Entry<String, WebViewEntry>> snapshot;
        synchronized (floatingWebViewMap) {
            snapshot = new ArrayList<>(floatingWebViewMap.entrySet());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, WebViewEntry> mapEntry : snapshot) {
            String id = mapEntry.getKey();
            WebViewEntry wvEntry = mapEntry.getValue();

            Map<String, Object> info = new HashMap<>();
            info.put("id", id);
            info.put("url", wvEntry.url);

            // 在 UI 线程获取 title
            final String[] titleHolder = new String[1];
            final CountDownLatch infoLatch = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    titleHolder[0] = wvEntry.floatingWebView.getWebView().getTitle();
                } finally {
                    infoLatch.countDown();
                }
            });
            infoLatch.await(5, TimeUnit.SECONDS);
            info.put("title", titleHolder[0] != null ? titleHolder[0] : "");

            // 通过 JS 获取 innerText（截取前 200 字符避免大字符串传输）
            String rawInnerText = evaluateJavascriptSync(wvEntry.floatingWebView,
                "(function(){" +
                    "  try {" +
                    "    var t = document.body ? document.body.innerText : '';" +
                    "    return t.substring(0, 200);" +
                    "  } catch(e) { return ''; }" +
                    "})()");

            String innerText = "";
            if (rawInnerText != null && !"null".equals(rawInnerText)) {
                try {
                    innerText = mapper.readValue(rawInnerText, String.class);
                } catch (Exception e) {
                    innerText = rawInnerText;
                    if (innerText.startsWith("\"") && innerText.endsWith("\"") && innerText.length() >= 2) {
                        innerText = innerText.substring(1, innerText.length() - 1)
                            .replace("\\n", "\n")
                            .replace("\\t", "\t")
                            .replace("\\\"", "\"");
                    }
                }
            }
            info.put("innerText", innerText != null ? innerText : "");

            result.add(info);
        }
        return result;
    }

    /** 从 map 中移除指定的 FloatingWebView（当 WebView 主动关闭时调用） */
    private void removeFromMap(FloatingWebView webView) {
        synchronized (floatingWebViewMap) {
            Iterator<Map.Entry<String, WebViewEntry>> iterator = floatingWebViewMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, WebViewEntry> entry = iterator.next();
                if (entry.getValue().floatingWebView == webView) {
                    iterator.remove();
                    log.info("[removeFromMap] Removed WebView id=" + entry.getKey());
                    break;
                }
            }
        }
    }

    /** 关闭指定 id 的浮动 WebView（对外，含 UI 线程销毁） */
    private void closeWebView(String id) {
        closeWebViewInternal(id);
    }


    /**
     * 关闭指定 id 的浮动 WebView
     * 注意：如果调用方已持有 floatingWebViewMap 锁，此方法不会再加锁（避免死锁）
     *      由 openOrGetWebViewByUrl 内部调用时属于此场景
     */
    private void closeWebViewInternal(String id) {
        WebViewEntry entry;
        synchronized (floatingWebViewMap) {
            entry = floatingWebViewMap.remove(id);
        }
        destroyWebViewEntry(id, entry);
    }

    /** 关闭所有浮动 WebView */
    private void closeAllWebViews() {
        List<Map.Entry<String, WebViewEntry>> snapshot;
        synchronized (floatingWebViewMap) {
            snapshot = new ArrayList<>(floatingWebViewMap.entrySet());
            floatingWebViewMap.clear();
        }
        for (Map.Entry<String, WebViewEntry> mapEntry : snapshot) {
            destroyWebViewEntry(mapEntry.getKey(), mapEntry.getValue());
        }
        log.info("[closeAllWebViews] All WebViews closed, count=" + snapshot.size());
    }

    /** 从视图树移除并销毁 WebView */
    private void destroyWebViewEntry(String id, WebViewEntry entry) {
        if (entry != null && activity != null) {
            FloatingWebView floating = entry.floatingWebView;
            activity.runOnUiThread(() -> {
                try {
                    ViewGroup parent = (ViewGroup) floating.getParent();
                    if (parent != null) {
                        parent.removeView(floating);
                    }
                    floating.getWebView().destroy();
                } catch (Exception e) {
                    log.warning("[destroyWebViewEntry] Error destroying WebView id=" + id + ": " + e.getMessage());
                }
            });
        }
        log.info("[closeWebView] Closed WebView id=" + id);
    }

    /** 同步执行 JavaScript 并获取返回值（在子线程调用安全） */
    private String evaluateJavascriptSync(FloatingWebView webView, String script) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] resultHolder = new String[1];

        activity.runOnUiThread(() -> {
            webView.getWebView().evaluateJavascript(script, value -> {
                resultHolder[0] = value;
                latch.countDown();
            });
        });

        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new RuntimeException("JavaScript execution timeout after 10 seconds");
        }
        return resultHolder[0];
    }

    // ==================== 文件操作 via exec ====================

    private String exec(String command) throws Exception {
        Map<String, Object> opts = new HashMap<>();
        opts.put("timeoutMs", 30_000);
        opts.put("maxResultChars", 100_000);
        return (String) tools.exec(command, opts);
    }

    private void execWrite(String command) throws Exception {
        Map<String, Object> opts = new HashMap<>();
        opts.put("timeoutMs", 30_000);
        opts.put("maxResultChars", 1_000);
        tools.exec(command, opts);
    }

    // ==================== 文件选择器 ====================

    private Object openFileChooserInternal(String acceptType) throws Exception {
        fileChooserLatch = new CountDownLatch(1);
        fileChooserResultUri = null;

        activity.runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(acceptType != null && !acceptType.isEmpty() ? acceptType : "*/*");

            try {
                activity.startActivityForResult(
                    Intent.createChooser(intent, "选择文件"),
                    FILE_CHOOSER_REQUEST_CODE_JBRIDGE);
            } catch (Exception e) {
                log.severe("[openFileChooser] Failed to start file chooser: " + e.getMessage());
                fileChooserLatch.countDown();
            }
        });

        if (!fileChooserLatch.await(120, TimeUnit.SECONDS)) {
            throw new RuntimeException("File chooser timeout");
        }

        if (fileChooserResultUri == null) {
            return null;
        }

        return getPathFromUri(fileChooserResultUri);
    }

    public void onFileChooserResult(int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri resultUri = data.getData();
            if (resultUri != null) {
                fileChooserResultUri = resultUri;
            }

            android.content.ClipData clipData = data.getClipData();
            if (clipData != null && clipData.getItemCount() > 0) {
                fileChooserResultUri = clipData.getItemAt(0).getUri();
            }
        }
        if (fileChooserLatch != null) {
            fileChooserLatch.countDown();
        }
    }

    private String getPathFromUri(Uri uri) {
        if (uri == null) return null;

        if ("file".equals(uri.getScheme())) {
            return uri.getPath();
        }

        try {
            String[] projection = {MediaStore.Images.Media.DATA};
            Cursor cursor = activity.getContentResolver().query(uri, projection, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int columnIndex = cursor.getColumnIndexOrThrow(projection[0]);
                        String path = cursor.getString(columnIndex);
                        if (path != null) return path;
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            // Fall through
        }

        try {
            File tempDir = new File(activity.getCacheDir(), "file_chooser");
            if (!tempDir.exists()) tempDir.mkdirs();

            String ext = getExtFromUri(uri);
            File tempFile = new File(tempDir, "selected" + ext);

            try (InputStream is = activity.getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(tempFile)) {
                if (is == null) return uri.toString();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
            return tempFile.getAbsolutePath();
        } catch (Exception e) {
            return uri.toString();
        }
    }

    private String getExtFromUri(Uri uri) {
        String lastPath = uri.getLastPathSegment();
        if (lastPath != null && lastPath.contains(".")) {
            return lastPath.substring(lastPath.lastIndexOf("."));
        }
        return "";
    }

    private int fileLineCount(String filePath) throws Exception {
        String out = exec("wc -l < " + shellEscapePath(filePath));
        return Integer.parseInt(out.trim());
    }

    private String shellEscapePath(String path) {
        return "'" + path.replace("'", "'\\\\''") + "'";
    }

    // --- search ---
    private Object searchViaExec(String rootDir, String filePattern, String contentRegex, int contextLineCount) throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        StringBuilder cmd = new StringBuilder("grep -rnH -E ");
        if (contextLineCount > 0) {
            cmd.append("-C ").append(contextLineCount).append(" ");
        }
        cmd.append(shellEscapePath(contentRegex)).append(" ").append(shellEscapePath(rootDir));
        if (filePattern != null && !filePattern.isEmpty()) {
            cmd.append(" --include=").append(shellEscapePath(filePattern));
        }
        String out = exec(cmd.toString());
        if (out == null || out.trim().isEmpty()) return results;

        String[] groups = out.split("\\n--\\n|\\n--$");
        for (String group : groups) {
            if (group.trim().isEmpty()) continue;
            String[] lines = group.split("\\n");
            int matchIdx = -1;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.matches("^[^:]+:[0-9]+:.*")) {
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
        int s = Math.max(1, startRow);
        int e = Math.min(totalLines, endRow);
        if (s > e) {
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
    private Object listFilesViaExec(String directory, String filePattern, int maxDepth) throws Exception {
        if (maxDepth <= 0) maxDepth = 1;
        StringBuilder cmd = new StringBuilder("find ");
        cmd.append(shellEscapePath(directory));
        cmd.append(" -maxdepth ").append(maxDepth).append(" -type f");
        if (filePattern != null && !filePattern.isEmpty()) {
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
        int actualStartRow = findLineByText(filePath, startLineText, startRow);
        int actualEndRow = findLineByText(filePath, endLineText, endRow);
        if (actualStartRow > actualEndRow) {
            throw new IllegalArgumentException("startRow(" + actualStartRow + ") > endRow(" + actualEndRow + ")");
        }
        String oldContent = exec("sed -n '" + actualStartRow + "," + actualEndRow + "p' " + shellEscapePath(filePath));

        if (newContent == null || newContent.isEmpty()) {
            execWrite("sed -i '" + actualStartRow + "," + actualEndRow + "d' " + shellEscapePath(filePath));
        } else {
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

    private int findLineByText(String filePath, String text, int nearRow) throws Exception {
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
        execWrite("printf '%s\\n' " + shellEscapePath(newContent) + " > " + shellEscapePath(tmpFile));
        if (afterRow == 0) {
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
        return mapper.valueToTree(obj);
    }
}
