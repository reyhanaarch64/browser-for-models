package rh.browser.models;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

/** Executes declarative .flow jobs and BeanShell .bshflow jobs in their own WebView. */
public final class WorkflowExecutor {
    private final Context context;
    private final WorkflowManager manager;
    public WorkflowExecutor(Context context, WorkflowManager manager) { this.context = context.getApplicationContext(); this.manager = manager; }

    public JSONObject execute(JSONObject metadata, JSONObject input, String executionId) throws Exception {
        WorkflowBrowser browser = new WorkflowBrowser(context);
        try {
            WorkflowRuntimeApi api = new WorkflowRuntimeApi(context, manager, browser, metadata, input, executionId);
            String id = metadata.optString("id", "");
            String entry = metadata.optString("entry", "");
            File entryFile = manager.resolveWorkflowFile(id, entry, false);
            String source = readText(entryFile);
            String format = metadata.optString("format", "flow").toLowerCase(Locale.US);
            if ("flow".equals(format)) return executeFlow(source, metadata, input, executionId, browser, api, entry);
            if ("bshflow".equals(format)) return executeBeanShell(source, metadata, input, executionId, api, entry);
            throw new WorkflowExecutionException("format workflow tidak dikenal: " + format, entry, 0);
        } finally { browser.destroy(); }
    }

