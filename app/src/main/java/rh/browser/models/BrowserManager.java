package rh.browser.models;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.app.DownloadManager;
import android.os.Environment;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Single-tab, headless/best-effort WebView controlled by MCP tools.
 * All WebView operations are marshalled to Android's main thread.
 */
public final class BrowserManager {
    private static final String TAG = "RhBrowser";
    private static final String PREFS = "browser";
    private static final String KEY_USER_AGENT = "user_agent";
    private static final String KEY_LAST_URL = "last_url";
    private static final String KEY_MODE = "mode";
    private static final String KEY_VIEWPORT_WIDTH = "viewport_width_css";
    private static final String KEY_VIEWPORT_HEIGHT = "viewport_height_css";
    private static final int MAX_RESOURCE_ITEMS = 500;
    private static final int MAX_RESOURCE_BYTES = 20000000;
    private static final int MAX_RESOURCE_TEXT_CHARS = 250000;
    private static final int MAX_CONSOLE_ENTRIES = 500;
    private static final int MAX_INSPECT_LIMIT = 200;
    private static final int MAX_SOURCE_CHARS = 100000;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object operationLock = new Object();
    private final Object consoleLock = new Object();
    private final Object resourceLock = new Object();
    private final ArrayList<ResourceEntry> resourceItems = new ArrayList<ResourceEntry>();
    private int nextResourceId = 1;
    private final ArrayList<JSONObject> consoleEntries = new ArrayList<JSONObject>();

    private volatile WebView webView;
    private volatile CountDownLatch pageLoadLatch;
    private volatile String activeUrl = "";
    private volatile String lastPageError = "";
    private volatile int pageProgress;
    private volatile boolean hasLoadedPage;
    private volatile long consoleDropped;

    public BrowserManager(Context context) {
        this.context = context;
    }

    /** Returns the same WebView instance used by MCP tool calls. Call from the UI thread. */
    public WebView getWebView() throws Exception {
        ensureCreated();
        return webView;
    }

    public JSONObject execute(String name, JSONObject args) throws Exception {
        if (args == null) args = new JSONObject();
        synchronized (operationLock) {
            if ("browser.open".equals(name)) return open(args);
            if ("browser.back".equals(name)) return back(args);
            if ("browser.inspect".equals(name)) return inspect(args);
            if ("browser.click".equals(name)) return click(args);
            if ("browser.input".equals(name)) return input(args);
            if ("browser.scroll".equals(name)) return scroll(args);
            if ("browser.source".equals(name)) return source(args);
            if ("browser.screenshot".equals(name)) return screenshot(args);
            if ("browser.clear_cookies".equals(name)) return clearCookies();
            if ("browser.clear_localstorage".equals(name)) return clearStorage(false);
            if ("browser.clear_sessionstorage".equals(name)) return clearStorage(true);
            if ("browser.set_user_agent".equals(name)) return setUserAgent(args);
            if ("browser.set_mode".equals(name)) return setMode(args);
            if ("browser.set_viewport".equals(name)) return setViewport(args);
            if ("browser.item.list".equals(name)) return listItems(args);
            if ("browser.get.item".equals(name)) return getItem(args);
            if ("browser.debug.console".equals(name)) return console(args);
        }
        throw new IllegalArgumentException("tool browser tidak dikenal: " + name);
    }

    private JSONObject open(JSONObject args) throws Exception {
        String url = normalizeUrl(args.optString("url", ""));
        int waitMs = clamp(args.optInt("waitMs", 18000), 500, 30000);
        ensureCreated();
        boolean loadSignalReceived = navigate(url, waitMs, false);
        JSONObject result = pageInfo();
        boolean loaded = loadSignalReceived && pageProgress >= 100 && lastPageError.length() == 0;
        result.put("requestedUrl", url);
        result.put("loaded", loaded);
        result.put("waitTimedOut", !loadSignalReceived);
        result.put("pageError", lastPageError);
        result.put("waitMs", waitMs);
        return result;
    }

    private JSONObject back(JSONObject args) throws Exception {
        ensureExistingPage();
        final boolean[] canGo = new boolean[1];
        final CountDownLatch done = new CountDownLatch(1);
        runOnMainSync(new Runnable() {
            public void run() {
                WebView v = webView;
                canGo[0] = v != null && v.canGoBack();
                if (canGo[0]) {
                    pageProgress = 0;
                    lastPageError = "";
                    pageLoadLatch = done;
                    v.goBack();
                } else {
                    done.countDown();
                }
            }
        });
        if (canGo[0]) done.await(15000L, TimeUnit.MILLISECONDS);
        clearLatch(done);
        JSONObject result = pageInfo();
        result.put("wentBack", canGo[0]);
        if (!canGo[0]) result.put("message", "Tidak ada halaman sebelumnya dalam riwayat tab ini.");
        return result;
    }

    private JSONObject inspect(JSONObject args) throws Exception {
        ensureExistingPage();
        int limit = clamp(args.optInt("limit", 80), 1, MAX_INSPECT_LIMIT);
        String js = "(function(){try{" +
                "var sel='a[href],button,input,textarea,select,[role=button],[onclick],[tabindex]';" +
                "var all=Array.prototype.slice.call(document.querySelectorAll(sel));" +
                "var chosen=all.slice(0," + limit + ");window.__phoneMcpRefs=chosen;" +
                "function trim(v,n){v=(v==null?'':String(v));return v.length>n?v.slice(0,n):v;}" +
                "var out=[];for(var i=0;i<chosen.length;i++){var e=chosen[i],r={};" +
                "r.ref='e'+i;r.tag=String(e.tagName||'').toLowerCase();" +
                "r.text=trim((e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim(),240);" +
                "r.id=trim(e.id,160);r.name=trim(e.getAttribute('name'),160);" +
                "r.type=trim(e.getAttribute('type'),80);r.placeholder=trim(e.getAttribute('placeholder'),200);" +
                "r.ariaLabel=trim(e.getAttribute('aria-label'),200);r.title=trim(e.getAttribute('title'),200);" +
                "r.href=trim(e.href,500);r.role=trim(e.getAttribute('role'),80);" +
                "r.disabled=!!e.disabled;r.visible=!!(e.getClientRects&&e.getClientRects().length);out.push(r);}" +
                "return JSON.stringify({url:location.href,title:document.title,readyState:document.readyState," +
                "totalElements:all.length,returned:out.length,truncated:all.length>chosen.length,elements:out});" +
                "}catch(e){return JSON.stringify({error:String(e),url:location.href});}})()";
        JSONObject data = evalObject(js, 15000L);
        data.put("note", "ref eN hanya berlaku untuk hasil inspeksi terakhir pada dokumen saat ini; inspeksi ulang dapat mengganti referensi.");
        return data;
    }

