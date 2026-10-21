package rh.browser.models;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    public void onReceive(Context context, Intent intent) {
        if (context == null || !ToolSettings.serverEnabled(context)) return;
        try {
            Intent i = new Intent(context, BrowserService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
            else context.startService(i);
            ServiceRecoveryReceiver.schedule(context, 15L * 60L * 1000L);
        } catch (Throwable t) {
            Log.e("RhBrowserBoot", "Gagal memulai service saat boot/update", t);
            ServiceRecoveryReceiver.schedule(context, 60L * 1000L);
        }
    }
}
