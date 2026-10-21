package rh.browser.models;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BrowserDownloads {
    private static final String PREF = "browser_downloads";
    private static final String KEY_IDS = "ids";
    private BrowserDownloads() {}

    public static long enqueue(Context context, String url, String userAgent, String contentDisposition, String mimeType, String referer) throws Exception {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) throw new IllegalArgumentException("Download hanya mendukung HTTP/HTTPS.");
        String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
        filename = safeFilename(filename);
        DownloadManager.Request request = new DownloadManager.Request(uri);
        request.setTitle(filename);
        request.setDescription("Rh Browser for Models");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
        if (mimeType != null && mimeType.trim().length() > 0) request.setMimeType(mimeType);
        if (userAgent != null && userAgent.length() > 0) request.addRequestHeader("User-Agent", userAgent);
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null && cookie.length() > 0) request.addRequestHeader("Cookie", cookie);
        if (referer != null && referer.matches("(?i)^https?://.*")) request.addRequestHeader("Referer", referer);
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) throw new IllegalStateException("Android DownloadManager tidak tersedia.");
        long id = manager.enqueue(request);
        synchronized (BrowserDownloads.class) {
            JSONArray old = readIds(context);
            JSONArray next = new JSONArray();
            next.put(id);
            for (int i=0; i<old.length(); i++) { long v=old.optLong(i,-1); if (v >= 0 && v != id) next.put(v); }
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_IDS,next.toString()).apply();
        }
        return id;
    }

    public static JSONArray list(Context context) throws Exception {
        JSONArray out = new JSONArray();
        JSONArray ids = readIds(context);
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return out;
        for (int i=0; i<ids.length(); i++) {
            long id = ids.optLong(i,-1); if (id < 0) continue;
            Cursor c = null;
            try {
                c = manager.query(new DownloadManager.Query().setFilterById(id));
                if (c == null || !c.moveToFirst()) continue;
                JSONObject item = new JSONObject();
                item.put("id", id);
                item.put("title", value(c, DownloadManager.COLUMN_TITLE));
                item.put("uri", value(c, DownloadManager.COLUMN_URI));
                item.put("status", status(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))));
                int totalIndex = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                int currentIndex = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                long total = totalIndex >= 0 ? c.getLong(totalIndex) : -1;
                long current = currentIndex >= 0 ? c.getLong(currentIndex) : 0;
                item.put("totalBytes", total); item.put("downloadedBytes", current);
                if (total > 0) {
                    item.put("progress", Math.min(100, (int)(current*100L/total)));
                } else {
                    item.put("progress", JSONObject.NULL);
                }
                int reasonIndex = c.getColumnIndex(DownloadManager.COLUMN_REASON);
                if (reasonIndex >= 0) item.put("reason", c.getInt(reasonIndex));
                out.put(item);
            } finally { if (c != null) c.close(); }
        }
        return out;
    }

    public static boolean cancel(Context context, long id) {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return false;
        manager.remove(id);
        JSONArray old = readIds(context); JSONArray next = new JSONArray();
        for (int i=0; i<old.length(); i++) { long v=old.optLong(i,-1); if (v >= 0 && v != id) next.put(v); }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_IDS,next.toString()).apply();
        return true;
    }

    private static JSONArray readIds(Context c) {
        String raw = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_IDS,"[]");
        try { return new JSONArray(raw); } catch (Exception e) { return new JSONArray(); }
    }
    private static String value(Cursor c, String column) { int i=c.getColumnIndex(column); return i < 0 ? "" : c.getString(i); }
    private static String status(int value) {
        if (value == DownloadManager.STATUS_PENDING) return "pending";
        if (value == DownloadManager.STATUS_RUNNING) return "downloading";
        if (value == DownloadManager.STATUS_PAUSED) return "paused";
        if (value == DownloadManager.STATUS_SUCCESSFUL) return "completed";
        if (value == DownloadManager.STATUS_FAILED) return "failed";
        return "unknown";
    }
    private static String safeFilename(String name) {
        String s = name == null ? "download.bin" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (s.startsWith(".")) s=s.substring(1);
        if (s.length()==0) s="download.bin";
        if (s.length()>180) s=s.substring(0,180);
        return s;
    }
}
