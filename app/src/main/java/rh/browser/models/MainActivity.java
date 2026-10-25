package rh.browser.models;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private BrowserService service;
    private boolean bound;
    private FrameLayout webContainer;
    private EditText address;
    private WebView webView;
    private LinearLayout root;
    private final Handler handler = new Handler();
    private final Runnable poll = new Runnable() {
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1200L);
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((BrowserService.LocalBinder)binder).getService();
            bound = true;
            attachBrowser();
            refreshStatus();
        }
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            webView = null;
            Toast.makeText(MainActivity.this, "Koneksi service terputus", Toast.LENGTH_SHORT).show();
        }
    };

    private void applyImmersiveMode() {
        UiKit.applyImmersive(this);
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(UiKit.BG);
        getWindow().setNavigationBarColor(UiKit.BG);
        applyImmersiveMode();
        ToolSettings.initializeServerPreference(this);
        buildUi();
        Intent i = new Intent(this, BrowserService.class);
        try {
            if (ToolSettings.serverEnabled(this)) {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            }
            bindService(i, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Toast.makeText(this, "Service browser gagal dimulai: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        requestNotificationPermissionIfNeeded();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 9011);
        }
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);
        root.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 8), UiKit.dp(this, 12), UiKit.dp(this, 5));
        setContentView(root);

        // Browser surface starts at the top: no branding/title strip.
        LinearLayout urlRow = UiKit.horizontal(this);
        urlRow.setBackground(UiKit.shape(UiKit.SURFACE, 17, UiKit.BORDER, 1, this));
        urlRow.setPadding(UiKit.dp(this, 11), 0, UiKit.dp(this, 5), 0);
        LinearLayout.LayoutParams urlLp = UiKit.lp(this, -1, 52);
        urlLp.topMargin = 0;
        root.addView(urlRow, urlLp);
        TextView lock = UiKit.icon(this, 0xf0ac, 15, UiKit.BLUE);
        urlRow.addView(lock, UiKit.lp(this, 22, 34));
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(13);
        address.setTextColor(UiKit.TEXT);
        address.setHintTextColor(UiKit.MUTED);
        address.setHint("Cari atau masukkan URL");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setPadding(UiKit.dp(this, 5), 0, UiKit.dp(this, 4), 0);
        address.setBackgroundColor(Color.TRANSPARENT);
        urlRow.addView(address, new LinearLayout.LayoutParams(0, -1, 1));
        TextView copy = UiKit.iconButton(this, 0xf0c5, "Salin URL");
        LinearLayout.LayoutParams copyLp = UiKit.lp(this, 40, 40);
        copyLp.rightMargin = UiKit.dp(this, 5);
        urlRow.addView(copy, copyLp);
        copy.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { copyCurrentUrl(); } });
        TextView go = UiKit.iconButton(this, 0xf061, "Buka URL");
        go.setBackground(UiKit.gradient(UiKit.BLUE, UiKit.BLUE_DARK, 13, this));
        urlRow.addView(go, UiKit.lp(this, 40, 40));
        go.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { navigateAddress(); } });
        address.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int action, KeyEvent event) {
                if (action == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    navigateAddress();
                    return true;
                }
                return false;
            }
        });

        LinearLayout controls = UiKit.horizontal(this);
        LinearLayout.LayoutParams controlsLp = UiKit.lp(this, -1, 43);
        controlsLp.topMargin = UiKit.dp(this, 8);
        root.addView(controls, controlsLp);
        addBrowserControl(controls, 0xf060, "Kembali", new View.OnClickListener() { public void onClick(View v) { if (webView != null && webView.canGoBack()) webView.goBack(); } });
        addBrowserControl(controls, 0xf061, "Maju", new View.OnClickListener() { public void onClick(View v) { if (webView != null && webView.canGoForward()) webView.goForward(); } });
        addBrowserControl(controls, 0xf2f1, "Muat ulang", new View.OnClickListener() { public void onClick(View v) { if (webView != null) webView.reload(); } });
        addBrowserControl(controls, 0xf019, "Download", new View.OnClickListener() { public void onClick(View v) { UiKit.openTab(MainActivity.this, DownloadsActivity.class); } });
        addBrowserControl(controls, 0xf1de, "Tool AI", new View.OnClickListener() { public void onClick(View v) { UiKit.openTab(MainActivity.this, ToolSettingsActivity.class); } });

        webContainer = new FrameLayout(this);
        webContainer.setBackground(UiKit.shape(0xFFFFFFFF, 16, UiKit.BORDER, 1, this));
        webContainer.setClipToOutline(true);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(-1, 0, 1);
        webLp.topMargin = UiKit.dp(this, 8);
        webLp.bottomMargin = UiKit.dp(this, 7);
        root.addView(webContainer, webLp);

        UiKit.addBottomNavigation(this, root, "browser");
    }

    private void addBrowserControl(LinearLayout parent, int iconCode, String label, View.OnClickListener listener) {
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER);
        item.setBackground(UiKit.shape(UiKit.CARD, 13, UiKit.BORDER, 1, this));
        TextView icon = UiKit.icon(this, iconCode, 16, UiKit.TEXT);
        item.addView(icon, UiKit.lp(this, -1, -1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.leftMargin = UiKit.dp(this, 3);
        lp.rightMargin = UiKit.dp(this, 3);
        parent.addView(item, lp);
        item.setContentDescription(label);
        item.setOnClickListener(listener);
    }

    private void addNav(LinearLayout parent, int iconCode, String label, View.OnClickListener listener, boolean active) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        if (active) item.setBackground(UiKit.shape(0xFF14385C, 14, 0, 0, this));
        TextView icon = UiKit.icon(this, iconCode, 16, active ? UiKit.BLUE : UiKit.MUTED);
        item.addView(icon, UiKit.lp(this, -1, 21));
        TextView text = UiKit.label(this, label, 10, active ? UiKit.TEXT : UiKit.MUTED, active);
        text.setGravity(Gravity.CENTER);
        item.addView(text, UiKit.lp(this, -1, 17));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.leftMargin = UiKit.dp(this, 2);
        lp.rightMargin = UiKit.dp(this, 2);
        parent.addView(item, lp);
        item.setOnClickListener(listener);
    }

    private void attachBrowser() {
        if (service == null || webContainer == null) return;
        try {
            WebView next = service.getBrowserView();
            if (next == null) return;
            android.view.ViewParent parent = next.getParent();
            if (parent instanceof ViewGroup && parent != webContainer) ((ViewGroup)parent).removeView(next);
            if (next.getParent() == null) webContainer.addView(next, new FrameLayout.LayoutParams(-1, -1));
            webView = next;
            String url = next.getUrl();
            if (url != null && url.length() > 0 && !address.hasFocus()) address.setText(url);
        } catch (Exception e) {
            Toast.makeText(this, "WebView gagal dipasang: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void navigateAddress() {
        String input = address.getText().toString().trim();
        if (input.length() == 0) return;

        // Any whitespace remaining after trim() is inside the query, so search
        // even when the text starts with an HTTP/HTTPS scheme.
        if (containsInternalWhitespace(input)) {
            openAddressTarget(buildGoogleSearchUrl(input));
            return;
        }

        String url = resolveAddress(input);
        if (url == null) {
            openAddressTarget(buildGoogleSearchUrl(input));
        } else {
            openAddressTarget(url);
        }
    }

    private boolean containsInternalWhitespace(String value) {
        int i;
        for (i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) return true;
        }
        return false;
    }

    private String buildGoogleSearchUrl(String query) {
        return "https://www.google.com/search?q=" + android.net.Uri.encode(query);
    }

    private void openAddressTarget(String url) {
        if (webView == null) attachBrowser();
        if (webView != null) {
            address.setText(url);
            webView.loadUrl(url);
            focusAddress(false);
        }
    }

    private String resolveAddress(String input) {
        boolean hasHttpScheme = input.matches("(?i)^https?://.*");
        try {
            if (hasHttpScheme) {
                java.net.URI parsed = new java.net.URI(input);
                String scheme = parsed.getScheme();
                String host = parsed.getHost();
                if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return null;
                if (!isValidHost(host, true) || !isValidPort(parsed.getPort())) return null;
                return input;
            }

            // Test the input as a possible host, domain, IP address, and optional port/path.
            java.net.URI parsed = new java.net.URI("https://" + input);
            String host = parsed.getHost();
            if (!isValidHost(host, false) || !isValidPort(parsed.getPort())) return null;

            // Plain local hosts and IP addresses commonly serve HTTP without TLS.
            if (host.equalsIgnoreCase("localhost") || isIpv4Address(host) || isIpv6Address(host)) {
                return "http://" + input;
            }
            return "https://" + input;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isValidPort(int port) {
        return port == -1 || (port > 0 && port <= 65535);
    }

    private boolean isValidHost(String host, boolean allowSingleLabel) {
        if (host == null || host.length() == 0) return false;

        String value = host;
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (isIpv6Address(value)) return true;
        if (isIpv4Address(value)) return true;
        if (value.matches("[0-9.]+")) return false;
        if (value.equalsIgnoreCase("localhost")) return true;
        if (value.length() > 253) return false;

        // A bare single word such as "mobil" is a search, not a host.
        if (!allowSingleLabel && value.indexOf('.') < 0) return false;
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.length() == 0) return false;

        String[] labels = value.split("\\.", -1);
        int i;
        for (i = 0; i < labels.length; i++) {
            String label = labels[i];
            if (label.length() == 0 || label.length() > 63) return false;
            if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') return false;
            int j;
            for (j = 0; j < label.length(); j++) {
                char c = label.charAt(j);
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-')) return false;
            }
        }
        return true;
    }

    private boolean isIpv4Address(String host) {
        if (host == null) return false;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int i;
        for (i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.length() == 0 || part.length() > 3) return false;
            int j;
            for (j = 0; j < part.length(); j++) {
                if (part.charAt(j) < '0' || part.charAt(j) > '9') return false;
            }
            try {
                int number = Integer.parseInt(part);
                if (number < 0 || number > 255) return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private boolean isIpv6Address(String host) {
        return host != null && host.indexOf(':') >= 0 && host.matches("(?i)^[0-9a-f:.]+$");
    }

    private void focusAddress(boolean focus) {
        if (focus && address != null) {
            address.requestFocus();
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT);
        } else {
            try { ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(), 0); }
            catch (Exception ignored) { }
            if (webView != null) webView.requestFocus();
        }
    }

    private void copyCurrentUrl() {
        String typed = address.getText().toString().trim();
        String url = address.hasFocus() && typed.length() > 0 ? typed : (webView != null ? webView.getUrl() : null);
        if (url == null || url.length() == 0) url = typed;
        if (url.length() == 0) { Toast.makeText(this, "Belum ada URL untuk disalin", Toast.LENGTH_SHORT).show(); return; }
        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Browser URL", url));
        Toast.makeText(this, "URL disalin", Toast.LENGTH_SHORT).show();
    }

    private void refreshStatus() {
        if (service == null) return;
        if (webView != null && !address.hasFocus()) {
            String current = webView.getUrl();
            if (current != null && current.length() > 0 && !current.equals(address.getText().toString())) address.setText(current);
        }
    }

    protected void onResume() {
        super.onResume();
        if (bound && (webView == null || webContainer == null || webView.getParent() != webContainer)) attachBrowser();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    protected void onDestroy() {
        handler.removeCallbacks(poll);
        if (webView != null) {
            try {
                android.view.ViewParent p = webView.getParent();
                if (p instanceof ViewGroup) ((ViewGroup)p).removeView(webView);
            } catch (Exception ignored) { }
        }
        if (bound) { try { unbindService(connection); } catch (Exception ignored) { } bound = false; }
        super.onDestroy();
    }
}
