package rh.browser.models;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

public class DownloadsActivity extends Activity {
    private LinearLayout list;
    private int loadRequestId;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Download Manager");
        UiKit.applyImmersive(this);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 6), UiKit.dp(this, 10), UiKit.dp(this, 5));
        root.setBackgroundColor(UiKit.BG);
        setContentView(root);
        LinearLayout top = UiKit.horizontal(this);
        TextView back = UiKit.iconButton(this, 0xf060, "Kembali");
        top.addView(back, UiKit.lp(this, 44, 44));
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(0, -2, 1);
        hLp.leftMargin = UiKit.dp(this, 12);
        top.addView(heading, hLp);
        heading.addView(UiKit.label(this, "Download Manager", 20, UiKit.TEXT, true));
        heading.addView(UiKit.label(this, "Unduhan HTTP/HTTPS browser", 12, UiKit.MUTED, false));
        TextView refresh = UiKit.iconButton(this, 0xf2f1, "Muat ulang");
        top.addView(refresh, UiKit.lp(this, 44, 44));
        refresh.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { load(); } });
        top.setVisibility(View.GONE);
        root.addView(top, UiKit.lp(this, -1, 0));

        LinearLayout system = UiKit.action(this, 0xf019, "Buka unduhan Android", false);
        LinearLayout.LayoutParams sysLp = UiKit.lp(this, -1, 44);
        sysLp.topMargin = UiKit.dp(this, 12);
        root.addView(system, sysLp);
        system.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { try { startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); } catch (Exception e) { Toast.makeText(DownloadsActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show(); } } });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollLp.topMargin = UiKit.dp(this, 12);
        root.addView(scroll, scrollLp);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        UiKit.addBottomNavigation(this, root, "downloads");
    }

    private void load() {
        if (list == null) return;
        final int requestId = ++loadRequestId;
        if (list.getChildCount() == 0) {
            LinearLayout loading = UiKit.card(this, 18);
            loading.addView(UiKit.label(this, "Memuat daftar unduhan...", 13, UiKit.MUTED, false));
            list.addView(loading, UiKit.lp(this, -1, -2));
        }
        final Context appContext = getApplicationContext();
        Thread worker = new Thread(new Runnable() {
            public void run() {
                JSONArray data = null;
                Exception failure = null;
                try {
                    data = BrowserDownloads.list(appContext);
                } catch (Exception e) {
                    failure = e;
                }
                final JSONArray result = data;
                final Exception error = failure;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || requestId != loadRequestId) return;
                        renderDownloads(result, error);
                    }
                });
            }
        }, "rh-download-query");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderDownloads(JSONArray a, Exception loadError) {
        if (list == null) return;
        list.removeAllViews();
        if (loadError != null) {
            LinearLayout error = UiKit.card(this, 16);
            error.addView(UiKit.label(this, "Gagal membaca unduhan", 15, UiKit.RED, true));
            String message = loadError.getMessage() == null ? loadError.getClass().getSimpleName() : loadError.getMessage();
            error.addView(UiKit.label(this, message, 12, UiKit.MUTED, false));
            list.addView(error, UiKit.lp(this, -1, -2));
            return;
        }
        if (a == null) a = new JSONArray();
        try {
            if (a.length() == 0) {
                LinearLayout empty = UiKit.card(this, 20);
                empty.setGravity(android.view.Gravity.CENTER);
                TextView icon = UiKit.icon(this, 0xf019, 32, UiKit.BLUE);
                empty.addView(icon, UiKit.lp(this, -1, 52));
                TextView text = UiKit.label(this, "Belum ada unduhan", 16, UiKit.TEXT, true);
                text.setGravity(android.view.Gravity.CENTER);
                empty.addView(text, UiKit.lp(this, -1, 28));
                TextView sub = UiKit.label(this, "Klik tautan download di browser. File yang terdeteksi akan muncul di sini.", 12, UiKit.MUTED, false);
                sub.setGravity(android.view.Gravity.CENTER);
                empty.addView(sub, UiKit.lp(this, -1, -2));
                list.addView(empty, UiKit.lp(this, -1, -2));
                return;
            }
            for (int i = 0; i < a.length(); i++) {
                final JSONObject o = a.getJSONObject(i);
                LinearLayout card = UiKit.card(this, 13);
                LinearLayout.LayoutParams cardLp = UiKit.lp(this, -1, -2);
                cardLp.bottomMargin = UiKit.dp(this, 9);
                list.addView(card, cardLp);
                LinearLayout top = UiKit.horizontal(this);
                TextView fileIcon = UiKit.icon(this, iconFor(o.optString("title", "")), 18, UiKit.BLUE);
                fileIcon.setBackground(UiKit.shape(UiKit.CARD_LIGHT, 12, 0, 0, this));
                top.addView(fileIcon, UiKit.lp(this, 42, 42));
                LinearLayout fileDetails = new LinearLayout(this);
                fileDetails.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(0, -2, 1);
                detailLp.leftMargin = UiKit.dp(this, 10);
                top.addView(fileDetails, detailLp);
                TextView name = UiKit.label(this, o.optString("title", "download"), 13, UiKit.TEXT, true);
                name.setTextIsSelectable(true);
                name.setMaxLines(2);
                fileDetails.addView(name);
                Object progress = o.opt("progress");
                String progressText = progress == JSONObject.NULL ? "Progres tidak tersedia" : progress + "%";
                String sizeText = format(o.optLong("downloadedBytes", 0)) + " / " + format(o.optLong("totalBytes", -1));
                TextView info = UiKit.label(this, o.optString("status", "unknown") + " · " + progressText + "\n" + sizeText, 11, UiKit.MUTED, false);
                fileDetails.addView(info);
                card.addView(top);
                if (progress != JSONObject.NULL) {
                    int p = Math.max(0, Math.min(100, (int)o.optInt("progress", 0)));
                    android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                    bar.setMax(100);
                    bar.setProgress(p);
                    if (android.os.Build.VERSION.SDK_INT >= 21) {
                        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(UiKit.BLUE));
                        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(UiKit.BORDER));
                    }
                    LinearLayout.LayoutParams barLp = UiKit.lp(this, -1, 5);
                    barLp.topMargin = UiKit.dp(this, 12);
                    card.addView(bar, barLp);
                }
                LinearLayout buttons = UiKit.horizontal(this);
                LinearLayout.LayoutParams btnLp = UiKit.lp(this, -1, 40);
                btnLp.topMargin = UiKit.dp(this, 10);
                card.addView(buttons, btnLp);
                LinearLayout cancel = UiKit.action(this, 0xf00d, "Hapus", false);
                buttons.addView(cancel, new LinearLayout.LayoutParams(0, -1, 1));
                cancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { BrowserDownloads.cancel(DownloadsActivity.this, o.optLong("id", -1)); load(); } });
                LinearLayout open = UiKit.action(this, 0xf35d, "Buka file", true);
                LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(0, -1, 1);
                openLp.leftMargin = UiKit.dp(DownloadsActivity.this, 7);
                buttons.addView(open, openLp);
                open.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openDownload(o.optLong("id", -1)); } });
            }
        } catch (Exception e) {
            LinearLayout error = UiKit.card(this, 16);
            error.addView(UiKit.label(this, "Gagal membaca unduhan", 15, UiKit.RED, true));
            error.addView(UiKit.label(this, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 12, UiKit.MUTED, false));
            list.addView(error, UiKit.lp(this, -1, -2));
        }
    }

    protected void onResume() {
        super.onResume();
        load();
    }

    protected void onDestroy() {
        loadRequestId++;
        super.onDestroy();
    }

    private void openDownload(long id) {
        try {
            DownloadManager dm = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
            Uri uri = dm.getUriForDownloadedFile(id);
            if (uri == null) throw new IllegalStateException("File belum selesai diunduh");
            Intent i = new Intent(Intent.ACTION_VIEW);
            String mime = dm.getMimeTypeForDownloadedFile(id);
            if (mime != null) i.setDataAndType(uri, mime);
            else i.setData(uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "File belum selesai atau tidak ada aplikasi pembuka yang sesuai.", Toast.LENGTH_SHORT).show();
        }
    }

    private int iconFor(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        if (n.endsWith(".apk")) return 0xf3cd;
        if (n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z")) return 0xf1c6;
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")) return 0xf1c5;
        if (n.endsWith(".pdf")) return 0xf1c1;
        if (n.endsWith(".js") || n.endsWith(".json") || n.endsWith(".txt")) return 0xf1c9;
        return 0xf15b;
    }

    private String format(long n) {
        if (n < 0) return "?";
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", n / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", n / (1024.0 * 1024.0));
    }
}