    private JSONObject executeFlow(String source, JSONObject metadata, JSONObject input, String executionId,
                                   WorkflowBrowser browser, WorkflowRuntimeApi api, String entry) throws Exception {
        JSONObject flow;
        try { flow = new JSONObject(source); }
        catch (Exception e) { throw new WorkflowExecutionException("syntax error JSON pada " + entry + ": " + safe(e), entry, extractJsonLine(source, e.getMessage()), sourceExcerpt(source), e); }
        JSONArray steps = flow.optJSONArray("steps");
        if (steps == null) throw new WorkflowExecutionException(".flow wajib memiliki array 'steps'", entry, 0);
        JSONObject vars = flow.optJSONObject("variables"); if (vars == null) vars = new JSONObject();
        Object response = null; boolean hasResponse = false;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) throw new WorkflowExecutionException("step ke-" + i + " harus berupa object JSON", entry, i + 1);
            api.setSourceLocation(entry, i + 1);
            String action = step.optString("action", step.optString("type", "")).trim().toLowerCase(Locale.US);
            try {
                if ("navigate".equals(action) || "open".equals(action)) {
                    browser.navigate(resolve(step.optString("url", ""), input, vars), step.optLong("timeout", 30000L));
                    vars.put("url", browser.getUrl());
                } else if ("waitfor".equals(action) || "wait_for".equals(action) || "wait".equals(action)) {
                    String condition = step.optString("condition", "element").toLowerCase(Locale.US);
                    long timeout = step.optLong("timeout", 10000L);
                    boolean ok;
                    if ("text".equals(condition)) ok = browser.waitForText(resolve(step.optString("text", ""), input, vars), timeout);
                    else if ("url".equals(condition)) ok = browser.waitForUrl(resolve(step.optString("url", ""), input, vars), timeout);
                    else if ("element_hidden".equals(condition) || "hidden".equals(condition)) {
                        long end = System.currentTimeMillis() + Math.max(1L, Math.min(timeout, 90000L)); ok = false;
                        do { if (!browser.exists(resolve(step.optString("selector", ""), input, vars))) { ok = true; break; } Thread.sleep(200L); } while (System.currentTimeMillis() < end);
                    } else if ("custom".equals(condition)) {
                        String expression = resolve(step.optString("expression", "false"), input, vars);
                        long end = System.currentTimeMillis() + Math.max(1L, Math.min(timeout, 90000L)); ok = false;
                        do { Object r = browser.evaluate("return !!(" + expression + ");"); if (Boolean.TRUE.equals(r)) { ok = true; break; } Thread.sleep(200L); } while (System.currentTimeMillis() < end);
                    } else ok = browser.waitFor(resolve(step.optString("selector", ""), input, vars), timeout);
                    if (!ok) throw new IllegalStateException("timeout menunggu kondisi: " + condition);
                } else if ("prompt".equals(action) || "input_prompt".equals(action)
                        || ("input".equals(action) && (step.has("prompt") || step.has("inputType") || (step.has("name") && !step.has("selector"))))) {
                    String inputName = step.optString("name", step.optString("as", ""));
                    String prompt = resolve(step.optString("prompt", step.optString("message", "Input diperlukan")), input, vars);
                    String inputType = step.optString("inputType", step.optString("input_type", "text"));
                    boolean required = step.optBoolean("required", true);
                    String defaultValue = resolve(step.optString("default", ""), input, vars);
                    JSONArray choices = step.optJSONArray("choices");
                    String value = api.promptInput(prompt, inputName, inputType, required, defaultValue, choices,
                            step.optLong("timeout", 10L * 60L * 1000L), step.optBoolean("alwaysPrompt", false));
                    if (inputName.length() > 0) vars.put(step.optString("as", inputName), value);
                    else vars.put(step.optString("as", "inputValue"), value);
                } else if ("input".equals(action) || "fill".equals(action)) {
                    browser.input(resolve(step.optString("selector", ""), input, vars), resolve(step.optString("value", ""), input, vars));
                } else if ("click".equals(action)) {
                    browser.click(resolve(step.optString("selector", ""), input, vars));
                    long settle = step.optLong("settleMs", 150L); if (settle > 0) Thread.sleep(Math.min(settle, 10000L));
                    vars.put("url", browser.getUrl());
                } else if ("extract".equals(action)) {
                    String selector = resolve(step.optString("selector", ""), input, vars);
                    String attr = resolve(step.optString("attribute", ""), input, vars);
                    String value = browser.extract(selector, attr);
                    String name = step.optString("as", "result"); vars.put(name, value == null ? JSONObject.NULL : value);
                } else if ("extractall".equals(action) || "extract_all".equals(action)) {
                    String selector = resolve(step.optString("selector", ""), input, vars);
                    String attr = resolve(step.optString("attribute", ""), input, vars);
                    vars.put(step.optString("as", "results"), browser.extractAll(selector, attr));
                } else if ("set".equals(action)) {
                    vars.put(step.optString("name", "value"), resolveValue(step.opt("value"), input, vars));
                } else if ("readasset".equals(action) || "read_asset".equals(action)) {
                    String assetPath = resolve(step.optString("path", ""), input, vars);
                    String encoding = step.optString("encoding", "utf8");
                    Object assetValue;
                    if ("base64".equalsIgnoreCase(encoding)) {
                        JSONObject asset = manager.readFileBase64(metadata.optString("id", ""), assetPath.startsWith("assets/") ? assetPath : "assets/" + assetPath);
                        assetValue = asset.optString("content", "");
                    } else {
                        String content = api.readAsset(assetPath);
                        assetValue = step.optBoolean("parseJson", false) ? new JSONTokener(content).nextValue() : content;
                    }
                    vars.put(step.optString("as", "asset"), assetValue == null ? JSONObject.NULL : assetValue);
                } else if ("writeasset".equals(action) || "write_asset".equals(action)) {
                    String assetPath = resolve(step.optString("path", ""), input, vars);
                    String content = resolve(step.optString("content", ""), input, vars);
                    if ("base64".equalsIgnoreCase(step.optString("encoding", "utf8")))
                        manager.writeFileBase64(metadata.optString("id", ""), assetPath.startsWith("assets/") ? assetPath : "assets/" + assetPath, content);
                    else api.writeAsset(assetPath, content);
                    vars.put(step.optString("as", "assetWritten"), Boolean.TRUE);
                } else if ("delay".equals(action) || "sleep".equals(action)) {
                    Thread.sleep(Math.max(0L, Math.min(step.optLong("ms", step.optLong("duration", 500L)), 60000L)));
                } else if ("scroll".equals(action)) {
                    api.scroll(resolve(step.optString("direction", "down"), input, vars), step.optInt("pixels", 600), resolve(step.optString("selector", ""), input, vars));
                } else if ("assert".equals(action)) {
                    String kind = step.optString("condition", "element"); boolean ok;
                    if ("text".equals(kind)) ok = browser.waitForText(resolve(step.optString("text", ""), input, vars), 1L);
                    else if ("url".equals(kind)) ok = browser.getUrl().contains(resolve(step.optString("url", ""), input, vars));
                    else ok = browser.exists(resolve(step.optString("selector", ""), input, vars));
                    if (!ok) throw new IllegalStateException(step.optString("message", "assert gagal: " + kind));
                } else if ("respond".equals(action) || "return".equals(action)) {
                    response = resolveValue(step.opt("value"), input, vars); hasResponse = true;
                } else if ("log".equals(action) || "printlog".equals(action)) {
                    api.printLog(resolve(String.valueOf(step.opt("message")), input, vars));
                } else {
                    throw new IllegalArgumentException("action tidak dikenal: " + action);
                }
            } catch (Exception e) {
                String msg = "step " + (i + 1) + " ('" + action + "') gagal: " + safe(e);
                throw new WorkflowExecutionException(msg, entry, i + 1, "step=" + step.toString(), e);
            }
        }
        Object finalResponse;
        if (flow.has("output")) finalResponse = resolveValue(flow.opt("output"), input, vars);
        else if (hasResponse) finalResponse = response;
        else finalResponse = vars.opt("result") == null ? JSONObject.NULL : vars.opt("result");
        JSONObject out = new JSONObject();
        out.put("response", finalResponse == null ? JSONObject.NULL : finalResponse);
        out.put("output", finalResponse == null ? JSONObject.NULL : finalResponse);
        out.put("stepsCompleted", steps.length());
        return out;
    }

    private JSONObject executeBeanShell(String source, JSONObject metadata, JSONObject input, String executionId,
                                        WorkflowRuntimeApi api, String entry) throws Exception {
        Class<?> interpreterClass;
        try { interpreterClass = Class.forName("bsh.Interpreter"); }
        catch (Throwable e) { throw new WorkflowExecutionException("BeanShell belum tersedia. Tambahkan bsh-2.0b4.jar ke folder libs AIDE, lalu build ulang aplikasi.", entry, 0, null, e); }
        Object interpreter;
        try {
            Constructor<?> ctor = interpreterClass.getConstructor();
            interpreter = ctor.newInstance();
            Method set = interpreterClass.getMethod("set", String.class, Object.class);
            Method eval = interpreterClass.getMethod("eval", String.class);
            set.invoke(interpreter, "browser", api);
            set.invoke(interpreter, "workflow", api);
            set.invoke(interpreter, "__rhApi", api);
            api.setSourceLocation(entry, 0);
            eval.invoke(interpreter, "void printLog(Object message) { __rhApi.printLog(message); }\nvoid logInfo(Object message) { __rhApi.logInfo(message); }\nvoid logWarn(Object message) { __rhApi.logWarn(message); }\nvoid logError(Object message) { __rhApi.logError(message); }\nString input(String prompt) { return __rhApi.promptInput(prompt); }\nString input(String prompt, String inputKey) { return __rhApi.promptInput(prompt, inputKey); }");
            Object returned = eval.invoke(interpreter, source);
            if (api.getResult() == null && returned != null) api.setResult(returned);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            int line = extractLine(cause);
            String msg = safe(cause);
            throw new WorkflowExecutionException("BeanShell error: " + msg, entry, line, stackTrace(cause), cause);
        } catch (Throwable e) {
            throw new WorkflowExecutionException("gagal menjalankan BeanShell: " + safe(e), entry, 0, stackTrace(e), e);
        }
        JSONObject out = new JSONObject();
        Object result = api.getResult();
        out.put("response", result == null ? JSONObject.NULL : normalizeResult(result));
        out.put("format", "bshflow");
        return out;
    }

    private Object normalizeResult(Object value) {
        if (value == null) return JSONObject.NULL;
        if (value instanceof JSONObject || value instanceof JSONArray || value instanceof Number || value instanceof Boolean) return value;
        if (value == JSONObject.NULL) return JSONObject.NULL;
        if (value instanceof String) {
            String s = (String)value;
            try { return new JSONTokener(s).nextValue(); } catch (Exception ignored) { return s; }
        }
        return String.valueOf(value);
    }

    private Object resolveValue(Object value, JSONObject input, JSONObject vars) throws Exception {
        if (value == null || value == JSONObject.NULL) return JSONObject.NULL;
        if (value instanceof String) {
            String text = (String)value;
            boolean dollar = text.matches("\\$\\{(?:input|vars)\\.[A-Za-z0-9_.-]+\\}");
            boolean moustache = text.matches("\\{\\{(?:input|vars)\\.[A-Za-z0-9_.-]+\\}\\}");
            if (dollar || moustache) {
                String key = dollar ? text.substring(2, text.length() - 1) : text.substring(2, text.length() - 2);
                if (key.startsWith("input.")) { Object found = pathValue(input, key.substring(6)); return found == null ? JSONObject.NULL : found; }
                if (key.startsWith("vars.")) { Object found = pathValue(vars, key.substring(5)); return found == null ? JSONObject.NULL : found; }
            }
            return resolve(text, input, vars);
        }
        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject)value, out = new JSONObject(); java.util.Iterator<String> keys = source.keys();
            while (keys.hasNext()) { String key = keys.next(); out.put(key, resolveValue(source.opt(key), input, vars)); } return out;
        }
        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray)value, out = new JSONArray(); for (int i = 0; i < source.length(); i++) out.put(resolveValue(source.opt(i), input, vars)); return out;
        }
        return value;
    }

    private String resolve(String value, JSONObject input, JSONObject vars) throws Exception {
        if (value == null) return "";
        String out = value;
        out = out.replace("{{input.", "${input.").replace("{{vars.", "${vars.").replace("{{url", "${url").replace("}}", "}");
        int loops = 0;
        while (loops++ < 50) {
            int start = out.indexOf("${"); if (start < 0) break;
            int end = out.indexOf('}', start + 2); if (end < 0) break;
            String key = out.substring(start + 2, end).trim(); Object found = null;
            if ("url".equals(key)) found = pathValue(vars, "url");
            else if (key.startsWith("input.")) found = pathValue(input, key.substring(6));
            else if (key.startsWith("vars.")) found = pathValue(vars, key.substring(5));
            String replacement = found == null || found == JSONObject.NULL ? "" : String.valueOf(found);
            out = out.substring(0, start) + replacement + out.substring(end + 1);
        }
        return out;
    }

    private Object pathValue(JSONObject json, String path) {
        if (json == null || path == null) return null;
        String[] parts = path.split("\\."); Object current = json;
        for (int i = 0; i < parts.length; i++) {
            if (current instanceof JSONObject) current = ((JSONObject)current).opt(parts[i]);
            else if (current instanceof JSONArray) { try { current = ((JSONArray)current).opt(Integer.parseInt(parts[i])); } catch (Exception e) { return null; } }
            else return null;
        }
        return current;
    }

    private String readText(File f) throws Exception {
        if (!f.isFile()) throw new IllegalArgumentException("file workflow tidak ditemukan: " + f.getName());
        if (f.length() > WorkflowManager.MAX_SCRIPT_BYTES) throw new IllegalArgumentException("script workflow lebih besar dari 2 MiB");
        java.io.FileInputStream in = new java.io.FileInputStream(f); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); }
        finally { in.close(); }
        return new String(out.toByteArray(), "UTF-8");
    }
    private int extractLine(Throwable t) {
        if (t == null) return 0;
        String[] names = new String[] { "getErrorLineNumber", "getLineNumber" };
        for (int i = 0; i < names.length; i++) try { Object value = t.getClass().getMethod(names[i]).invoke(t); if (value instanceof Number) return ((Number)value).intValue(); } catch (Exception ignored) { }
        return 0;
    }
    private int extractJsonLine(String source, String message) {
        if (source == null || message == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)(?:character|position)\\s+(\\d+)").matcher(message);
        if (!matcher.find()) return 0;
        int offset;
        try { offset = Integer.parseInt(matcher.group(1)); } catch (Exception e) { return 0; }
        offset = Math.max(0, Math.min(offset, source.length()));
        int line = 1; for (int i = 0; i < offset; i++) if (source.charAt(i) == '\n') line++;
        return line;
    }
    private String sourceExcerpt(String source) { if (source == null) return ""; return source.length() > 2000 ? source.substring(0, 2000) : source; }
    private String stackTrace(Throwable t) { java.io.StringWriter sw = new java.io.StringWriter(); java.io.PrintWriter pw = new java.io.PrintWriter(sw); if (t != null) t.printStackTrace(pw); pw.flush(); String s = sw.toString(); return s.length() > 8000 ? s.substring(0, 8000) : s; }
    private String safe(Throwable e) { if (e == null) return "error tidak diketahui"; String s = e.getMessage(); if (s == null || s.trim().length() == 0) s = e.getClass().getSimpleName(); return s.length() > 1000 ? s.substring(0, 1000) : s; }
}
