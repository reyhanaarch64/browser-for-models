package rh.browser.models;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;

/** Object surface intentionally exposed to BeanShell workflow scripts. */
public final class WorkflowRuntimeApi {
    private final Context context;
    private final WorkflowManager manager;
    private final WorkflowBrowser browser;
    private final JSONObject metadata;
    private final JSONObject requestInput;
    private final String executionId;
    private volatile Object result;
    private volatile String currentSource;
    private volatile int currentLine;

    WorkflowRuntimeApi(Context context, WorkflowManager manager, WorkflowBrowser browser,
                       JSONObject metadata, JSONObject requestInput, String executionId) {
        this.context = context;
        this.manager = manager;
        this.browser = browser;
        this.metadata = metadata;
        this.requestInput = requestInput == null ? new JSONObject() : requestInput;
        this.executionId = executionId;
        currentSource = metadata.optString("entry", "");
    }

    public void navigate(String url) throws Exception { browser.navigate(url, 30000L); }
    public void navigate(String url, long timeoutMs) throws Exception { browser.navigate(url, timeoutMs); }
    public boolean waitFor(String selector, long timeoutMs) throws Exception { return browser.waitFor(selector, timeoutMs); }
    public boolean waitForText(String text, long timeoutMs) throws Exception { return browser.waitForText(text, timeoutMs); }
    public boolean waitForUrl(String urlPart, long timeoutMs) throws Exception { return browser.waitForUrl(urlPart, timeoutMs); }
    public void input(String selector, String value) throws Exception { browser.input(selector, value); }
    public void click(String selector) throws Exception { browser.click(selector); }
    public String extract(String selector) throws Exception { return browser.extract(selector); }
    public String extract(String selector, String attribute) throws Exception { return browser.extract(selector, attribute); }
    public JSONArray extractAll(String selector) throws Exception { return browser.extractAll(selector, ""); }
    public JSONArray extractAll(String selector, String attribute) throws Exception { return browser.extractAll(selector, attribute); }
    public String text(String selector) throws Exception { return browser.extract(selector); }
    public boolean exists(String selector) throws Exception { return browser.exists(selector); }
    public String getUrl() { return browser.getUrl(); }
    public String promptInput(String prompt) throws Exception {
        return promptInput(prompt, "", "text", true, "", new JSONArray(), 10L * 60L * 1000L, true);
    }
    public String promptInput(String prompt, String inputKey) throws Exception {
        String key = inputKey == null ? "" : inputKey.trim();
        return promptInput(prompt, key, "text", true, "", new JSONArray(), 10L * 60L * 1000L, key.length() == 0);
    }
    public String promptInput(String prompt, String inputKey, String inputType, boolean required,
                              String defaultValue, JSONArray choices, long timeoutMs, boolean alwaysPrompt) throws Exception {
        String key = inputKey == null ? "" : inputKey.trim();
        if (key.length() > 0 && !alwaysPrompt && requestInput.has(key)) {
            Object existing = requestInput.opt(key);
            if (existing != null && existing != JSONObject.NULL) {
                String value = String.valueOf(existing);
                if (value.length() > 0 || !required) return value;
            }
        }
        String value = manager.awaitInput(metadata.optString("id"), executionId,
                prompt == null ? "Input diperlukan" : prompt, inputType, required, defaultValue, choices,
                timeoutMs, currentSource, currentLine);
        if (key.length() > 0) requestInput.put(key, value);
        return value;
    }
    public JSONObject getRequestInput() {
        try { return new JSONObject(requestInput.toString()); } catch (Exception e) { return new JSONObject(); }
    }
    public String getInput(String key) { Object v = requestInput.opt(key); return v == null || v == JSONObject.NULL ? null : String.valueOf(v); }
    public Object getInputValue(String key) { return requestInput.opt(key); }
    public JSONObject getInputJson() { try { return new JSONObject(requestInput.toString()); } catch (Exception e) { return new JSONObject(); } }
    public void setResult(Object value) { result = value; }
    public Object getResult() { return result; }
    public void printLog(Object message) { manager.printLog(metadata.optString("id"), executionId, String.valueOf(message), currentSource, currentLine); }
    public void logInfo(Object message) { manager.addLog(metadata.optString("id"), executionId, "info", "script.info", String.valueOf(message), currentSource, currentLine, null); }
    public void logWarn(Object message) { manager.addLog(metadata.optString("id"), executionId, "warn", "script.warn", String.valueOf(message), currentSource, currentLine, null); }
    public void logError(Object message) { manager.addLog(metadata.optString("id"), executionId, "error", "script.error", String.valueOf(message), currentSource, currentLine, null); }
    public void setSourceLocation(String source, int line) { currentSource = source == null ? "" : source; currentLine = Math.max(0, line); }
    public String readAsset(String relativePath) throws Exception {
        String path = relativePath == null ? "" : relativePath.trim();
        if (!path.startsWith("assets/")) path = "assets/" + path;
        JSONObject file = manager.readFile(metadata.optString("id"), path);
        return file.optString("content", "");
    }
    public void writeAsset(String relativePath, String content) throws Exception {
        String path = relativePath == null ? "" : relativePath.trim();
        if (!path.startsWith("assets/")) path = "assets/" + path;
        manager.writeFile(metadata.optString("id"), path, content);
    }
    public String assetPath(String relativePath) throws Exception {
        String path = relativePath == null ? "" : relativePath.trim();
        if (!path.startsWith("assets/")) path = "assets/" + path;
        File f = manager.resolveWorkflowFile(metadata.optString("id"), path, false);
        return f.getAbsolutePath();
    }
    public void delay(long milliseconds) throws Exception {
        long amount = Math.max(0L, Math.min(milliseconds, 60000L));
        Thread.sleep(amount);
    }
    public void scroll(String direction, int pixels, String selector) throws Exception {
        int amount = Math.max(1, Math.min(pixels, 10000));
        String dir = direction == null ? "down" : direction.toLowerCase(java.util.Locale.US);
        String target = selector == null || selector.length() == 0 ? "window" : "document.querySelector(" + JSONObject.quote(selector) + ")";
        String x = "0", y = "0";
        if ("up".equals(dir)) y = "-" + amount; else if ("down".equals(dir)) y = String.valueOf(amount);
        else if ("left".equals(dir)) x = "-" + amount; else if ("right".equals(dir)) x = String.valueOf(amount);
        Object r = browser.evaluate("var t=" + target + "; if(!t) return false; if(t===window) window.scrollBy(" + x + "," + y + "); else t.scrollBy(" + x + "," + y + "); return true;");
        if (!Boolean.TRUE.equals(r)) throw new IllegalArgumentException("target scroll tidak ditemukan");
    }
}
