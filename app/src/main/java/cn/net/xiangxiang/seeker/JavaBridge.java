package cn.net.xiangxiang.seeker;

import android.webkit.JavascriptInterface;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import cn.net.xiangxiang.reaction.frontend.tools.FrontendJavaTools;
import cn.net.xiangxiang.reaction.frontend.tools.file.IFileContentOperator;

public class JavaBridge {
    private static final Logger log = Logger.getLogger(JavaBridge.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();
    private final FrontendJavaTools tools;

    public JavaBridge() {
        this(new FrontendJavaTools());
    }

    public JavaBridge(FrontendJavaTools tools) {
        this.tools = tools;
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
                List<IFileContentOperator.SearchResult> search = tools.search(rootDir, filePattern, contentRegex, contextLineCount);
                return search;
            }

            case "readLines": {
                String filePath = args.get(0).asText();
                int startRow = args.get(1).asInt();
                int endRow = args.get(2).asInt();
                return tools.readLines(filePath, startRow, endRow);
            }

            case "listFiles": {
                String directory = args.get(0).asText();
                String filePattern = optNullableText(args, 1);
                int maxDepth = args.get(2).asInt();
                return tools.listFiles(directory, filePattern, maxDepth);
            }

            case "replaceLines": {
                String filePath = args.get(0).asText();
                String newContent = optNullableText(args, 1);
                String startLineText = args.get(2).asText();
                String endLineText = args.get(3).asText();
                int startRow = args.get(4).asInt();
                int endRow = args.get(5).asInt();
                return tools.replaceLines(filePath, newContent, startLineText, endLineText, startRow, endRow);
            }

            case "insertLines": {
                String filePath = args.get(0).asText();
                int afterRow = args.get(1).asInt();
                String newContent = args.get(2).asText();
                return tools.insertLines(filePath, afterRow, newContent);
            }

            case "replaceByAnchor": {
                String filePath = args.get(0).asText();
                String anchorText = args.get(1).asText();
                int beforeCount = args.get(2).asInt();
                int afterCount = args.get(3).asInt();
                String newContent = optNullableText(args, 4);
                return tools.replaceByAnchor(filePath, anchorText, beforeCount, afterCount, newContent);
            }

            case "insertAfterAnchor": {
                String filePath = args.get(0).asText();
                String anchorText = args.get(1).asText();
                String newContent = args.get(2).asText();
                return tools.insertAfterAnchor(filePath, anchorText, newContent);
            }

            case "insertBeforeAnchor": {
                String filePath = args.get(0).asText();
                String anchorText = args.get(1).asText();
                String newContent = args.get(2).asText();
                return tools.insertBeforeAnchor(filePath, anchorText, newContent);
            }

            default:
                throw new IllegalArgumentException("Unknown method: " + methodName);
        }
    }

    // ==================== 参数解析辅助 ====================

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
