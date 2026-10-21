package rh.browser.models;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;

public final class ToolSettings {
    private static final String PREF = "tool_access";
    private static final String KEY_AUTH = "auth_enabled";
    private static final String KEY_TOKEN = "mcp_token";
    private static final String KEY_SERVER = "server_enabled";

    private ToolSettings() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

public static boolean isEnabled(Context c, ToolDef t) {
    if (t == null) return false;
    String key = "tool." + t.name;
    if (p(c).contains(key)) return p(c).getBoolean(key, t.defaultEnabled);
    if (t.accessMode == ToolDef.ACCESS_READ) {
        String oldKey = "cat." + t.category + ".read";
        if (p(c).contains(oldKey)) return p(c).getBoolean(oldKey, t.defaultEnabled);
    } else if (t.accessMode == ToolDef.ACCESS_WRITE) {
        String oldKey = "cat." + t.category + ".write";
        if (p(c).contains(oldKey)) return p(c).getBoolean(oldKey, t.defaultEnabled);
    }
    return t.defaultEnabled;
}

    public static boolean categoryHasWrite(String category) {
        for (ToolDef t : ToolRegistry.all()) {
            if (category.equals(t.category) && t.accessMode == ToolDef.ACCESS_WRITE) return true;
        }
        return false;
    }

    private static boolean categoryDefaultRead(String category) {
        for (ToolDef t : ToolRegistry.all()) {
            if (category.equals(t.category) && t.accessMode == ToolDef.ACCESS_READ && t.defaultEnabled) return true;
        }
        return false;
    }

    private static boolean categoryDefaultWrite(String category) {
        for (ToolDef t : ToolRegistry.all()) {
            if (category.equals(t.category) && t.accessMode == ToolDef.ACCESS_WRITE && t.defaultEnabled) return true;
        }
        return false;
    }

public static void setTool(Context c, ToolDef t, boolean enabled) {
    if (t == null) return;
    p(c).edit().putBoolean("tool." + t.name, enabled).apply();
}

public static boolean getCategoryRead(Context c, String category) {
    for (ToolDef t : ToolRegistry.all()) {
        if (category.equals(t.category) && t.accessMode == ToolDef.ACCESS_READ && isEnabled(c, t)) return true;
    }
    return false;
}

public static boolean getCategoryWrite(Context c, String category) {
    for (ToolDef t : ToolRegistry.all()) {
        if (category.equals(t.category) && t.accessMode == ToolDef.ACCESS_WRITE && isEnabled(c, t)) return true;
    }
    return false;
}

public static void setCategory(Context c, String category, boolean read, boolean write) {
    SharedPreferences.Editor e = p(c).edit();
    for (ToolDef t : ToolRegistry.all()) {
        if (!category.equals(t.category)) continue;
        if (t.accessMode == ToolDef.ACCESS_READ) e.putBoolean("tool." + t.name, read);
        else if (t.accessMode == ToolDef.ACCESS_WRITE) e.putBoolean("tool." + t.name, write);
    }
    e.apply();
}

public static void enableAll(Context c) {
    SharedPreferences.Editor e = p(c).edit();
    for (ToolDef t : ToolRegistry.all()) e.putBoolean("tool." + t.name, true);
    e.apply();
}

public static void disableAll(Context c) {
    SharedPreferences.Editor e = p(c).edit();
    for (ToolDef t : ToolRegistry.all()) e.putBoolean("tool." + t.name, false);
    e.apply();
}

    public static boolean authEnabled(Context c) {
        return p(c).getBoolean(KEY_AUTH, false);
    }

    public static void setAuthEnabled(Context c, boolean value) {
        p(c).edit().putBoolean(KEY_AUTH, value).apply();
    }

    public static String token(Context c) {
        String token = p(c).getString(KEY_TOKEN, null);
        if (token == null || token.length() < 16) {
            token = generateToken();
            p(c).edit().putString(KEY_TOKEN, token).apply();
        }
        return token;
    }

    public static String regenerateToken(Context c) {
        String token = generateToken();
        p(c).edit().putString(KEY_TOKEN, token).apply();
        return token;
    }

    private static String generateToken() {
        return java.util.UUID.randomUUID().toString().replace("-", "")
                + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    public static boolean serverEnabled(Context c) {
        return p(c).getBoolean(KEY_SERVER, false);
    }

    public static boolean hasServerPreference(Context c) {
        return p(c).contains(KEY_SERVER);
    }

    public static void initializeServerPreference(Context c) {
        SharedPreferences prefs = p(c);
        if (!prefs.contains(KEY_SERVER)) {
            prefs.edit().putBoolean(KEY_SERVER, true).apply();
        }
    }

    public static void setServerEnabled(Context c, boolean enabled) {
        p(c).edit().putBoolean(KEY_SERVER, enabled).apply();
    }
}
