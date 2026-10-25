package rh.browser.models;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Native storage, metadata, log database, ZIP import/export and MCP management API. */
public final class WorkflowManager {
    public static final int ENDPOINT_PORT = 8084;
    public static final int MAX_EXCLUSIVE_ENDPOINTS = 5;
    public static final int MAX_SCRIPT_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ZIP_BYTES = 50 * 1024 * 1024;
    private static final String TAG = "RhWorkflow";
    private static WorkflowManager singleton;
    private final Context context;
    private final File root;
    private final LogDb logDb;
    private WorkflowExecutor executor;
    private final ConcurrentHashMap<String, AtomicInteger> activeExecutions = new ConcurrentHashMap<String, AtomicInteger>();
    private final ConcurrentHashMap<String, ExecutionState> executionStates = new ConcurrentHashMap<String, ExecutionState>();
    private final ConcurrentHashMap<String, PendingInput> pendingInputs = new ConcurrentHashMap<String, PendingInput>();

    private static final class ExecutionState {
        final String workflowId;
        final String executionId;
        final long startedAt;
        volatile String status;
        volatile JSONObject result;
        volatile JSONObject error;
        volatile long finishedAt;
        ExecutionState(String workflowId, String executionId, long startedAt) {
            this.workflowId = workflowId; this.executionId = executionId; this.startedAt = startedAt; this.status = "running";
        }
    }

