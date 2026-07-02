package app.sabre.wzsabre;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Invisible activity that Highway Radar can launch (it is advertised as the
 * {@code alternative_startup_activity} in our discovery response) to bring the
 * plugin to the foreground.
 *
 * Why this exists: on Android 15/16, starting a foreground service from a
 * background broadcast receiver is BFSL-restricted. But when HR launches this
 * activity, our app is momentarily in the foreground, so starting the service
 * here is always allowed — a reliable escape hatch that complements the
 * exact-alarm bypass in {@link ForegroundServiceStarter}.
 *
 * The activity starts the service, optionally bounces back to HR (if HR passed
 * callback_app/callback_component, mirroring the official plugin), and finishes
 * immediately without showing any UI.
 */
public class AltStartupActivity extends Activity {
    private static final String TAG = "SABREAltStartup";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "Launched — starting service from foreground");

        ForegroundServiceStarter.start(this, null, null);

        // If HR told us how to return to it, do so (matches official 2.2 behaviour).
        // This activity is exported, so restrict the callback target to Highway Radar
        // — otherwise any app could make us launch an arbitrary component (confused
        // deputy) with us as the attributed source.
        try {
            String cbApp  = getIntent().getStringExtra("callback_app");
            String cbComp = getIntent().getStringExtra("callback_component");
            if (cbApp != null && cbComp != null
                    && SabreResponseBuilder.HR_PACKAGE.equals(cbApp)) {
                Intent back = new Intent();
                back.setComponent(new ComponentName(cbApp, cbComp));
                back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(back);
            } else if (cbApp != null) {
                Log.w(TAG, "Ignoring callback to non-HR package: " + cbApp);
            }
        } catch (Exception e) {
            Log.w(TAG, "callback relaunch failed: " + e.getMessage());
        }

        finish();
    }
}
