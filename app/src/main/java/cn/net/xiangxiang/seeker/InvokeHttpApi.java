package cn.net.xiangxiang.seeker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

public class InvokeHttpApi {
    private static final Logger log = Logger.getLogger(InvokeHttpApi.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();
    private final JavaBridge javaBridge;
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public InvokeHttpApi(JavaBridge javaBridge) {
        this.javaBridge = javaBridge;
        startServer();
    }

    private void startServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(9876);
                running = true;
                log.info("InvokeHttpApi server started on port 9876");
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        handleClient(client);
                    } catch (Exception e) {
                        if (running) {
                            log.warning("Accept error: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                log.severe("Could not start server: " + e.getMessage());
            }
        }, "InvokeHttpApi").start();
    }


    private void handleClient(Socket client) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            OutputStream output = client.getOutputStream();
            try {
                String requestLine = reader.readLine();
                if (requestLine == null) return;
                String[] parts = requestLine.split(" ");
                String method = parts[0];
                String path = parts[1];

                int contentLength = 0;
                while (true) {
                    String line = reader.readLine();
                    if (line == null || line.isEmpty()) break;
                    if (line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }

                String body = "";
                if ("POST".equalsIgnoreCase(method) && contentLength > 0) {
                    char[] buf = new char[contentLength];
                    int totalRead = 0;
                    while (totalRead < contentLength) {
                        int read = reader.read(buf, totalRead, contentLength - totalRead);
                        if (read == -1) break;
                        totalRead += read;
                    }
                    body = new String(buf, 0, totalRead);
                }

                String response;
                if ("POST".equalsIgnoreCase(method) && "/invokeMethod".equals(path)) {
                    try {
                        JsonNode root = mapper.readTree(body);
                        String methodName = root.get("methodName").asText();
                        String params = root.has("params") ? root.get("params").toString() : null;
                        String result = javaBridge.invokeMethod(methodName, params);
                        response = result;
                    } catch (Exception e) {
                        ObjectNode err = mapper.createObjectNode();
                        err.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
                        response = err.toString();
                        log.warning("handleClient invokeMethod error: " + e.getMessage());
                    }
                } else {
                    ObjectNode err = mapper.createObjectNode();
                    err.put("error", "Only POST /invokeMethod allowed");
                    response = err.toString();
                }

                byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
                String header = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + respBytes.length + "\r\nConnection: close\r\n\r\n";
                output.write(header.getBytes(StandardCharsets.UTF_8));
                output.write(respBytes);
                output.flush();
            } finally {
                try { client.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.warning("Fatal error handling client: " + e.getMessage());
            try { client.close(); } catch (Exception ignored) {}
        }
    }


    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            // ignore
        }
    }
}

