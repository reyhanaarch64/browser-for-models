package rh.browser.models;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BrowserService extends Service implements McpServer.McpToolHandler {
    private static final String TAG="RhBrowserService";
    private static final int NOTIFICATION_ID=9010;
    private static final String CHANNEL_ID="rh_browser_mcp";
    private static final long WATCHDOG_INTERVAL_MS = 10L * 60L * 1000L;
    private static final long HEALTH_CHECK_INTERVAL_MS = 60L * 1000L;
    private static final long FAILURE_RETRY_DELAY_MS = 60L * 1000L;
    private final LocalBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BrowserManager browserManager;
    private McpServer server;
    private WorkflowManager workflowManager;
    private WorkflowHttpServer workflowHttpServer;
    private volatile Process cloudflared;
    private volatile boolean serverRunning;
    private volatile boolean tunnelWanted;
    private volatile String publicUrl="";
    private volatile String tunnelError="";
    private volatile boolean foregroundActive;
    private volatile boolean serviceStopping;
    private final Runnable healthWatchdog = new Runnable() {
        public void run() {
            if (serviceStopping || !ToolSettings.serverEnabled(BrowserService.this)) return;
            try {
                if (!foregroundActive) ensureForeground("Menjaga MCP server tetap aktif...");
                if (!isServerRunning()) {
                    Log.w(TAG, "Health watchdog: MCP listener tidak berjalan; mencoba memulihkan");
                    startMcpServer();
                } else {
                    startWorkflowHttpServer();
                }
                ServiceRecoveryReceiver.schedule(BrowserService.this, isServerRunning() ? WATCHDOG_INTERVAL_MS : FAILURE_RETRY_DELAY_MS);
            } catch (Throwable t) {
                Log.e(TAG, "Health watchdog gagal", t);
                ServiceRecoveryReceiver.schedule(BrowserService.this, FAILURE_RETRY_DELAY_MS);
            }
            if (!serviceStopping && ToolSettings.serverEnabled(BrowserService.this)) {
                handler.removeCallbacks(this);
                handler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS);
            }
        }
    };

    public class LocalBinder extends Binder { public BrowserService getService() { return BrowserService.this; } }
    public void onCreate() {
        super.onCreate();
        serviceStopping = false;
        createChannel();
        boolean enabled = ToolSettings.serverEnabled(this);
        tunnelWanted = false;
        publicUrl = "";
        // Jangan menampilkan URL lama setelah proses/service dibuat ulang.
        getSharedPreferences("browser_server", MODE_PRIVATE).edit().remove("public_url").apply();
        if (enabled) ensureForeground("Menyiapkan browser MCP...");
        browserManager = new BrowserManager(this);
        workflowManager = WorkflowManager.get(this);
        if (enabled) {
            startMcpServer();
            ServiceRecoveryReceiver.schedule(this, isServerRunning() ? WATCHDOG_INTERVAL_MS : FAILURE_RETRY_DELAY_MS);
            armHealthWatchdog();
        }
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        serviceStopping = false;
        if (!ToolSettings.serverEnabled(this)) {
            stopMcpServer();
            ServiceRecoveryReceiver.cancel(this);
            try { stopForeground(true); } catch (Exception ignored) { }
            foregroundActive = false;
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        ensureForeground("Memulihkan browser MCP...");
        if (browserManager == null) browserManager = new BrowserManager(this);
        if (workflowManager == null) workflowManager = WorkflowManager.get(this);
        if (!isServerRunning()) startMcpServer();
        startWorkflowHttpServer();
        if (isServerRunning()) {
            updateNotification(tunnelWanted && publicUrl.length() > 0
                    ? "MCP aktif · tunnel terhubung"
                    : "MCP aktif · port " + McpServer.PORT);
        } else {
            updateNotification(tunnelError.length() > 0 ? tunnelError : "MCP belum aktif · akan mencoba ulang");
        }
        ServiceRecoveryReceiver.schedule(this, isServerRunning() ? WATCHDOG_INTERVAL_MS : FAILURE_RETRY_DELAY_MS);
        armHealthWatchdog();
        return ToolSettings.serverEnabled(this) ? START_STICKY : START_NOT_STICKY;
    }

    public void onTaskRemoved(Intent rootIntent) {
        if (ToolSettings.serverEnabled(this) && !serviceStopping) {
            ServiceRecoveryReceiver.schedule(this, 3000L);
        }
        super.onTaskRemoved(rootIntent);
    }

    public IBinder onBind(Intent intent) { return binder; }

    public boolean onUnbind(Intent intent) {
        if (!ToolSettings.serverEnabled(this)) {
            ServiceRecoveryReceiver.cancel(this);
            stopSelf();
        }
        return false;
    }
    public BrowserManager getBrowserManager() { return browserManager; }
    public android.webkit.WebView getBrowserView() throws Exception { return browserManager.getWebView(); }
    public boolean isServerRunning() { return serverRunning && server != null && server.isRunning(); }
    public String getPublicUrl() { return publicUrl; }
    public String getTunnelError() { return tunnelError; }
    public boolean isTunnelRequested() { return tunnelWanted; }
    public boolean isTunnelRunning() { return isProcessRunning(cloudflared); }
    public synchronized void startMcpServer() {
        serviceStopping = false;
        ToolSettings.setServerEnabled(this, true);
        ensureForeground("Menyalakan MCP server...");
        try {
            if (server != null && server.isRunning()) {
                serverRunning = true;
                startWorkflowHttpServer();
                updateNotification("MCP aktif · port " + McpServer.PORT);
                ServiceRecoveryReceiver.schedule(this, WATCHDOG_INTERVAL_MS);
                armHealthWatchdog();
                return;
            }
            if (server != null) server.stop();
            server = new McpServer(this, this);
            server.start();
            serverRunning = true;
            getSharedPreferences("browser_server",MODE_PRIVATE).edit().putBoolean("running",true).apply();
            startWorkflowHttpServer();
            updateNotification("MCP aktif · port " + McpServer.PORT);
            ServiceRecoveryReceiver.schedule(this, WATCHDOG_INTERVAL_MS);
            armHealthWatchdog();
        } catch (Exception e) {
            serverRunning = false;
            tunnelError = "MCP: " + safe(e);
            Log.e(TAG,"start MCP",e);
            updateNotification("MCP gagal: " + safe(e));
            ServiceRecoveryReceiver.schedule(this, FAILURE_RETRY_DELAY_MS);
            armHealthWatchdog();
        }
    }

    public synchronized void stopMcpServer() {
        serviceStopping = true;
        handler.removeCallbacks(healthWatchdog);
        if (workflowHttpServer != null) { try { workflowHttpServer.stop(); } catch (Exception ignored) { } workflowHttpServer = null; }
        ToolSettings.setServerEnabled(this, false);
        stopTunnel();
        serverRunning = false;
        if (server != null) { try { server.stop(); } catch (Exception ignored) { } server = null; }
        getSharedPreferences("browser_server",MODE_PRIVATE).edit().putBoolean("running",false).remove("public_url").apply();
        ServiceRecoveryReceiver.cancel(this);
        if (foregroundActive) {
            try { stopForeground(true); } catch (Exception ignored) { }
            foregroundActive = false;
        }
    }

    public synchronized void startTunnel() {
        tunnelWanted=true;
        if (!isServerRunning()) { startMcpServer(); if (!isServerRunning()) return; }
        if (isProcessRunning(cloudflared)) return;
        try {
            File binary = new File(getApplicationInfo().nativeLibraryDir,"libcloudflared.so");
            if (!binary.exists() || !binary.canExecute()) {
                tunnelError="libcloudflared.so tidak ditemukan atau tidak executable untuk ABI perangkat.";
                getSharedPreferences("browser_server",MODE_PRIVATE).edit().putString("tunnel_error", tunnelError).apply();
                updateNotification(tunnelError);
                return;
            }
            publicUrl=""; tunnelError="";
            getSharedPreferences("browser_server",MODE_PRIVATE).edit().remove("public_url").remove("tunnel_error").apply();
            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath(),"tunnel","--url","http://127.0.0.1:"+McpServer.PORT);
            pb.redirectErrorStream(true); cloudflared=pb.start(); final Process p=cloudflared;
            Thread reader=new Thread(new Runnable(){ public void run(){
                BufferedReader br=null;
                try {
                    br=new BufferedReader(new InputStreamReader(p.getInputStream(),"UTF-8")); String line;
                    Pattern pattern=Pattern.compile("https://[a-zA-Z0-9.-]*\\.trycloudflare\\.com");
                    while((line=br.readLine())!=null){ Matcher m=pattern.matcher(line); if(m.find()){
                        publicUrl=m.group()+"/mcp"; getSharedPreferences("browser_server",MODE_PRIVATE).edit().putString("public_url",publicUrl).remove("tunnel_error").apply(); updateNotification("Tunnel aktif");
                    }}
                } catch(Exception e){
                    if (tunnelWanted) {
                        tunnelError=safe(e);
                        getSharedPreferences("browser_server",MODE_PRIVATE).edit().putString("tunnel_error", tunnelError).apply();
                        Log.e(TAG,"cloudflared output",e);
                    }
                }
                finally {
                    try{if(br!=null)br.close();}catch(Exception ignored){}
                    if (tunnelWanted && serverRunning) {
                        try {
                            int exitCode = p.exitValue();
                            if (publicUrl.length() > 0) {
                                publicUrl = "";
                                getSharedPreferences("browser_server", MODE_PRIVATE).edit().remove("public_url").apply();
                            }
                            if (tunnelWanted) {
                                tunnelError = "Cloudflare Tunnel berhenti (exit " + exitCode + ")";
                                getSharedPreferences("browser_server", MODE_PRIVATE).edit().putString("tunnel_error", tunnelError).apply();
                                updateNotification(tunnelError);
                            }
                        } catch (IllegalThreadStateException stillRunning) { }
                    }
                    synchronized(BrowserService.this){if(cloudflared==p)cloudflared=null;}
                    if(tunnelWanted && serverRunning) handler.postDelayed(new Runnable(){public void run(){if(tunnelWanted&&serverRunning&&!isProcessRunning(cloudflared)) startTunnel();}},5000L);
                }
            }},"rh-browser-cloudflared"); reader.setDaemon(true); reader.start();
        } catch(Exception e){
            tunnelError=safe(e);
            getSharedPreferences("browser_server",MODE_PRIVATE).edit().putString("tunnel_error", tunnelError).apply();
            Log.e(TAG,"start tunnel",e);
            updateNotification("Tunnel gagal: "+tunnelError);
        }
    }
    public synchronized void stopTunnel() {
        tunnelWanted=false;
        Process p=cloudflared;
        cloudflared=null;
        if(p!=null){try{p.destroy();}catch(Exception ignored){}}
        publicUrl="";
        tunnelError="";
        getSharedPreferences("browser_server",MODE_PRIVATE).edit().remove("public_url").remove("tunnel_error").apply();
        if (isServerRunning()) updateNotification("MCP aktif · tunnel dimatikan");
    }
    public synchronized void startWorkflowHttpServer() {
        if (!ToolSettings.serverEnabled(this)) return;
        try {
            if (workflowManager == null) workflowManager = WorkflowManager.get(this);
            if (workflowHttpServer != null && workflowHttpServer.isRunning()) return;
            if (workflowHttpServer != null) workflowHttpServer.stop();
            workflowHttpServer = new WorkflowHttpServer(this, workflowManager);
            workflowHttpServer.start();
        } catch (Exception e) {
            Log.e(TAG, "start workflow HTTP", e);
            getSharedPreferences("workflow_server", MODE_PRIVATE).edit().putBoolean("running", false).apply();
            workflowManager = WorkflowManager.get(this);
            workflowManager.addLog("*", "", "error", "workflow.server_start_failed", "Workflow HTTP server gagal mulai: " + safe(e), "", 0, null);
        }
    }

    public boolean isWorkflowHttpRunning() { return workflowHttpServer != null && workflowHttpServer.isRunning(); }

    public JSONObject callTool(String name, JSONObject args) throws Exception {
        ToolDef def=ToolRegistry.get(name);
        if(def==null) return toolError("Tool tidak ditemukan: "+name);
        if(!ToolSettings.isEnabled(this,def)) return toolError("Tool dinonaktifkan: "+name);
        try {
            if (name != null && name.startsWith("workflow.")) {
                JSONObject workflowValue = WorkflowManager.get(this).callMcpTool(name, args == null ? new JSONObject() : args);
                JSONObject workflowResult = new JSONObject(); JSONArray workflowContent = new JSONArray();
                JSONObject workflowText = new JSONObject(); workflowText.put("type", "text"); workflowText.put("text", workflowValue.toString()); workflowContent.put(workflowText);
                workflowResult.put("content", workflowContent); workflowResult.put("isError", false); workflowResult.put("structuredContent", workflowValue);
                return workflowResult;
            }
            JSONObject value=browserManager.execute(name,args==null?new JSONObject():args);
            JSONObject result=new JSONObject(); JSONArray content=new JSONArray();
            String image=value.optString("__mcp_image_base64",null), imageMime=value.optString("__mcp_image_mime","image/png");
            String blob=value.optString("__mcp_resource_base64",null), blobMime=value.optString("__mcp_resource_mime","application/octet-stream"), blobUri=value.optString("__mcp_resource_uri","rh-browser://resource/unknown");
            if(value.has("__mcp_image_base64"))value.remove("__mcp_image_base64"); if(value.has("__mcp_image_mime"))value.remove("__mcp_image_mime");
            if(value.has("__mcp_resource_base64"))value.remove("__mcp_resource_base64"); if(value.has("__mcp_resource_mime"))value.remove("__mcp_resource_mime"); if(value.has("__mcp_resource_uri"))value.remove("__mcp_resource_uri");
            JSONObject txt=new JSONObject();txt.put("type","text");txt.put("text",value.toString());content.put(txt);
            if(image!=null&&image.length()>0){JSONObject it=new JSONObject();it.put("type","image");it.put("data",image);it.put("mimeType",imageMime);content.put(it);}
            if(blob!=null&&blob.length()>0){JSONObject res=new JSONObject();res.put("uri",blobUri);res.put("mimeType",blobMime);res.put("blob",blob);JSONObject it=new JSONObject();it.put("type","resource");it.put("resource",res);content.put(it);}
            result.put("content",content);result.put("isError",false);result.put("structuredContent",value);return result;
        } catch(Exception e){ Log.e(TAG,"Tool "+name,e); return toolError(safe(e)); }
    }
    private JSONObject toolError(String message){JSONObject r=new JSONObject();try{JSONArray a=new JSONArray();JSONObject t=new JSONObject();t.put("type","text");t.put("text",message);a.put(t);r.put("content",a);r.put("isError",true);}catch(Exception ignored){}return r;}
    public void onDestroy() {
        serviceStopping = true;
        handler.removeCallbacks(healthWatchdog);
        if (ToolSettings.serverEnabled(this)) ServiceRecoveryReceiver.schedule(this, 5000L);
        else ServiceRecoveryReceiver.cancel(this);
        stopTunnel();
        if (server != null) { try { server.stop(); } catch (Exception ignored) { } server = null; }
        if (workflowHttpServer != null) { try { workflowHttpServer.stop(); } catch (Exception ignored) { } workflowHttpServer = null; }
        serverRunning = false;
        if (browserManager != null) { browserManager.destroy(); browserManager = null; }
        try { stopForeground(true); } catch (Exception ignored) { }
        foregroundActive = false;
        super.onDestroy();
    }
    private void armHealthWatchdog() {
        if (serviceStopping || !ToolSettings.serverEnabled(this)) {
            handler.removeCallbacks(healthWatchdog);
            return;
        }
        handler.removeCallbacks(healthWatchdog);
        handler.postDelayed(healthWatchdog, HEALTH_CHECK_INTERVAL_MS);
    }
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL_ID,"Rh Browser MCP",NotificationManager.IMPORTANCE_LOW);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);}}
    private Notification buildNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);
        int smallIcon = android.R.drawable.ic_menu_view;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setContentTitle("Rh Browser for Models")
                .setContentText(text)
                .setSmallIcon(smallIcon)
                .setContentIntent(pi)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .build();
    }

    private synchronized void ensureForeground(String message) {
        createChannel();
        Notification n = buildNotification(message);
        startForegroundCompat(n);
        foregroundActive = true;
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            try { startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); return; }
            catch (Exception e) { Log.w(TAG, "startForeground(type) gagal, fallback", e); }
        }
        startForeground(NOTIFICATION_ID, n);
    }
    private void updateNotification(String text) {
        if (!foregroundActive) return;
        try { ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification(text)); }
        catch (Exception ignored) { }
    }
    private boolean isProcessRunning(Process p) { if (p == null) return false; try { p.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; } catch (Exception e) { return false; } }
    private String safe(Exception e){String s=e.getMessage();if(s==null||s.length()==0)s=e.getClass().getSimpleName();return s.length()>400?s.substring(0,400):s;}
}
