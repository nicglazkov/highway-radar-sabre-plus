package app.sabre.wzsabre;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * WorkManager worker that starts SabreService as a foreground service.
 *
 * Used as a fallback when startForegroundService() is denied from a broadcast receiver
 * (Android 12+ background FGS start restrictions).  WorkManager expedited tasks bypass
 * these restrictions and can reliably start foreground services even when the app is
 * in the background or its process was frozen.
 *
 * Mirrors the official wzsabre's WorkManager fallback (last resort in
 * ForegroundServiceStarter's escalation chain).
 */
public class ServiceStartWorker extends Worker {
    private static final String TAG = "ServiceStartWorker";

    public static final String KEY_ACTION = "extra_action";
    public static final String KEY_DATA   = "extra_data";

    public ServiceStartWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String action = getInputData().getString(KEY_ACTION);
        String data   = getInputData().getString(KEY_DATA);

        Intent svc = new Intent(context, SabreService.class);
        if (action != null) svc.putExtra("action", action);
        if (data   != null) svc.putExtra("data",   data);

        try {
            // Expedited WorkManager jobs run with a brief FGS-start grant, so
            // startForegroundService() is allowed here. SabreService promotes itself
            // to foreground via startForeground() in onCreate().
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
            Log.d(TAG, "Started SabreService for action: " + action);
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start SabreService: " + e.getMessage());
            return Result.failure();
        }
    }
}
