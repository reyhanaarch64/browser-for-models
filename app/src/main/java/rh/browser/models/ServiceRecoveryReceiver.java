package rh.browser.models;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** Best-effort watchdog. Vivo autostart and battery settings are still required. */
public class ServiceRecoveryReceiver extends BroadcastReceiver {
    private static final String TAG = "RhBrowserRecovery";
    private static final int REQUEST_CODE = 9077;
    private static final long NORMAL_RETRY_MS = 10L * 60L * 1000L;
    private static final long FAILURE_RETRY_MS = 60L * 1000L;

    public void onReceive(Context context, Intent intent) {
        if (context == null || !ToolSettings.serverEnabled(context)) {
            if (context != null) cancel(context);
            return;
        }
        try {
            /* Keep a future check armed even if a background start is denied. */
            schedule(context, NORMAL_RETRY_MS);
            Intent serviceIntent = new Intent(context, BrowserService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent);
            else context.startService(serviceIntent);
        } catch (Throwable t) {
            Log.e(TAG, "Gagal memulihkan BrowserService", t);
            schedule(context, FAILURE_RETRY_MS);
        }
    }

    public static void schedule(Context context, long delayMs) {
        if (context == null || delayMs < 0L || !ToolSettings.serverEnabled(context)) {
            if (context != null) cancel(context);
            return;
        }
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, ServiceRecoveryReceiver.class);
        PendingIntent pi = pending(context, i);
        long when = System.currentTimeMillis() + Math.max(3000L, delayMs);
        try {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            else am.set(AlarmManager.RTC_WAKEUP, when, pi);
        } catch (Throwable t) {
            Log.e(TAG, "Gagal menjadwalkan recovery", t);
        }
    }

    public static void cancel(Context context) {
        if (context == null) return;
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, ServiceRecoveryReceiver.class);
        PendingIntent pi = pending(context, i);
        try { am.cancel(pi); } catch (Throwable ignored) { }
        try { pi.cancel(); } catch (Throwable ignored) { }
    }

    private static PendingIntent pending(Context context, Intent intent) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }
}
