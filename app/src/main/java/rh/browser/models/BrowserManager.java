package rh.browser.models;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.FileNotFoundException;
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
    private static final int MAX_NETWORK_ITEMS = 3000;
    private static final int MAX_RESOURCE_BYTES = 20000000;
    private static final int MAX_RESOURCE_TEXT_CHARS = 250000;
    private static final int MAX_CONSOLE_ENTRIES = 500;
    private static final int MAX_INSPECT_LIMIT = 200;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object operationLock = new Object();
    private final Object consoleLock = new Object();
    private final Object resourceLock = new Object();
    private final Object networkLock = new Object();
    private final ArrayList<ResourceEntry> resourceItems = new ArrayList<ResourceEntry>();
    private final ArrayList<NetworkEntry> networkItems = new ArrayList<NetworkEntry>();
    private int nextResourceId = 1;
    private int nextNetworkId = 1;
    private long networkDropped;
    private long networkScriptDropped;
    private final ArrayList<JSONObject> consoleEntries = new ArrayList<JSONObject>();

    private volatile WebView webView;
    private volatile ValueCallback<Uri[]> pendingFileChooser;
    private volatile Uri[] pendingFileUris;
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
            if ("browser.search_element".equals(name)) return searchElement(args);
            if ("browser.get_element".equals(name)) return getElement(args);
            if ("browser.input_text".equals(name)) return inputText(args);
            if ("browser.input_files".equals(name)) return inputFiles(args);
            if ("browser.input_option".equals(name)) return inputOption(args);
            if ("browser.click_at".equals(name)) return clickAt(args);
            if ("browser.map_coordinates".equals(name)) return mapCoordinates(args);
            if ("browser.wait_for".equals(name)) return waitFor(args);
            if ("browser.press_key".equals(name)) return pressKey(args);
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
            if ("browser.network".equals(name)) return network(args);
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
        boolean full = args.optBoolean("full", false) || "full".equals(mode);
        if (!"html".equals(mode) && !"text".equals(mode) && !"full".equals(mode)) throw new IllegalArgumentException("mode harus html, text, atau full.");
        String expression = ("text".equals(mode)) ? "(document.body?document.body.innerText:'')" : "(document.documentElement?document.documentElement.outerHTML:'')";
        String limitExpression = args.has("maxChars") && !full ? "Math.max(0," + Math.max(0,args.optInt("maxChars",0)) + ")" : "s.length";
        String js = "(function(){try{var s=String(" + expression + ");var total=s.length;var max=" + limitExpression + ";var out=s.slice(0,max);return JSON.stringify({ok:true,url:location.href,title:document.title,mode:" + JSONObject.quote(mode) + ",length:total,returnedChars:out.length,truncated:out.length<total,full:out.length===total,source:out,next_actions:out.length<total?['browser.source with a larger maxChars','browser.source with full=true']:[]});}catch(e){return JSON.stringify({ok:false,error:String(e),warnings:[],data:null,next_actions:[]});}})()";
        return evalObject(js, 30000L);
    }

    private JSONObject searchElement(JSONObject args) throws Exception {
        ensureExistingPage();
        String text = args.optString("text", "");
        String pattern = args.optString("pattern", "");
        if (text.length()==0 && pattern.length()==0) throw new IllegalArgumentException("Isi text atau pattern untuk mencari elemen.");
        int limit=clamp(args.optInt("limit",20),1,100);
        String js="(function(){try{var q="+JSONObject.quote(text)+",p="+JSONObject.quote(pattern)+",all=document.querySelectorAll('body *'),out=[];var re=null;if(p){try{re=new RegExp(p,'i')}catch(x){return JSON.stringify({success:false,error:'Regex tidak valid: '+x,warnings:[],data:[],next_actions:['Perbaiki parameter pattern atau gunakan text']})}}function norm(s){return String(s||'').replace(/\\s+/g,' ').trim()}function selector(e){if(e.id)return '#'+e.id;var a=e.tagName.toLowerCase();if(e.className&&typeof e.className==='string')a+='.'+e.className.trim().split(/\\s+/).slice(0,3).join('.');return a}for(var i=0;i<all.length&&out.length<"+limit+";i++){var e=all[i],t=norm(e.innerText||e.textContent),hit=re?re.test(t):t.toLowerCase().indexOf(q.toLowerCase())>=0;if(!hit||!t)continue;var r=e.getBoundingClientRect(),st=getComputedStyle(e),vis=!!(r.width&&r.height&&st.display!=='none'&&st.visibility!=='hidden'),tag=e.tagName.toLowerCase(),inter= /^(a|button|input|textarea|select)$/.test(tag)||e.hasAttribute('onclick')||e.getAttribute('role')==='button';var score=(t.toLowerCase()===q.toLowerCase()?1:(t.toLowerCase().indexOf(q.toLowerCase())===0?.9:.65));out.push({selector:selector(e),id:e.id||'',className:typeof e.className==='string'?e.className:'',tag:tag,text:t.slice(0,500),parent:e.parentElement?{tag:e.parentElement.tagName.toLowerCase(),id:e.parentElement.id||'',text:norm(e.parentElement.innerText).slice(0,180)}:null,children:Array.prototype.slice.call(e.children,0,8).map(function(c){return {tag:c.tagName.toLowerCase(),id:c.id||'',text:norm(c.innerText||c.textContent).slice(0,100)}}),coordinates:{x:r.left,y:r.top,width:r.width,height:r.height,centerX:r.left+r.width/2,centerY:r.top+r.height/2,pageX:r.left+window.scrollX,pageY:r.top+window.scrollY},visible:vis,interactable:inter&&!e.disabled,confidence:score})}return JSON.stringify({success:out.length>0,error:out.length?'':('Tidak ada elemen yang cocok dengan '+(q||p)),warnings:[],data:out,next_actions:out.length?['Gunakan selector dari kandidat terpilih pada browser.get_element atau tool interaksi']:['Perluas sample teks, coba pattern yang lebih longgar, jalankan browser.source, atau browser.inspect']})}catch(e){return JSON.stringify({success:false,error:String(e),warnings:[],data:[],next_actions:[]})}})()";
        return evalObject(js,20000L);
    }

    private JSONObject getElement(JSONObject args) throws Exception {
        ensureExistingPage();
        String ref=args.optString("elementRef","").trim(),sel=args.optString("selector","").trim(),id=args.optString("id","").trim(),cls=args.optString("className","").trim(),txt=args.optString("text","").trim();
        String find=ref.length()>0?"(window.__phoneMcpRefs&&window.__phoneMcpRefs["+parseRef(ref)+"])":sel.length()>0?"document.querySelector("+JSONObject.quote(sel)+")":id.length()>0?"document.getElementById("+JSONObject.quote(id)+")":cls.length()>0?"document.getElementsByClassName("+JSONObject.quote(cls)+")[0]": "(function(){var a=document.querySelectorAll('body *');for(var i=0;i<a.length;i++){if(String(a[i].innerText||a[i].textContent||'').replace(/\\s+/g,' ').trim().indexOf("+JSONObject.quote(txt)+")>=0)return a[i]}return null})()";
        String js="(function(){try{var e="+find+";if(!e)return JSON.stringify({success:false,error:'Elemen tidak ditemukan',warnings:[],data:null,next_actions:['Coba browser.search_element atau periksa selector']});var r=e.getBoundingClientRect(),s=getComputedStyle(e),a={};for(var i=0;i<e.attributes.length;i++)a[e.attributes[i].name]=e.attributes[i].value;var t=e.tagName.toLowerCase(),v=!!(r.width&&r.height&&s.display!=='none'&&s.visibility!=='hidden');return JSON.stringify({success:true,error:null,warnings:[],data:{tag:t,id:e.id||'',className:typeof e.className==='string'?e.className:'',attributes:a,text:String(e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim(),coordinates:{x:r.left,y:r.top,width:r.width,height:r.height,centerX:r.left+r.width/2,centerY:r.top+r.height/2,pageX:r.left+scrollX,pageY:r.top+scrollY},parent:e.parentElement?{tag:e.parentElement.tagName.toLowerCase(),id:e.parentElement.id||'',text:String(e.parentElement.innerText||'').slice(0,300)}:null,children:Array.prototype.slice.call(e.children,0,30).map(function(c){return {tag:c.tagName.toLowerCase(),id:c.id||'',className:typeof c.className==='string'?c.className:'',text:String(c.innerText||c.textContent||'').replace(/\\s+/g,' ').trim().slice(0,200)}}),visible:v,interactable:(/^(a|button|input|textarea|select)$/.test(t)||e.hasAttribute('onclick')||e.getAttribute('role')==='button')&&!e.disabled,enabled:!e.disabled,disabled:!!e.disabled,readOnly:!!e.readOnly,computedStyle:{display:s.display,visibility:s.visibility,position:s.position,zIndex:s.zIndex,color:s.color,backgroundColor:s.backgroundColor,fontSize:s.fontSize,fontWeight:s.fontWeight,width:s.width,height:s.height,opacity:s.opacity}},next_actions:[]})}catch(x){return JSON.stringify({success:false,error:String(x),warnings:[],data:null,next_actions:[]})}})()";
        return evalObject(js,20000L);
    }

    private int parseRef(String ref) { if(!ref.matches("e[0-9]{1,4}")) throw new IllegalArgumentException("elementRef harus seperti e0 atau e12"); return Integer.parseInt(ref.substring(1)); }

    private JSONObject inputText(JSONObject args) throws Exception {
        JSONObject a=new JSONObject(args.toString());
        if(!a.has("value")&&a.has("text"))a.put("value",a.optString("text",""));
        String sel=a.optString("selector","");
        if(sel.length()==0&&a.optString("target","").length()>0)a.put("selector",a.optString("target"));
        JSONObject r=input(a);
        if("file".equalsIgnoreCase(r.optString("type",""))) throw new IllegalArgumentException("browser.input_text hanya untuk teks; gunakan browser.input_files untuk input type=file.");
        return contract(r);
    }

    private JSONObject inputFiles(JSONObject args) throws Exception {
        ensureExistingPage();
        JSONArray specs=args.optJSONArray("files");
        if(specs==null){specs=new JSONArray();specs.put(new JSONObject(args.toString()));}
        if(specs.length()<1)throw new IllegalArgumentException("files harus berisi minimal satu file.");
        if(specs.length()>20)throw new IllegalArgumentException("Maksimal 20 file dalam satu panggilan.");
        File dir=new File(context.getCacheDir(),"mcp_input_files");if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Gagal membuat direktori file sementara.");
        JSONArray metadata=new JSONArray();Uri[] uris=new Uri[specs.length()];
        for(int i=0;i<specs.length();i++){
            JSONObject spec=specs.optJSONObject(i);if(spec==null)throw new IllegalArgumentException("files["+i+"] harus object.");
            String sourceType=spec.optString("sourceType",args.optString("sourceType","")).toLowerCase(Locale.US);
            String filename=spec.optString("filename",specs.length()==1?args.optString("filename","upload.bin"):"upload_"+(i+1)+".bin");
            if(filename.length()==0||filename.indexOf("/")>=0||filename.indexOf("\\")>=0||"..".equals(filename))throw new IllegalArgumentException("filename harus nama file saja, tanpa path.");
            byte[] bytes;
            if("content".equals(sourceType))bytes=spec.optString("content",args.optString("content","")).getBytes("UTF-8");
            else if("base64".equals(sourceType)){String encoded=spec.optString("base64",args.optString("base64",""));int comma=encoded.indexOf(",");if(encoded.startsWith("data:")&&comma>=0)encoded=encoded.substring(comma+1);bytes=Base64.decode(encoded,Base64.DEFAULT);}
            else if("path".equals(sourceType)){String path=spec.optString("path",args.optString("path",""));if(path.length()==0)throw new IllegalArgumentException("path wajib untuk sourceType=path");File f=new File(path);if(!f.isFile())throw new IllegalArgumentException("File path tidak ditemukan atau bukan file: "+path);bytes=readFileBytes(f);if(filename.equals("upload.bin"))filename=f.getName();}
            else if("url".equals(sourceType)){String sourceUrl=spec.optString("url",args.optString("url",""));if(!isHttpUrl(sourceUrl))throw new IllegalArgumentException("url harus HTTP/HTTPS");HttpURLConnection c=(HttpURLConnection)new URL(sourceUrl).openConnection();int timeout=clamp(args.optInt("timeout",15000),1000,60000);c.setConnectTimeout(timeout);c.setReadTimeout(timeout);c.setInstanceFollowRedirects(true);try{int code=c.getResponseCode();if(code<200||code>=300)throw new IllegalStateException("Download sumber file gagal, HTTP "+code);InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>20000000)throw new IllegalArgumentException("File melebihi 20 MB");}bytes=out.toByteArray();}finally{c.disconnect();}}
            else throw new IllegalArgumentException("sourceType harus path, url, base64, atau content.");
            if(bytes.length>20000000)throw new IllegalArgumentException("File melebihi 20 MB: "+filename);
            File outFile=File.createTempFile("mcp_","_"+filename,dir);FileOutputStream fos=new FileOutputStream(outFile);try{fos.write(bytes);fos.flush();}finally{fos.close();}
            uris[i]=Uri.parse("content://rh.browser.models.files/"+outFile.getName());metadata.put(new JSONObject().put("filename",filename).put("bytes",bytes.length).put("mime",spec.optString("mime",args.optString("mime","application/octet-stream"))));
        }
        final String sel=args.optString("selector","").trim(),id=args.optString("id","").trim(),name=args.optString("name","").trim(),ref=args.optString("elementRef","").trim();
        String find=ref.length()>0?"(window.__phoneMcpRefs&&window.__phoneMcpRefs["+parseRef(ref)+"])":sel.length()>0?"document.querySelector("+JSONObject.quote(sel)+")":id.length()>0?"document.getElementById("+JSONObject.quote(id)+")":name.length()>0?"document.querySelector('input[type=file][name='+CSS.escape("+JSONObject.quote(name)+")+']')":"document.querySelector('input[type=file]')";
        final boolean multi=args.optBoolean("multiple",false)||uris.length>1;pendingFileUris=uris;
        String js="(function(){try{var e="+find+";if(!e||String(e.type).toLowerCase()!=='file')return JSON.stringify({success:false,error:'Target tidak ditemukan atau bukan input type=file',warnings:[],data:null,next_actions:['Gunakan browser.search_element untuk menemukan input file']});if(e.disabled)return JSON.stringify({success:false,error:'Input file disabled',warnings:[],data:null,next_actions:[]});if("+multi+"&&!e.multiple)return JSON.stringify({success:false,error:'Input tidak memiliki atribut multiple',warnings:[],data:{multiple:false},next_actions:['Pilih satu file atau gunakan input file yang mendukung multiple']});e.click();return JSON.stringify({success:true,error:null,warnings:['File disalurkan melalui callback native WebView; situs masih dapat menolak tipe atau ukuran file.'],data:{tag:'input',type:'file',multiple:e.multiple,fileCount:"+uris.length+"},next_actions:[]})}catch(x){return JSON.stringify({success:false,error:String(x),warnings:[],data:null,next_actions:[]})}})()";
        JSONObject result=contract(evalObject(js,clamp(args.optInt("timeout",15000),1000,60000)));if(!result.optBoolean("success"))pendingFileUris=null;else result.put("files",metadata);return result;
    }

    private byte[] readFileBytes(File file) throws Exception { if(file.length()>20000000L)throw new IllegalArgumentException("File melebihi 20 MB");FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream();try{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}finally{in.close();}return out.toByteArray(); }


    private JSONObject inputOption(JSONObject args) throws Exception {
        ensureExistingPage();String sel=args.optString("selector","").trim(),target=args.optString("target","").trim();if(sel.length()==0)sel=target;
        String value=args.optString("value",""),label=args.optString("label",""),text=args.optString("text",""),ref=args.optString("elementRef","");int index=args.optInt("index",-1);
        String find=ref.length()>0?"(window.__phoneMcpRefs&&window.__phoneMcpRefs["+parseRef(ref)+"])":sel.length()>0?"document.querySelector("+JSONObject.quote(sel)+")":"null";
        String js="(function(){try{var e="+find+";if(!e)return JSON.stringify({success:false,error:'Target tidak ditemukan; berikan selector atau elementRef',warnings:[],data:null,next_actions:['Gunakan browser.search_element untuk menemukan dropdown/radio']});var tag=e.tagName.toLowerCase();if(tag==='select'){var opts=e.options,chosen=-1;for(var i=0;i<opts.length;i++){if(("+index+">=0&&i==="+index+")||("+JSONObject.quote(value)+"!==''&&opts[i].value==="+JSONObject.quote(value)+")||("+JSONObject.quote((label.length()>0?label:text))+"!==''&&(opts[i].text||'').trim()==="+JSONObject.quote((label.length()>0?label:text))+")){chosen=i;break}}if(chosen<0)return JSON.stringify({success:false,error:'Option tidak ditemukan',warnings:[],data:{optionCount:opts.length},next_actions:['Ambil opsi yang tersedia dengan browser.get_element']});e.selectedIndex=chosen;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return JSON.stringify({success:true,error:null,warnings:[],data:{tag:tag,index:chosen,value:opts[chosen].value,label:opts[chosen].text,selected:true},next_actions:[]})}var q="+JSONObject.quote((label.length()>0?label:text))+",items=[];if(tag==='input'&&e.type==='radio'){items=[e].concat(Array.prototype.slice.call(document.querySelectorAll('input[type=radio]')))}else{var root=e.closest('[role=combobox],[role=listbox],[role=menu],select')||e.parentElement||document;items=Array.prototype.slice.call(root.querySelectorAll('[role=option],[role=radio],option,button,[role=menuitem],li,[data-value]'))}var hit=null;for(var j=0;j<items.length;j++){var x=items[j],tx=String(x.innerText||x.textContent||'').replace(/\\s+/g,' ').trim();if(("+JSONObject.quote(value)+"!==''&&(x.value==="+JSONObject.quote(value)+"||x.getAttribute('data-value')==="+JSONObject.quote(value)+"))||(q!==''&&tx===q)){hit=x;break}}if(!hit&&"+index+">=0&&items["+index+"])hit=items["+index+"];if(!hit)return JSON.stringify({success:false,error:'Item custom dropdown tidak ditemukan',warnings:[],data:{candidateCount:items.length},next_actions:['Klik untuk membuka dropdown dahulu, lalu coba visible text yang persis']});hit.click();if(hit.type==='radio')hit.checked=true;return JSON.stringify({success:true,error:null,warnings:['Custom dropdown ditangani secara heuristic; situs dapat memerlukan urutan klik khusus.'],data:{tag:hit.tagName.toLowerCase(),text:String(hit.innerText||hit.textContent||'').trim(),value:hit.value||hit.getAttribute('data-value')||'',selected:true},next_actions:[]})}catch(x){return JSON.stringify({success:false,error:String(x),warnings:[],data:null,next_actions:[]})}})()";
        return contract(evalObject(js,20000L));
    }

    private JSONObject clickAt(JSONObject args) throws Exception {
        ensureExistingPage();double x=args.optDouble("x",Double.NaN),y=args.optDouble("y",Double.NaN);if(Double.isNaN(x)||Double.isNaN(y))throw new IllegalArgumentException("x dan y wajib berupa angka");String space=args.optString("coordinateSpace","viewport");String rel=args.optString("relativeTo","");String js="(function(){try{var x="+x+",y="+y+",space="+JSONObject.quote(space)+",base=null;"+(rel.length()>0?"base=document.querySelector("+JSONObject.quote(rel)+");if(!base)return JSON.stringify({success:false,error:'relativeTo selector tidak ditemukan',warnings:[],data:null,next_actions:[]});var br=base.getBoundingClientRect();x+=br.left;y+=br.top;":"")+"if(space==='page'){x-=window.scrollX;y-=window.scrollY}else if(space!=='viewport'&&space!=='relative')return JSON.stringify({success:false,error:'coordinateSpace harus viewport, page, atau relative',warnings:[],data:null,next_actions:[]});var e=document.elementFromPoint(x,y);if(!e)return JSON.stringify({success:false,error:'Tidak ada elemen pada koordinat tersebut',warnings:[],data:{x:x,y:y},next_actions:['Gunakan browser.map_coordinates untuk melihat grid']});var ev=document.createEvent('MouseEvents');ev.initMouseEvent('click',true,true,window,1,x,y,x,y,false,false,false,false,0,null);e.dispatchEvent(ev);return JSON.stringify({success:true,error:null,warnings:['Klik DOM sintetis tidak selalu equivalen dengan input sentuh native dan dapat ditolak situs.'],data:{x:x,y:y,coordinateSpace:space,hitTag:e.tagName.toLowerCase(),id:e.id||'',className:typeof e.className==='string'?e.className:'',text:String(e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim().slice(0,200),url:location.href},next_actions:[]})}catch(e){return JSON.stringify({success:false,error:String(e),warnings:[],data:null,next_actions:[]})}})()";return contract(evalObject(js,20000L));
    }

    private JSONObject mapCoordinates(JSONObject args) throws Exception {
        ensureExistingPage();int cell=clamp(args.optInt("cellSize",80),30,250);boolean labels=args.optBoolean("labels",true);String js="(function(){var old=document.getElementById('__rh_coord_grid');if(old)old.remove();var d=document.createElement('div');d.id='__rh_coord_grid';d.style='position:fixed;inset:0;z-index:2147483647;pointer-events:none;background-image:linear-gradient(to right,rgba(255,0,0,.65) 1px,transparent 1px),linear-gradient(to bottom,rgba(255,0,0,.65) 1px,transparent 1px);background-size:"+cell+"px "+cell+"px';"+(labels?"for(var y=0;y<innerHeight;y+="+cell+"){for(var x=0;x<innerWidth;x+="+cell+"){var l=document.createElement('span');l.textContent=x+','+y;l.style='position:absolute;left:'+x+'px;top:'+y+'px;background:#fff;color:#b00000;font:11px monospace;padding:1px 2px';d.appendChild(l)}}":"")+"document.documentElement.appendChild(d);return JSON.stringify({success:true,data:{cellSize:"+cell+",viewportWidth:innerWidth,viewportHeight:innerHeight,coordinateSpace:'viewport'},warnings:[],next_actions:['Gunakan titik viewport dari grid pada browser.click_at'],error:null})})()";JSONObject result=contract(evalObject(js,10000L));JSONObject shot=screenshot(new JSONObject());String cleanup="(function(){var e=document.getElementById('__rh_coord_grid');if(e)e.remove();return true})()";try{evaluateJavascript(cleanup,5000L);}catch(Exception ignored){}shot.put("grid",result.optJSONObject("data"));shot.put("note","Gambar screenshot memuat grid koordinat viewport; gunakan koordinat label sebagai x,y untuk browser.click_at.");return shot;
    }

    private JSONObject waitFor(JSONObject args) throws Exception {
        ensureExistingPage();String condition=args.optString("condition","").toLowerCase(Locale.US),sel=args.optString("selector",""),text=args.optString("text",""),url=args.optString("url",""),expr=args.optString("expression","true");int timeout=clamp(args.optInt("timeout",10000),0,120000),interval=clamp(args.optInt("interval",250),50,2000);long started=System.currentTimeMillis();boolean ok=false;String last="";
        while(System.currentTimeMillis()-started<=timeout){String test;if("element".equals(condition))test="!!document.querySelector("+JSONObject.quote(sel)+")";else if("element_hidden".equals(condition))test="!document.querySelector("+JSONObject.quote(sel)+")||!document.querySelector("+JSONObject.quote(sel)+").getClientRects().length";else if("text".equals(condition))test="String(document.body?document.body.innerText:'').indexOf("+JSONObject.quote(text)+")>=0";else if("url".equals(condition))test="location.href.indexOf("+JSONObject.quote(url)+")>=0";else if("page_load".equals(condition))test="document.readyState==='complete'";else if("network_idle".equals(condition))test="true";else if("custom".equals(condition))test="!!("+expr+")";else throw new IllegalArgumentException("condition harus element, text, url, page_load, element_hidden, network_idle, atau custom.");JSONObject state=evalObject("(function(){try{return JSON.stringify({ok:!!("+test+"),url:location.href,readyState:document.readyState})}catch(e){return JSON.stringify({ok:false,error:String(e)})}})()",5000L);last=state.optString("error","");if(state.optBoolean("ok")){ok=true;break;}if("network_idle".equals(condition)){if(System.currentTimeMillis()-started>=Math.min(timeout,1000)){ok=true;break;}}if(timeout==0)break;Thread.sleep(interval);}
        JSONObject out=new JSONObject();out.put("success",ok);out.put("error",ok?JSONObject.NULL:"timeout");out.put("warnings",new JSONArray());out.put("data",new JSONObject().put("condition",condition).put("elapsedMs",System.currentTimeMillis()-started).put("timeoutMs",timeout).put("url",getActiveUrl()).put("lastError",last));out.put("next_actions",ok?new JSONArray():new JSONArray().put("Periksa selector/kondisi, tingkatkan timeout, atau jalankan browser.inspect"));return out;
    }

    private JSONObject pressKey(JSONObject args) throws Exception {
        ensureExistingPage();String key=args.optString("key","").trim().toLowerCase(Locale.US),sel=args.optString("selector","").trim();if(key.length()==0)throw new IllegalArgumentException("key wajib diisi");String target=sel.length()>0?"document.querySelector("+JSONObject.quote(sel)+")":"(document.activeElement||document.body)";String[] keys=key.split("\\+");String k=keys[keys.length-1];String js="(function(){try{var e="+target+";if(!e)return JSON.stringify({success:false,error:'Target tidak ditemukan',warnings:[],data:null,next_actions:[]});e.focus();var k="+JSONObject.quote(k)+",map={enter:'Enter',tab:'Tab',escape:'Escape',esc:'Escape',backspace:'Backspace',delete:'Delete',arrowup:'ArrowUp',arrowdown:'ArrowDown',arrowleft:'ArrowLeft',arrowright:'ArrowRight',space:' ',home:'Home',end:'End'};k=map[k]||("+JSONObject.quote(k)+".length===1?"+JSONObject.quote(k)+":"+JSONObject.quote(k)+");var opt={key:k,code:k, bubbles:true,cancelable:true,ctrlKey:"+args.optBoolean("ctrl",key.indexOf("ctrl+")>=0)+",altKey:"+args.optBoolean("alt",key.indexOf("alt+")>=0)+",shiftKey:"+args.optBoolean("shift",key.indexOf("shift+")>=0)+",metaKey:"+args.optBoolean("meta",key.indexOf("meta+")>=0)+"};e.dispatchEvent(new KeyboardEvent('keydown',opt));e.dispatchEvent(new KeyboardEvent('keypress',opt));e.dispatchEvent(new KeyboardEvent('keyup',opt));if(k==='Enter'&&e.form&&/^(INPUT|TEXTAREA)$/.test(e.tagName)&&e.tagName==='INPUT'){}return JSON.stringify({success:true,error:null,warnings:['KeyboardEvent sintetis tidak memicu semua perilaku keyboard native; ctrl+c/v mungkin dibatasi WebView.'],data:{key:k,target:e.tagName.toLowerCase(),id:e.id||'',valueLength:typeof e.value==='string'?e.value.length:null},next_actions:[]})}catch(x){return JSON.stringify({success:false,error:String(x),warnings:[],data:null,next_actions:[]})}})()";return contract(evalObject(js,15000L));
    }

    private JSONObject contract(JSONObject raw) throws Exception {
        if(raw.has("success"))return raw;JSONObject out=new JSONObject();boolean ok=raw.optBoolean("ok",!raw.has("error"));out.put("success",ok);out.put("error",raw.has("error")?raw.opt("error"):JSONObject.NULL);out.put("warnings",new JSONArray());out.put("data",raw);out.put("next_actions",new JSONArray());return out;
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

    private JSONObject network(JSONObject args) throws Exception {
        try {
            return networkInternal(args);
        } catch (Exception e) {
            JSONObject out = new JSONObject();
            out.put("success", false);
            out.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            out.put("warnings", new JSONArray());
            out.put("data", JSONObject.NULL);
            JSONArray next = new JSONArray();
            next.put("Pastikan halaman HTTP/HTTPS sudah dibuka dengan browser.open dan periksa parameter filter.");
            out.put("next_actions", next);
            return out;
        }
    }

    private JSONObject networkInternal(JSONObject args) throws Exception {
        ensureExistingPage();
        JSONArray warnings = new JSONArray();
        String instrumentationError = "";
        String pageTitle = "";
        try {
            evalObject(networkInstrumentationScript(), 8000L);
            JSONObject snapshot = evalObject(networkSnapshotScript(), 12000L);
            if (snapshot.has("error")) instrumentationError = snapshot.optString("error", "");
            pageTitle = snapshot.optString("title", "");
            mergeNetworkSnapshot(snapshot);
        } catch (Exception e) {
            instrumentationError = e.getMessage() == null ? e.toString() : e.getMessage();
        }

        String domainFilter = args.optString("domain", "").trim().toLowerCase(Locale.US);
        String urlFilter = args.optString("url", "").trim().toLowerCase(Locale.US);
        String methodFilter = args.optString("method", "").trim();
        String typeFilter = args.optString("type", "").trim();
        String statusFilter = args.optString("status", "").trim();
        String sourceFilter = args.optString("source", "").trim().toLowerCase(Locale.US);
        boolean filterSuccess = args.has("success") && !args.isNull("success");
        boolean successValue = args.optBoolean("success", false);
        long minDuration = args.has("minDuration") ? args.optLong("minDuration", -1L) : -1L;
        long maxDuration = args.has("maxDuration") ? args.optLong("maxDuration", -1L) : -1L;
        int offset = Math.max(0, args.optInt("offset", 0));
        boolean hasLimit = args.has("limit") && !args.isNull("limit");
        int limit = hasLimit ? clamp(args.optInt("limit", 100), 1, MAX_NETWORK_ITEMS) : Integer.MAX_VALUE;
        if (minDuration < -1L || maxDuration < -1L) throw new IllegalArgumentException("minDuration/maxDuration harus bernilai 0 atau lebih.");
        if (minDuration >= 0L && maxDuration >= 0L && minDuration > maxDuration) throw new IllegalArgumentException("minDuration tidak boleh lebih besar dari maxDuration.");

        JSONArray matched = new JSONArray();
        int totalTracked;
        long dropped;
        long scriptDropped;
        synchronized (networkLock) {
            totalTracked = networkItems.size();
            dropped = networkDropped;
            scriptDropped = networkScriptDropped;
            for (int i = 0; i < networkItems.size(); i++) {
                NetworkEntry e = networkItems.get(i);
                if (!matchesNetworkFilter(e, domainFilter, urlFilter, methodFilter, typeFilter, statusFilter, sourceFilter, filterSuccess, successValue, minDuration, maxDuration)) continue;
                matched.put(networkEntryJson(e));
            }
        }
        JSONArray output = new JSONArray();
        int returned = 0;
        for (int i = offset; i < matched.length() && returned < limit; i++) {
            output.put(matched.get(i));
            returned++;
        }
        boolean hasMore = ((long) offset + (long) returned) < (long) matched.length();
        if (instrumentationError.length() > 0) warnings.put("Instrumentasi JavaScript tidak berhasil dibaca: " + instrumentationError);
        warnings.put("Observasi menggabungkan WebViewClient, fetch/XHR yang terinstrumentasi, dan Resource Timing. WebViewClient tidak menyediakan status/durasi/header respons untuk semua respons sukses; kolom yang tidak tersedia ditandai null.");
        warnings.put("Instrumentasi fetch/XHR dipasang setelah onPageFinished. Request JavaScript yang selesai sebelum pemasangan bisa hanya terlihat sebagai resource timing atau bisa tidak terdeteksi.");
        warnings.put("Header sensitif seperti cookie, authorization, proxy-authorization, dan set-cookie tidak dikembalikan.");
        warnings.put("WebSocket dan EventSource tidak direkam sebagai siklus koneksi lengkap. Durasi fetch bisa mencerminkan waktu sampai response headers jika Resource Timing tidak menyediakan durasi transfer; sizeBytes adalah estimasi dari Resource Timing dan bisa tidak tersedia.");
        if (dropped > 0L) warnings.put("Penyimpanan native membuang " + dropped + " entri terlama karena melewati kapasitas " + MAX_NETWORK_ITEMS + ". Gunakan filter atau ambil data lebih sering.");
        if (scriptDropped > 0L) warnings.put("Instrumentasi halaman membuang " + scriptDropped + " event JavaScript terlama karena melewati kapasitas internalnya.");
        if (matched.length() == 0) warnings.put("Tidak ada request yang cocok dengan filter saat ini.");

        JSONObject data = new JSONObject();
        data.put("url", getActiveUrl());
        data.put("title", pageTitle);
        data.put("totalTracked", totalTracked);
        data.put("totalMatching", matched.length());
        data.put("returned", output.length());
        data.put("offset", offset);
        data.put("hasMore", hasMore);
        data.put("droppedNativeEntries", dropped);
        data.put("droppedJavascriptEvents", scriptDropped);
        data.put("requests", output);
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("error", JSONObject.NULL);
        result.put("warnings", warnings);
        result.put("data", data);
        JSONArray next = new JSONArray();
        if (hasMore) next.put("Ambil halaman berikutnya dengan offset=" + (offset + returned) + " dan filter yang sama.");
        if (matched.length() == 0) next.put("Coba hapus filter, buka ulang halaman, atau picu interaksi yang menghasilkan request lalu panggil browser.network lagi.");
        next.put("Gunakan browser.network dengan domain, method, type, status, success, minDuration, atau maxDuration untuk memfilter hasil.");
        result.put("next_actions", next);
        return result;
    }

    private boolean matchesNetworkFilter(NetworkEntry e, String domain, String url, String methods, String types,
                                        String statuses, String source, boolean filterSuccess, boolean successValue,
                                        long minDuration, long maxDuration) {
        if (domain.length() > 0) {
            String host = Uri.parse(e.url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            if (!(host.equals(domain) || host.endsWith("." + domain))) return false;
        }
        if (url.length() > 0 && e.url.toLowerCase(Locale.US).indexOf(url) < 0) return false;
        if (!matchesCsv(methods, e.method, true)) return false;
        if (!matchesCsv(types, e.type, false)) return false;
        if (!matchesCsv(statuses, e.statusCode > 0 ? String.valueOf(e.statusCode) : "unknown", false)) return false;
        if (source.length() > 0 && e.source.toLowerCase(Locale.US).indexOf(source) < 0) return false;
        if (filterSuccess && (e.success == null || e.success.booleanValue() != successValue)) return false;
        if (minDuration >= 0L && (e.durationMs < 0L || e.durationMs < minDuration)) return false;
        if (maxDuration >= 0L && (e.durationMs < 0L || e.durationMs > maxDuration)) return false;
        return true;
    }

    private boolean matchesCsv(String filter, String value, boolean upper) {
        if (filter == null || filter.trim().length() == 0) return true;
        if (value == null) value = "";
        String candidate = upper ? value.toUpperCase(Locale.US) : value.toLowerCase(Locale.US);
        String[] parts = filter.split(",");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.length() == 0) continue;
            if (upper) part = part.toUpperCase(Locale.US); else part = part.toLowerCase(Locale.US);
            if (candidate.equals(part)) return true;
        }
        return false;
    }

    private JSONObject networkEntryJson(NetworkEntry e) throws Exception {
        JSONObject row = new JSONObject();
        row.put("id", e.id);
        row.put("url", e.url);
        String host = Uri.parse(e.url).getHost();
        row.put("domain", host == null ? JSONObject.NULL : host);
        row.put("method", e.method.length() == 0 ? JSONObject.NULL : e.method);
        row.put("type", e.type.length() == 0 ? "resource" : e.type);
        row.put("source", e.source);
        if (e.startedAt > 0L) row.put("startedAt", Long.valueOf(e.startedAt));
        else row.put("startedAt", JSONObject.NULL);
        if (e.durationMs >= 0L) row.put("durationMs", Long.valueOf(e.durationMs));
        else row.put("durationMs", JSONObject.NULL);
        if (e.statusCode > 0) row.put("status", Integer.valueOf(e.statusCode));
        else row.put("status", JSONObject.NULL);
        row.put("success", e.success == null ? JSONObject.NULL : e.success);
        row.put("isMainFrame", e.mainFrame);
        if (e.sizeBytes >= 0L) row.put("sizeBytes", Long.valueOf(e.sizeBytes));
        else row.put("sizeBytes", JSONObject.NULL);
        row.put("sizeKnown", e.sizeBytes >= 0L);
        row.put("initiator", e.initiator.length() == 0 ? JSONObject.NULL : e.initiator);
        row.put("error", e.error.length() == 0 ? JSONObject.NULL : e.error);
        row.put("requestHeaders", headersJson(e.requestHeaders));
        row.put("responseHeaders", headersJson(e.responseHeaders));
        row.put("responseHeadersAvailable", !e.responseHeaders.isEmpty());
        return row;
    }

    private JSONObject headersJson(LinkedHashMap<String, String> headers) throws Exception {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, String> h : headers.entrySet()) out.put(h.getKey(), h.getValue());
        return out;
    }

    private String networkInstrumentationScript() {
        return "(function(){try{if(window.__rhNetworkInstalled)return true;window.__rhNetworkInstalled=true;window.__rhNetworkEvents=window.__rhNetworkEvents||[];window.__rhNetworkDropped=window.__rhNetworkDropped||0;window.__rhNetworkTimeOrigin=window.__rhNetworkTimeOrigin||((window.performance&&performance.timeOrigin)?performance.timeOrigin:(Date.now()-((window.performance&&performance.now)?performance.now():0)));" +
            "function safeUrl(u){try{return new URL(String(u),location.href).href}catch(e){return String(u||'')}}" +
            "function headerObject(h){var o={},n=0;try{if(!h)return o;if(typeof h.forEach==='function'){h.forEach(function(v,k){if(n++<50)o[String(k).toLowerCase()]=String(v).slice(0,2048)})}else if(Array.isArray(h)){for(var i=0;i<h.length&&n<50;i++){if(h[i]&&h[i].length>1)o[String(h[i][0]).toLowerCase()]=String(h[i][1]).slice(0,2048),n++}}else{for(var k in h){if(Object.prototype.hasOwnProperty.call(h,k)&&n++<50)o[String(k).toLowerCase()]=String(h[k]).slice(0,2048)}}}catch(e){}return o}" +
            "function responseHeaderObject(raw){var o={};String(raw||'').split(/\\r?\\n/).forEach(function(line){var i=line.indexOf(':');if(i>0)o[line.slice(0,i).trim().toLowerCase()]=line.slice(i+1).trim().slice(0,2048)});return o}" +
            "function push(e){try{e.pageUrl=location.href;e.source='javascript';e.startedAt=e.startedAt||Date.now();if(window.__rhNetworkEvents.length>=1000){window.__rhNetworkEvents.shift();window.__rhNetworkDropped++}window.__rhNetworkEvents.push(e)}catch(x){}}" +
            "if(window.fetch){var originalFetch=window.fetch;window.fetch=function(input,init){var url=safeUrl(input&&input.url?input.url:input),method=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase(),reqHeaders=headerObject((init&&init.headers)||(input&&input.headers)),wall=Date.now(),start=(window.performance&&performance.now)?performance.now():0;var p;try{p=originalFetch.apply(this,arguments)}catch(e){push({url:url,method:method,type:'fetch',startedAt:wall,durationMs:0,status:null,success:false,error:String(e),requestHeaders:reqHeaders,responseHeaders:{}});throw e}return p.then(function(res){var dur=(window.performance&&performance.now)?Math.max(0,performance.now()-start):Math.max(0,Date.now()-wall),rh={};try{res.headers.forEach(function(v,k){rh[String(k).toLowerCase()]=String(v).slice(0,2048)})}catch(e){}push({url:url,method:method,type:'fetch',startedAt:wall,durationMs:dur,status:res.status,success:!!res.ok,error:res.ok?'':('HTTP '+res.status),requestHeaders:reqHeaders,responseHeaders:rh,sizeBytes:-1});return res},function(err){var dur=(window.performance&&performance.now)?Math.max(0,performance.now()-start):Math.max(0,Date.now()-wall);push({url:url,method:method,type:'fetch',startedAt:wall,durationMs:dur,status:null,success:false,error:String(err),requestHeaders:reqHeaders,responseHeaders:{}});throw err})}}" +
            "if(window.XMLHttpRequest){var xo=XMLHttpRequest.prototype.open,xs=XMLHttpRequest.prototype.send,xh=XMLHttpRequest.prototype.setRequestHeader;XMLHttpRequest.prototype.open=function(m,u){this.__rhDone=false;this.__rhN={method:String(m||'GET').toUpperCase(),url:safeUrl(u),headers:{}};return xo.apply(this,arguments)};XMLHttpRequest.prototype.setRequestHeader=function(k,v){try{if(this.__rhN)this.__rhN.headers[String(k).toLowerCase()]=String(v).slice(0,2048)}catch(e){}return xh.apply(this,arguments)};XMLHttpRequest.prototype.send=function(){var xhr=this,meta=this.__rhN||{method:'GET',url:'',headers:{}},wall=Date.now(),start=(window.performance&&performance.now)?performance.now():0;function finish(){if(xhr.__rhDone)return;xhr.__rhDone=true;var dur=(window.performance&&performance.now)?Math.max(0,performance.now()-start):Math.max(0,Date.now()-wall),st=0,rh={};try{st=xhr.status;rh=responseHeaderObject(xhr.getAllResponseHeaders())}catch(e){}push({url:meta.url,method:meta.method,type:'xhr',startedAt:wall,durationMs:dur,status:st||null,success:st>=200&&st<400,error:st===0?'XHR failed or was blocked':(st>=400?'HTTP '+st:''),requestHeaders:meta.headers,responseHeaders:rh,sizeBytes:-1})}try{xhr.addEventListener('loadend',finish)}catch(e){}try{return xs.apply(this,arguments)}catch(e){push({url:meta.url,method:meta.method,type:'xhr',startedAt:wall,durationMs:0,status:null,success:false,error:String(e),requestHeaders:meta.headers,responseHeaders:{}});throw e}}}" +
            "return true}catch(e){return false}})()";
    }

    private String networkSnapshotScript() {
        return "(function(){try{var now=Date.now(),pn=window.performance,perfNow=pn&&pn.now?pn.now():0,origin=window.__rhNetworkTimeOrigin||(now-perfNow),events=(window.__rhNetworkEvents||[]).slice(),timings=[];try{var a=pn&&pn.getEntriesByType?pn.getEntriesByType('resource'):[];for(var i=0;i<a.length;i++){var e=a[i];if(!e||!e.name||!/^https?:/i.test(e.name))continue;timings.push({url:e.name,type:e.initiatorType||'resource',startedAt:Math.max(0,Math.round(origin+e.startTime)),durationMs:Math.max(0,Math.round(e.duration||0)),sizeBytes:(e.transferSize||e.encodedBodySize||0)>0?(e.transferSize||e.encodedBodySize):-1,initiator:e.initiatorType||'',requestStartMs:e.requestStart||0,responseStartMs:e.responseStart||0,responseEndMs:e.responseEnd||0})}}catch(x){}var d=window.__rhNetworkDropped||0;var out=JSON.stringify({url:location.href,title:document.title,events:events,resourceTiming:timings,droppedJavascriptEvents:d});window.__rhNetworkEvents=[];window.__rhNetworkDropped=0;return out}catch(e){return JSON.stringify({error:String(e),events:[],resourceTiming:[],droppedJavascriptEvents:0})}})()";
    }

    private void mergeNetworkSnapshot(JSONObject snapshot) throws Exception {
        if (snapshot == null) return;
        long dropped = snapshot.optLong("droppedJavascriptEvents", 0L);
        synchronized (networkLock) { networkScriptDropped += dropped; }
        JSONArray events = snapshot.optJSONArray("events");
        if (events != null) {
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null) continue;
                String url = event.optString("url", "");
                String method = event.optString("method", "UNKNOWN").toUpperCase(Locale.US);
                String type = event.optString("type", "fetch");
                long startedAt = event.optLong("startedAt", System.currentTimeMillis());
                NetworkEntry entry = findOrCreateNetworkEntry(url, method, type, "javascript", startedAt, false, event.optJSONObject("requestHeaders"));
                if (entry == null) continue;
                synchronized (networkLock) {
                    entry.source = appendSource(entry.source, "javascript");
                    if (type.length() > 0) entry.type = type;
                    if (event.has("durationMs") && !event.isNull("durationMs")) entry.durationMs = Math.max(0L, event.optLong("durationMs", -1L));
                    if (event.has("status") && !event.isNull("status")) entry.statusCode = event.optInt("status", -1);
                    if (event.has("success") && !event.isNull("success")) entry.success = Boolean.valueOf(event.optBoolean("success"));
                    if (event.has("sizeBytes") && !event.isNull("sizeBytes")) entry.sizeBytes = event.optLong("sizeBytes", -1L);
                    if (event.has("error")) entry.error = event.optString("error", "");
                    if (event.has("requestHeaders")) copySafeHeaders(entry.requestHeaders, event.optJSONObject("requestHeaders"));
                    if (event.has("responseHeaders")) copySafeHeaders(entry.responseHeaders, event.optJSONObject("responseHeaders"));
                }
            }
        }
        JSONArray timings = snapshot.optJSONArray("resourceTiming");
        if (timings != null) {
            for (int i = 0; i < timings.length(); i++) {
                JSONObject timing = timings.optJSONObject(i);
                if (timing == null) continue;
                String url = timing.optString("url", "");
                long startedAt = timing.optLong("startedAt", System.currentTimeMillis());
                String type = timing.optString("type", "resource");
                NetworkEntry entry = findOrCreateNetworkEntry(url, "UNKNOWN", type, "resource_timing", startedAt, false, null);
                if (entry == null) continue;
                synchronized (networkLock) {
                    entry.source = appendSource(entry.source, "resource_timing");
                    entry.timingStartedAt = startedAt;
                    if ((entry.type.length() == 0 || "resource".equals(entry.type)) && type.length() > 0) entry.type = type;
                    long timingDuration = timing.optLong("durationMs", -1L);
                    if (timingDuration >= 0L && (entry.durationMs < 0L || timingDuration > entry.durationMs)) entry.durationMs = timingDuration;
                    if (entry.sizeBytes < 0L && timing.optLong("sizeBytes", -1L) > 0L) entry.sizeBytes = timing.optLong("sizeBytes", -1L);
                    if (entry.initiator.length() == 0) entry.initiator = timing.optString("initiator", "");
                }
            }
        }
    }

    private NetworkEntry findOrCreateNetworkEntry(String url, String method, String type, String source, long startedAt, boolean mainFrame, JSONObject headers) {
        if (!isHttpUrl(url) || url.length() > 8192) return null;
        if (method == null || method.length() == 0) method = "UNKNOWN";
        if (type == null || type.length() == 0) type = "resource";
        synchronized (networkLock) {
            NetworkEntry best = null;
            long bestDelta = Long.MAX_VALUE;
            for (int i = networkItems.size() - 1; i >= 0; i--) {
                NetworkEntry candidate = networkItems.get(i);
                if (!url.equals(candidate.url)) continue;
                boolean methodMatch = "UNKNOWN".equals(method) || "UNKNOWN".equals(candidate.method) || method.equalsIgnoreCase(candidate.method);
                if (!methodMatch) continue;
                long delta = Math.abs(candidate.startedAt - startedAt);
                long threshold = "webview".equals(source) ? 1000L : 1500L;
                if ("webview".equals(source)) {
                    if (!mainFrame || !candidate.mainFrame || !"webview".equals(candidate.source)) continue;
                } else if ("javascript".equals(source)) {
                    if (candidate.mainFrame != mainFrame || !"webview".equals(candidate.source)) continue;
                } else if ("resource_timing".equals(source)) {
                    if (candidate.mainFrame) continue;
                    if (candidate.timingStartedAt > 0L && candidate.timingStartedAt != startedAt) continue;
                    if (candidate.timingStartedAt > 0L) threshold = Long.MAX_VALUE;
                }
                if (delta <= threshold && delta < bestDelta) { best = candidate; bestDelta = delta; }
            }
            if (best == null) {
                while (networkItems.size() >= MAX_NETWORK_ITEMS) { networkItems.remove(0); networkDropped++; }
                best = new NetworkEntry();
                best.id = "n" + nextNetworkId++;
                best.url = url;
                best.method = method;
                best.type = type;
                best.source = source;
                best.startedAt = startedAt > 0L ? startedAt : System.currentTimeMillis();
                best.mainFrame = mainFrame;
                networkItems.add(best);
            } else {
                if ("UNKNOWN".equals(best.method) && !"UNKNOWN".equals(method)) best.method = method;
                if ((best.type.length() == 0 || "resource".equals(best.type) || "UNKNOWN".equals(best.type)) && type.length() > 0) best.type = type;
                best.mainFrame = best.mainFrame || mainFrame;
            }
            if (headers != null) copySafeHeaders(best.requestHeaders, headers);
            return best;
        }
    }

    private String appendSource(String current, String next) {
        if (current == null || current.length() == 0) return next;
        if (current.indexOf(next) >= 0) return current;
        return current + "+" + next;
    }

    private void copySafeHeaders(LinkedHashMap<String, String> target, JSONObject source) {
        if (target == null || source == null) return;
        java.util.Iterator<String> keys = source.keys();
        int count = 0;
        while (keys.hasNext() && count < 50) {
            String key = keys.next();
            if (key == null) continue;
            String lower = key.toLowerCase(Locale.US);
            if ("cookie".equals(lower) || "set-cookie".equals(lower) || "set-cookie2".equals(lower) || "authorization".equals(lower) || "proxy-authorization".equals(lower)) continue;
            String value = source.optString(key, "");
            if (value.length() > 2048) value = value.substring(0, 2048);
            target.put(key, value);
            count++;
        }
    }

    private void updateNetworkResponse(String url, int status, Map<String, String> headers, String error) {
        if (url == null || url.length() == 0) return;
        synchronized (networkLock) {
            NetworkEntry best = null;
            for (int i = networkItems.size() - 1; i >= 0; i--) {
                NetworkEntry e = networkItems.get(i);
                if (url.equals(e.url) && e.statusCode <= 0 && e.success == null) { best = e; break; }
            }
            if (best == null) return;
            if (status > 0) best.statusCode = status;
            if (status > 0) best.success = Boolean.valueOf(status >= 200 && status < 400);
            if (status >= 400 && best.error.length() == 0) best.error = "HTTP " + status;
            if (error != null && error.length() > 0) { best.error = error; best.success = Boolean.FALSE; }
            if (best.durationMs < 0L) best.durationMs = Math.max(0L, System.currentTimeMillis() - best.startedAt);
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    if (h.getKey() == null || h.getValue() == null) continue;
                    String lower = h.getKey().toLowerCase(Locale.US);
                    if ("set-cookie".equals(lower) || "set-cookie2".equals(lower) || "authorization".equals(lower) || "cookie".equals(lower)) continue;
                    best.responseHeaders.put(h.getKey(), h.getValue().length() > 2048 ? h.getValue().substring(0, 2048) : h.getValue());
                }
            }
        }
    }

    private void clearNetworkItems() {
        synchronized (networkLock) {
            networkItems.clear();
            networkDropped = 0L;
            networkScriptDropped = 0L;
        }
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

    private static final class NetworkEntry {
        String id;
        String url;
        String method = "UNKNOWN";
        String type = "resource";
        String source = "webview";
        long startedAt;
        long durationMs = -1L;
        int statusCode = -1;
        Boolean success;
        boolean mainFrame;
        long sizeBytes = -1L;
        long timingStartedAt = -1L;
        String initiator = "";
        String error = "";
        final LinkedHashMap<String, String> requestHeaders = new LinkedHashMap<String, String>();
        final LinkedHashMap<String, String> responseHeaders = new LinkedHashMap<String, String>();
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
                        clearNetworkItems();
                        if (isHttpUrl(activeUrl)) {
                            recordResource(activeUrl, "document", "GET", -1L, null);
                            findOrCreateNetworkEntry(activeUrl, "UNKNOWN", "document", "webview", System.currentTimeMillis(), true, null);
                        }
                    }

                    @SuppressWarnings("deprecation")
                    public WebResourceResponse shouldInterceptRequest(WebView v, String targetUrl) {
                        recordResource(targetUrl, guessResourceType(targetUrl), "GET", -1L, null);
                        boolean main = targetUrl != null && targetUrl.equals(activeUrl);
                        findOrCreateNetworkEntry(targetUrl, "GET", main ? "document" : guessResourceType(targetUrl), "webview", System.currentTimeMillis(), main, null);
                        return null;
                    }

                    public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                        if (request != null) {
                            Map<String, String> headers = request.getRequestHeaders();
                            String requestUrl = request.getUrl().toString();
                            String requestType = request.isForMainFrame() ? "document" : guessResourceType(requestUrl);
                            recordResource(requestUrl, requestType, request.getMethod(), -1L, headers);
                            JSONObject safeHeaders = new JSONObject();
                            if (headers != null) { for (Map.Entry<String, String> h : headers.entrySet()) { if (h.getKey() != null && h.getValue() != null) try { safeHeaders.put(h.getKey(), h.getValue()); } catch (Exception ignored) { } } }
                            findOrCreateNetworkEntry(requestUrl, request.getMethod(), requestType, "webview", System.currentTimeMillis(), request.isForMainFrame(), safeHeaders);
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
                        try { v.evaluateJavascript(networkInstrumentationScript(), null); } catch (Exception ignored) { }
                        CountDownLatch latch = pageLoadLatch;
                        if (latch != null) latch.countDown();
                    }

                    @SuppressWarnings("deprecation")
                    public void onReceivedError(WebView v, int errorCode, String description, String failingUrl) {
                        if (failingUrl != null) updateNetworkResponse(failingUrl, -1, null, "WebView error " + errorCode + ": " + description);
                        if (failingUrl != null && failingUrl.equals(v.getUrl())) {
                            lastPageError = "WebView error " + errorCode + ": " + description;
                        }
                        CountDownLatch latch = pageLoadLatch;
                        if (latch != null) latch.countDown();
                    }

                    public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                        if (request != null) updateNetworkResponse(request.getUrl().toString(), -1, null, "WebView error " + error.getErrorCode() + ": " + String.valueOf(error.getDescription()));
                        if (request != null && request.isForMainFrame()) {
                            lastPageError = "WebView error " + error.getErrorCode() + ": " + String.valueOf(error.getDescription());
                            CountDownLatch latch = pageLoadLatch;
                            if (latch != null) latch.countDown();
                        }
                    }

                    public void onReceivedHttpError(WebView v, WebResourceRequest request, WebResourceResponse response) {
                        if (request != null && response != null) updateNetworkResponse(request.getUrl().toString(), response.getStatusCode(), response.getResponseHeaders(), null);
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
                    public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback, FileChooserParams params) {
                        ValueCallback<Uri[]> previous = pendingFileChooser;
                        if (previous != null) previous.onReceiveValue(null);
                        pendingFileChooser = callback;
                        Uri[] files = pendingFileUris;
                        pendingFileUris = null;
                        if (files != null && files.length > 0) {
                            pendingFileChooser = null;
                            callback.onReceiveValue(files);
                            return true;
                        }
                        pendingFileChooser = null;
                        callback.onReceiveValue(null);
                        return true;
                    }

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
                    clearNetworkItems();
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