    private JSONObject click(JSONObject args) throws Exception {
        ensureExistingPage();
        String ref = args.optString("elementRef", "").trim();
        String selector = args.optString("selector", "").trim();
        String text = args.optString("text", "").trim();
        if (ref.length() == 0 && selector.length() == 0 && text.length() == 0) {
            throw new IllegalArgumentException("Isi salah satu: elementRef, selector, atau text.");
        }
        if (ref.length() > 0 && !ref.matches("e[0-9]{1,4}")) throw new IllegalArgumentException("elementRef harus seperti e0 atau e12.");
        int settleMs = clamp(args.optInt("settleMs", 800), 0, 10000);
        String js;
        if (ref.length() > 0) {
            int index = Integer.parseInt(ref.substring(1));
            js = "(function(){try{var a=window.__phoneMcpRefs;var e=a&&a[" + index + "];if(!e||!e.isConnected)return JSON.stringify({ok:false,error:'Referensi kedaluwarsa; jalankan browser.inspect lagi.'});" +
                    "var d={tag:String(e.tagName||'').toLowerCase(),text:String((e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim()).slice(0,240),url:location.href};" +
                    "if(e.disabled)return JSON.stringify({ok:false,error:'Elemen sedang disabled',element:d});try{e.focus();}catch(x){}e.click();d.clicked=true;return JSON.stringify({ok:true,element:d});" +
                    "}catch(x){return JSON.stringify({ok:false,error:String(x),url:location.href});}})()";
        } else if (selector.length() > 0) {
            js = "(function(){try{var e=document.querySelector(" + JSONObject.quote(selector) + ");if(!e)return JSON.stringify({ok:false,error:'Selector tidak menemukan elemen',url:location.href});" +
                    "var d={tag:String(e.tagName||'').toLowerCase(),id:String(e.id||''),text:String((e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim()).slice(0,240),url:location.href};" +
                    "if(e.disabled)return JSON.stringify({ok:false,error:'Elemen sedang disabled',element:d});try{e.focus();}catch(x){}e.click();d.clicked=true;return JSON.stringify({ok:true,element:d});" +
                    "}catch(x){return JSON.stringify({ok:false,error:String(x),url:location.href});}})()";
        } else {
            js = "(function(){try{var q=" + JSONObject.quote(text) + ";var s='button,a[href],input[type=button],input[type=submit],[role=button],[onclick]';" +
                    "var a=Array.prototype.slice.call(document.querySelectorAll(s));function t(e){return String((e.innerText||e.textContent||e.value||'').replace(/\\s+/g,' ').trim());}" +
                    "var e=null;for(var i=0;i<a.length;i++){if(t(a[i])===q){e=a[i];break;}}if(!e){for(var j=0;j<a.length;j++){if(t(a[j]).toLowerCase().indexOf(q.toLowerCase())>=0&&q.length){e=a[j];break;}}}" +
                    "if(!e)return JSON.stringify({ok:false,error:'Tidak menemukan elemen interaktif dengan teks tersebut',url:location.href});" +
                    "var d={tag:String(e.tagName||'').toLowerCase(),id:String(e.id||''),text:t(e).slice(0,240),url:location.href};if(e.disabled)return JSON.stringify({ok:false,error:'Elemen sedang disabled',element:d});" +
                    "try{e.focus();}catch(x){}e.click();d.clicked=true;return JSON.stringify({ok:true,element:d});" +
                    "}catch(x){return JSON.stringify({ok:false,error:String(x),url:location.href});}})()";
        }
        JSONObject result = evalObject(js, 15000L);
        if (settleMs > 0) Thread.sleep(settleMs);
        JSONObject after = pageInfo();
        result.put("settledForMs", settleMs);
        result.put("urlAfter", after.optString("url", ""));
        result.put("titleAfter", after.optString("title", ""));
        result.put("pageError", lastPageError);
        result.put("note", "Klik dipicu lewat DOM .click(); situs dapat menolak atau mengabaikan klik sintetis.");
        return result;
    }

    private JSONObject input(JSONObject args) throws Exception {
        ensureExistingPage();
        String ref = args.optString("elementRef", "").trim();
        String selector = args.optString("selector", "").trim();
        String id = args.optString("id", "").trim();
        String name = args.optString("name", "").trim();
        String placeholder = args.optString("placeholder", "").trim();
        if (ref.length() == 0 && selector.length() == 0 && id.length() == 0 && name.length() == 0 && placeholder.length() == 0) {
            throw new IllegalArgumentException("Tentukan elementRef, selector, id, name, atau placeholder.");
        }
        if (ref.length() > 0 && !ref.matches("e[0-9]{1,4}")) throw new IllegalArgumentException("elementRef harus seperti e0 atau e12.");
        if (!args.has("value")) throw new IllegalArgumentException("Parameter value wajib diisi; gunakan string kosong untuk mengosongkan input.");
        String value = args.optString("value", "");
        if (value.length() > 20000) throw new IllegalArgumentException("value terlalu panjang (maksimum 20000 karakter).");
        boolean clearFirst = args.optBoolean("clearFirst", true);
        int settleMs = clamp(args.optInt("settleMs", 250), 0, 5000);
        String find;
        if (ref.length() > 0) {
            int index = Integer.parseInt(ref.substring(1));
            find = "(function(){var a=window.__phoneMcpRefs;return a&&a[" + index + "]||null;})()";
        } else if (selector.length() > 0) {
            find = "document.querySelector(" + JSONObject.quote(selector) + ")";
        } else if (id.length() > 0) {
            find = "document.getElementById(" + JSONObject.quote(id) + ")";
        } else if (name.length() > 0) {
            find = "(function(){var a=document.querySelectorAll('[name]');for(var i=0;i<a.length;i++){if(a[i].getAttribute('name')===" + JSONObject.quote(name) + ")return a[i];}return null;})()";
        } else {
            find = "(function(){var a=document.querySelectorAll('[placeholder]');for(var i=0;i<a.length;i++){if(a[i].getAttribute('placeholder')===" + JSONObject.quote(placeholder) + ")return a[i];}return null;})()";
        }
        String js = "(function(){try{var e=" + find + ";if(!e||!e.isConnected)return JSON.stringify({ok:false,error:'Elemen tidak ditemukan atau referensinya kedaluwarsa',url:location.href});" +
                "var tag=String(e.tagName||'').toLowerCase(),type=String(e.type||'').toLowerCase();if(tag!=='input'&&tag!=='textarea'&&tag!=='select'&&!e.isContentEditable)return JSON.stringify({ok:false,error:'Elemen bukan input yang dapat diisi',tag:tag,type:type});" +
                "if(type==='file')return JSON.stringify({ok:false,error:'Input file tidak didukung oleh browser.input'});if(e.disabled||e.readOnly)return JSON.stringify({ok:false,error:'Elemen disabled atau readonly',tag:tag,type:type});" +
                "var val=" + JSONObject.quote(value) + ";try{e.focus();}catch(x){};" +
                (clearFirst ? "" : "var old=String(e.value==null?'':e.value);val=old+val;") +
                "try{var proto=tag==='textarea'?window.HTMLTextAreaElement.prototype:(tag==='select'?window.HTMLSelectElement.prototype:window.HTMLInputElement.prototype);var desc=Object.getOwnPropertyDescriptor(proto,'value');if(desc&&desc.set)desc.set.call(e,val);else e.value=val;}catch(x){e.value=val;}" +
                "try{e.dispatchEvent(new Event('input',{bubbles:true}));}catch(x){var ev=document.createEvent('Event');ev.initEvent('input',true,true);e.dispatchEvent(ev);}try{e.dispatchEvent(new Event('change',{bubbles:true}));}catch(x){}" +
                "return JSON.stringify({ok:true,tag:tag,type:type,id:String(e.id||''),name:String(e.name||''),placeholder:String(e.getAttribute('placeholder')||''),valueLength:String(e.value==null?'':e.value).length,checked:typeof e.checked==='boolean'?e.checked:null,url:location.href});" +
                "}catch(x){return JSON.stringify({ok:false,error:String(x),url:location.href});}})()";
        JSONObject result = evalObject(js, 15000L);
        if (settleMs > 0) Thread.sleep(settleMs);
        result.put("settledForMs", settleMs);
        result.put("note", "Nilai di-set melalui DOM dan event input/change. Framework atau validasi situs dapat mengubah atau menolak nilai tersebut.");
        return result;
    }

