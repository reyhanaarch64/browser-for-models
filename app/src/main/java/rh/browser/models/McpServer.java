package rh.browser.models;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class McpServer {
    public static final int PORT = 9010;
    private final Context context;
    private final McpToolHandler handler;
    private final ExecutorService executor = Executors.newFixedThreadPool(12);
    private static final int MAX_REQUEST_BYTES = 24 * 1024 * 1024;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public interface McpToolHandler {
        JSONObject callTool(String name, JSONObject arguments) throws Exception;
    }

    public McpServer(Context context, McpToolHandler handler) {
        this.context = context.getApplicationContext();
        this.handler = handler;
    }

    public synchronized void start() throws IOException {
        if (running && acceptThread != null && acceptThread.isAlive() && serverSocket != null && !serverSocket.isClosed()) return;
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) { }
        serverSocket = new ServerSocket(PORT, 64, java.net.InetAddress.getByName("127.0.0.1"));
        running = true;
        acceptThread = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    Socket socket = null;
                    try {
                        ServerSocket ss = serverSocket;
                        if (ss == null || ss.isClosed()) {
                            if (running) {
                                try { Thread.sleep(250L); } catch (InterruptedException ignored) { }
                            }
                            continue;
                        }
                        final Socket accepted = ss.accept();
                        socket = accepted;
                        try {
                            executor.execute(new Runnable() {
                                public void run() {
                                    handle(accepted);
                                }
                            });
                            socket = null;
                        } catch (Throwable t) {
                            android.util.Log.e("PhoneToMCP", "executor", t);
                        }
                    } catch (Throwable e) {
                        if (running) {
                            android.util.Log.e("PhoneToMCP", "accept", e);
                            try { Thread.sleep(250L); } catch (InterruptedException ignored) { }
                        }
                    } finally {
                        if (socket != null) close(socket);
                    }
                }
            }
        }, "mcp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
        serverSocket = null;
        acceptThread = null;
    }

    public boolean isRunning() {
        Thread t = acceptThread;
        return running && t != null && t.isAlive() && serverSocket != null && !serverSocket.isClosed();
    }

    private void handle(Socket socket) {
        InputStream in = null;
        try {
            socket.setSoTimeout(60000);
            in = new BufferedInputStream(socket.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) {
                close(socket);
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                send(socket, 400, "application/json", jsonError(null, -32600, "request HTTP tidak valid").toString());
                return;
            }
            String method = parts[0].toUpperCase(Locale.US);
            String path = parts[1];
            Map<String, String> headers = new HashMap<String, String>();
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
            }

            if ("/health".equals(path)) {
                send(socket, 200, "application/json; charset=utf-8", "{\"ok\":true,\"service\":\"Rh Browser for Models\"}");
                return;
            }
            if (!path.equals("/mcp") && !path.startsWith("/mcp?")) {
                send(socket, 404, "application/json", "{\"error\":\"not found\"}");
                return;
            }
            if ("OPTIONS".equals(method)) {
                send(socket, 204, "application/json", "");
                return;
            }
            if ("GET".equals(method)) {
                send(socket, 405, "application/json", "{\"error\":\"MCP endpoint menggunakan POST JSON response\"}");
                return;
            }
            if (!"POST".equals(method)) {
                send(socket, 405, "application/json", "{\"error\":\"method not allowed\"}");
                return;
            }

            if (ToolSettings.authEnabled(context) && !authorized(headers, path)) {
                send(socket, 401, "application/json", "{\"error\":\"unauthorized\"}");
                return;
            }

            String contentLengthValue = headers.get("content-length");
            int contentLength;
            try { contentLength = contentLengthValue == null ? 0 : Integer.parseInt(contentLengthValue); }
            catch (NumberFormatException e) { contentLength = 0; }
            if (contentLength < 0 || contentLength > MAX_REQUEST_BYTES) {
                send(socket, 413, "application/json", "{\"error\":\"payload terlalu besar\"}");
                return;
            }
            byte[] bodyBytes = readBytes(in, contentLength);
            String body = new String(bodyBytes, Charset.forName("UTF-8"));
            if (body.trim().length() == 0) {
                send(socket, 400, "application/json", "{\"error\":\"body kosong\"}");
                return;
            }
            JSONObject req = new JSONObject(body);
            boolean notification = !req.has("id");
            JSONObject result = dispatch(req);
            if (notification) {
                send(socket, 202, "application/json", "");
            } else {
                send(socket, 200, "application/json; charset=utf-8", result.toString());
            }
        } catch (Exception e) {
            try {
                send(socket, 400, "application/json; charset=utf-8", jsonError(null, -32700, safeMessage(e)).toString());
            } catch (Exception ignored) {
            }
        } finally {
            close(socket);
        }
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        boolean gotAny = false;
        while ((b = in.read()) != -1) {
            gotAny = true;
            if (b == '\n') break;
            if (b != '\r') out.write(b);
        }
        if (!gotAny && out.size() == 0) return null;
        return new String(out.toByteArray(), Charset.forName("ISO-8859-1"));
    }

    private byte[] readBytes(InputStream in, int count) throws IOException {
        byte[] out = new byte[count];
        int pos = 0;
        while (pos < count) {
            int n = in.read(out, pos, count - pos);
            if (n < 0) throw new IOException("body HTTP terpotong");
            pos += n;
        }
        return out;
    }

    private boolean authorized(Map<String, String> headers, String path) {
        String expected = ToolSettings.token(context);
        String auth = headers.get("authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return expected.equals(auth.substring(7).trim());
        }
        int q = path.indexOf('?');
        if (q >= 0) {
            String query = path.substring(q + 1);
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "access_token".equals(pair.substring(0, eq))) {
                    return expected.equals(java.net.URLDecoder.decode(pair.substring(eq + 1)));
                }
            }
        }
        return false;
    }

    private JSONObject dispatch(JSONObject req) throws Exception {
        Object id = req.has("id") ? req.get("id") : null;
        String method = req.optString("method", "");
        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        if (id != null && id != JSONObject.NULL) response.put("id", id);

        if ("initialize".equals(method)) {
            JSONObject params = req.optJSONObject("params");
            String requested = params == null ? "2026-07-28" : params.optString("protocolVersion", "2026-07-28");
            String version = "2025-11-25".equals(requested) ? "2025-11-25" : "2026-07-28";
            JSONObject caps = new JSONObject();
            JSONObject tools = new JSONObject();
            tools.put("listChanged", false);
            caps.put("tools", tools);
            JSONObject info = new JSONObject();
            info.put("name", "Rh Browser for Models");
            info.put("version", "1.5.0-models-browser.beta");
            JSONObject r = new JSONObject();
            r.put("protocolVersion", version);
            r.put("capabilities", caps);
            r.put("serverInfo", info);
            r.put("instructions", "Rh Browser for Models menyediakan browser WebView dan sistem workflow. Workflow .flow dan .bshflow boleh berjalan tanpa endpoint, memakai server bersama port 8084, atau memakai satu port eksklusif per workflow (maksimal lima port eksklusif aktif). Workflow dapat meminta input interaktif; gunakan workflow.start agar eksekusi tidak menahan permintaan MCP ketika menunggu input, lalu workflow.get_pending_input untuk melihat prompt, workflow.submit_input untuk mengirim jawaban hanya jika permissions.aiWriteInput aktif pada workflow tersebut, dan workflow.get_execution untuk memantau status serta hasil akhir. workflow.run tetap sinkron. Log persisten tersedia melalui workflow.get_info_logs, workflow.get_error_logs, atau workflow.get_logs.");
            response.put("result", r);
            return response;
        }
        if ("notifications/initialized".equals(method) || "notifications/cancelled".equals(method)) return response;
        if ("ping".equals(method)) {
            response.put("result", new JSONObject());
            return response;
        }
        if ("tools/list".equals(method)) {
            JSONObject r = new JSONObject();
            r.put("tools", ToolRegistry.toJson(context));
            response.put("result", r);
            return response;
        }
        if ("resources/list".equals(method)) {
            JSONObject r = new JSONObject();
            r.put("resources", new JSONArray());
            response.put("result", r);
            return response;
        }
        if ("prompts/list".equals(method)) {
            JSONObject r = new JSONObject();
            r.put("prompts", new JSONArray());
            response.put("result", r);
            return response;
        }
        if ("tools/call".equals(method)) {
            JSONObject params = req.optJSONObject("params");
            if (params == null) return jsonError(id, -32602, "params wajib ada");
            String toolName = params.optString("name", "");
            JSONObject args = params.optJSONObject("arguments");
            if (args == null) args = new JSONObject();
            response.put("result", handler.callTool(toolName, args));
            return response;
        }
        return jsonError(id, -32601, "method tidak didukung: " + method);
    }

    public static JSONObject jsonError(Object id, int code, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("jsonrpc", "2.0");
            if (id == null) o.put("id", JSONObject.NULL);
            else o.put("id", id);
            JSONObject err = new JSONObject();
            err.put("code", code);
            err.put("message", message);
            o.put("error", err);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static String safeMessage(Exception e) {
        String s = e.getMessage();
        if (s == null || s.length() == 0) return e.getClass().getSimpleName();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private void send(Socket socket, int code, String type, String body) throws IOException {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        byte[] bytes = body == null ? new byte[0] : body.getBytes(Charset.forName("UTF-8"));
        String status = code == 200 ? "OK" : code == 202 ? "Accepted" : code == 400 ? "Bad Request" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found" : code == 405 ? "Method Not Allowed" : code == 413 ? "Payload Too Large" : "Error";
        String header = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Content-Type, Authorization, MCP-Protocol-Version, Mcp-Method, Mcp-Name\r\n"
                + "\r\n";
        out.write(header.getBytes(Charset.forName("UTF-8")));
        out.write(bytes);
        out.flush();
    }

    private void close(Socket socket) {
        try { socket.close(); } catch (Exception ignored) { }
    }
}
