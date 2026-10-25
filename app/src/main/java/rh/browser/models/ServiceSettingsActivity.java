package rh.browser.models;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class ServiceSettingsActivity extends Activity {
    private BrowserService service;
    private boolean bound;
    private TextView serverState, tunnelState, localUrl, publicUrl, tokenView, batteryState;
    private LinearLayout serverAction, tunnelAction;
    private final Handler handler = new Handler();
    private final Runnable poll = new Runnable() { public void run() { refresh(); handler.postDelayed(this, 1400L); } };
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) { service = ((BrowserService.LocalBinder)binder).getService(); bound = true; refresh(); }
        public void onServiceDisconnected(ComponentName name) { service = null; bound = false; }
    };

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Service & Tunnel");
        UiKit.applyImmersive(this);
        buildUi();
        Intent i = new Intent(this, BrowserService.class);
        try {
            if (ToolSettings.serverEnabled(this)) {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            }
            bindService(i, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) { Toast.makeText(this, "Tidak bisa menghubungkan service: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);
        root.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 6), UiKit.dp(this, 10), UiKit.dp(this, 5));
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
        heading.addView(UiKit.label(this, "Service & Tunnel", 20, UiKit.TEXT, true));
        heading.addView(UiKit.label(this, "Pengaturan server background", 12, UiKit.MUTED, false));
        top.setVisibility(View.GONE);
        root.addView(top, UiKit.lp(this, -1, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollLp.topMargin = UiKit.dp(this, 10);
        root.addView(scroll, scrollLp);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        UiKit.addBottomNavigation(this, root, "service");

        LinearLayout serverCard = UiKit.card(this, 15);
        content.addView(serverCard, UiKit.lp(this, -1, -2));
        LinearLayout serverHeader = UiKit.horizontal(this);
        TextView serverIcon = UiKit.icon(this, 0xf233, 22, UiKit.GREEN);
        serverHeader.addView(serverIcon, UiKit.lp(this, 36, 36));
        LinearLayout serverNames = new LinearLayout(this);
        serverNames.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams namesLp = new LinearLayout.LayoutParams(0, -2, 1);
        namesLp.leftMargin = UiKit.dp(this, 9);
        serverHeader.addView(serverNames, namesLp);
        serverNames.addView(UiKit.label(this, "MCP Server", 15, UiKit.TEXT, true));
        serverState = UiKit.label(this, "Memeriksa status...", 12, UiKit.MUTED, false);
        serverNames.addView(serverState);
        serverCard.addView(serverHeader);
        serverAction = UiKit.action(this, 0xf04b, "Mulai server", true);
        LinearLayout.LayoutParams actionLp = UiKit.lp(this, -1, 43);
        actionLp.topMargin = UiKit.dp(this, 12);
        serverCard.addView(serverAction, actionLp);
        serverAction.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (service != null) { if (service.isServerRunning()) service.stopMcpServer(); else { requestStartedService(); service.startMcpServer(); } refresh(); } } });
        addCopyRow(serverCard, "Endpoint lokal", "http://127.0.0.1:" + McpServer.PORT + "/mcp", false);
        addCopyRow(serverCard, "Bearer token (untuk mode autentikasi)", ToolSettings.token(this), true);

        LinearLayout authCard = UiKit.card(this, 14);
        LinearLayout.LayoutParams authCardLp = UiKit.lp(this, -1, -2);
        authCardLp.topMargin = UiKit.dp(this, 10);
        content.addView(authCard, authCardLp);
        LinearLayout authRow = UiKit.horizontal(this);
        authCard.addView(authRow, UiKit.lp(this, -1, -2));
        LinearLayout authText = new LinearLayout(this);
        authText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams authTextLp = new LinearLayout.LayoutParams(0, -2, 1);
        authTextLp.rightMargin = UiKit.dp(this, 8);
        authRow.addView(authText, authTextLp);
        authText.addView(UiKit.label(this, "Autentikasi Bearer Token", 14, UiKit.TEXT, true));
        authText.addView(UiKit.label(this, "Opsional · nonaktif secara default", 11, UiKit.MUTED, false));
        Switch authSwitch = new Switch(this);
        authSwitch.setText("");
        authSwitch.setChecked(ToolSettings.authEnabled(this));
        authSwitch.setContentDescription("Aktifkan autentikasi Bearer Token");
        authRow.addView(authSwitch, UiKit.lp(this, -2, -2));
        final TextView authNote = UiKit.label(this, authSwitch.isChecked()
            ? "Bearer Token diwajibkan untuk request MCP. Isi header Authorization: Bearer <token> pada klien yang mendukungnya."
            : "Tanpa token, klien AI bisa terhubung tanpa header Authorization. Jangan buka tunnel publik tanpa kontrol akses jika endpoint tidak dilindungi.", 11, UiKit.MUTED, false);
        authNote.setLineSpacing(UiKit.dp(this, 2), 1.0f);
        LinearLayout.LayoutParams authNoteLp = UiKit.lp(this, -1, -2);
        authNoteLp.topMargin = UiKit.dp(this, 7);
        authCard.addView(authNote, authNoteLp);
        authSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                ToolSettings.setAuthEnabled(ServiceSettingsActivity.this, checked);
                authNote.setText(checked
                    ? "Bearer Token diwajibkan untuk request MCP. Isi header Authorization: Bearer <token> pada klien yang mendukungnya."
                    : "Autentikasi dimatikan agar klien tanpa header Authorization bisa terhubung. Endpoint yang terekspos melalui tunnel publik tidak terlindungi token.");
            }
        });

        LinearLayout tunnelCard = UiKit.card(this, 15);
        LinearLayout.LayoutParams tunnelLp = UiKit.lp(this, -1, -2);
        tunnelLp.topMargin = UiKit.dp(this, 12);
        content.addView(tunnelCard, tunnelLp);
        LinearLayout tunnelHeader = UiKit.horizontal(this);
        TextView tunnelIcon = UiKit.icon(this, 0xf0c2, 22, UiKit.BLUE);
        tunnelHeader.addView(tunnelIcon, UiKit.lp(this, 36, 36));
        LinearLayout tunnelNames = new LinearLayout(this);
        tunnelNames.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tnLp = new LinearLayout.LayoutParams(0, -2, 1);
        tnLp.leftMargin = UiKit.dp(this, 9);
        tunnelHeader.addView(tunnelNames, tnLp);
        tunnelNames.addView(UiKit.label(this, "Cloudflare Tunnel", 15, UiKit.TEXT, true));
        tunnelState = UiKit.label(this, "Menunggu status...", 12, UiKit.MUTED, false);
        tunnelNames.addView(tunnelState);
        tunnelCard.addView(tunnelHeader);
        publicUrl = UiKit.label(this, "URL publik belum tersedia", 12, UiKit.MUTED, false);
        publicUrl.setTextIsSelectable(true);
        LinearLayout.LayoutParams publicLp = UiKit.lp(this, -1, -2);
        publicLp.topMargin = UiKit.dp(this, 10);
        tunnelCard.addView(publicUrl, publicLp);
        LinearLayout copyTunnel = UiKit.action(this, 0xf0c5, "Salin URL tunnel", false);
        LinearLayout.LayoutParams ctLp = UiKit.lp(this, -1, 42);
        ctLp.topMargin = UiKit.dp(this, 8);
        tunnelCard.addView(copyTunnel, ctLp);
        copyTunnel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (service != null && service.getPublicUrl().length() > 0) copy(service.getPublicUrl(), "URL tunnel"); else Toast.makeText(ServiceSettingsActivity.this, "Tunnel belum mendapat URL", Toast.LENGTH_SHORT).show(); } });
        tunnelAction = UiKit.action(this, 0xf021, "Mulai tunnel", true);
        LinearLayout.LayoutParams taLp = UiKit.lp(this, -1, 43);
        taLp.topMargin = UiKit.dp(this, 8);
        tunnelCard.addView(tunnelAction, taLp);
        tunnelAction.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (service == null) return;
                if (!service.isTunnelRequested()) {
                    requestStartedService();
                    service.startTunnel();
                } else if (service.getTunnelError().length() > 0 && !service.isTunnelRunning() && service.getPublicUrl().length() == 0) {
                    // Kalau percobaan sebelumnya gagal, ketukan berikutnya mencoba lagi.
                    service.stopTunnel();
                    service.startTunnel();
                } else {
                    service.stopTunnel();
                }
                refresh();
            }
        });

        LinearLayout vivoCard = UiKit.card(this, 15);
        LinearLayout.LayoutParams vivoLp = UiKit.lp(this, -1, -2);
        vivoLp.topMargin = UiKit.dp(this, 12);
        content.addView(vivoCard, vivoLp);
        vivoCard.addView(UiKit.label(this, "Ketahanan background · Vivo", 16, UiKit.TEXT, true));
        TextView note = UiKit.label(this, "Untuk Vivo, aktifkan Autostart untuk Rh Browser, izinkan penggunaan baterai/background tanpa pembatasan, lalu kunci aplikasi di Recent Apps bila opsi itu tersedia. Setelah mengubah pengaturan, kembali ke sini dan mulai ulang server. Nama menu dapat berbeda antar versi FuntouchOS.", 12, UiKit.MUTED, false);
        note.setLineSpacing(UiKit.dp(this, 2), 1.0f);
        LinearLayout.LayoutParams noteLp = UiKit.lp(this, -1, -2);
        noteLp.topMargin = UiKit.dp(this, 5);
        vivoCard.addView(note, noteLp);
        LinearLayout battery = UiKit.action(this, 0xf240, "Izinkan pengecualian baterai", false);
        LinearLayout.LayoutParams batLp = UiKit.lp(this, -1, 43);
        batLp.topMargin = UiKit.dp(this, 12);
        vivoCard.addView(battery, batLp);
        battery.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openBatterySettings(); } });
        LinearLayout autostart = UiKit.action(this, 0xf0e7, "Pengaturan Autostart Vivo", false);
        LinearLayout.LayoutParams autoLp = UiKit.lp(this, -1, 43);
        autoLp.topMargin = UiKit.dp(this, 8);
        vivoCard.addView(autostart, autoLp);
        autostart.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openVivoAutostart(); } });
        batteryState = UiKit.label(this, "", 11, UiKit.MUTED, false);
        LinearLayout.LayoutParams bsLp = UiKit.lp(this, -1, -2);
        bsLp.topMargin = UiKit.dp(this, 7);
        vivoCard.addView(batteryState, bsLp);
    }

    private void addCopyRow(LinearLayout parent, String title, String value, final boolean isToken) {
        LinearLayout row = UiKit.horizontal(this);
        LinearLayout.LayoutParams rowLp = UiKit.lp(this, -1, -2);
        rowLp.topMargin = UiKit.dp(this, 12);
        parent.addView(row, rowLp);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams namesLp = new LinearLayout.LayoutParams(0, -2, 1);
        row.addView(names, namesLp);
        names.addView(UiKit.label(this, title, 11, UiKit.MUTED, false));
        final TextView valueView = UiKit.label(this, value, 12, UiKit.TEXT, true);
        valueView.setTextIsSelectable(true);
        names.addView(valueView);
        TextView copy = UiKit.iconButton(this, 0xf0c5, "Salin " + title);
        row.addView(copy, UiKit.lp(this, 40, 40));
        copy.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { copy(isToken ? ToolSettings.token(ServiceSettingsActivity.this) : "http://127.0.0.1:" + McpServer.PORT + "/mcp", isToken ? "Bearer token" : "Endpoint lokal"); } });
        if (isToken) tokenView = valueView;
        else localUrl = valueView;
    }

    private void refresh() {
        boolean running = service != null && service.isServerRunning();
        if (serverState != null) {
            serverState.setText(running ? "Berjalan di port " + McpServer.PORT : "Server sedang berhenti");
            serverState.setTextColor(running ? UiKit.GREEN : UiKit.MUTED);
            serverAction.setBackground(running ? UiKit.shape(0xFF3B2030, 16, 0xFF7F344A, 1, this) : UiKit.gradient(UiKit.BLUE, UiKit.BLUE_DARK, 16, this));
            serverAction.setContentDescription(running ? "Stop server" : "Start server");
            UiKit.setActionText(serverAction, running ? "Hentikan server" : "Mulai server");
            if (localUrl != null) localUrl.setText("http://127.0.0.1:" + McpServer.PORT + "/mcp");
        }
        if (tunnelState != null) {
            String pub = service == null ? "" : service.getPublicUrl();
            String err = service == null ? "" : service.getTunnelError();
            boolean requested = service != null && service.isTunnelRequested();
            tunnelState.setText(pub.length() > 0 ? "Terhubung" : (err.length() > 0 ? err : (requested ? "Menghubungkan..." : "Tidak aktif · nyalakan manual")));
            tunnelState.setTextColor(pub.length() > 0 ? UiKit.GREEN : UiKit.MUTED);
            publicUrl.setText(pub.length() > 0 ? pub : "URL publik akan muncul setelah tunnel dinyalakan manual.");
            String actionText = !requested ? "Mulai tunnel" : ((err.length() > 0 && service != null && !service.isTunnelRunning() && pub.length() == 0) ? "Coba lagi" : "Hentikan tunnel");
            tunnelAction.setContentDescription(actionText);
            UiKit.setActionText(tunnelAction, actionText);
        }
        if (batteryState != null && Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            boolean ignored = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            batteryState.setText("Optimasi baterai: " + (ignored ? "diabaikan" : "belum diizinkan") + " · Autostart Vivo perlu diperiksa manual.");
            batteryState.setTextColor(ignored ? UiKit.GREEN : UiKit.MUTED);
        }
    }

    private void requestStartedService() {
        try {
            Intent i = new Intent(this, BrowserService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            Toast.makeText(this, "Tidak bisa meminta service berjalan: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void copy(String text, String label) {
        ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, label + " disalin", Toast.LENGTH_SHORT).show();
    }

    private void openBatterySettings() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
                if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Toast.makeText(this, "Pengecualian baterai sudah aktif", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                request.setData(Uri.parse("package:" + getPackageName()));
                startActivity(request);
            } else {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        } catch (Exception e) {
            openAppInfo("Pengaturan baterai tidak tersedia; buka info aplikasi.");
        }
    }

    private void openVivoAutostart() {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            openAppInfo("Menu Autostart Vivo tidak ditemukan pada versi sistem ini. Periksa pengelolaan background dari pengaturan aplikasi.");
        }
    }

    private void openAppInfo(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) { }
    }

    protected void onResume() { super.onResume(); handler.removeCallbacks(poll); handler.post(poll); }
    protected void onPause() { handler.removeCallbacks(poll); super.onPause(); }
    protected void onDestroy() { handler.removeCallbacks(poll); if (bound) { try { unbindService(connection); } catch (Exception ignored) { } bound = false; } super.onDestroy(); }
}