    private JSONObject scroll(JSONObject args) throws Exception {
        ensureExistingPage();
        String direction = args.optString("direction", "down").trim().toLowerCase(Locale.US);
        if (!"up".equals(direction) && !"down".equals(direction) && !"left".equals(direction) && !"right".equals(direction)) {
            throw new IllegalArgumentException("direction harus up, down, left, atau right.");
        }
        int pixels = clamp(args.optInt("pixels", 600), 1, 10000);
        int deltaX = "left".equals(direction) ? -pixels : ("right".equals(direction) ? pixels : 0);
        int deltaY = "up".equals(direction) ? -pixels : ("down".equals(direction) ? pixels : 0);
        String selector = args.optString("selector", "").trim();
        int settleMs = clamp(args.optInt("settleMs", 300), 0, 5000);
        String target = selector.length() == 0 ? "null" : "document.querySelector(" + JSONObject.quote(selector) + ")";
        String js = "(function(){try{var e=" + target + ";if(" + JSONObject.quote(selector) + ".length&&!e)return JSON.stringify({ok:false,error:'Selector tidak menemukan elemen',url:location.href});" +
                "var beforeX=e?(e.scrollLeft||0):(window.scrollX||0),beforeY=e?(e.scrollTop||0):(window.scrollY||0);" +
                "if(e){if(e.scrollBy)e.scrollBy(" + deltaX + "," + deltaY + ");else{e.scrollLeft+=(" + deltaX + ");e.scrollTop+=(" + deltaY + ");}}else{window.scrollBy(" + deltaX + "," + deltaY + ");}" +
                "var afterX=e?(e.scrollLeft||0):(window.scrollX||0),afterY=e?(e.scrollTop||0):(window.scrollY||0);return JSON.stringify({ok:true,target:e?'element':'page',selector:" + JSONObject.quote(selector) + ",direction:" + JSONObject.quote(direction) + ",requestedPixels:" + pixels + ",beforeX:beforeX,beforeY:beforeY,afterX:afterX,afterY:afterY,moved:(beforeX!==afterX||beforeY!==afterY),url:location.href});" +
                "}catch(x){return JSON.stringify({ok:false,error:String(x),url:location.href});}})()";
        JSONObject result = evalObject(js, 15000L);
        if (settleMs > 0) Thread.sleep(settleMs);
        result.put("settledForMs", settleMs);
        result.put("note", "Scroll dijalankan melalui scrollBy DOM; situs dapat memakai container scroll khusus atau mengubah posisi secara otomatis.");
        return result;
    }

    private JSONObject source(JSONObject args) throws Exception {
        ensureExistingPage();
        String mode = args.optString("mode", "html").toLowerCase(Locale.US);
        if (!"html".equals(mode) && !"text".equals(mode)) throw new IllegalArgumentException("mode harus html atau text.");
        int maxChars = clamp(args.optInt("maxChars", 20000), 100, MAX_SOURCE_CHARS);
        String expression = "html".equals(mode) ?
                "(document.documentElement?document.documentElement.outerHTML:'')" :
                "(document.body?document.body.innerText:'')";
        String js = "(function(){var s=String(" + expression + ");var full=s.length;var max=" + maxChars + ";return JSON.stringify({url:location.href,title:document.title,mode:" + JSONObject.quote(mode) + ",length:full,truncated:full>max,source:s.slice(0,max)});})()";
        return evalObject(js, 20000L);
    }

    private JSONObject screenshot(JSONObject args) throws Exception {
        ensureExistingPage();
        final Bitmap[] captured = new Bitmap[1];
        final int[] dimensions = new int[2];
        final int[] viewportCss = new int[2];
        runOnMainSync(new Runnable() {
            public void run() {
                WebView v = webView;
                if (v == null) return;
                int fullWidth = v.getWidth();
                int fullHeight = v.getHeight();
                if (fullWidth <= 0 || fullHeight <= 0) {
                    applyViewportToView(v, getViewportWidthCss(), getViewportHeightCss());
                    fullWidth = v.getWidth();
                    fullHeight = v.getHeight();
                }
                if (fullWidth <= 0 || fullHeight <= 0) return;
                float scale = Math.min(1.0f, Math.min(1280.0f / (float) fullWidth, 1920.0f / (float) fullHeight));
                int outWidth = Math.max(1, Math.round(fullWidth * scale));
                int outHeight = Math.max(1, Math.round(fullHeight * scale));
                try {
                    Bitmap b = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(b);
                    canvas.drawColor(android.graphics.Color.WHITE);
                    canvas.scale(scale, scale);
                    v.draw(canvas);
                    captured[0] = b;
                    dimensions[0] = outWidth;
                    dimensions[1] = outHeight;
                    viewportCss[0] = getViewportWidthCss();
                    viewportCss[1] = getViewportHeightCss();
                } catch (Throwable t) {
                    Log.w(TAG, "screenshot gagal", t);
                }
            }
        });
        if (captured[0] == null) throw new IllegalStateException("WebView tidak menghasilkan bitmap. Screenshot background bersifat best-effort pada perangkat ini.");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean compressed = captured[0].compress(Bitmap.CompressFormat.PNG, 100, output);
        captured[0].recycle();
        if (!compressed) throw new IllegalStateException("Gagal mengompresi screenshot.");
        byte[] bytes = output.toByteArray();
        String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("url", getActiveUrl());
        result.put("width", dimensions[0]);
        result.put("height", dimensions[1]);
        result.put("viewportWidthCss", viewportCss[0]);
        result.put("viewportHeightCss", viewportCss[1]);
        result.put("mode", getMode());
        result.put("format", "image/png");
        result.put("bytes", bytes.length);
        result.put("screenshotAttached", true);
        result.put("__mcp_image_base64", base64);
        result.put("__mcp_image_mime", "image/png");
        result.put("note", "Screenshot menangkap viewport aktif pada satu gambar; konten di luar viewport perlu dilihat dengan browser.scroll lalu screenshot lagi. Gambar dikecilkan bila perlu agar tidak melewati batas dimensi.");
        return result;
    }

