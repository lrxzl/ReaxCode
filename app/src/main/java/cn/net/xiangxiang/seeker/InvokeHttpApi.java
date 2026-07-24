package cn.net.xiangxiang.seeker;

import android.util.Log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import fi.iki.elonen.NanoHTTPD;

public class InvokeHttpApi extends NanoHTTPD {

    private static final String TAG = "InvokeHttpApi";
    private final JavaBridge javaBridge;
    private final ObjectMapper mapper = new ObjectMapper();

    public InvokeHttpApi(JavaBridge javaBridge, int port) throws IOException {
        super(port);
        this.javaBridge = javaBridge;
        start(SOCKET_READ_TIMEOUT, false);
        Log.i(TAG, "Server started on port " + port);
    }

    @Override
    public Response serve(IHTTPSession session) {
        // 关键：处理 CORS 预检请求 (OPTIONS)
        if (Method.OPTIONS == session.getMethod()) {
            return corsEnabledResponse(newFixedLengthResponse(""));
        }

        // 只允许 POST + /invokeMethod
        if (Method.POST != session.getMethod() || !"/invokeMethod".equals(session.getUri())) {
            return corsEnabledResponse(
                newFixedLengthResponse(Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT, "Only POST /invokeMethod is supported")
            );
        }

        try {
            // 安全读取 JSON body
            String bodyStr = readRequestBody(session.getInputStream(),
                session.getHeaders().get("content-length"));
            Log.d(TAG, "Received body: " + bodyStr);

            if (bodyStr.isEmpty()) {
                return corsEnabledResponse(
                    jsonResponse(Response.Status.BAD_REQUEST, "Empty request body")
                );
            }

            JsonNode root = mapper.readTree(bodyStr);
            if (!root.has("methodName")) {
                return corsEnabledResponse(
                    jsonResponse(Response.Status.BAD_REQUEST, "Missing 'methodName'")
                );
            }

            String methodName = root.get("methodName").asText();
            String params = root.has("params") ? root.get("params").toString() : null;

            String result = javaBridge.invokeMethod(methodName, params);
            return corsEnabledResponse(
                newFixedLengthResponse(Response.Status.OK,
                    "application/json; charset=utf-8", result)
            );

        } catch (Exception e) {
            Log.e(TAG, "Error handling request", e);
            return corsEnabledResponse(
                jsonResponse(Response.Status.INTERNAL_ERROR,
                    e.getMessage() != null ? e.getMessage() : e.toString())
            );
        }
    }

    /**
     * 读取指定长度的请求体，避免 NanoHTTPD 默认解析带来的问题
     */
    private String readRequestBody(InputStream inputStream, String contentLengthStr) throws IOException {
        int contentLength = 0;
        try {
            contentLength = Integer.parseInt(contentLengthStr != null ? contentLengthStr : "0");
        } catch (NumberFormatException ignored) {}

        if (contentLength <= 0) {
            // 无 Content-Length 时尝试读取全部可用数据
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[4096];
            int n;
            while ((n = inputStream.read(data)) != -1) {
                buffer.write(data, 0, n);
                if (inputStream.available() <= 0) break;
            }
            return buffer.toString("UTF-8");
        }

        byte[] bodyBytes = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int read = inputStream.read(bodyBytes, offset, contentLength - offset);
            if (read == -1) break;
            offset += read;
        }
        return new String(bodyBytes, 0, offset, StandardCharsets.UTF_8);
    }

    /**
     * 为所有响应添加 CORS 头，支持跨域访问
     */
    private Response corsEnabledResponse(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
        return response;
    }

    private Response jsonResponse(Response.Status status, String errorMsg) {
        ObjectNode err = mapper.createObjectNode();
        err.put("error", errorMsg);
        return newFixedLengthResponse(status,
            "application/json; charset=utf-8", err.toString());
    }

    public void shutdown() {
        stop();
        Log.i(TAG, "Server stopped");
    }
}