    private static final class PendingInput {
        final String workflowId, executionId, promptId, prompt, inputType;
        final boolean required;
        final String defaultValue;
        final JSONArray choices;
        final long createdAt, deadline;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String value;
        volatile String submittedBy = "";
        volatile long submittedAt;
        volatile boolean cancelled;
        PendingInput(String workflowId, String executionId, String promptId, String prompt, String inputType,
                     boolean required, String defaultValue, JSONArray choices, long createdAt, long deadline) {
            this.workflowId = workflowId; this.executionId = executionId; this.promptId = promptId;
            this.prompt = prompt; this.inputType = inputType; this.required = required;
            this.defaultValue = defaultValue == null ? "" : defaultValue;
            this.choices = choices == null ? new JSONArray() : choices;
            this.createdAt = createdAt; this.deadline = deadline;
        }
        synchronized boolean submit(String text, String by) {
            if (latch.getCount() == 0 || cancelled) return false;
            String v = text == null ? "" : text;
            if (required && v.trim().length() == 0) return false;
            value = v; submittedBy = by == null ? "unknown" : by; submittedAt = System.currentTimeMillis();
            latch.countDown(); return true;
        }
        synchronized boolean cancel(String by) {
            if (latch.getCount() == 0) return false;
            cancelled = true; value = ""; submittedBy = by == null ? "runtime" : by;
            submittedAt = System.currentTimeMillis(); latch.countDown(); return true;
        }
        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("workflow_id", workflowId); o.put("execution_id", executionId); o.put("prompt_id", promptId);
            o.put("prompt", prompt); o.put("input_type", inputType); o.put("required", required);
            o.put("default_value", "password".equals(inputType) ? "" : defaultValue); o.put("choices", new JSONArray(choices.toString()));
            o.put("status", latch.getCount() == 0 ? "answered" : "waiting_input");
            o.put("created_at", createdAt); o.put("created_at_text", timestampText(createdAt));
            o.put("deadline", deadline); o.put("deadline_text", timestampText(deadline));
            return o;
        }
    }

    public static synchronized WorkflowManager get(Context context) {
        if (singleton == null) singleton = new WorkflowManager(context.getApplicationContext());
        return singleton;
    }

    private WorkflowManager(Context context) {
        this.context = context.getApplicationContext();
        root = new File(this.context.getFilesDir(), "workflows");
        if (!root.exists()) root.mkdirs();
        logDb = new LogDb(this.context);
        executor = new WorkflowExecutor(this.context, this);
    }

    public File getRootDirectory() { return root; }
    public Context getContext() { return context; }

    public JSONObject createMetadata(String id, String name, String description, String format,
                                     String entry, String endpointPath) throws Exception {
        String safeId = validateId(id);
        String safeFormat = normalFormat(format);
        JSONObject meta = new JSONObject();
        meta.put("id", safeId);
        meta.put("name", clean(name, safeId, 120));
        meta.put("description", clean(description, "", 1000));
        meta.put("format", safeFormat);
        String defaultEntry = "script/main." + safeFormat;
        meta.put("entry", normalizeRelative(entry == null || entry.trim().length() == 0 ? defaultEntry : entry));
        meta.put("enabled", false);
        meta.put("createdAt", System.currentTimeMillis());
        meta.put("updatedAt", System.currentTimeMillis());
        JSONObject endpoint = new JSONObject();
        endpoint.put("enabled", false);
        endpoint.put("mode", "none");
        endpoint.put("method", "POST");
        endpoint.put("path", normalizeEndpointPath(endpointPath, safeId));
        endpoint.put("port", 0);
        endpoint.put("autoPort", true);
        meta.put("endpoint", endpoint);
        JSONObject permissions = new JSONObject();
        permissions.put("aiWriteInput", false);
        meta.put("permissions", permissions);
        return meta;
    }

    public synchronized JSONObject createWorkflow(JSONObject metadata) throws Exception {
        if (metadata == null) throw new IllegalArgumentException("metadata tidak boleh kosong");
        String id = validateId(metadata.optString("id", ""));
        File dir = workflowDirectory(id);
        if (dir.exists()) throw new IllegalArgumentException("workflow dengan ID '" + id + "' sudah ada");
        JSONObject meta = normalizeMetadata(metadata, id);
        // A package or MCP-created workflow cannot grant AI input-write permission to itself.
        JSONObject localPermissions = meta.optJSONObject("permissions");
        if (localPermissions == null) localPermissions = new JSONObject();
        localPermissions.put("aiWriteInput", false);
        meta.put("permissions", localPermissions);
        // Installing or creating a package must not activate it implicitly.
        meta.put("enabled", false);
        File scripts = new File(dir, "script");
        File assets = new File(dir, "assets");
        if (!scripts.mkdirs() && !scripts.isDirectory()) throw new IllegalStateException("gagal membuat folder script");
        if (!assets.mkdirs() && !assets.isDirectory()) { deleteRecursive(dir); throw new IllegalStateException("gagal membuat folder assets"); }
        writeMetadataFile(dir, meta);
        String entry = meta.optString("entry");
        File entryFile = resolveWorkflowFile(id, entry, true);
        if (!entryFile.exists()) {
            String starter = "flow".equals(meta.optString("format")) ? defaultFlowSource() : defaultBeanShellSource();
            writeUtf8(entryFile, starter);
        }
        addLog(id, "", "info", "workflow.created", "Workflow dibuat", "metadata.json", 0, null);
        return meta;
    }

    public synchronized JSONObject updateMetadata(String id, JSONObject changes) throws Exception {
        JSONObject current = getMetadata(id);
        if (current == null) throw new IllegalArgumentException("workflow tidak ditemukan: " + id);
        if (changes == null) return current;
        String newId = changes.optString("id", id);
        if (!id.equals(newId)) throw new IllegalArgumentException("ID workflow tidak dapat diganti setelah dibuat");
        JSONObject next = new JSONObject(current.toString());
        if (changes.has("name")) next.put("name", clean(changes.optString("name"), id, 120));
        if (changes.has("description")) next.put("description", clean(changes.optString("description"), "", 1000));
        if (changes.has("format")) next.put("format", normalFormat(changes.optString("format")));
        if (changes.has("entry")) next.put("entry", normalizeRelative(changes.optString("entry")));
        if (changes.has("enabled")) {
            if (changes.optBoolean("enabled", false) && !current.optBoolean("enabled", false)) {
                throw new IllegalArgumentException("gunakan workflow.activate untuk mengaktifkan workflow");
            }
            next.put("enabled", changes.optBoolean("enabled", false));
        }
        if (changes.has("endpointPath") || changes.has("endpoint") || changes.has("endpointMode") || changes.has("endpointPort")) {
            JSONObject endpoint = next.optJSONObject("endpoint");
            if (endpoint == null) endpoint = new JSONObject();
            JSONObject incoming = changes.optJSONObject("endpoint");
            if (changes.has("endpointPath")) endpoint.put("path", normalizeEndpointPath(changes.optString("endpointPath"), id));
            if (changes.has("endpointMode")) endpoint.put("mode", normalizeEndpointMode(changes.optString("endpointMode", "none")));
            if (changes.has("endpointPort")) {
                int port = changes.optInt("endpointPort", 0);
                validateEndpointPort(port, true);
                endpoint.put("port", port);
                endpoint.put("autoPort", port == 0);
            }
            if (incoming != null) {
                if (incoming.has("path")) endpoint.put("path", normalizeEndpointPath(incoming.optString("path"), id));
                if (incoming.has("mode")) endpoint.put("mode", normalizeEndpointMode(incoming.optString("mode", "none")));
                if (incoming.has("enabled")) endpoint.put("enabled", incoming.optBoolean("enabled", false));
                if (incoming.has("method")) endpoint.put("method", incoming.optString("method", "POST").toUpperCase(Locale.US));
                if (incoming.has("port")) { int port = incoming.optInt("port", 0); validateEndpointPort(port, port == 0 || incoming.optBoolean("autoPort", false)); endpoint.put("port", port); endpoint.put("autoPort", port == 0 || incoming.optBoolean("autoPort", false)); }
                if (incoming.has("autoPort")) endpoint.put("autoPort", incoming.optBoolean("autoPort", false));
            }
            String mode = normalizeEndpointMode(endpoint.optString("mode", "none"));
            endpoint.put("mode", mode);
            endpoint.put("enabled", !"none".equals(mode));
            if ("shared".equals(mode)) { endpoint.put("port", ENDPOINT_PORT); endpoint.put("autoPort", false); }
            else if ("none".equals(mode)) { endpoint.put("port", 0); endpoint.put("autoPort", true); }
            else {
                int port = endpoint.optInt("port", 0);
                validateEndpointPort(port, endpoint.optBoolean("autoPort", port == 0));
                if (port == ENDPOINT_PORT) throw new IllegalArgumentException("port 8084 digunakan server bersama");
            }
            next.put("endpoint", endpoint);
        }
        if (changes.has("permissions") || changes.has("aiWriteInput")) {
            JSONObject permissions = next.optJSONObject("permissions");
            if (permissions == null) permissions = new JSONObject();
            JSONObject incoming = changes.optJSONObject("permissions");
            if (incoming != null && incoming.has("aiWriteInput")) permissions.put("aiWriteInput", incoming.optBoolean("aiWriteInput", false));
            if (changes.has("aiWriteInput")) permissions.put("aiWriteInput", changes.optBoolean("aiWriteInput", false));
            next.put("permissions", permissions);
        }
        next.put("updatedAt", System.currentTimeMillis());
        next = normalizeMetadata(next, id);
        writeMetadataFile(workflowDirectory(id), next);
        addLog(id, "", "info", "workflow.metadata_updated", "Metadata diperbarui", "metadata.json", 0, null);
        if (next.optBoolean("enabled", false)) WorkflowHttpServer.reconcileCurrent();
        return next;
    }

    public synchronized JSONObject getMetadata(String id) throws Exception {
        if (!isValidId(id)) return null;
        File dir = workflowDirectory(id);
        File file = new File(dir, "metadata.json");
        if (!file.isFile()) return null;
        JSONObject meta = new JSONObject(readUtf8(file, 1024 * 1024));
        JSONObject endpoint = meta.optJSONObject("endpoint");
        boolean needsMigration = endpoint != null && !endpoint.has("mode");
        if (needsMigration || !meta.has("permissions")) {
            JSONObject normalized = normalizeMetadata(meta, id);
            writeMetadataFile(dir, normalized);
            meta = normalized;
        }
        return meta;
    }

    public synchronized JSONArray listWorkflows() throws Exception {
        JSONArray result = new JSONArray();
        File[] dirs = root.listFiles();
        if (dirs == null) return result;
        java.util.Arrays.sort(dirs, new java.util.Comparator<File>() {
            public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
        });
        for (int i = 0; i < dirs.length; i++) {
            if (!dirs[i].isDirectory() || !isValidId(dirs[i].getName())) continue;
            try {
                JSONObject meta = getMetadata(dirs[i].getName());
                if (meta != null) {
                    JSONObject summary = new JSONObject(meta.toString());
                    JSONObject validation = validateWorkflow(dirs[i].getName(), true);
                    summary.put("valid", validation.optBoolean("ok", false));
                    summary.put("validationMessage", validation.optString("message", ""));
                    JSONObject endpoint = meta.optJSONObject("endpoint");
                    String endpointMode = endpoint == null ? "none" : normalizeEndpointMode(endpoint.optString("mode", "none"));
                    String endpointPath = endpoint == null ? "/workflow/" + dirs[i].getName() : endpoint.optString("path", "/workflow/" + dirs[i].getName());
                    int endpointPort = endpoint == null ? 0 : endpoint.optInt("port", 0);
                    boolean serviceRunning = context.getSharedPreferences("workflow_server", Context.MODE_PRIVATE).getBoolean("running", false);
                    boolean endpointConfigured = !"none".equals(endpointMode) && endpoint != null && endpoint.optBoolean("enabled", false);
                    boolean endpointRuntimeActive = meta.optBoolean("enabled", false) && validation.optBoolean("ok", false) && serviceRunning && endpointConfigured && WorkflowHttpServer.isEndpointRunning(dirs[i].getName(), endpointMode);
                    String endpointUrl = "";
                    if ("shared".equals(endpointMode)) endpointUrl = "http://127.0.0.1:" + ENDPOINT_PORT + endpointPath;
                    else if ("exclusive".equals(endpointMode) && endpointPort > 0) endpointUrl = "http://127.0.0.1:" + endpointPort + endpointPath;
                    summary.put("endpointUrl", endpointUrl);
                    summary.put("endpointMode", endpointMode);
                    summary.put("endpointPort", endpointPort);
                    summary.put("endpointConfigured", endpointConfigured);
                    summary.put("endpointRuntimeActive", endpointRuntimeActive);
                    summary.put("runtimeActive", meta.optBoolean("enabled", false) && validation.optBoolean("ok", false));
                    JSONObject perms = meta.optJSONObject("permissions");
                    summary.put("aiWriteInput", perms != null && perms.optBoolean("aiWriteInput", false));
                    summary.put("runningExecutions", getRunningExecutions(dirs[i].getName()));
                    int pendingCount = getPendingInputCount(dirs[i].getName());
                    summary.put("pendingInputCount", pendingCount);
                    summary.put("runtimeState", pendingCount > 0 ? "waiting_input" : getRunningExecutions(dirs[i].getName()) > 0 ? "running" : meta.optBoolean("enabled", false) ? "active" : "disabled");
                    result.put(summary);
                }
            } catch (Exception e) {
                Log.w(TAG, "metadata workflow rusak: " + dirs[i].getName(), e);
                JSONObject broken = new JSONObject();
                broken.put("id", dirs[i].getName());
                broken.put("name", dirs[i].getName());
                broken.put("valid", false);
                broken.put("validationMessage", e.getMessage() == null ? "Metadata rusak" : e.getMessage());
                result.put(broken);
            }
        }
        return result;
    }

    public synchronized JSONObject validateWorkflow(String id, boolean checkBeanShell) throws Exception {
        JSONObject result = new JSONObject();
        result.put("workflowId", id == null ? "" : id);
        try {
            JSONObject meta = getMetadata(id);
            if (meta == null) throw new IllegalArgumentException("metadata.json tidak ditemukan");
            String idFromMeta = validateId(meta.optString("id", ""));
            if (!id.equals(idFromMeta)) throw new IllegalArgumentException("ID metadata tidak sama dengan nama folder");
            String format = normalFormat(meta.optString("format", ""));
            String entry = normalizeRelative(meta.optString("entry", ""));
            if (!entry.startsWith("script/")) throw new IllegalArgumentException("entry point wajib berada di folder script/");
            String lower = entry.toLowerCase(Locale.US);
            if ("flow".equals(format) && !lower.endsWith(".flow")) throw new IllegalArgumentException("format flow wajib memakai ekstensi .flow");
            if ("bshflow".equals(format) && !lower.endsWith(".bshflow")) throw new IllegalArgumentException("format bshflow wajib memakai ekstensi .bshflow");
            File entryFile = resolveWorkflowFile(id, entry, false);
            if (!entryFile.isFile()) throw new IllegalArgumentException("entry point tidak ditemukan: " + entry);
            String source = readUtf8(entryFile, MAX_SCRIPT_BYTES);
            if ("flow".equals(format)) {
                JSONObject flow;
                try { flow = new JSONObject(source); }
                catch (Exception parseError) { throw new WorkflowExecutionException("syntax error JSON pada " + entry + ": " + safeMessage(parseError), entry, extractJsonLine(source, parseError.getMessage()), source.length() > 2000 ? source.substring(0, 2000) : source, parseError); }
                JSONArray steps = flow.optJSONArray("steps");
                if (steps == null) throw new IllegalArgumentException("file .flow harus memiliki array 'steps'");
            } else if (checkBeanShell) {
                if (!classExists("bsh.Interpreter")) throw new IllegalStateException("BeanShell belum ditemukan. Letakkan bsh-2.0b4.jar di folder libs proyek AIDE lalu build ulang aplikasi.");
                validateBeanShellSyntax(source, entry);
            }
            JSONObject endpoint = meta.optJSONObject("endpoint");
            if (endpoint == null) endpoint = new JSONObject();
            String endpointMode = normalizeEndpointMode(endpoint.optString("mode", endpoint.optBoolean("enabled", false) ? "shared" : "none"));
            String path = normalizeEndpointPath(endpoint.optString("path", ""), id);
            String method = endpoint.optString("method", "POST").toUpperCase(Locale.US);
            int port = endpoint.optInt("port", "shared".equals(endpointMode) ? ENDPOINT_PORT : 0);
            if (!"none".equals(endpointMode) && !"POST".equals(method)) throw new IllegalArgumentException("method workflow saat ini harus POST");
            if ("shared".equals(endpointMode)) {
                if (port != ENDPOINT_PORT) throw new IllegalArgumentException("server bersama wajib menggunakan port " + ENDPOINT_PORT);
                if (endpoint.optBoolean("enabled", true)) {
                    File[] dirs = root.listFiles();
                    if (dirs != null) for (int i = 0; i < dirs.length; i++) {
                        File otherDir = dirs[i];
                        if (!otherDir.isDirectory() || id.equals(otherDir.getName()) || !isValidId(otherDir.getName())) continue;
                        try {
                            JSONObject other = getMetadata(otherDir.getName());
                            if (other == null || !other.optBoolean("enabled", false)) continue;
                            JSONObject otherEndpoint = other.optJSONObject("endpoint");
                            if (otherEndpoint != null && "shared".equals(normalizeEndpointMode(otherEndpoint.optString("mode", otherEndpoint.optBoolean("enabled", false) ? "shared" : "none")))
                                    && otherEndpoint.optBoolean("enabled", false) && path.equals(otherEndpoint.optString("path", ""))) {
                                throw new IllegalArgumentException("endpoint path sudah digunakan workflow " + other.optString("id"));
                            }
                        } catch (IllegalArgumentException e) { throw e; }
                        catch (Exception ignored) { }
                    }
                }
            } else if ("exclusive".equals(endpointMode)) {
                validateEndpointPort(port, endpoint.optBoolean("autoPort", port == 0));
                if (port == ENDPOINT_PORT) throw new IllegalArgumentException("port 8084 sudah digunakan server bersama");
            }
            result.put("ok", true);
            result.put("message", "Workflow valid");
            result.put("id", id);
            result.put("format", format);
            result.put("entry", entry);
            result.put("endpointMode", endpointMode);
            result.put("endpointPath", path);
            result.put("endpointPort", port);
            result.put("endpointConfigured", !"none".equals(endpointMode) && endpoint.optBoolean("enabled", false));
        } catch (Exception e) {
            result.put("ok", false);
            result.put("message", safeMessage(e));
            result.put("errorType", e.getClass().getSimpleName());
            if (e instanceof WorkflowExecutionException) {
                result.put("sourceFile", ((WorkflowExecutionException)e).sourceFile);
                result.put("lineNumber", ((WorkflowExecutionException)e).lineNumber);
                if (((WorkflowExecutionException)e).details != null) result.put("details", ((WorkflowExecutionException)e).details);
            }
        }
        return result;
    }

    private int extractJsonLine(String source, String message) {
        if (source == null || message == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)(?:character|position)\\s+(\\d+)").matcher(message);
        if (!matcher.find()) return 0;
        int offset;
        try { offset = Integer.parseInt(matcher.group(1)); } catch (Exception e) { return 0; }
        offset = Math.max(0, Math.min(offset, source.length()));
        int line = 1;
        for (int i = 0; i < offset; i++) if (source.charAt(i) == '\n') line++;
        return line;
    }

    private void validateBeanShellSyntax(String source, String entry) throws Exception {
        Class<?> parserClass;
        try { parserClass = Class.forName("bsh.Parser"); }
        catch (Throwable e) { throw new IllegalStateException("BeanShell Parser tidak tersedia; periksa bsh-2.0b4.jar", e); }
        Object parser;
        try { parser = parserClass.getConstructor(java.io.Reader.class).newInstance(new java.io.StringReader(source)); }
        catch (Exception e) { throw new IllegalStateException("gagal membuat parser BeanShell: " + safeMessage(e), e); }
        java.lang.reflect.Method lineMethod = parserClass.getMethod("Line");
        java.lang.reflect.Method popMethod = parserClass.getMethod("popNode");
        int maxCalls = Math.min(500000, Math.max(1000, source.length() + 16));
        try {
            for (int i = 0; i < maxCalls; i++) {
                Object eof = lineMethod.invoke(parser);
                if (Boolean.TRUE.equals(eof)) return;
                popMethod.invoke(parser);
            }
            throw new IllegalArgumentException("parser BeanShell tidak mencapai akhir file dalam batas parse");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            int line = extractBeanShellLine(cause);
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            throw new WorkflowExecutionException("BeanShell syntax error: " + message, entry, line, cause.toString(), cause);
        }
    }

    private int extractBeanShellLine(Throwable error) {
        if (error == null) return 0;
        String[] methods = new String[] { "getErrorLineNumber", "getLineNumber" };
        for (int i = 0; i < methods.length; i++) {
            try { Object value = error.getClass().getMethod(methods[i]).invoke(error); if (value instanceof Number) return ((Number)value).intValue(); }
            catch (Exception ignored) { }
        }
        String message = error.getMessage();
        if (message != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)line\\s+(\\d+)").matcher(message);
            if (m.find()) try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) { }
        }
        return 0;
    }

    public synchronized JSONObject validateWorkflowAndLog(String id) throws Exception {
        JSONObject validation = validateWorkflow(id, true);
        if (!validation.optBoolean("ok", false)) logValidationFailure(id, validation);
        return validation;
    }

    private void logValidationFailure(String id, JSONObject validation) {
        if (!isValidId(id)) return;
        try {
            JSONObject meta = getMetadata(id);
            if (meta == null) return;
            addLog(id, "", "error", "workflow.validation_failed", validation.optString("message", "workflow tidak valid"), validation.optString("sourceFile", meta.optString("entry", "")), validation.optInt("lineNumber", 0), validation.toString());
        } catch (Exception e) { Log.w(TAG, "gagal mencatat validation error", e); }
    }

    public synchronized JSONObject activate(String id) throws Exception {
        JSONObject meta = getMetadata(id);
        if (meta == null) throw new IllegalArgumentException("workflow tidak ditemukan: " + id);
        JSONObject validation = validateWorkflow(id, true);
        if (!validation.optBoolean("ok", false)) {
            logValidationFailure(id, validation);
            throw new IllegalArgumentException(validation.optString("message", "workflow tidak valid"));
        }
        meta.put("enabled", true);
        meta.put("updatedAt", System.currentTimeMillis());
        writeMetadataFile(workflowDirectory(id), meta);
        JSONObject endpoint = meta.optJSONObject("endpoint");
        String mode = endpoint == null ? "none" : normalizeEndpointMode(endpoint.optString("mode", "none"));
        String message = "Workflow diaktifkan";
        if ("shared".equals(mode)) message += "; endpoint bersama " + validation.optString("endpointPath") + " pada port 8084";
        else if ("exclusive".equals(mode)) message += "; endpoint eksklusif dikonfigurasi pada port " + endpoint.optInt("port", 0);
        else message += "; tanpa endpoint HTTP";
        addLog(id, "", "info", "workflow.activated", message, "metadata.json", 0, null);
        WorkflowHttpServer.reconcileCurrent();
        return getMetadata(id);
    }

    public synchronized JSONObject deactivate(String id) throws Exception {
        JSONObject meta = getMetadata(id);
        if (meta == null) throw new IllegalArgumentException("workflow tidak ditemukan: " + id);
        meta.put("enabled", false);
        meta.put("updatedAt", System.currentTimeMillis());
        writeMetadataFile(workflowDirectory(id), meta);
        addLog(id, "", "info", "workflow.deactivated", "Workflow dinonaktifkan", "metadata.json", 0, null);
        cancelPendingInputsForWorkflow(id, "Workflow dinonaktifkan");
        WorkflowHttpServer.reconcileCurrent();
        return getMetadata(id);
    }

    public synchronized void deleteWorkflow(String id) throws Exception {
        File dir = workflowDirectory(id);
        if (!dir.exists()) throw new IllegalArgumentException("workflow tidak ditemukan: " + id);
        if (getRunningExecutions(id) > 0) throw new IllegalStateException("workflow sedang berjalan; tunggu sampai eksekusi selesai sebelum menghapusnya");
        if (!deleteRecursive(dir)) throw new IllegalStateException("gagal menghapus folder workflow");
        cancelPendingInputsForWorkflow(id, "Workflow dihapus");
        SQLiteDatabase db = logDb.getWritableDatabase();
        db.delete("workflow_logs", "workflow_id=?", new String[] { id });
        WorkflowHttpServer.reconcileCurrent();
    }

    public synchronized File resolveWorkflowFile(String id, String relative, boolean createParents) throws Exception {
        File dir = workflowDirectory(id);
        String safe = normalizeRelative(relative);
        if ("metadata.json".equals(safe) || safe.startsWith("metadata.json/")) throw new IllegalArgumentException("metadata.json dikelola melalui API metadata");
        if (!safe.startsWith("script/") && !safe.startsWith("assets/")) throw new IllegalArgumentException("file workflow harus berada dalam script/ atau assets/");
        File target = new File(dir, safe);
        String rootPath = dir.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(rootPath)) throw new IllegalArgumentException("path file keluar dari folder workflow");
        if (createParents) {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) throw new IllegalStateException("gagal membuat folder " + parent.getName());
        }
        return target;
    }

    public synchronized void writeFile(String id, String relative, String content) throws Exception {
        if (content == null) content = "";
        byte[] bytes = content.getBytes("UTF-8");
        if (bytes.length > MAX_SCRIPT_BYTES) throw new IllegalArgumentException("file teks melebihi 2 MiB");
        File target = resolveWorkflowFile(id, relative, true);
        writeBytes(target, bytes);
        addLog(id, "", "info", "workflow.file_saved", "File disimpan: " + normalizeRelative(relative), normalizeRelative(relative), 0, null);
    }

    public synchronized void writeAssetBytes(String id, String relative, InputStream input, long maxBytes) throws Exception {
        String path = normalizeRelative(relative);
        if (!path.startsWith("assets/")) throw new IllegalArgumentException("file impor harus berada di assets/");
        File target = resolveWorkflowFile(id, path, true);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(target);
            byte[] buffer = new byte[8192];
            long count = 0;
            int n;
            while ((n = input.read(buffer)) != -1) {
                count += n;
                if (count > maxBytes) throw new IllegalArgumentException("aset melebihi batas ukuran");
                out.write(buffer, 0, n);
            }
            out.flush();
        } finally { if (out != null) try { out.close(); } catch (Exception ignored) { } }
    }

    public synchronized JSONObject readFile(String id, String relative) throws Exception {
        File f = resolveWorkflowFile(id, relative, false);
        if (!f.isFile()) throw new IllegalArgumentException("file tidak ditemukan: " + relative);
        if (f.length() > MAX_SCRIPT_BYTES) throw new IllegalArgumentException("file lebih besar dari batas pembacaan teks 2 MiB");
        JSONObject o = new JSONObject();
        o.put("workflowId", id); o.put("path", normalizeRelative(relative)); o.put("content", readUtf8(f, MAX_SCRIPT_BYTES)); o.put("sizeBytes", f.length()); o.put("encoding", "utf8");
        return o;
    }

    public synchronized JSONObject readFileBase64(String id, String relative) throws Exception {
        File f = resolveWorkflowFile(id, relative, false);
        if (!f.isFile()) throw new IllegalArgumentException("file tidak ditemukan: " + relative);
        if (f.length() > 20L * 1024L * 1024L) throw new IllegalArgumentException("file melebihi batas base64 20 MiB");
        FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); } finally { in.close(); }
        JSONObject o = new JSONObject(); o.put("workflowId", id); o.put("path", normalizeRelative(relative)); o.put("content", android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)); o.put("sizeBytes", f.length()); o.put("encoding", "base64"); return o;
    }

    public synchronized void writeFileBase64(String id, String relative, String content) throws Exception {
        if (content == null) content = "";
        byte[] bytes;
        try { bytes = android.util.Base64.decode(content, android.util.Base64.DEFAULT); }
        catch (Exception e) { throw new IllegalArgumentException("konten base64 tidak valid"); }
        if (bytes.length > 20L * 1024L * 1024L) throw new IllegalArgumentException("file melebihi batas 20 MiB");
        File target = resolveWorkflowFile(id, relative, true); writeBytes(target, bytes);
        addLog(id, "", "info", "workflow.asset_saved", "File biner/base64 disimpan: " + normalizeRelative(relative), normalizeRelative(relative), 0, "bytes=" + bytes.length);
    }

    public synchronized void deleteFile(String id, String relative) throws Exception {
        File f = resolveWorkflowFile(id, relative, false);
        if (!f.exists()) throw new IllegalArgumentException("file tidak ditemukan: " + relative);
        if (!deleteRecursive(f)) throw new IllegalStateException("gagal menghapus file");
    }

    public synchronized JSONArray listFiles(String id) throws Exception {
        if (getMetadata(id) == null) throw new IllegalArgumentException("workflow tidak ditemukan: " + id);
        JSONArray out = new JSONArray();
        listFilesRec(workflowDirectory(id), workflowDirectory(id), out);
        return out;
    }

    private void listFilesRec(File rootDir, File current, JSONArray out) throws Exception {
        File[] files = current.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, new java.util.Comparator<File>() { public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); } });
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isDirectory()) { listFilesRec(rootDir, f, out); continue; }
            if ("metadata.json".equals(f.getName())) continue;
            String path = f.getCanonicalPath().substring(rootDir.getCanonicalPath().length() + 1).replace(File.separatorChar, '/');
            JSONObject item = new JSONObject(); item.put("path", path); item.put("sizeBytes", f.length()); item.put("directory", false); out.put(item);
        }
    }

    public synchronized void createFolder(String id, String relative) throws Exception {
        String path = normalizeRelative(relative);
        if (!path.startsWith("script/") && !path.startsWith("assets/")) throw new IllegalArgumentException("folder harus berada di script/ atau assets/");
        File folder = resolveWorkflowFile(id, path + "/.folder", true).getParentFile();
        if (!folder.exists() && !folder.mkdirs() && !folder.isDirectory()) throw new IllegalStateException("gagal membuat folder");
    }

    public JSONObject run(String id, JSONObject input) throws Exception {
        return runInternal(id, input, false, null, null, null);
    }

    public JSONObject runEnabled(String id, JSONObject input) throws Exception {
        return runInternal(id, input, true, null, null, null);
    }

    /** Starts a workflow without holding the MCP request open while it waits for interactive input. */
    public JSONObject startAsync(final String id, final JSONObject input) throws Exception {
        final String executionId = UUID.randomUUID().toString();
        final CountDownLatch prepared = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Exception> startupError = new java.util.concurrent.atomic.AtomicReference<Exception>();
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try { runInternal(id, input, false, executionId, prepared, startupError); }
                catch (Exception e) { if (prepared.getCount() > 0) { startupError.set(e); prepared.countDown(); } }
            }
        }, "rh-workflow-async-" + id);
        thread.setDaemon(true);
        thread.start();
        if (!prepared.await(10L, TimeUnit.SECONDS)) throw new IllegalStateException("workflow tidak memulai dalam batas waktu");
        Exception failure = startupError.get();
        if (failure != null) throw failure;
        return getExecution(executionId);
    }

    private JSONObject runInternal(String id, JSONObject input, boolean requireEnabled) throws Exception {
        return runInternal(id, input, requireEnabled, null, null, null);
    }

    private JSONObject runInternal(String id, JSONObject input, boolean requireEnabled, String requestedExecutionId,
                                   CountDownLatch prepared, java.util.concurrent.atomic.AtomicReference<Exception> startupError) throws Exception {
        final JSONObject meta;
        final String executionId;
        final long started;
        try {
            synchronized (this) {
                meta = getMetadata(id);
                if (meta == null) throw new IllegalArgumentException("workflow tidak ditemukan: " + id);
                JSONObject validation = validateWorkflow(id, true);
                if (!validation.optBoolean("ok", false)) {
                    logValidationFailure(id, validation);
                    throw new IllegalArgumentException(validation.optString("message", "workflow tidak valid"));
                }
                if (requireEnabled && !meta.optBoolean("enabled", false)) {
                    addLog(id, "", "error", "execution.rejected", "Workflow sedang nonaktif; endpoint hanya menjalankan workflow aktif", meta.optString("entry", ""), 0, null);
                    throw new IllegalStateException("workflow sedang nonaktif; aktifkan dulu sebelum dipanggil melalui endpoint HTTP");
                }
                executionId = requestedExecutionId == null ? UUID.randomUUID().toString() : requestedExecutionId;
                started = System.currentTimeMillis();
                beginExecution(id);
                executionStates.put(executionId, new ExecutionState(id, executionId, started));
                pruneExecutionStates();
                addLog(id, executionId, "info", "execution.started", "Eksekusi dimulai", meta.optString("entry"), 0, input == null ? null : "inputKeys=" + input.names());
            }
        } catch (Exception e) {
            if (startupError != null && prepared != null) startupError.set(e);
            if (prepared != null) prepared.countDown();
            throw e;
        }
        if (prepared != null) prepared.countDown();
        try {
            JSONObject result = executor.execute(meta, input == null ? new JSONObject() : input, executionId);
            long duration = System.currentTimeMillis() - started;
            ExecutionState state = executionStates.get(executionId);
            if (state != null) { state.status = "completed"; state.result = result; state.finishedAt = System.currentTimeMillis(); }
            addLog(id, executionId, "info", "execution.completed", "Eksekusi selesai dalam " + duration + " ms", meta.optString("entry"), 0, null);
            JSONObject output = new JSONObject(); output.put("ok", true); output.put("workflowId", id); output.put("executionId", executionId); output.put("durationMs", duration); output.put("result", result == null ? JSONObject.NULL : result);
            return output;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - started;
            ExecutionState state = executionStates.get(executionId);
            Throwable rootCause = e instanceof WorkflowExecutionException && e.getCause() != null ? e.getCause() : e;
            JSONObject detail = new JSONObject(); detail.put("errorType", rootCause.getClass().getSimpleName()); detail.put("wrapperType", e.getClass().getSimpleName()); detail.put("message", safeMessage(e)); detail.put("durationMs", duration);
            int line = e instanceof WorkflowExecutionException ? ((WorkflowExecutionException)e).lineNumber : 0;
            String source = e instanceof WorkflowExecutionException ? ((WorkflowExecutionException)e).sourceFile : meta.optString("entry");
            if (e instanceof WorkflowExecutionException && ((WorkflowExecutionException)e).details != null) detail.put("causeDetails", ((WorkflowExecutionException)e).details);
            if (state != null) { state.status = "failed"; state.error = detail; state.finishedAt = System.currentTimeMillis(); }
            addLog(id, executionId, "error", "execution.failed", safeMessage(e), source, line, detail.toString());
            throw e;
        } finally {
            endExecution(id);
        }
    }

    private void beginExecution(String id) {
        AtomicInteger count = activeExecutions.get(id);
        if (count == null) { AtomicInteger fresh = new AtomicInteger(0); AtomicInteger existing = activeExecutions.putIfAbsent(id, fresh); count = existing == null ? fresh : existing; }
        count.incrementAndGet();
    }
    private void endExecution(String id) {
        AtomicInteger count = activeExecutions.get(id);
        if (count != null && count.decrementAndGet() <= 0) activeExecutions.remove(id, count);
    }
    public int getRunningExecutions(String id) { AtomicInteger count = activeExecutions.get(id); return count == null ? 0 : Math.max(0, count.get()); }
    public int getPendingInputCount(String workflowId) { int n = 0; for (PendingInput p : pendingInputs.values()) if (workflowId.equals(p.workflowId) && p.latch.getCount() > 0 && !p.cancelled) n++; return n; }

    public void addLog(String workflowId, String executionId, String level, String event, String message,
                       String sourceFile, int lineNumber, String details) {
        try {
            SQLiteDatabase db = logDb.getWritableDatabase();
            android.content.ContentValues values = new android.content.ContentValues();
            values.put("workflow_id", workflowId == null ? "" : workflowId);
            values.put("timestamp", System.currentTimeMillis());
            values.put("level", normalLevel(level));
            values.put("event", event == null ? "" : event);
            values.put("message", message == null ? "" : message);
            values.put("details", details);
            values.put("source_file", sourceFile);
            values.put("line_number", lineNumber);
            values.put("execution_id", executionId == null ? "" : executionId);
            db.insert("workflow_logs", null, values);
            // Bound persistent log growth without deleting recent diagnostics.
            db.execSQL("DELETE FROM workflow_logs WHERE id NOT IN (SELECT id FROM workflow_logs ORDER BY id DESC LIMIT 20000)");
        } catch (Exception e) { Log.e(TAG, "gagal menulis workflow log", e); }
    }

    public void printLog(String workflowId, String executionId, String message, String sourceFile, int lineNumber) {
        addLog(workflowId, executionId, "info", "script.printLog", message, sourceFile, lineNumber, null);
    }

    public JSONObject getLogs(String id, String level, String since, long sinceTimestamp, int limit, int offset, String executionId) throws Exception {
        if (!"*".equals(id) && getMetadata(id) == null) throw new IllegalArgumentException("workflow tidak ditemukan: " + id);
        long lower = sinceTimestamp;
        String filter = since == null ? "all" : since.trim().toLowerCase(Locale.US);
        long now = System.currentTimeMillis();
        if ("last_5m".equals(filter) || "5m".equals(filter)) lower = Math.max(lower, now - 5L * 60L * 1000L);
        else if ("last_30m".equals(filter) || "30m".equals(filter)) lower = Math.max(lower, now - 30L * 60L * 1000L);
        else if ("last_1h".equals(filter) || "1h".equals(filter)) lower = Math.max(lower, now - 60L * 60L * 1000L);
        else if (!"all".equals(filter) && filter.length() > 0) throw new IllegalArgumentException("since harus all, last_5m, last_30m, atau last_1h");
        if (limit < 1) limit = 100; if (limit > 5000) limit = 5000; if (offset < 0) offset = 0;
        StringBuilder where = new StringBuilder(); ArrayList<String> args = new ArrayList<String>();
        if (!"*".equals(id)) { where.append("workflow_id=?"); args.add(id); }
        if (lower > 0) { if (where.length() > 0) where.append(" AND "); where.append("timestamp>=?"); args.add(String.valueOf(lower)); }
        String lev = level == null ? "all" : level.toLowerCase(Locale.US);
        if ("info".equals(lev)) { if (where.length() > 0) where.append(" AND "); where.append("level IN ('info','warn','debug')"); }
        else if ("error".equals(lev)) { if (where.length() > 0) where.append(" AND "); where.append("level='error'"); }
        else if (!"all".equals(lev) && lev.length() > 0) { if (where.length() > 0) where.append(" AND "); where.append("level=?"); args.add(normalLevel(lev)); }
        if (executionId != null && executionId.trim().length() > 0) { if (where.length() > 0) where.append(" AND "); where.append("execution_id=?"); args.add(executionId); }
        Cursor c = null;
        JSONArray rows = new JSONArray();
        try {
            c = logDb.getReadableDatabase().query("workflow_logs", null, where.length() == 0 ? null : where.toString(),
                    args.size() == 0 ? null : args.toArray(new String[args.size()]), null, null, "timestamp DESC, id DESC", String.valueOf(limit) + " OFFSET " + offset);
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(c.getColumnIndexOrThrow("id")));
                o.put("workflowId", c.getString(c.getColumnIndexOrThrow("workflow_id")));
                o.put("timestamp", c.getLong(c.getColumnIndexOrThrow("timestamp")));
                o.put("timestampText", formatTimestamp(c.getLong(c.getColumnIndexOrThrow("timestamp"))));
                o.put("level", c.getString(c.getColumnIndexOrThrow("level")));
                o.put("event", c.getString(c.getColumnIndexOrThrow("event")));
                o.put("message", c.getString(c.getColumnIndexOrThrow("message")));
                o.put("details", c.getString(c.getColumnIndexOrThrow("details")));
                o.put("sourceFile", c.getString(c.getColumnIndexOrThrow("source_file")));
                o.put("lineNumber", c.getInt(c.getColumnIndexOrThrow("line_number")));
                o.put("executionId", c.getString(c.getColumnIndexOrThrow("execution_id")));
                rows.put(o);
            }
        } finally { if (c != null) c.close(); }
        JSONObject result = new JSONObject(); result.put("workflowId", id); result.put("level", lev); result.put("since", filter); result.put("sinceTimestamp", lower); result.put("limit", limit); result.put("offset", offset); result.put("count", rows.length()); result.put("logs", rows);
        return result;
    }

    public String awaitInput(String workflowId, String executionId, String prompt, String inputType,
                             boolean required, String defaultValue, JSONArray choices, long timeoutMs,
                             String sourceFile, int lineNumber) throws Exception {
        JSONObject meta = getMetadata(workflowId);
        if (meta == null) throw new IllegalArgumentException("workflow tidak ditemukan: " + workflowId);
        long now = System.currentTimeMillis();
        long timeout = timeoutMs <= 0L ? 10L * 60L * 1000L : Math.max(1000L, Math.min(timeoutMs, 30L * 60L * 1000L));
        String promptId = UUID.randomUUID().toString();
        String type = normalizeInputType(inputType);
        PendingInput pending = new PendingInput(workflowId, executionId, promptId,
                prompt == null ? "Input diperlukan" : prompt, type, required, defaultValue, choices, now, now + timeout);
        pendingInputs.put(promptId, pending);
        ExecutionState state = executionStates.get(executionId);
        if (state != null) state.status = "waiting_input";
        JSONObject details = new JSONObject();
        details.put("promptId", promptId); details.put("inputType", type); details.put("required", required);
        details.put("createdAt", now); details.put("deadline", now + timeout);
        if (choices != null) details.put("choices", new JSONArray(choices.toString()));
        addLog(workflowId, executionId, "info", "input.requested", pending.prompt, sourceFile, lineNumber, details.toString());
        try {
            boolean answered = pending.latch.await(timeout, TimeUnit.MILLISECONDS);
            if (!answered && pending.cancel("timeout")) {
                JSONObject timeoutDetails = new JSONObject(); timeoutDetails.put("promptId", promptId); timeoutDetails.put("timeoutMs", timeout);
                addLog(workflowId, executionId, "error", "input.timeout", "Input tidak dijawab sebelum timeout: " + pending.prompt, sourceFile, lineNumber, timeoutDetails.toString());
                throw new WorkflowExecutionException("timeout menunggu input pengguna/AI: " + pending.prompt, sourceFile, lineNumber, timeoutDetails.toString());
            }
            if (pending.cancelled) throw new WorkflowExecutionException("input dibatalkan: " + pending.prompt, sourceFile, lineNumber);
            if (state != null) state.status = "running";
            JSONObject answerDetails = new JSONObject(); answerDetails.put("promptId", promptId); answerDetails.put("submittedBy", pending.submittedBy); answerDetails.put("submittedAt", pending.submittedAt);
            addLog(workflowId, executionId, "info", "input.submitted", "Input diterima untuk prompt: " + pending.prompt, sourceFile, lineNumber, answerDetails.toString());
            return pending.value == null ? "" : pending.value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkflowExecutionException("eksekusi terganggu saat menunggu input: " + pending.prompt, sourceFile, lineNumber, null, e);
        } finally {
            pendingInputs.remove(promptId, pending);
            if (state != null && "waiting_input".equals(state.status)) state.status = "running";
        }
    }

    public JSONObject getPendingInputs(String workflowFilter, int limit) throws Exception {
        String filter = workflowFilter == null ? "*" : workflowFilter.trim();
        int max = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 500));
        JSONArray rows = new JSONArray();
        ArrayList<PendingInput> copy = new ArrayList<PendingInput>(pendingInputs.values());
        java.util.Collections.sort(copy, new java.util.Comparator<PendingInput>() {
            public int compare(PendingInput a, PendingInput b) { return a.createdAt < b.createdAt ? -1 : a.createdAt == b.createdAt ? 0 : 1; }
        });
        for (int i = 0; i < copy.size() && rows.length() < max; i++) {
            PendingInput p = copy.get(i);
            if (!"*".equals(filter) && filter.length() > 0 && !filter.equals(p.workflowId)) continue;
            if (p.latch.getCount() == 0 || p.cancelled) continue;
            JSONObject row = p.toJson();
            // Prompt metadata is available to AI, but the answer value is never returned here.
            rows.put(row);
        }
        JSONObject result = new JSONObject(); result.put("count", rows.length()); result.put("pending", rows); result.put("workflowId", filter); return result;
    }

    public synchronized JSONObject submitInput(String workflowId, String executionId, String promptId, String value, boolean fromAi) throws Exception {
        if (workflowId == null || workflowId.trim().length() == 0) throw new IllegalArgumentException("workflow_id wajib diisi");
        PendingInput pending = pendingInputs.get(promptId == null ? "" : promptId);
        if (pending == null) throw new IllegalArgumentException("prompt tidak ditemukan atau sudah dijawab/kedaluwarsa: " + promptId);
        if (!workflowId.equals(pending.workflowId)) throw new IllegalArgumentException("prompt_id tidak termasuk workflow_id tersebut");
        if (executionId != null && executionId.length() > 0 && !executionId.equals(pending.executionId)) throw new IllegalArgumentException("execution_id tidak cocok dengan prompt");
        if (fromAi) {
            JSONObject meta = getMetadata(workflowId);
            JSONObject permissions = meta == null ? null : meta.optJSONObject("permissions");
            if (permissions == null || !permissions.optBoolean("aiWriteInput", false)) {
                addLog(workflowId, pending.executionId, "warn", "input.ai_write_denied", "AI mencoba mengirim input tetapi izin write input nonaktif", "", 0, "promptId=" + promptId);
                throw new SecurityException("izin AI write input nonaktif untuk workflow '" + workflowId + "'");
            }
        }
        String submittedValue = value == null ? "" : value;
        validateInputValue(pending, submittedValue);
        String by = fromAi ? "ai" : "user";
        if (!pending.submit(submittedValue, by)) {
            if (pending.latch.getCount() == 0) throw new IllegalStateException("prompt sudah dijawab");
            throw new IllegalArgumentException("input wajib diisi");
        }
        JSONObject out = new JSONObject(); out.put("ok", true); out.put("workflowId", workflowId); out.put("executionId", pending.executionId);
        out.put("promptId", promptId); out.put("submittedBy", by); out.put("submittedAt", pending.submittedAt); out.put("submittedAtText", timestampText(pending.submittedAt));
        return out;
    }

    public JSONObject getExecution(String executionId) throws Exception {
        ExecutionState state = executionStates.get(executionId == null ? "" : executionId);
        if (state == null) { JSONObject notFound = new JSONObject(); notFound.put("found", false); notFound.put("executionId", executionId == null ? "" : executionId); return notFound; }
        JSONObject out = new JSONObject(); out.put("found", true); out.put("workflowId", state.workflowId); out.put("executionId", state.executionId);
        out.put("startedAt", state.startedAt); out.put("startedAtText", timestampText(state.startedAt)); out.put("status", state.status);
        out.put("pendingInputCount", countPendingInputs(state.workflowId, state.executionId));
        if (state.finishedAt > 0L) { out.put("finishedAt", state.finishedAt); out.put("finishedAtText", timestampText(state.finishedAt)); }
        if (state.result != null) out.put("result", state.result);
        if (state.error != null) out.put("error", state.error);
        return out;
    }

    private int countPendingInputs(String workflowId, String executionId) {
        int n = 0; for (PendingInput p : pendingInputs.values()) if (p.workflowId.equals(workflowId) && p.executionId.equals(executionId) && p.latch.getCount() > 0 && !p.cancelled) n++; return n;
    }

    private void cancelPendingInputsForWorkflow(String workflowId, String reason) {
        for (PendingInput p : pendingInputs.values()) {
            if (!workflowId.equals(p.workflowId) || p.latch.getCount() == 0) continue;
            if (p.cancel("runtime")) addLog(workflowId, p.executionId, "warn", "input.cancelled", reason, "", 0, "promptId=" + p.promptId);
        }
    }

    private void validateInputValue(PendingInput pending, String value) {
        if (pending.required && value.trim().length() == 0) throw new IllegalArgumentException("input wajib diisi");
        if ("number".equals(pending.inputType) && value.trim().length() > 0) {
            try { Double.parseDouble(value.trim()); } catch (Exception e) { throw new IllegalArgumentException("input harus berupa angka"); }
        } else if ("boolean".equals(pending.inputType) && value.trim().length() > 0
                && !"true".equalsIgnoreCase(value.trim()) && !"false".equalsIgnoreCase(value.trim())
                && !"ya".equalsIgnoreCase(value.trim()) && !"tidak".equalsIgnoreCase(value.trim())) {
            throw new IllegalArgumentException("input boolean harus true/false atau ya/tidak");
        } else if ("choice".equals(pending.inputType) && pending.choices.length() > 0) {
            boolean found = false; for (int i = 0; i < pending.choices.length(); i++) if (value.equals(String.valueOf(pending.choices.opt(i)))) found = true;
            if (!found) throw new IllegalArgumentException("nilai input tidak termasuk pilihan yang tersedia");
        }
    }

    private String normalizeInputType(String type) {
        String t = type == null ? "text" : type.trim().toLowerCase(Locale.US);
        if ("text".equals(t) || "number".equals(t) || "password".equals(t) || "boolean".equals(t) || "choice".equals(t)) return t;
        throw new IllegalArgumentException("input_type harus text, number, password, boolean, atau choice");
    }

    private void pruneExecutionStates() {
        if (executionStates.size() <= 300) return;
        ArrayList<ExecutionState> states = new ArrayList<ExecutionState>(executionStates.values());
        java.util.Collections.sort(states, new java.util.Comparator<ExecutionState>() {
            public int compare(ExecutionState a, ExecutionState b) { return a.startedAt < b.startedAt ? -1 : a.startedAt == b.startedAt ? 0 : 1; }
        });
        int remove = states.size() - 250;
        for (int i = 0; i < remove; i++) if (!"running".equals(states.get(i).status) && !"waiting_input".equals(states.get(i).status)) executionStates.remove(states.get(i).executionId);
    }

    private static String timestampText(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(timestamp));
    }

    public JSONObject callMcpTool(String name, JSONObject a) throws Exception {
        if (a == null) a = new JSONObject();
        String id = a.optString("workflow_id", a.optString("id", ""));
        if ("workflow.list".equals(name)) { JSONObject r = new JSONObject(); r.put("workflows", listWorkflows()); return r; }
        if ("workflow.create".equals(name)) {
            JSONObject meta;
            if (a.has("metadata")) meta = a.optJSONObject("metadata");
            else {
                meta = createMetadata(id, a.optString("name", id), a.optString("description", ""), a.optString("format", "flow"), a.optString("entry", ""), a.optString("endpoint_path", ""));
                JSONObject endpoint = meta.optJSONObject("endpoint");
                if (endpoint != null && a.has("endpoint_mode")) {
                    String mode = normalizeEndpointMode(a.optString("endpoint_mode", "none"));
                    endpoint.put("mode", mode); endpoint.put("enabled", !"none".equals(mode));
                    if ("shared".equals(mode)) { endpoint.put("port", ENDPOINT_PORT); endpoint.put("autoPort", false); }
                    else if ("exclusive".equals(mode)) { int port = a.optInt("endpoint_port", 0); validateEndpointPort(port, port == 0); endpoint.put("port", port); endpoint.put("autoPort", port == 0); }
                    else { endpoint.put("port", 0); endpoint.put("autoPort", true); }
                }
            }
            JSONObject created = createWorkflow(meta);
            String createdId = created.optString("id", id);
            if (a.has("content")) writeFile(createdId, created.optString("entry"), a.optString("content", ""));
            JSONObject r = new JSONObject(); r.put("workflow", getMetadata(createdId)); r.put("created", true); return r;
        }
        if ("workflow.update".equals(name)) {
            JSONObject changes = a.optJSONObject("changes");
            if (changes == null) changes = new JSONObject(a.toString());
            if (a.has("endpoint_path") && !changes.has("endpointPath")) changes.put("endpointPath", a.optString("endpoint_path", ""));
            if (a.has("endpoint_mode") && !changes.has("endpointMode")) changes.put("endpointMode", a.optString("endpoint_mode", "none"));
            if (a.has("endpoint_port") && !changes.has("endpointPort")) changes.put("endpointPort", a.optInt("endpoint_port", 0));
            if (a.has("ai_write_input") && !changes.has("aiWriteInput")) changes.put("aiWriteInput", a.optBoolean("ai_write_input", false));
            // Permission grant/revoke is a local-user decision; MCP cannot alter this field.
            changes.remove("permissions");
            changes.remove("aiWriteInput");
            JSONObject result = updateMetadata(id, changes);
            if (a.has("content")) writeFile(id, result.optString("entry"), a.optString("content", ""));
            JSONObject r = new JSONObject(); r.put("workflow", result); return r;
        }
        if ("workflow.delete".equals(name)) { deleteWorkflow(id); JSONObject r = new JSONObject(); r.put("deleted", true); r.put("workflowId", id); return r; }
        if ("workflow.read_file".equals(name)) return "base64".equalsIgnoreCase(a.optString("encoding", "utf8")) ? readFileBase64(id, a.optString("path", "")) : readFile(id, a.optString("path", ""));
        if ("workflow.write_file".equals(name)) {
            if ("base64".equalsIgnoreCase(a.optString("encoding", "utf8"))) writeFileBase64(id, a.optString("path", ""), a.optString("content", ""));
            else writeFile(id, a.optString("path", ""), a.optString("content", ""));
            JSONObject r = new JSONObject(); r.put("saved", true); r.put("workflowId", id); r.put("path", a.optString("path", "")); r.put("encoding", a.optString("encoding", "utf8")); return r;
        }
        if ("workflow.delete_file".equals(name)) { deleteFile(id, a.optString("path", "")); JSONObject r = new JSONObject(); r.put("deleted", true); return r; }
        if ("workflow.create_folder".equals(name)) { createFolder(id, a.optString("path", "")); JSONObject r = new JSONObject(); r.put("created", true); r.put("workflowId", id); r.put("path", a.optString("path", "")); return r; }
        if ("workflow.list_files".equals(name)) { JSONObject r = new JSONObject(); r.put("workflowId", id); r.put("files", listFiles(id)); return r; }
        if ("workflow.validate".equals(name)) return validateWorkflowAndLog(id);
        if ("workflow.activate".equals(name)) {
            JSONObject activated = activate(id); JSONObject endpoint = activated.optJSONObject("endpoint");
            String mode = endpoint == null ? "none" : endpoint.optString("mode", "none");
            String url = "";
            if ("shared".equals(mode)) url = "http://127.0.0.1:" + ENDPOINT_PORT + endpoint.optString("path", "");
            else if ("exclusive".equals(mode) && endpoint.optInt("port", 0) > 0) url = "http://127.0.0.1:" + endpoint.optInt("port") + endpoint.optString("path", "");
            JSONObject r = new JSONObject(); r.put("workflow", activated); r.put("endpointUrl", url.length() == 0 ? JSONObject.NULL : url); r.put("endpointMode", mode); return r;
        }
        if ("workflow.deactivate".equals(name)) { JSONObject r = new JSONObject(); r.put("workflow", deactivate(id)); return r; }
        if ("workflow.start".equals(name)) return startAsync(id, a.optJSONObject("input"));
        if ("workflow.run".equals(name)) return run(id, a.optJSONObject("input"));
        if ("workflow.get_pending_input".equals(name)) return getPendingInputs(id.length() == 0 ? "*" : id, a.optInt("limit", 100));
        if ("workflow.submit_input".equals(name)) return submitInput(id, a.optString("execution_id", ""), a.optString("prompt_id", ""), a.optString("value", ""), true);
        if ("workflow.get_execution".equals(name)) return getExecution(a.optString("execution_id", ""));
        if ("workflow.get_logs".equals(name)) return getLogs(id.length() == 0 ? "*" : id, a.optString("level", "all"), a.optString("since", "all"), a.optLong("since_timestamp", 0L), a.optInt("limit", 100), a.optInt("offset", 0), a.optString("execution_id", ""));
        if ("workflow.get_info_logs".equals(name)) return getLogs(id.length() == 0 ? "*" : id, "info", a.optString("since", "all"), a.optLong("since_timestamp", 0L), a.optInt("limit", 100), a.optInt("offset", 0), a.optString("execution_id", ""));
        if ("workflow.get_error_logs".equals(name) || "workflow.get_errors".equals(name)) return getLogs(id.length() == 0 ? "*" : id, "error", a.optString("since", "all"), a.optLong("since_timestamp", 0L), a.optInt("limit", 100), a.optInt("offset", 0), a.optString("execution_id", ""));
        throw new IllegalArgumentException("workflow tool tidak dikenal: " + name);
    }

    public synchronized void exportZip(String id, OutputStream output) throws Exception {
        JSONObject meta = getMetadata(id);
        if (meta == null) throw new IllegalArgumentException("workflow tidak ditemukan: " + id);
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output));
        try { addZipFiles(zip, workflowDirectory(id), workflowDirectory(id), "workflow/"); zip.finish(); zip.flush(); }
        finally { try { zip.close(); } catch (Exception ignored) { } }
    }

    private void addZipFiles(ZipOutputStream zip, File rootDir, File current, String prefix) throws Exception {
        File[] files = current.listFiles(); if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            String relative = f.getCanonicalPath().substring(rootDir.getCanonicalPath().length() + 1).replace(File.separatorChar, '/');
            if (f.isDirectory()) {
                zip.putNextEntry(new ZipEntry(prefix + relative + "/"));
                zip.closeEntry();
                addZipFiles(zip, rootDir, f, prefix);
                continue;
            }
            zip.putNextEntry(new ZipEntry(prefix + relative));
            FileInputStream in = new FileInputStream(f);
            try { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) zip.write(b, 0, n); }
            finally { in.close(); zip.closeEntry(); }
        }
    }

    public synchronized JSONObject importZip(InputStream input) throws Exception {
        File temp = new File(context.getCacheDir(), "workflow-import-" + UUID.randomUUID().toString());
        if (!temp.mkdirs()) throw new IllegalStateException("gagal membuat folder sementara");
        long total = 0L; int count = 0;
        try {
            ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input));
            try {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    count++; if (count > 1000) throw new IllegalArgumentException("ZIP berisi terlalu banyak file");
                    String name = entry.getName().replace('\\', '/');
                    if (name.startsWith("/") || name.indexOf(':') >= 0 || containsDotDot(name)) throw new IllegalArgumentException("path ZIP tidak valid: " + name);
                    if (!name.startsWith("workflow/") && !name.equals("workflow")) throw new IllegalArgumentException("paket ZIP harus memiliki folder root workflow/");
                    File out = new File(temp, name);
                    if (!out.getCanonicalPath().startsWith(temp.getCanonicalPath() + File.separator) && !out.equals(temp)) throw new IllegalArgumentException("ZIP mencoba keluar dari folder ekstraksi");
                    if (entry.isDirectory()) { if (!out.exists() && !out.mkdirs()) throw new IllegalStateException("gagal membuat folder ZIP"); }
                    else {
                        File parent = out.getParentFile(); if (!parent.exists() && !parent.mkdirs()) throw new IllegalStateException("gagal membuat folder ZIP");
                        FileOutputStream fos = new FileOutputStream(out); byte[] b = new byte[8192]; int n; long fileSize = 0;
                        try { while ((n = zip.read(b)) != -1) { total += n; fileSize += n; if (total > MAX_ZIP_BYTES || fileSize > 20L * 1024L * 1024L) throw new IllegalArgumentException("ukuran ZIP melampaui batas"); fos.write(b, 0, n); } }
                        finally { fos.close(); }
                    }
                    zip.closeEntry();
                }
            } finally { zip.close(); }
            File workflowFolder = new File(temp, "workflow");
            File metaFile = new File(workflowFolder, "metadata.json");
            if (!metaFile.isFile()) throw new IllegalArgumentException("metadata.json tidak ditemukan di ZIP");
            JSONObject meta = new JSONObject(readUtf8(metaFile, 1024 * 1024));
            String id = validateId(meta.optString("id", ""));
            if (workflowDirectory(id).exists()) throw new IllegalArgumentException("workflow ID sudah terpasang: " + id);
            meta = normalizeMetadata(meta, id);
            meta.put("enabled", false);
            // Permission grants must be made by the local user in the editor, not by ZIP contents.
            JSONObject importedPermissions = meta.optJSONObject("permissions");
            if (importedPermissions == null) importedPermissions = new JSONObject();
            importedPermissions.put("aiWriteInput", false);
            meta.put("permissions", importedPermissions);
            writeUtf8(metaFile, meta.toString(2));
            File[] children = workflowFolder.listFiles();
            if (children == null) throw new IllegalArgumentException("folder workflow kosong");
            File dest = workflowDirectory(id);
            if (!workflowFolder.renameTo(dest)) {
                copyRecursive(workflowFolder, dest);
                deleteRecursive(workflowFolder);
            }
            JSONObject validation = validateWorkflow(id, true);
            addLog(id, "", validation.optBoolean("ok") ? "info" : "error", "workflow.imported", "Workflow diimpor dari ZIP: " + validation.optString("message"), "metadata.json", 0, null);
            JSONObject result = new JSONObject(); result.put("workflow", getMetadata(id)); result.put("valid", validation.optBoolean("ok")); result.put("validation", validation); return result;
        } finally { deleteRecursive(temp); }
    }

    private JSONObject normalizeMetadata(JSONObject input, String id) throws Exception {
        JSONObject out = new JSONObject(input.toString());
        out.put("id", validateId(id));
        out.put("name", clean(out.optString("name", id), id, 120));
        out.put("description", clean(out.optString("description", ""), "", 1000));
        String format = normalFormat(out.optString("format", "flow")); out.put("format", format);
        String entry = normalizeRelative(out.optString("entry", "script/main." + format));
        if (!entry.startsWith("script/")) throw new IllegalArgumentException("entry point harus berada di script/");
        out.put("entry", entry);
        if (!out.has("enabled")) out.put("enabled", false);
        JSONObject endpoint = out.optJSONObject("endpoint"); if (endpoint == null) endpoint = new JSONObject();
        boolean legacyEndpoint = !endpoint.has("mode");
        String mode = normalizeEndpointMode(endpoint.optString("mode", legacyEndpoint && endpoint.optBoolean("enabled", false) ? "shared" : "none"));
        endpoint.put("mode", mode);
        endpoint.put("path", normalizeEndpointPath(endpoint.optString("path", ""), id));
        endpoint.put("method", endpoint.optString("method", "POST").toUpperCase(Locale.US));
        endpoint.put("enabled", !"none".equals(mode));
        if ("shared".equals(mode)) { endpoint.put("port", ENDPOINT_PORT); endpoint.put("autoPort", false); }
        else if ("none".equals(mode)) { endpoint.put("port", 0); endpoint.put("autoPort", true); }
        else {
            int configuredPort = endpoint.optInt("port", 0);
            validateEndpointPort(configuredPort, endpoint.optBoolean("autoPort", configuredPort == 0));
            if (configuredPort == ENDPOINT_PORT) throw new IllegalArgumentException("port 8084 digunakan server bersama");
            endpoint.put("port", configuredPort);
            endpoint.put("autoPort", endpoint.optBoolean("autoPort", configuredPort == 0));
        }
        out.put("endpoint", endpoint);
        JSONObject permissions = out.optJSONObject("permissions"); if (permissions == null) permissions = new JSONObject();
        permissions.put("aiWriteInput", permissions.optBoolean("aiWriteInput", out.optBoolean("aiWriteInput", false)));
        out.put("permissions", permissions);
        if (!out.has("createdAt")) out.put("createdAt", System.currentTimeMillis());
        out.put("updatedAt", System.currentTimeMillis());
        return out;
    }

    synchronized void setAssignedEndpointPort(String id, int port) {
        try {
            JSONObject meta = getMetadata(id);
            if (meta == null) return;
            JSONObject endpoint = meta.optJSONObject("endpoint");
            if (endpoint == null || !"exclusive".equals(endpoint.optString("mode", "none"))) return;
            endpoint.put("port", port);
            // autoPort stays true for automatically selected ports, so a future session can retry/reallocate.
            meta.put("updatedAt", System.currentTimeMillis());
            writeMetadataFile(workflowDirectory(id), meta);
        } catch (Exception e) { Log.e(TAG, "gagal menyimpan port workflow " + id, e); }
    }

    private File workflowDirectory(String id) throws Exception {
        String safe = validateId(id);
        File dir = new File(root, safe);
        if (!dir.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IllegalArgumentException("workflow path invalid");
        return dir;
    }

    public static String validateId(String id) {
        if (!isValidId(id)) throw new IllegalArgumentException("ID wajib berisi 1-64 karakter: huruf, angka, underscore, atau hyphen");
        return id;
    }
    private static boolean isValidId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{1,64}"); }
    private static String normalFormat(String value) {
        String f = value == null ? "flow" : value.trim().toLowerCase(Locale.US);
        if (".flow".equals(f)) f = "flow"; if (".bshflow".equals(f)) f = "bshflow";
        if (!"flow".equals(f) && !"bshflow".equals(f)) throw new IllegalArgumentException("format harus flow atau bshflow");
        return f;
    }
    private static String clean(String value, String fallback, int max) {
        String s = value == null ? "" : value.trim(); if (s.length() == 0) s = fallback; if (s.length() > max) s = s.substring(0, max); return s;
    }
    public static String normalizeRelative(String path) {
        if (path == null) throw new IllegalArgumentException("path kosong");
        String s = path.trim().replace('\\', '/');
        while (s.startsWith("./")) s = s.substring(2);
        if (s.length() == 0 || s.startsWith("/") || s.indexOf(':') >= 0 || containsDotDot(s)) throw new IllegalArgumentException("path relatif tidak valid: " + path);
        if (s.indexOf('\u0000') >= 0) throw new IllegalArgumentException("path tidak valid");
        return s;
    }
    private static boolean containsDotDot(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) if ("..".equals(parts[i])) return true;
        return false;
    }
    private static String normalizeEndpointMode(String mode) {
        String m = mode == null ? "none" : mode.trim().toLowerCase(Locale.US);
        if ("none".equals(m) || "shared".equals(m) || "exclusive".equals(m)) return m;
        throw new IllegalArgumentException("mode endpoint harus none, shared, atau exclusive");
    }
    private static void validateEndpointPort(int port, boolean autoPort) {
        if (autoPort && port == 0) return;
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("port eksklusif harus 1024-65535, atau 0 untuk otomatis");
        if (port == 9010) throw new IllegalArgumentException("port 9010 digunakan server MCP");
    }
    private static String normalizeEndpointPath(String path, String id) {
        String s = path == null ? "" : path.trim();
        if (s.length() == 0) s = "/workflow/" + id;
        if (!s.startsWith("/")) s = "/" + s;
        if (s.indexOf('?') >= 0 || s.indexOf('#') >= 0 || s.contains("..") || s.indexOf(' ') >= 0 || s.indexOf('\\') >= 0) throw new IllegalArgumentException("endpoint path tidak valid");
        return s;
    }
    private void writeMetadataFile(File dir, JSONObject meta) throws Exception {
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("gagal membuat folder workflow");
        writeUtf8(new File(dir, "metadata.json"), meta.toString(2));
    }
    private static void writeUtf8(File file, String value) throws Exception { writeBytes(file, value.getBytes("UTF-8")); }
    private static void writeBytes(File file, byte[] bytes) throws Exception {
        File parent = file.getParentFile(); if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("gagal membuat folder parent");
        FileOutputStream out = new FileOutputStream(file); try { out.write(bytes); out.flush(); } finally { out.close(); }
    }
    private static String readUtf8(File file, long max) throws Exception {
        if (file.length() > max) throw new IllegalArgumentException("file terlalu besar: " + file.getName());
        FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); }
        finally { in.close(); }
        return new String(out.toByteArray(), "UTF-8");
    }
    private static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) { File[] list = f.listFiles(); if (list != null) for (int i = 0; i < list.length; i++) if (!deleteRecursive(list[i])) return false; }
        return f.delete();
    }
    private static void copyRecursive(File from, File to) throws Exception {
        if (from.isDirectory()) {
            if (!to.exists() && !to.mkdirs()) throw new IllegalStateException("gagal menyalin folder");
            File[] children = from.listFiles(); if (children != null) for (int i = 0; i < children.length; i++) copyRecursive(children[i], new File(to, children[i].getName()));
        } else {
            FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to);
            try { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); } finally { in.close(); out.close(); }
        }
    }
    private static String formatTimestamp(long time) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(time)); }
    private static String normalLevel(String level) { String s = level == null ? "info" : level.toLowerCase(Locale.US); if (!"info".equals(s) && !"warn".equals(s) && !"error".equals(s) && !"debug".equals(s)) return "info"; return s; }
    private static String safeMessage(Exception e) { String s = e.getMessage(); if (s == null || s.trim().length() == 0) s = e.getClass().getSimpleName(); return s.length() > 1000 ? s.substring(0, 1000) : s; }
    private static boolean classExists(String name) { try { Class.forName(name); return true; } catch (Throwable ignored) { return false; } }
    private static String defaultFlowSource() {
        return "{\n  \"steps\": [\n    { \"action\": \"navigate\", \"url\": \"https://example.com\" },\n    { \"action\": \"extract\", \"selector\": \"h1\", \"as\": \"title\" },\n    { \"action\": \"respond\", \"value\": \"${vars.title}\" }\n  ]\n}\n";
    }
    private static String defaultBeanShellSource() {
        return "// API workflow: browser.navigate(url), browser.input(selector, value), browser.click(selector), browser.extract(selector), browser.waitFor(selector, timeoutMs)\n// Input request: browser.getInput(\"message\")\n// Log: printLog(\"pesan log\")\n\n// browser.navigate(\"https://example.com\");\n// printLog(\"halaman dibuka\");\n// browser.setResult(browser.extract(\"h1\"));\n";
    }

    private static final class LogDb extends SQLiteOpenHelper {
        LogDb(Context context) { super(context, "workflow_log.db", null, 1); }
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE workflow_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, workflow_id TEXT NOT NULL, timestamp INTEGER NOT NULL, level TEXT NOT NULL, event TEXT NOT NULL, message TEXT NOT NULL, details TEXT, source_file TEXT, line_number INTEGER NOT NULL DEFAULT 0, execution_id TEXT NOT NULL DEFAULT '')");
            db.execSQL("CREATE INDEX idx_workflow_logs_time ON workflow_logs(workflow_id, timestamp DESC)");
            db.execSQL("CREATE INDEX idx_workflow_logs_level ON workflow_logs(workflow_id, level, timestamp DESC)");
            db.execSQL("CREATE INDEX idx_workflow_logs_execution ON workflow_logs(execution_id)");
        }
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }
    }
}
