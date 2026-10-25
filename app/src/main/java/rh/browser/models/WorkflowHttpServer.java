package rh.browser.models;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local workflow HTTP endpoint coordinator. Port 8084 is shared by workflows using
 * endpoint.mode=shared. Each endpoint.mode=exclusive workflow owns one separate listener.
 */
public final class WorkflowHttpServer {
    private static final String TAG = "RhWorkflowHttp";
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static volatile WorkflowHttpServer current;

    private final Context context;
    private final WorkflowManager manager;
    private volatile Listener sharedListener;
    private final java.util.concurrent.ConcurrentHashMap<String, Listener> exclusiveListeners = new java.util.concurrent.ConcurrentHashMap<String, Listener>();
    private final AtomicBoolean reconcileScheduled = new AtomicBoolean(false);
    private final AtomicBoolean reconcileAgain = new AtomicBoolean(false);
    private volatile boolean started;

    public WorkflowHttpServer(Context context, WorkflowManager manager) {
        this.context = context.getApplicationContext();
        this.manager = manager;
        current = this;
    }

    public static void reconcileCurrent() {
        final WorkflowHttpServer server = current;
        if (server == null || !server.started) return;
        if (!server.reconcileScheduled.compareAndSet(false, true)) {
            server.reconcileAgain.set(true);
            return;
        }
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    do {
                        server.reconcileAgain.set(false);
                        try { server.reconcileExclusiveListeners(); }
                        catch (Exception e) { Log.e(TAG, "reconcile workflow endpoints gagal", e); }
                    } while (server.started && server.reconcileAgain.getAndSet(false));
                } finally {
                    server.reconcileScheduled.set(false);
                    if (server.started && server.reconcileAgain.get()) reconcileCurrent();
                }
            }
        }, "rh-workflow-endpoints-reconcile");
        worker.setDaemon(true);
        worker.start();
    }

    public static boolean isEndpointRunning(String workflowId, String mode) {
        WorkflowHttpServer server = current;
        if (server == null || !server.started) return false;
        if ("shared".equals(mode)) return server.sharedListener != null && server.sharedListener.isRunning();
        if ("exclusive".equals(mode)) {
            Listener listener = server.exclusiveListeners.get(workflowId);
            return listener != null && listener.isRunning();
        }
        return false;
    }

    public synchronized void start() throws Exception {
        if (started) { reconcileExclusiveListeners(); return; }
        // This object coordinates all workflow endpoints. Port 8084 is bound only when
        // at least one enabled workflow explicitly requests the shared endpoint mode.
        started = true;
        context.getSharedPreferences("workflow_server", Context.MODE_PRIVATE).edit().putBoolean("running", true).apply();
        try {
            reconcileExclusiveListeners();
            Log.i(TAG, "Workflow endpoint coordinator aktif; port bersama dibuka hanya jika diperlukan");
        } catch (Exception e) {
            stop();
            throw e;
        }
    }

    public synchronized void stop() {
        started = false;
        if (sharedListener != null) { sharedListener.stop(); sharedListener = null; }
        for (Listener l : new ArrayList<Listener>(exclusiveListeners.values())) l.stop();
        exclusiveListeners.clear();
        context.getSharedPreferences("workflow_server", Context.MODE_PRIVATE).edit().putBoolean("running", false).apply();
        if (current == this) current = null;
    }

    public synchronized boolean isRunning() {
        return started;
    }

    private synchronized void reconcileExclusiveListeners() throws Exception {
        if (!started) return;
        JSONArray workflows = manager.listWorkflows();
        Map<String, JSONObject> desired = new HashMap<String, JSONObject>();
        boolean sharedRequired = false;
        String sharedOwner = "";
        for (int i = 0; i < workflows.length(); i++) {
            JSONObject summary = workflows.optJSONObject(i);
            if (summary == null || !summary.optBoolean("enabled", false) || !summary.optBoolean("valid", false)) continue;
            String id = summary.optString("id", "");
            JSONObject meta = manager.getMetadata(id);
            if (meta == null) continue;
            JSONObject endpoint = meta.optJSONObject("endpoint");
            if (endpoint == null || !endpoint.optBoolean("enabled", false)) continue;
            String mode = endpoint.optString("mode", "none");
            if ("shared".equals(mode)) {
                sharedRequired = true;
                if (sharedOwner.length() == 0) sharedOwner = id;
            } else if ("exclusive".equals(mode)) {
                desired.put(id, meta);
            }
        }

        if (sharedRequired) {
            if (sharedListener == null || !sharedListener.isRunning()) {
                if (sharedListener != null) { sharedListener.stop(); sharedListener = null; }
                Listener candidate = new Listener(WorkflowManager.ENDPOINT_PORT, null);
                try {
                    candidate.start();
                    sharedListener = candidate;
                    manager.addLog(sharedOwner, "", "info", "endpoint.shared_started", "Server bersama aktif di port " + WorkflowManager.ENDPOINT_PORT, "metadata.json", 0, "port=" + WorkflowManager.ENDPOINT_PORT);
                } catch (Exception e) {
                    candidate.stop();
                    sharedListener = null;
                    manager.addLog(sharedOwner, "", "error", "endpoint.shared_bind_failed", "Server bersama gagal membuka port " + WorkflowManager.ENDPOINT_PORT, "metadata.json", 0, safe(e));
                }
            }
        } else if (sharedListener != null) {
            sharedListener.stop();
            sharedListener = null;
            Log.i(TAG, "Server bersama dihentikan karena tidak ada workflow aktif yang memakainya");
        }

        ArrayList<String> existingIds = new ArrayList<String>(exclusiveListeners.keySet());
        for (int i = 0; i < existingIds.size(); i++) {
            String id = existingIds.get(i);
            Listener existing = exclusiveListeners.get(id);
            JSONObject meta = desired.get(id);
            int wantedPort = -1;
            if (meta != null) {
                JSONObject ep = meta.optJSONObject("endpoint");
                wantedPort = ep == null ? -1 : ep.optInt("port", 0);
            }
            if (meta == null || existing == null || (wantedPort > 0 && wantedPort != existing.port)) {
                if (existing != null) existing.stop();
                exclusiveListeners.remove(id);
            }
        }

        ArrayList<String> desiredIds = new ArrayList<String>(desired.keySet());
        java.util.Collections.sort(desiredIds, String.CASE_INSENSITIVE_ORDER);
        for (int desiredIndex = 0; desiredIndex < desiredIds.size(); desiredIndex++) {
            String id = desiredIds.get(desiredIndex);
            if (exclusiveListeners.containsKey(id)) {
                Listener existing = exclusiveListeners.get(id);
                JSONObject retainedMeta = desired.get(id);
                JSONObject retainedEndpoint = retainedMeta == null ? null : retainedMeta.optJSONObject("endpoint");
                if (existing != null && retainedEndpoint != null && retainedEndpoint.optBoolean("autoPort", false)
                        && retainedEndpoint.optInt("port", 0) == 0) manager.setAssignedEndpointPort(id, existing.port);
                continue;
            }
            if (exclusiveListeners.size() >= WorkflowManager.MAX_EXCLUSIVE_ENDPOINTS) {
                manager.addLog(id, "", "error", "endpoint.port_limit", "Batas lima port eksklusif aktif telah tercapai", "metadata.json", 0, "max=" + WorkflowManager.MAX_EXCLUSIVE_ENDPOINTS);
                continue;
            }
            JSONObject meta = desired.get(id);
            JSONObject endpoint = meta.optJSONObject("endpoint");
            int requestedPort = endpoint == null ? 0 : endpoint.optInt("port", 0);
            boolean autoPort = endpoint != null && endpoint.optBoolean("autoPort", requestedPort == 0);
            Listener listener = null;
            Exception bindError = null;
            if (requestedPort > 0) {
                try { listener = new Listener(requestedPort, id); listener.start(); }
                catch (Exception e) { bindError = e; if (listener != null) listener.stop(); listener = null; }
            }
            if (listener == null && (requestedPort == 0 || autoPort)) {
                for (int attempt = 0; attempt < 10 && listener == null; attempt++) {
                    try {
                        Listener candidate = new Listener(0, id);
                        candidate.start();
                        if (candidate.port == WorkflowManager.ENDPOINT_PORT || candidate.port == 9010) {
                            candidate.stop();
                            bindError = new IllegalStateException("port yang dialokasikan dicadangkan untuk service lain");
                        } else listener = candidate;
                    } catch (Exception e) { bindError = e; }
                }
            }
            if (listener == null) {
                manager.addLog(id, "", "error", "endpoint.bind_failed", "Endpoint eksklusif gagal membuka port", "metadata.json", 0,
                        bindError == null ? "requestedPort=" + requestedPort : "requestedPort=" + requestedPort + "; error=" + safe(bindError));
                continue;
            }
            exclusiveListeners.put(id, listener);
            if (listener.port != requestedPort) manager.setAssignedEndpointPort(id, listener.port);
            manager.addLog(id, "", "info", "endpoint.exclusive_started", "Endpoint eksklusif aktif di port " + listener.port, "metadata.json", 0, "port=" + listener.port);
        }
        context.getSharedPreferences("workflow_server", Context.MODE_PRIVATE).edit().putBoolean("running", started).apply();
    }

    private final class Listener {
        volatile int port;
        final String exclusiveWorkflowId;
        final ExecutorService workers = Executors.newFixedThreadPool(4);
        volatile boolean running;
        ServerSocket socket;
        Thread thread;

        Listener(int port, String exclusiveWorkflowId) { this.port = port; this.exclusiveWorkflowId = exclusiveWorkflowId; }

        void start() throws Exception {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32);
            port = socket.getLocalPort();
            running = true;
            thread = new Thread(new Runnable() {
                public void run() {
                    while (running) {
                        Socket client = null;
                        try {
                            client = socket.accept();
                            final Socket accepted = client;
                            workers.execute(new Runnable() { public void run() { handle(accepted, Listener.this); } });
                            client = null;
                        } catch (Exception e) {
                            if (running) Log.e(TAG, "accept gagal pada port " + Listener.this.port, e);
                        } finally { if (client != null) close(client); }
                    }
                }
            }, "rh-workflow-http-" + port);
            thread.setDaemon(true);
            thread.start();
        }

        boolean isRunning() { return running && socket != null && !socket.isClosed() && thread != null && thread.isAlive(); }
        void stop() {
            running = false;
            try { if (socket != null) socket.close(); } catch (Exception ignored) { }
            socket = null;
            try { workers.shutdownNow(); } catch (Exception ignored) { }
        }
    }

    private void handle(Socket socket, Listener listener) {
        try {
            socket.setSoTimeout(30 * 60 * 1000);
            InputStream input = new BufferedInputStream(socket.getInputStream());
            String line = readLine(input);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) { send(socket, 400, error("HTTP request tidak valid")); return; }
            String method = parts[0].toUpperCase(Locale.US);
            String requestPath = parts[1];
            int query = requestPath.indexOf('?'); if (query >= 0) requestPath = requestPath.substring(0, query);
            Map<String, String> headers = new HashMap<String, String>();
            while ((line = readLine(input)) != null && line.length() > 0) {
                int colon = line.indexOf(':'); if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
            }
            if ("/health".equals(requestPath)) {
                JSONObject health = new JSONObject(); health.put("ok", true); health.put("service", "workflow-http"); health.put("port", listener.port); send(socket, 200, health); return;
            }
            if ("OPTIONS".equals(method)) { send(socket, 204, new JSONObject()); return; }
            JSONObject meta = listener.exclusiveWorkflowId == null ? findSharedByPath(requestPath) : findExclusive(listener.exclusiveWorkflowId, requestPath);
            if (meta == null) { send(socket, 404, error("endpoint workflow tidak ditemukan atau tidak aktif")); return; }
            String id = meta.optString("id", "");
            JSONObject endpoint = meta.optJSONObject("endpoint");
            if (endpoint == null) { send(socket, 404, error("konfigurasi endpoint tidak ditemukan")); return; }
            if (!method.equalsIgnoreCase(endpoint.optString("method", "POST"))) {
                send(socket, 405, error("method tidak diizinkan; gunakan " + endpoint.optString("method", "POST"))); return;
            }
            if (!"POST".equals(method)) { send(socket, 405, error("workflow saat ini hanya menerima POST JSON")); return; }
            String lengthText = headers.get("content-length");
            if (lengthText == null) { send(socket, 411, error("Content-Length wajib disertakan")); return; }
            int length;
            try { length = Integer.parseInt(lengthText); } catch (Exception e) { length = -1; }
            if (length < 0 || length > MAX_BODY) { send(socket, 413, error("body harus 0 sampai 2 MiB")); return; }
            byte[] bytes = readExact(input, length);
            JSONObject body;
            try { body = bytes.length == 0 ? new JSONObject() : new JSONObject(new String(bytes, Charset.forName("UTF-8"))); }
            catch (Exception parseError) { send(socket, 400, error("body request harus berupa JSON object yang valid")); return; }
            JSONObject result = manager.runEnabled(id, body);
            send(socket, 200, result);
        } catch (IllegalArgumentException e) {
            try { send(socket, 400, error(safe(e))); } catch (Exception ignored) { }
        } catch (Exception e) {
            Log.e(TAG, "workflow endpoint gagal", e);
            try { send(socket, 500, error(safe(e))); } catch (Exception ignored) { }
        } finally { close(socket); }
    }

    private JSONObject findSharedByPath(String path) {
        try {
            JSONArray list = manager.listWorkflows();
            for (int i = 0; i < list.length(); i++) {
                JSONObject summary = list.optJSONObject(i);
                if (summary == null || !summary.optBoolean("enabled", false) || !summary.optBoolean("valid", false)) continue;
                JSONObject meta = manager.getMetadata(summary.optString("id", ""));
                if (meta == null) continue;
                JSONObject endpoint = meta.optJSONObject("endpoint");
                if (endpoint != null && endpoint.optBoolean("enabled", false) && "shared".equals(endpoint.optString("mode", "none"))
                        && path.equals(endpoint.optString("path", ""))) return meta;
            }
        } catch (Exception e) { Log.e(TAG, "mencari endpoint shared", e); }
        return null;
    }

    private JSONObject findExclusive(String workflowId, String path) {
        try {
            JSONObject meta = manager.getMetadata(workflowId);
            if (meta == null || !meta.optBoolean("enabled", false)) return null;
            JSONObject endpoint = meta.optJSONObject("endpoint");
            if (endpoint == null || !endpoint.optBoolean("enabled", false) || !"exclusive".equals(endpoint.optString("mode", "none"))) return null;
            if (!path.equals(endpoint.optString("path", "/"))) return null;
            return meta;
        } catch (Exception e) { return null; }
    }

    private String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int b; boolean got = false;
        while ((b = in.read()) != -1) { got = true; if (b == '\n') break; if (b != '\r') out.write(b); if (out.size() > 8192) throw new IllegalArgumentException("header terlalu besar"); }
        if (!got && out.size() == 0) return null; return new String(out.toByteArray(), "ISO-8859-1");
    }
    private byte[] readExact(InputStream in, int length) throws Exception {
        byte[] out = new byte[length]; int pos = 0; while (pos < length) { int n = in.read(out, pos, length - pos); if (n < 0) throw new IllegalArgumentException("body HTTP terpotong"); pos += n; } return out;
    }
    private JSONObject error(String message) { JSONObject o = new JSONObject(); try { o.put("ok", false); o.put("error", message); } catch (Exception ignored) { } return o; }
    private void send(Socket socket, int code, JSONObject body) throws Exception {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream()); byte[] bytes = body == null ? new byte[0] : body.toString().getBytes("UTF-8");
        String status = code == 200 ? "OK" : code == 204 ? "No Content" : code == 400 ? "Bad Request" : code == 404 ? "Not Found" : code == 405 ? "Method Not Allowed" : code == 411 ? "Length Required" : code == 413 ? "Payload Too Large" : code == 429 ? "Too Many Requests" : "Internal Server Error";
        String header = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Content-Type\r\nAccess-Control-Allow-Methods: POST, OPTIONS\r\n\r\n";
        out.write(header.getBytes("ISO-8859-1")); out.write(bytes); out.flush();
    }
    private void close(Socket s) { if (s != null) try { s.close(); } catch (Exception ignored) { } }
    private String safe(Exception e) { String s = e.getMessage(); if (s == null || s.length() == 0) s = e.getClass().getSimpleName(); return s.length() > 1000 ? s.substring(0, 1000) : s; }
}
