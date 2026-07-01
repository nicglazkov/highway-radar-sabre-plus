package app.sabre.wzsabre;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;

/**
 * Robustly starts {@link SabreService} as a foreground service, escalating through
 * fallbacks that bypass the Android 12+/15+ background foreground-service-launch
 * (BFSL) restrictions.
 *
 * Why this is needed: on Android 15/16 (useNewBfslLogic), BOTH startService() and
 * startForegroundService() from a background BroadcastReceiver are denied
 * ("Background start not allowed ... caps=-------"), so the service never starts and
 * HR shows "Crowd-Sourced Alert Problems" / "plugin not responding".
 *
 * Strategy (escalating):
 *   1. API &lt; 26  -> startService()
 *   2. API 26-30 -> startForegroundService()
 *   3. API &gt;= 31 -> try startForegroundService(); if BFSL denies it:
 *        a. schedule an IMMEDIATE exact alarm whose PendingIntent is
 *           getForegroundService(). An alarm-triggered FGS start is exempt from
 *           BFSL, so the service starts the instant the alarm fires.
 *        b. otherwise fall back to a WorkManager expedited job.
 */
final class ForegroundServiceStarter {
    private static final String TAG = "FGSStarter";

    private ForegroundServiceStarter() {}

    /** Build the service intent and start it. */
    static void start(Context context, String action, String data) {
        Intent intent = new Intent(context, SabreService.class);
        if (action != null) intent.putExtra("action", action);
        if (data != null)   intent.putExtra("data", data);
        start(context, intent);
    }

    static void start(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) { // < API 31
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
                Log.d(TAG, "Service start succeeded (pre-31)");
            } catch (Exception e) {
                Log.w(TAG, "Service start failed (pre-31): " + e.getMessage());
                startViaWorkManager(context, intent);
            }
            return;
        }

        // API >= 31: BFSL may deny a background FGS start. Escalate.
        try {
            context.startForegroundService(intent);
            Log.d(TAG, "startForegroundService succeeded");
        } catch (Exception e) {
            Log.w(TAG, "startForegroundService denied (BFSL): " + e.getMessage()
                    + " — trying exact-alarm bypass");
            if (tryExactAlarm(context, intent)) {
                Log.d(TAG, "Scheduled exact-alarm FGS start");
                return;
            }
            startViaWorkManager(context, intent);
        }
    }

    /**
     * Schedule an immediate exact alarm that starts the foreground service.
     * An alarm-triggered FGS start carries a temporary background-start exemption,
     * so it succeeds even when a direct startForegroundService() is BFSL-denied.
     * Only ever called from the API&nbsp;&gt;=&nbsp;31 branch of {@link #start}.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private static boolean tryExactAlarm(Context context, Intent intent) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                Log.w(TAG, "Cannot schedule exact alarms — permission not granted");
                return false;
            }
            String action = intent.getStringExtra("action");
            int requestCode = action != null ? action.hashCode() : 0;
            PendingIntent pi = PendingIntent.getForegroundService(
                    context, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), pi);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "Exact alarm SecurityException: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Exact alarm failed: " + e.getMessage());
            return false;
        }
    }

    private static void startViaWorkManager(Context context, Intent intent) {
        try {
            Data input = new Data.Builder()
                    .putString(ServiceStartWorker.KEY_ACTION, intent.getStringExtra("action"))
                    .putString(ServiceStartWorker.KEY_DATA,   intent.getStringExtra("data"))
                    .build();
            OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(ServiceStartWorker.class)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(input)
                    .build();
            WorkManager.getInstance(context).enqueue(work);
            Log.d(TAG, "Enqueued WorkManager fallback");
        } catch (Exception e) {
            Log.e(TAG, "WorkManager fallback failed: " + e.getMessage());
        }
    }
}
