package rh.browser.models;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;

import org.json.JSONTokener;
import org.json.JSONObject;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Independent workflow WebView; WebView profile/cookies remain the app's default shared profile. */
public final class WorkflowBrowser {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile WebView webView;
    private volatile CountDownLatch pageLatch = new CountDownLatch(0);
    private volatile String lastFinishedUrl = "";
    private volatile int pageStartCount;
    private volatile int pageFinishedCount;

    public WorkflowBrowser(final Context context) throws Exception {
        this.context = context.getApplicationContext();
        callMain(new Callable<Object>() {
            public Object call() throws Exception {
                WebView w = new WebView(context);
                WebSettings settings = w.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(true);
                settings.setLoadsImagesAutomatically(true);
                settings.setJavaScriptCanOpenWindowsAutomatically(false);
                settings.setSupportMultipleWindows(false);
                try { settings.setAllowFileAccess(false); } catch (Exception ignored) { }
                try { settings.setAllowContentAccess(false); } catch (Exception ignored) { }
                try {
                    String ua = context.getSharedPreferences("browser", Context.MODE_PRIVATE).getString("user_agent", "");
                    if (ua != null && ua.trim().length() > 0) settings.setUserAgentString(ua);
                } catch (Exception ignored) { }
                CookieManager cookies = CookieManager.getInstance();
                cookies.setAcceptCookie(true);
                try { cookies.setAcceptThirdPartyCookies(w, true); } catch (Exception ignored) { }
                w.setVisibility(View.INVISIBLE);
                w.setWebViewClient(new WebViewClient() {
                    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                        pageStartCount++;
                    }
                    public void onPageFinished(WebView view, String url) {
                        lastFinishedUrl = url == null ? "" : url;
                        pageFinishedCount++;
                        CountDownLatch latch = pageLatch;
                        if (latch != null) latch.countDown();
                    }
                });
                webView = w;
                return null;
            }
        }, 10000L);
    }

    public void navigate(final String url, long timeoutMs) throws Exception {
        final String clean = url == null ? "" : url.trim();
        if (!(clean.startsWith("http://") || clean.startsWith("https://"))) throw new IllegalArgumentException("URL workflow harus memakai http:// atau https://");
        final CountDownLatch latch = new CountDownLatch(1);
        pageLatch = latch;
        callMain(new Callable<Object>() {
            public Object call() throws Exception { WebView w = requireWebView(); w.loadUrl(clean); return null; }
        }, 10000L);
        long wait = timeoutMs <= 0 ? 30000L : Math.min(timeoutMs, 90000L);
        if (!latch.await(wait, TimeUnit.MILLISECONDS)) throw new WorkflowExecutionException("timeout saat memuat halaman: " + url, "", 0);
        try { Thread.sleep(150L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public Object evaluate(final String functionBody) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> returned = new AtomicReference<String>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        main.post(new Runnable() {
            public void run() {
                try {
                    WebView w = requireWebView();
                    String expression = "JSON.stringify((function(){" + functionBody + "\n})())";
                    w.evaluateJavascript(expression, new android.webkit.ValueCallback<String>() {
                        public void onReceiveValue(String value) { returned.set(value); latch.countDown(); }
                    });
                } catch (Throwable t) { failure.set(t); latch.countDown(); }
            }
        });
        if (!latch.await(25000L, TimeUnit.MILLISECONDS)) throw new WorkflowExecutionException("timeout saat menjalankan JavaScript di WebView", "", 0);
        if (failure.get() != null) throw new Exception("gagal menjalankan JavaScript: " + failure.get().getMessage(), failure.get());
        String raw = returned.get();
        if (raw == null || "null".equals(raw) || "undefined".equals(raw)) return null;
        Object decoded;
        try { decoded = new JSONTokener(raw).nextValue(); }
        catch (Exception e) { return raw; }
        if (decoded == JSONObject.NULL) return null;
        if (!(decoded instanceof String)) return decoded;
        String json = (String)decoded;
        if (json == null || json.length() == 0) return null;
        try {
            Object result = new JSONTokener(json).nextValue();
            return result == JSONObject.NULL ? null : result;
        } catch (Exception e) { return json; }
    }

    public boolean waitFor(final String selector, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + (timeoutMs <= 0 ? 10000L : Math.min(timeoutMs, 90000L));
        do {
            Object value = evaluate("return !!document.querySelector(" + JSONObject.quote(selector) + ");");
            if (Boolean.TRUE.equals(value)) return true;
            Thread.sleep(200L);
        } while (System.currentTimeMillis() < end);
        return false;
    }

    public boolean waitForText(final String text, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + (timeoutMs <= 0 ? 10000L : Math.min(timeoutMs, 90000L));
        do {
            Object value = evaluate("return (document.body && document.body.innerText || '').indexOf(" + JSONObject.quote(text) + ") >= 0;");
            if (Boolean.TRUE.equals(value)) return true;
            Thread.sleep(200L);
        } while (System.currentTimeMillis() < end);
        return false;
    }

    public boolean waitForUrl(final String part, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + (timeoutMs <= 0 ? 10000L : Math.min(timeoutMs, 90000L));
        do {
            String url = getUrl();
            if (url != null && url.contains(part)) return true;
            Thread.sleep(200L);
        } while (System.currentTimeMillis() < end);
        return false;
    }

    public boolean exists(final String selector) throws Exception {
        return Boolean.TRUE.equals(evaluate("return !!document.querySelector(" + JSONObject.quote(selector) + ");"));
    }

    public void input(final String selector, final String value) throws Exception {
        Object result = evaluate("var e=document.querySelector(" + JSONObject.quote(selector) + "); if(!e) return {ok:false,error:'element tidak ditemukan'}; e.focus(); var v=" + JSONObject.quote(value == null ? "" : value) + "; if(e.isContentEditable){e.textContent=v;} else {var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set)d.set.call(e,v);else e.value=v;} try{e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}catch(x){} return {ok:true,tag:e.tagName};");
        if (!(result instanceof JSONObject) || !((JSONObject)result).optBoolean("ok", false)) throw new IllegalArgumentException("input gagal untuk selector: " + selector);
    }

    public void click(final String selector) throws Exception {
        final int previousStart = pageStartCount;
        Object result = evaluate("var e=document.querySelector(" + JSONObject.quote(selector) + "); if(!e) return {ok:false}; e.scrollIntoView({block:'center',inline:'center'}); e.click(); return {ok:true};");
        if (!(result instanceof JSONObject) || !((JSONObject)result).optBoolean("ok", false)) throw new IllegalArgumentException("elemen tidak ditemukan untuk click: " + selector);
        // Wait briefly for a navigation to begin; if it does, await that page load. SPA/XHR clicks continue without a full load.
        long detectUntil = System.currentTimeMillis() + 500L;
        while (pageStartCount <= previousStart && System.currentTimeMillis() < detectUntil) Thread.sleep(50L);
        if (pageStartCount > previousStart) {
            long end = System.currentTimeMillis() + 30000L;
            while (pageFinishedCount < pageStartCount && System.currentTimeMillis() < end) Thread.sleep(50L);
            if (pageFinishedCount < pageStartCount) throw new WorkflowExecutionException("timeout setelah klik navigasi: " + selector, "", 0);
        }
    }

    public String extract(final String selector) throws Exception { return extract(selector, ""); }
    public String extract(final String selector, final String attribute) throws Exception {
        Object value = evaluate("var e=document.querySelector(" + JSONObject.quote(selector) + "); if(!e) return null; var a=" + JSONObject.quote(attribute == null ? "" : attribute) + "; return a.length?e.getAttribute(a):(e.innerText!==undefined?e.innerText:e.textContent); ");
        return value == null ? null : String.valueOf(value);
    }

    public org.json.JSONArray extractAll(final String selector, final String attribute) throws Exception {
        Object value = evaluate("var out=[]; var es=document.querySelectorAll(" + JSONObject.quote(selector) + "); var a=" + JSONObject.quote(attribute == null ? "" : attribute) + "; for(var i=0;i<es.length && i<1000;i++){var e=es[i];out.push(a.length?e.getAttribute(a):(e.innerText!==undefined?e.innerText:e.textContent));} return out;");
        if (value instanceof org.json.JSONArray) return (org.json.JSONArray)value;
        org.json.JSONArray a = new org.json.JSONArray(); if (value != null) a.put(value); return a;
    }

    public String getUrl() {
        final String[] result = new String[] { "" };
        try { callMain(new Callable<Object>() { public Object call() { WebView w = webView; result[0] = w == null ? "" : w.getUrl(); return null; } }, 3000L); }
        catch (Exception ignored) { }
        return result[0];
    }

    public void destroy() {
        try {
            callMain(new Callable<Object>() {
                public Object call() {
                    WebView w = webView;
                    webView = null;
                    if (w != null) { try { w.stopLoading(); } catch (Exception ignored) { } try { w.setWebViewClient(null); } catch (Exception ignored) { } try { w.destroy(); } catch (Exception ignored) { } }
                    try { CookieManager.getInstance().flush(); } catch (Exception ignored) { }
                    return null;
                }
            }, 5000L);
        } catch (Exception ignored) { }
    }

    private WebView requireWebView() { WebView w = webView; if (w == null) throw new IllegalStateException("WebView workflow sudah ditutup"); return w; }
    private <T> T callMain(final Callable<T> callable, long timeoutMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return callable.call();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<T> result = new AtomicReference<T>();
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        main.post(new Runnable() { public void run() { try { result.set(callable.call()); } catch (Throwable t) { error.set(t); } finally { latch.countDown(); } } });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new IllegalStateException("timeout saat mengakses UI thread WebView");
        if (error.get() != null) { Throwable t = error.get(); if (t instanceof Exception) throw (Exception)t; throw new Exception(t); }
        return result.get();
    }
}
