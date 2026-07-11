package app.sabre.wzsabre;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "SABREProxy";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Received action: " + action);
        try {
            if ("app.sabre.HANDSHAKE".equals(action) || "app.sabre.wzsabre.HANDSHAKE".equals(action)) {
                handleHandshake(context, intent);
            } else if (action != null && action.endsWith("REQUEST")) {
                // Accept BOTH our own FETCH_REQUEST and the official wzsabre's REQUEST.
                // Highway Radar caches a plugin registration by package id: a user who
                // had the official wzsabre first leaves HR firing app.sabre.wzsabre.REQUEST
                // at us forever (it never re-discovers the same package), so we must
                // listen for it or that user gets zero data.
                DebugLog.noteFetchAction(action);
                ForegroundServiceStarter.start(context, "FETCH_REQUEST", intent.getStringExtra("data"));
            } else if (action != null && action.contains("SHUTDOWN")) {
                // HR is ending the session (it usually reopens one shortly). Forward
                // to the service so it stops after a short grace period; a new session
                // within the window cancels the stop. If the service isn't running
                // there's nothing to shut down, so we don't start it just to stop it.
                Log.d(TAG, "Shutdown received — forwarding to service");
                if (SabreService.RUNNING) {
                    try {
                        context.startService(new Intent(context, SabreService.class)
                                .putExtra("action", "SHUTDOWN"));
                    } catch (Exception e) {
                        Log.w(TAG, "Could not forward shutdown: " + e.getMessage());
                    }
                }
            } else if (BuildConfig.DEBUG && action != null && action.endsWith(".WAZE_TEST")) {
                // Debug-only: exercise the Waze RT protocol (register -> login -> query).
                final double lat = numberExtra(intent, "lat", 37.8044);
                final double lon = numberExtra(intent, "lon", -122.2712);
                new Thread(() -> app.sabre.wzsabre.waze.WazeProtocolSource.selfTest(lat, lon)).start();
            } else if (BuildConfig.DEBUG && action != null && action.endsWith(".INJECT_TEST")) {
                // Debug-only: toggle synthetic one-of-each-type alerts to validate HR rendering.
                SabreService.injectTestAlerts = intent.getBooleanExtra("on", true);
                if (intent.hasExtra("lat")) SabreService.testLat = numberExtra(intent, "lat", SabreService.testLat);
                if (intent.hasExtra("lon")) SabreService.testLon = numberExtra(intent, "lon", SabreService.testLon);
                Log.d(TAG, "injectTestAlerts=" + SabreService.injectTestAlerts
                        + " @ " + SabreService.testLat + "," + SabreService.testLon);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling broadcast", e);
        }
    }

    /**
     * Reads a numeric extra regardless of how it was typed: adb's --ef sends a
     * Float, --ed a Double — getDoubleExtra() silently returns the default for a
     * Float extra, which made the documented --ef test commands no-ops.
     */
    private static double numberExtra(Intent intent, String key, double dflt) {
        Object v = intent.getExtras() != null ? intent.getExtras().get(key) : null;
        return (v instanceof Number) ? ((Number) v).doubleValue() : dflt;
    }

    private void handleHandshake(Context context, Intent intent) throws Exception {
        Log.d(TAG, "Handling handshake");
        DebugLog.handshakeReceived();
        String rawData = intent.getStringExtra("data");
        String responseAction = null;
        if (rawData != null) {
            try {
                JSONObject req = new JSONObject(rawData);
                responseAction = req.optString("response_action", null);
                Log.d(TAG, "Discovery request: " + rawData);
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse discovery request JSON: " + e.getMessage());
            }
        }
        if (responseAction == null)
            responseAction = intent.getStringExtra("response_action");
        if (responseAction == null) {
            Log.e(TAG, "No response_action in handshake — cannot respond");
            return;
        }
        String pkg = context.getPackageName();
        JSONObject response = new JSONObject();
        response.put("id",           pkg);
        response.put("name",         "SABRE Plus (CHP, Waze, Caltrans)");
        response.put("package_name", pkg);
        response.put("version",      BuildConfig.VERSION_NAME);
        JSONArray sources = new JSONArray();
        JSONObject s1 = new JSONObject(); s1.put("id", "chp");  s1.put("name", "CHP Live Feed");      sources.put(s1);
        JSONObject s2 = new JSONObject(); s2.put("id", "waze"); s2.put("name", "Waze");               sources.put(s2);
        JSONObject s3 = new JSONObject(); s3.put("id", "lcs");  s3.put("name", "Caltrans Closures");  sources.put(s3);
        JSONObject s4 = new JSONObject(); s4.put("id", "fire"); s4.put("name", "Wildfires");          sources.put(s4);
        JSONObject s5 = new JSONObject(); s5.put("id", "chains"); s5.put("name", "Chain Controls");   sources.put(s5);
        response.put("supported_sources", sources);
        // Advertise the SAME action names the official wzsabre uses, so every HR
        // (whether it re-discovers us or keeps a cached wzsabre registration) fires
        // the identical actions we listen for. We also still listen for our older
        // FETCH_REQUEST/SUBMIT_REPORT names for any HR that cached those.
        response.put("request_action",  "app.sabre.wzsabre.REQUEST");
        response.put("report_action",   "app.sabre.wzsabre.REPORT");
        response.put("confirm_action",  "app.sabre.wzsabre.CONFIRM");
        response.put("discard_action",  "app.sabre.wzsabre.DISCARD");
        response.put("shutdown_action", "app.sabre.wzsabre.SHUTDOWN");
        // alternative_startup_activity lets HR launch us to the foreground so the
        // service can start without hitting Android 15/16 BFSL restrictions. This is
        // the last field of HR 3.2's SabreDiscoveryResponse model. Do NOT re-add the
        // old wzsabre 2.x "update_url" field: HR 3.2 dropped it and parses the
        // discovery response strictly, so an extra key breaks discovery.
        response.put("alternative_startup_activity", pkg + ".AltStartupActivity");
        Intent resp = new Intent(responseAction);
        resp.setPackage(SabreResponseBuilder.HR_PACKAGE);   // deliver only to HR
        resp.putExtra("data", response.toString());
        context.sendBroadcast(resp);
        Log.d(TAG, "Handshake response sent to: " + responseAction + " data: " + response);

        // Pre-warm SabreService so it is already initialized when the first
        // FETCH_REQUEST arrives. Without this, a cold-start service has to
        // initialize OkHttp, the Waze session, etc. while HR is already
        // waiting for a response, causing the "plugin not responding" error.
        ForegroundServiceStarter.start(context, null, null);
    }
}