    private JSONObject setMode(JSONObject args) throws Exception {
        String mode = args.optString("mode", "").trim().toLowerCase(Locale.US);
        if (!"desktop".equals(mode) && !"mobile".equals(mode)) {
            throw new IllegalArgumentException("mode harus desktop atau mobile.");
        }
        ensureCreated();
        final String chosenMode = mode;
        final int width = "desktop".equals(mode) ? 1280 : getDeviceViewportWidthCss();
        final int height = "desktop".equals(mode) ? 900 : getDeviceViewportHeightCss();
        final String userAgent = "desktop".equals(mode) ? buildDesktopUserAgent() : "";
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_MODE, mode)
                .putString(KEY_USER_AGENT, userAgent)
                .putInt(KEY_VIEWPORT_WIDTH, width)
                .putInt(KEY_VIEWPORT_HEIGHT, height)
                .apply();
        runOnMainSync(new Runnable() {
            public void run() {
                WebView v = webView;
                if (v == null) return;
                WebSettings settings = v.getSettings();
                settings.setUserAgentString(userAgent.length() == 0 ? WebSettings.getDefaultUserAgent(context) : userAgent);
                settings.setUseWideViewPort(true);
                settings.setLoadWithOverviewMode(false);
                applyViewportToView(v, width, height);
            }
        });
        boolean reloadCurrent = args.optBoolean("reloadCurrent", true);
        int waitMs = clamp(args.optInt("waitMs", 18000), 500, 30000);
        String current = getActiveUrl();
        boolean reloadStarted = reloadCurrent && isHttpUrl(current);
        boolean reloadFinished = false;
        if (reloadStarted) reloadFinished = navigate(current, waitMs, true);
        JSONObject result = pageInfo();
        result.put("ok", true);
        result.put("mode", chosenMode);
        result.put("userAgentProfile", "desktop".equals(mode) ? "desktop Chrome-style" : "WebView default mobile");
        result.put("reloadStarted", reloadStarted);
        result.put("reloadFinished", reloadFinished);
        result.put("note", "Mode memilih profil User-Agent dan ukuran viewport default. browser.set_user_agent dapat mengubah UA manual; browser.set_viewport dapat mengubah ukuran tanpa mengganti mode.");
        return result;
    }

    private JSONObject setViewport(JSONObject args) throws Exception {
        if (!args.has("width") || !args.has("height")) throw new IllegalArgumentException("Parameter width dan height wajib diisi dalam CSS pixel.");
        int width = clamp(args.optInt("width", getViewportWidthCss()), 320, 1280);
        int height = clamp(args.optInt("height", getViewportHeightCss()), 240, 1000);
        ensureCreated();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_VIEWPORT_WIDTH, width)
                .putInt(KEY_VIEWPORT_HEIGHT, height)
                .apply();
        final int finalWidth = width;
        final int finalHeight = height;
        runOnMainSync(new Runnable() {
            public void run() {
                WebView v = webView;
                if (v != null) applyViewportToView(v, finalWidth, finalHeight);
            }
        });
        boolean reloadCurrent = args.optBoolean("reloadCurrent", false);
        int waitMs = clamp(args.optInt("waitMs", 18000), 500, 30000);
        String current = getActiveUrl();
        boolean reloadStarted = reloadCurrent && isHttpUrl(current);
        boolean reloadFinished = false;
        if (reloadStarted) reloadFinished = navigate(current, waitMs, true);
        JSONObject result = pageInfo();
        result.put("ok", true);
        result.put("viewportWidthCss", width);
        result.put("viewportHeightCss", height);
        result.put("reloadStarted", reloadStarted);
        result.put("reloadFinished", reloadFinished);
        result.put("note", "Ukuran dibatasi 320-1280 CSS px untuk lebar dan 240-1000 CSS px untuk tinggi. Screenshot menangkap viewport ini saja; konten di luarnya tetap perlu di-scroll.");
        return result;
    }

    private JSONObject listItems(JSONObject args) throws Exception {
        ensureExistingPage();
        int limit = clamp(args.optInt("limit", 100), 1, 300);
        String js = "(function(){try{var out=[],seen={};function add(u,t,z){if(!u)return;u=String(u);if(!/^https?:\\/\\//i.test(u)||seen[u])return;seen[u]=1;out.push({url:u,type:String(t||''),sizeBytes:Number(z||0)});}" +
                "try{var p=performance.getEntriesByType?performance.getEntriesByType('resource'):[];for(var i=0;i<p.length&&out.length<500;i++){var e=p[i];add(e.name,e.initiatorType,e.encodedBodySize||e.transferSize||0);}}catch(x){}" +
                "try{var a=document.querySelectorAll('img[src],img[srcset],script[src],link[href],iframe[src],source[src],video[src],audio[src],track[src]');for(var j=0;j<a.length&&out.length<500;j++){var e=a[j],u=e.currentSrc||e.src||e.href;if(u)add(u,String(e.tagName||'').toLowerCase(),0);}}catch(y){}" +
                "return JSON.stringify({url:location.href,title:document.title,resources:out});}catch(e){return JSON.stringify({error:String(e),resources:[]});}})()";
        JSONObject observed = evalObject(js, 20000L);
        JSONArray found = observed.optJSONArray("resources");
        if (found != null) {
            for (int i = 0; i < found.length(); i++) {
                JSONObject item = found.optJSONObject(i);
                if (item == null) continue;
                String url = item.optString("url", "");
                String type = item.optString("type", "resource");
                long size = item.optLong("sizeBytes", 0L);
                recordResource(url, type, "GET", size > 0 ? size : -1L, null);
            }
        }
        JSONArray output = new JSONArray();
        int total;
        synchronized (resourceLock) {
            total = resourceItems.size();
            int start = Math.max(0, total - limit);
            for (int i = start; i < total; i++) {
                ResourceEntry entry = resourceItems.get(i);
                JSONObject row = new JSONObject();
                row.put("itemId", entry.id);
                row.put("url", entry.url);
                row.put("type", entry.resourceType);
                row.put("method", entry.method);
                if (entry.estimatedBytes > 0) {
                    row.put("sizeBytes", entry.estimatedBytes);
                } else {
                    row.put("sizeBytes", JSONObject.NULL);
                }
                row.put("sizeKnown", entry.estimatedBytes > 0);
                row.put("retrievable", isHttpUrl(entry.url) && "GET".equalsIgnoreCase(entry.method));
                row.put("firstSeenAt", entry.firstSeenAt);
                output.put(row);
            }
        }
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("url", getActiveUrl());
        result.put("title", observed.optString("title", ""));
        result.put("totalTracked", total);
        result.put("returned", output.length());
        result.put("truncated", total > output.length());
        result.put("items", output);
        result.put("maxTracked", MAX_RESOURCE_ITEMS);
        result.put("maxDownloadBytes", MAX_RESOURCE_BYTES);
        result.put("note", "Daftar berisi resource HTTP(S) yang diamati WebView dan Resource Timing/DOM. Ukuran bisa tidak tersedia; browser.get.item mencoba mengambil ulang GET resource memakai cookie WebView yang cocok. Resource yang butuh request body/header khusus atau URL sementara mungkin gagal.");
        return result;
    }

    private JSONObject getItem(JSONObject args) throws Exception {
        ensureExistingPage();
        String itemId = args.optString("itemId", "").trim();
        if (!itemId.matches("r[0-9]{1,9}")) throw new IllegalArgumentException("itemId harus seperti r1 atau r24 dari browser.item.list.");
        int requestedMax = clamp(args.optInt("maxBytes", MAX_RESOURCE_BYTES), 1, MAX_RESOURCE_BYTES);
        ResourceEntry entry = findResource(itemId);
        if (entry == null) throw new IllegalArgumentException("Item tidak ditemukan; jalankan browser.item.list lagi untuk halaman saat ini.");
        if (!isHttpUrl(entry.url)) throw new IllegalArgumentException("Hanya resource HTTP/HTTPS yang dapat diambil ulang.");
        if (!"GET".equalsIgnoreCase(entry.method)) throw new IllegalArgumentException("Resource ini bukan request GET; pengambilan ulang otomatis tidak didukung.");
        if (entry.estimatedBytes > requestedMax) throw new IllegalArgumentException("Ukuran teramati sekitar " + entry.estimatedBytes + " byte, melewati batas " + requestedMax + " byte. File tidak diberikan ke AI.");
        DownloadResult downloaded = downloadResource(entry, requestedMax);
        String mime = normalizeMime(downloaded.mimeType);
        if ("application/octet-stream".equals(mime) && "image".equalsIgnoreCase(entry.resourceType)) {
            String lowerUrl = entry.url.toLowerCase(Locale.US);
            if (lowerUrl.matches(".*\\.png([?#].*)?$")) mime = "image/png";
            else if (lowerUrl.matches(".*\\.(jpg|jpeg)([?#].*)?$")) mime = "image/jpeg";
            else if (lowerUrl.matches(".*\\.gif([?#].*)?$")) mime = "image/gif";
            else if (lowerUrl.matches(".*\\.webp([?#].*)?$")) mime = "image/webp";
        }
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("itemId", entry.id);
        result.put("url", entry.url);
        result.put("finalUrl", downloaded.finalUrl);
        result.put("mimeType", mime);
        result.put("bytes", downloaded.bytes.length);
        result.put("maxBytes", requestedMax);
        result.put("httpStatus", downloaded.statusCode);
        result.put("retrieval", "refetched_with_webview_cookies_when_available");
        if (isSupportedImageMime(mime)) {
            result.put("contentKind", "image_attachment");
            result.put("__mcp_image_base64", Base64.encodeToString(downloaded.bytes, Base64.NO_WRAP));
            result.put("__mcp_image_mime", mime);
            result.put("note", "Gambar dilampirkan sebagai image MCP supaya AI dapat melihatnya.");
        } else if (isTextMime(mime)) {
            String charset = getCharset(downloaded.contentType);
            String text;
            try { text = new String(downloaded.bytes, charset); }
            catch (Exception ignored) { text = new String(downloaded.bytes, "UTF-8"); }
            boolean truncated = text.length() > MAX_RESOURCE_TEXT_CHARS;
            result.put("contentKind", "text");
            result.put("text", truncated ? text.substring(0, MAX_RESOURCE_TEXT_CHARS) : text);
            result.put("characters", text.length());
            result.put("truncated", truncated);
            result.put("note", truncated ? "Isi teks dipotong pada 250000 karakter agar output MCP tidak terlalu besar." : "Isi resource ditampilkan sebagai teks.");
        } else {
            result.put("contentKind", "embedded_resource");
            result.put("__mcp_resource_base64", Base64.encodeToString(downloaded.bytes, Base64.NO_WRAP));
            result.put("__mcp_resource_mime", mime);
            result.put("__mcp_resource_uri", "phone-mcp://browser/item/" + entry.id);
            result.put("note", "Resource biner dilampirkan sebagai embedded MCP resource agar tidak diduplikasi sebagai teks Base64.");
        }
        return result;
    }

    private DownloadResult downloadResource(ResourceEntry entry, int maxBytes) throws Exception {
        URL current = new URL(entry.url);
        String originalHost = current.getHost();
        String originalProtocol = current.getProtocol();
        int originalPort = current.getPort() >= 0 ? current.getPort() : current.getDefaultPort();
        final LinkedHashMap<String, String> savedHeaders = new LinkedHashMap<String, String>();
        synchronized (resourceLock) { savedHeaders.putAll(entry.requestHeaders); }
        HttpURLConnection connection = null;
        int redirects = 0;
        while (true) {
            connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(25000);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(true);
            connection.setRequestProperty("Accept-Encoding", "identity");
            String ua = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USER_AGENT, "");
            connection.setRequestProperty("User-Agent", ua.length() == 0 ? WebSettings.getDefaultUserAgent(context) : ua);
            String cookies = CookieManager.getInstance().getCookie(current.toString());
            if (cookies != null && cookies.length() > 0) connection.setRequestProperty("Cookie", cookies);
            int currentPort = current.getPort() >= 0 ? current.getPort() : current.getDefaultPort();
            boolean sameOrigin = current.getHost() != null && current.getHost().equalsIgnoreCase(originalHost)
                    && current.getProtocol().equalsIgnoreCase(originalProtocol) && currentPort == originalPort;
            if (sameOrigin) {
                for (Map.Entry<String, String> header : savedHeaders.entrySet()) {
                    String name = header.getKey();
                    String value = header.getValue();
                    if (name == null || value == null || value.length() > 8192) continue;
                    String lower = name.toLowerCase(Locale.US);
                    if ("host".equals(lower) || "connection".equals(lower) || "content-length".equals(lower) || "cookie".equals(lower) || "user-agent".equals(lower) || "accept-encoding".equals(lower) || "proxy-authorization".equals(lower)) continue;
                    try { connection.setRequestProperty(name, value); } catch (Exception ignored) { }
                }
            }
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_SEE_OTHER || status == 307 || status == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || redirects++ >= 5) throw new IllegalStateException("Resource mengalihkan terlalu banyak kali atau tidak memberi Location.");
                URL next = new URL(current, location);
                if (!"http".equalsIgnoreCase(next.getProtocol()) && !"https".equalsIgnoreCase(next.getProtocol())) throw new IllegalStateException("Redirect ke skema non-HTTP(S) ditolak.");
                current = next;
                continue;
            }
            if (status < 200 || status >= 300) {
                String message = "Server menolak pengambilan resource: HTTP " + status;
                connection.disconnect();
                throw new IllegalStateException(message);
            }
            int length = connection.getContentLength();
            if (length > maxBytes) {
                connection.disconnect();
                throw new IllegalStateException("Resource berukuran " + length + " byte, melewati batas " + maxBytes + " byte. File tidak diberikan ke AI.");
            }
            InputStream input = null;
            ByteArrayOutputStream output = new ByteArrayOutputStream(length > 0 ? Math.min(length, 65536) : 16384);
            try {
                input = connection.getInputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > maxBytes) throw new IllegalStateException("Resource melewati batas " + maxBytes + " byte. File tidak diberikan ke AI.");
                    output.write(buffer, 0, count);
                }
                DownloadResult value = new DownloadResult();
                value.bytes = output.toByteArray();
                value.mimeType = connection.getContentType();
                value.contentType = connection.getContentType();
                value.finalUrl = current.toString();
                value.statusCode = status;
                return value;
            } finally {
                if (input != null) try { input.close(); } catch (Exception ignored) { }
                connection.disconnect();
                try { output.close(); } catch (Exception ignored) { }
            }
        }
    }

    private ResourceEntry findResource(String id) {
        synchronized (resourceLock) {
            for (int i = 0; i < resourceItems.size(); i++) {
                if (id.equals(resourceItems.get(i).id)) return resourceItems.get(i);
            }
        }
        return null;
    }

    private void recordResource(String url, String type, String method, long size, Map<String, String> headers) {
        if (!isHttpUrl(url) || url.length() > 8192) return;
        String requestMethod = method == null || method.length() == 0 ? "GET" : method.toUpperCase(Locale.US);
        synchronized (resourceLock) {
            ResourceEntry entry = null;
            for (int i = resourceItems.size() - 1; i >= 0; i--) {
                if (url.equals(resourceItems.get(i).url)) {
                    entry = resourceItems.get(i);
                    break;
                }
            }
            if (entry == null) {
                while (resourceItems.size() >= MAX_RESOURCE_ITEMS) resourceItems.remove(0);
                entry = new ResourceEntry();
                entry.id = "r" + nextResourceId++;
                entry.url = url;
                entry.method = requestMethod;
                entry.resourceType = type == null || type.length() == 0 ? guessResourceType(url) : type;
                entry.firstSeenAt = System.currentTimeMillis();
                entry.estimatedBytes = size;
                resourceItems.add(entry);
            } else {
                if (entry.estimatedBytes <= 0 && size > 0) entry.estimatedBytes = size;
                if ((entry.resourceType == null || entry.resourceType.length() == 0 || "resource".equals(entry.resourceType)) && type != null) entry.resourceType = type;
                if ("GET".equals(entry.method) && !"GET".equals(requestMethod)) entry.method = requestMethod;
            }
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    if (h.getKey() == null || h.getValue() == null) continue;
                    String key = h.getKey().toLowerCase(Locale.US);
                    if ("host".equals(key) || "connection".equals(key) || "content-length".equals(key) || "cookie".equals(key) || "proxy-authorization".equals(key)) continue;
                    if (h.getValue().length() <= 8192 && (entry.requestHeaders.containsKey(h.getKey()) || entry.requestHeaders.size() < 30)) entry.requestHeaders.put(h.getKey(), h.getValue());
                }
            }
        }
    }

    private void clearResourceItems() {
        synchronized (resourceLock) { resourceItems.clear(); }
    }

    private String guessResourceType(String url) {
        String path = url == null ? "" : url.toLowerCase(Locale.US);
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") || path.endsWith(".avif") || path.endsWith(".ico")) return "image";
        if (path.endsWith(".css")) return "style";
        if (path.endsWith(".js") || path.endsWith(".mjs")) return "script";
        if (path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".otf")) return "font";
        if (path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m3u8") || path.endsWith(".mp3") || path.endsWith(".ogg")) return "media";
        if (path.endsWith(".json")) return "fetch";
        if (path.endsWith(".html") || path.endsWith(".htm")) return "document";
        return "resource";
    }

    private String normalizeMime(String mime) {
        if (mime == null || mime.trim().length() == 0) return "application/octet-stream";
        int semicolon = mime.indexOf(';');
        return (semicolon >= 0 ? mime.substring(0, semicolon) : mime).trim().toLowerCase(Locale.US);
    }

    private boolean isSupportedImageMime(String mime) {
        return "image/png".equals(mime) || "image/jpeg".equals(mime) || "image/gif".equals(mime) || "image/webp".equals(mime);
    }

    private boolean isTextMime(String mime) {
        return mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript") || mime.contains("xml") || "application/x-www-form-urlencoded".equals(mime);
    }

    private String getCharset(String contentType) {
        if (contentType == null) return "UTF-8";
        Matcher matcher = Pattern.compile("(?i)charset\\s*=\\s*[\\\"']?([^;\\\"' ]+)").matcher(contentType);
        return matcher.find() ? matcher.group(1).trim() : "UTF-8";
    }

    private String buildDesktopUserAgent() {
        String base = WebSettings.getDefaultUserAgent(context);
        Matcher matcher = Pattern.compile("Chrome/([0-9.]+)").matcher(base == null ? "" : base);
        String version = matcher.find() ? matcher.group(1) : "120.0.0.0";
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + version + " Safari/537.36";
    }

    private String getMode() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, "mobile");
    }

    private int getDeviceViewportWidthCss() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float density = dm.density > 0 ? dm.density : 1.0f;
        return clamp(Math.round(dm.widthPixels / density), 320, 1280);
    }

    private int getDeviceViewportHeightCss() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float density = dm.density > 0 ? dm.density : 1.0f;
        return clamp(Math.round(dm.heightPixels / density), 480, 1000);
    }

    private int getViewportWidthCss() {
        int fallback = getDeviceViewportWidthCss();
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VIEWPORT_WIDTH, fallback);
    }

    private int getViewportHeightCss() {
        int fallback = getDeviceViewportHeightCss();
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VIEWPORT_HEIGHT, fallback);
    }

    private void applyViewportToView(WebView view, int widthCss, int heightCss) {
        if (view == null) return;
        if (view.getParent() != null) { view.requestLayout(); return; }
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float density = dm.density > 0 ? dm.density : 1.0f;
        int widthPx = Math.max(1, Math.round(widthCss * density));
        int heightPx = Math.max(1, Math.round(heightCss * density));
        int widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY);
        view.measure(widthSpec, heightSpec);
        view.layout(0, 0, widthPx, heightPx);
        view.invalidate();
    }

    private static final class ResourceEntry {
        String id;
        String url;
        String method;
        String resourceType;
        long estimatedBytes = -1L;
        long firstSeenAt;
        final LinkedHashMap<String, String> requestHeaders = new LinkedHashMap<String, String>();
    }

    private static final class DownloadResult {
        byte[] bytes;
        String mimeType;
        String contentType;
        String finalUrl;
        int statusCode;
    }

    private JSONObject clearCookies() throws Exception {
        String url = currentHttpUrl();
        CookieManager manager = CookieManager.getInstance();
        String current = manager.getCookie(url);
        LinkedHashSet<String> names = new LinkedHashSet<String>();
        if (current != null) {
            String[] pieces = current.split(";");
            for (int i = 0; i < pieces.length; i++) {
                int eq = pieces[i].indexOf('=');
                if (eq > 0) {
                    String name = pieces[i].substring(0, eq).trim();
                    if (name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) names.add(name);
                }
            }
        }
        Uri parsed = Uri.parse(url);
        String host = parsed.getHost();
        if (host == null || host.length() == 0) throw new IllegalStateException("Hostname situs aktif tidak tersedia.");
        String path = parsed.getPath();
        if (path == null || path.length() == 0) path = "/";
        Set<String> paths = new LinkedHashSet<String>();
        paths.add("/");
        StringBuilder prefix = new StringBuilder();
        String[] pathParts = path.split("/");
        for (int i = 0; i < pathParts.length; i++) {
            if (pathParts[i].length() == 0) continue;
            prefix.append('/').append(pathParts[i]);
            paths.add(prefix.toString());
        }
        Set<String> domains = new LinkedHashSet<String>();
        domains.add(""); // host-only cookie
        String[] hostParts = host.split("\\.");
        for (int i = 0; i < hostParts.length - 1; i++) {
            StringBuilder d = new StringBuilder();
            for (int j = i; j < hostParts.length; j++) {
                if (d.length() > 0) d.append('.');
                d.append(hostParts[j]);
            }
            if (d.indexOf(".") >= 0) domains.add(d.toString());
        }
        String origin = parsed.buildUpon().path("/").query(null).fragment(null).build().toString();
        int attempts = 0;
        for (String cookieName : names) {
            for (String cookiePath : paths) {
                for (String domain : domains) {
                    String cookie = cookieName + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=" + cookiePath;
                    if (domain.length() > 0) cookie += "; Domain=" + domain;
                    try {
                        manager.setCookie(origin, cookie);
                        attempts++;
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        manager.flush();
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("url", url);
        result.put("host", host);
        result.put("cookieNamesFound", names.size());
        result.put("deletionAttempts", attempts);
        result.put("bestEffort", true);
        result.put("note", "Nilai cookie tidak dikembalikan. Cookie dengan aturan domain/path khusus mungkin tidak semuanya terhapus.");
        return result;
    }

    private JSONObject clearStorage(boolean session) throws Exception {
        ensureExistingPage();
        String storageName = session ? "sessionStorage" : "localStorage";
        String js = "(function(){try{var s=window[" + JSONObject.quote(storageName) + "];var n=s.length;s.clear();return JSON.stringify({ok:true,storage:" + JSONObject.quote(storageName) + ",removedKeys:n,origin:location.origin,url:location.href});}" +
                "catch(e){return JSON.stringify({ok:false,storage:" + JSONObject.quote(storageName) + ",error:String(e),url:location.href});}})()";
        return evalObject(js, 10000L);
    }

    private JSONObject setUserAgent(JSONObject args) throws Exception {
        if (!args.has("userAgent")) throw new IllegalArgumentException("Parameter userAgent wajib diisi; kirim string kosong atau 'default' untuk reset.");
        String requested = args.optString("userAgent", "").trim();
        if (requested.indexOf('\r') >= 0 || requested.indexOf('\n') >= 0) throw new IllegalArgumentException("User-Agent tidak boleh mengandung baris baru.");
        if (requested.length() > 1024) throw new IllegalArgumentException("User-Agent maksimum 1024 karakter.");
        if (requested.length() == 0 || "default".equalsIgnoreCase(requested)) requested = "";
        final String value = requested;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_USER_AGENT, value).apply();
        boolean reloadCurrent = args.optBoolean("reloadCurrent", true);
        final String urlBefore = getActiveUrl();
        final boolean[] hasView = new boolean[1];
        final CountDownLatch done = new CountDownLatch(1);
        runOnMainSync(new Runnable() {
            public void run() {
                WebView v = webView;
                hasView[0] = v != null;
                if (v != null) {
                    v.getSettings().setUserAgentString(value.length() == 0 ? WebSettings.getDefaultUserAgent(context) : value);
                }
                done.countDown();
            }
        });
        boolean reloaded = false;
        boolean reloadFinished = false;
        if (hasView[0] && reloadCurrent && isHttpUrl(urlBefore)) {
            final CountDownLatch load = new CountDownLatch(1);
            runOnMainSync(new Runnable() {
                public void run() {
                    WebView v = webView;
                    if (v != null) {
                        pageProgress = 0;
                        lastPageError = "";
                        pageLoadLatch = load;
                        v.reload();
                    } else {
                        load.countDown();
                    }
                }
            });
            reloadFinished = load.await(18000L, TimeUnit.MILLISECONDS);
            clearLatch(load);
            reloaded = true;
        }
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("userAgent", value.length() == 0 ? "default" : value);
        result.put("persistedGlobally", true);
        result.put("reloadStarted", reloaded);
        result.put("reloadFinished", reloadFinished);
        result.put("activeUrl", getActiveUrl());
        result.put("note", "User-Agent baru berlaku pada request berikutnya; mengubahnya tidak menjadikan WebView identik dengan Chrome.");
        return result;
    }

    private JSONObject console(JSONObject args) throws Exception {
        int limit = clamp(args.optInt("limit", 100), 1, MAX_CONSOLE_ENTRIES);
        String filter = args.optString("level", "all").trim().toLowerCase(Locale.US);
        boolean clearAfterRead = args.optBoolean("clearAfterRead", false);
        JSONArray entries = new JSONArray();
        int matched = 0;
        long dropped;
        synchronized (consoleLock) {
            dropped = consoleDropped;
            int start = consoleEntries.size() - 1;
            for (int i = start; i >= 0 && matched < limit; i--) {
                JSONObject item = consoleEntries.get(i);
                String level = item.optString("level", "").toLowerCase(Locale.US);
                if (!"all".equals(filter) && !levelMatches(level, filter)) continue;
                entries.put(item);
                matched++;
            }
            if (clearAfterRead) {
                consoleEntries.clear();
                consoleDropped = 0;
            }
        }
        JSONObject result = new JSONObject();
        result.put("url", getActiveUrl());
        result.put("count", matched);
        result.put("droppedOlderEntries", dropped);
        result.put("levelFilter", filter.length() == 0 ? "all" : filter);
        result.put("entriesNewestFirst", entries);
        result.put("clearedAfterRead", clearAfterRead);
        result.put("maxStoredEntries", MAX_CONSOLE_ENTRIES);
        result.put("note", "Isi yang tercatat dibatasi oleh console callback Android WebView; level/detail tidak selalu identik dengan DevTools Chrome.");
        return result;
    }

    private boolean levelMatches(String actual, String requested) {
        if ("warn".equals(requested)) requested = "warning";
        if ("info".equals(requested)) requested = "log";
        return requested.equals(actual);
    }

    private void ensureCreated() throws Exception {
        if (webView != null) return;
        runOnMainSync(new Runnable() {
            public void run() {
                if (webView != null) return;
                final WebView view = new WebView(context);
                WebSettings settings = view.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(true);
                settings.setSupportZoom(true);
                settings.setBuiltInZoomControls(true);
                settings.setDisplayZoomControls(false);
                settings.setLoadsImagesAutomatically(true);
                settings.setUseWideViewPort(true);
                settings.setLoadWithOverviewMode(false);
                settings.setAllowFileAccess(false);
                settings.setAllowContentAccess(false);
                settings.setJavaScriptCanOpenWindowsAutomatically(false);
                settings.setSupportMultipleWindows(false);
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                String userAgent = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USER_AGENT, "");
                if (userAgent != null && userAgent.length() > 0) settings.setUserAgentString(userAgent);
                CookieManager cookies = CookieManager.getInstance();
                cookies.setAcceptCookie(true);
                if (android.os.Build.VERSION.SDK_INT >= 21) cookies.setAcceptThirdPartyCookies(view, true);
                view.setWebViewClient(new WebViewClient() {
                    public boolean shouldOverrideUrlLoading(WebView v, String targetUrl) {
                        return !isHttpUrl(targetUrl);
                    }

                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        return request == null || !isHttpUrl(request.getUrl().toString());
                    }

                    public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                        activeUrl = url == null ? "" : url;
                        hasLoadedPage = isHttpUrl(activeUrl);
                        lastPageError = "";
                        pageProgress = 0;
                        clearResourceItems();
                        if (isHttpUrl(activeUrl)) recordResource(activeUrl, "document", "GET", -1L, null);
                    }

                    @SuppressWarnings("deprecation")
                    public WebResourceResponse shouldInterceptRequest(WebView v, String targetUrl) {
                        recordResource(targetUrl, guessResourceType(targetUrl), "GET", -1L, null);
                        return null;
                    }

                    public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                        if (request != null) {
                            Map<String, String> headers = request.getRequestHeaders();
                            recordResource(request.getUrl().toString(), guessResourceType(request.getUrl().toString()), request.getMethod(), -1L, headers);
                        }
                        return null;
                    }

                    public void onPageFinished(WebView v, String url) {
                        activeUrl = url == null ? "" : url;
                        hasLoadedPage = isHttpUrl(activeUrl);
                        if (url != null && isHttpUrl(url)) {
                            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_URL, url).apply();
                        }
                        pageProgress = 100;
                        CountDownLatch latch = pageLoadLatch;
                        if (latch != null) latch.countDown();
                    }

                    @SuppressWarnings("deprecation")
                    public void onReceivedError(WebView v, int errorCode, String description, String failingUrl) {
                        if (failingUrl != null && failingUrl.equals(v.getUrl())) {
                            lastPageError = "WebView error " + errorCode + ": " + description;
                        }
                        CountDownLatch latch = pageLoadLatch;
                        if (latch != null) latch.countDown();
                    }

                    public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                        if (request != null && request.isForMainFrame()) {
                            lastPageError = "WebView error " + error.getErrorCode() + ": " + String.valueOf(error.getDescription());
                            CountDownLatch latch = pageLoadLatch;
                            if (latch != null) latch.countDown();
                        }
                    }

                    public void onReceivedHttpError(WebView v, WebResourceRequest request, WebResourceResponse response) {
                        if (request != null && request.isForMainFrame() && response != null && response.getStatusCode() >= 400) {
                            lastPageError = "HTTP " + response.getStatusCode() + " pada halaman utama";
                        }
                    }
                });
                view.setDownloadListener(new DownloadListener() {
                    public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                        try {
                            BrowserDownloads.enqueue(context, url, userAgent, contentDisposition, mimeType, getActiveUrl());
                        } catch (Exception e) {
                            Log.e(TAG, "Gagal memasukkan download ke Download Manager", e);
                        }
                    }
                });
                view.setWebChromeClient(new WebChromeClient() {
                    public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                        addConsoleMessage(message);
                        return true;
                    }

                    public void onProgressChanged(WebView v, int newProgress) {
                        pageProgress = newProgress;
                    }
                });
                view.setBackgroundColor(android.graphics.Color.WHITE);
                view.setVerticalScrollBarEnabled(false);
                view.setHorizontalScrollBarEnabled(false);
                webView = view;
                settings.setUseWideViewPort(true);
                settings.setLoadWithOverviewMode(false);
                applyViewportToView(view, getViewportWidthCss(), getViewportHeightCss());
            }
        });
    }

    private void addConsoleMessage(android.webkit.ConsoleMessage message) {
        if (message == null) return;
        String level = "log";
        android.webkit.ConsoleMessage.MessageLevel m = message.messageLevel();
        if (m == android.webkit.ConsoleMessage.MessageLevel.ERROR) level = "error";
        else if (m == android.webkit.ConsoleMessage.MessageLevel.WARNING) level = "warning";
        else if (m == android.webkit.ConsoleMessage.MessageLevel.DEBUG) level = "debug";
        else if (m == android.webkit.ConsoleMessage.MessageLevel.TIP) level = "tip";
        JSONObject item = new JSONObject();
        try {
            item.put("timestamp", System.currentTimeMillis());
            item.put("level", level);
            item.put("message", message.message() == null ? "" : message.message());
            item.put("sourceId", message.sourceId() == null ? "" : message.sourceId());
            item.put("lineNumber", message.lineNumber());
            item.put("pageUrl", getActiveUrl());
        } catch (JSONException ignored) {
        }
        synchronized (consoleLock) {
            if (consoleEntries.size() >= MAX_CONSOLE_ENTRIES) {
                consoleEntries.remove(0);
                consoleDropped++;
            }
            consoleEntries.add(item);
        }
    }

    private boolean navigate(final String url, int waitMs, final boolean reload) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        runOnMainSync(new Runnable() {
            public void run() {
                WebView v = webView;
                if (v == null) {
                    done.countDown();
                    return;
                }
                pageProgress = 0;
                lastPageError = "";
                pageLoadLatch = done;
                if (reload) v.reload(); else v.loadUrl(url);
            }
        });
        boolean loaded = done.await(waitMs, TimeUnit.MILLISECONDS);
        clearLatch(done);
        if (getActiveUrl().length() == 0) activeUrl = url;
        return loaded;
    }

    private void ensureExistingPage() throws Exception {
        ensureCreated();
        String url = hasLoadedPage ? activeUrl : "";
        if (!isHttpUrl(url)) {
            String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_URL, "");
            if (isHttpUrl(saved)) {
                navigate(saved, 15000, false);
            } else {
                throw new IllegalStateException("Belum ada halaman browser. Panggil browser.open terlebih dahulu.");
            }
        }
    }

    private JSONObject pageInfo() throws Exception {
        if (webView == null) {
            JSONObject empty = new JSONObject();
            empty.put("url", "");
            empty.put("title", "");
            empty.put("opened", false);
            return empty;
        }
        try {
            JSONObject info = evalObject("(function(){return JSON.stringify({url:location.href,title:document.title,readyState:document.readyState,origin:location.origin,scrollX:window.scrollX||0,scrollY:window.scrollY||0,viewportWidth:window.innerWidth||0,viewportHeight:window.innerHeight||0});})()", 10000L);
            info.put("mode", getMode());
            info.put("viewportWidthCss", getViewportWidthCss());
            info.put("viewportHeightCss", getViewportHeightCss());
            String savedUa = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USER_AGENT, "");
            info.put("userAgentProfile", savedUa.length() == 0 ? "WebView default" : "custom/profile");
            return info;
        } catch (Exception e) {
            JSONObject fallback = new JSONObject();
            fallback.put("url", getActiveUrl());
            fallback.put("title", "");
            fallback.put("readyState", "unknown");
            fallback.put("metadataError", e.getMessage() == null ? e.toString() : e.getMessage());
            return fallback;
        }
    }

    private JSONObject evalObject(String script, long timeoutMs) throws Exception {
        String raw = evaluateJavascript(script, timeoutMs);
        Object parsed = decodeEvalValue(raw);
        if (parsed instanceof JSONObject) return (JSONObject) parsed;
        JSONObject wrapper = new JSONObject();
        wrapper.put("value", parsed == null ? JSONObject.NULL : parsed);
        return wrapper;
    }

    private String evaluateJavascript(final String script, long timeoutMs) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final String[] output = new String[1];
        runOnMainSync(new Runnable() {
            public void run() {
                WebView v = webView;
                if (v == null) {
                    output[0] = null;
                    done.countDown();
                    return;
                }
                v.evaluateJavascript(script, new ValueCallback<String>() {
                    public void onReceiveValue(String value) {
                        output[0] = value;
                        done.countDown();
                    }
                });
            }
        });
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new IllegalStateException("WebView tidak merespons JavaScript dalam " + timeoutMs + " ms.");
        if (output[0] == null) throw new IllegalStateException("WebView belum tersedia.");
        return output[0];
    }

    private Object decodeEvalValue(String raw) throws Exception {
        if (raw == null || "null".equals(raw)) return JSONObject.NULL;
        Object first = new JSONTokener(raw).nextValue();
        if (first instanceof String) {
            String decoded = (String) first;
            if (decoded.length() == 0) return decoded;
            try {
                return new JSONTokener(decoded).nextValue();
            } catch (Exception ignored) {
                return decoded;
            }
        }
        return first;
    }

    private void runOnMainSync(final Runnable runnable) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
            return;
        }
        final CountDownLatch done = new CountDownLatch(1);
        final Throwable[] failure = new Throwable[1];
        boolean posted = mainHandler.post(new Runnable() {
            public void run() {
                try {
                    runnable.run();
                } catch (Throwable t) {
                    failure[0] = t;
                } finally {
                    done.countDown();
                }
            }
        });
        if (!posted) throw new IllegalStateException("Gagal menjadwalkan operasi ke main thread.");
        if (!done.await(10000L, TimeUnit.MILLISECONDS)) throw new IllegalStateException("Main thread Android tidak merespons.");
        if (failure[0] != null) {
            if (failure[0] instanceof Exception) throw (Exception) failure[0];
            throw new RuntimeException(failure[0]);
        }
    }

    private void clearLatch(CountDownLatch expected) {
        if (pageLoadLatch == expected) pageLoadLatch = null;
    }

    private String currentHttpUrl() throws Exception {
        ensureExistingPage();
        String url = getActiveUrl();
        if (!isHttpUrl(url)) throw new IllegalStateException("Fitur ini hanya berlaku untuk situs HTTP/HTTPS.");
        return url;
    }

    private String getActiveUrl() {
        String url = activeUrl;
        if (url == null || url.length() == 0) {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_URL, "");
        }
        return url;
    }

    private String normalizeUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.length() == 0) throw new IllegalArgumentException("Parameter url wajib diisi.");
        if (url.matches("(?i)^[a-z][a-z0-9+.-]*:.*") && !url.matches("(?i)^https?://.*")) {
            throw new IllegalArgumentException("Skema URL selain HTTP/HTTPS tidak diizinkan.");
        }
        if (!url.matches("(?i)^https?://.*")) url = "https://" + url;
        Uri parsed = Uri.parse(url);
        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) || host == null || host.length() == 0) {
            throw new IllegalArgumentException("URL harus berupa alamat HTTP/HTTPS yang valid.");
        }
        return url;
    }

    private boolean isHttpUrl(String url) {
        if (url == null) return false;
        Uri parsed = Uri.parse(url);
        String scheme = parsed.getScheme();
        return scheme != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && parsed.getHost() != null;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public void destroy() {
        try {
            runOnMainSync(new Runnable() {
                public void run() {
                    WebView v = webView;
                    webView = null;
                    hasLoadedPage = false;
                    activeUrl = "";
                    clearResourceItems();
                    if (v != null) {
                        try { v.stopLoading(); } catch (Exception ignored) { }
                        try { v.setWebChromeClient(null); } catch (Exception ignored) { }
                        try { v.setWebViewClient(null); } catch (Exception ignored) { }
                        try { v.destroy(); } catch (Exception ignored) { }
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "WebView destroy", e);
        }
    }
}
