package app.sabre.wzsabre;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts SabreService automatically after the device boots or the app is updated.
 *
 * Without this, users must open the app manually after every reboot (or update)
 * before Highway Radar can reach the plugin. BOOT_COMPLETED and
 * MY_PACKAGE_REPLACED are explicit exemptions from Android's background FGS
 * start restrictions, so startForegroundService() is allowed here even on
 * Android 12-15.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "SABREBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        Log.d(TAG, action + ": starting SabreService");
        // Both actions are exempt from BFSL, but route through the starter so the
        // exact-alarm / WorkManager fallbacks apply if the direct start is denied.
        ForegroundServiceStarter.start(context, null, null);
    }
}
