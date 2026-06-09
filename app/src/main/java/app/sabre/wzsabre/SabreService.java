package app.sabre.wzsabre;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SabreService extends Service {
    private static final String TAG = "SABREService";
    private static final String CHANNEL_ID = "SabreServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    private ExecutorService executor;
    private CHPSource chpSource;
    private app.sabre.wzsabre.waze.WazeProtocolSource wazeSource;

    @Override
    public void onCreate() {
        super.onCreate();
        executor   = Executors.newFixedThreadPool(4);
        chpSource  = new CHPSource();
        wazeSource = new app.sabre.wzsabre.waze.WazeProtocolSource(this);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildForegroundNotification());
        Log.d(TAG, "Service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getStringExtra("action");
        String data   = intent.getStringExtra("data");
        if (action == null) return START_STICKY;
        if (action.contains("FETCH_REQUEST")) handleFetchRequest(data);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    // Hard deadline: respond to HR within this many ms regardless of Waze status.
    // HR shows "Crowd-Sourced Alert Problems" if we exceed its internal timeout.
    private static final long RESPONSE_BUDGET_MS = 8_000;

    private void handleFetchRequest(String data) {
        executor.submit(() -> {
            try {
                JSONObject req = new JSONObject(data);
                String requestId      = req.getString("request_id");
                String responseAction = req.getString("response_action");
                double lat    = req.has("lat")      ? req.getDouble("lat")      : req.getDouble("latitude");
                double lon    = req.has("lon")      ? req.getDouble("lon")      : req.getDouble("longitude");
                double radius = req.has("radius_m") ? req.getDouble("radius_m") : req.getDouble("radius");

                Log.d(TAG, String.format("Fetch: lat=%.4f lon=%.4f radius=%.0fm", lat, lon, radius));

                long deadline = System.currentTimeMillis() + RESPONSE_BUDGET_MS;

                // Load config fresh on each request so changes take effect immediately
                ChpConfig chpConfig = ChpConfig.load(SabreService.this);

                // CHP and Waze run in parallel (4-thread pool leaves 2 threads free here)
                Future<List<SabreAlert>> chpFuture  = executor.submit(() -> chpSource.fetchAlerts(lat, lon, radius, chpConfig));
                Future<List<SabreAlert>> wazeFuture = executor.submit(() -> wazeSource.fetchAlerts(lat, lon, radius));

                List<SabreAlert> allAlerts = new ArrayList<>();

                // CHP is always fast (~0.5 s); give it up to 5 s just in case
                try {
                    allAlerts.addAll(chpFuture.get(5, TimeUnit.SECONDS));
                    Log.d(TAG, "CHP: " + allAlerts.size() + " alerts");
                } catch (TimeoutException e) {
                    Log.w(TAG, "CHP timed out");
                    chpFuture.cancel(true);
                }

                // Give Waze whatever budget remains; cancel if exceeded
                long wazeMs = deadline - System.currentTimeMillis();
                if (wazeMs > 500) {
                    try {
                        List<SabreAlert> wazeAlerts = wazeFuture.get(wazeMs, TimeUnit.MILLISECONDS);
                        allAlerts.addAll(wazeAlerts);
                        Log.d(TAG, "Waze: " + wazeAlerts.size() + " alerts");
                    } catch (TimeoutException e) {
                        Log.w(TAG, "Waze exceeded budget — sending without Waze data");
                        wazeFuture.cancel(true);
                    }
                } else {
                    Log.w(TAG, "No budget left for Waze");
                    wazeFuture.cancel(true);
                }

                if (BuildConfig.DEBUG && injectTestAlerts) {
                    allAlerts.addAll(buildTestAlerts(testLat, testLon));
                }

                Log.d(TAG, "Sending " + allAlerts.size() + " total alerts");
                sendFetchResponse(responseAction, requestId, allAlerts);
            } catch (Exception e) {
                Log.e(TAG, "Error handling fetch request", e);
            }
        });
    }

    /** Debug-only: toggled by the INJECT_TEST broadcast to validate HR's rendering of every type. */
    public static volatile boolean injectTestAlerts = false;
    /** Fixed anchor (set by the INJECT_TEST broadcast) the synthetic alerts cluster around. */
    public static volatile double testLat = 38.4015, testLon = -121.8000;

    /**
     * One synthetic alert of each SABRE type, lined up ~120m north of (lat,lon) and
     * spread east-west, so driving north makes Highway Radar pop them all as stacked
     * cards — used to visually confirm HR renders every alert type correctly.
     */
    private List<SabreAlert> buildTestAlerts(double lat, double lon) {
        String[][] specs = {
            {"POLICE_VISIBLE",            "TEST Police visible"},
            {"POLICE_HIDDEN",             "TEST Police hidden (speed trap)"},
            {"ACCIDENT_MAJOR",            "TEST Accident major"},
            {"ACCIDENT_MINOR",            "TEST Accident minor"},
            {"HAZARD_ON_ROAD_CONGESTION", "TEST Congestion / closure"},
            {"HAZARD_ON_ROAD_DEBRIS",     "TEST Debris on road"},
            {"HAZARD_WEATHER_FOG",        "TEST Weather (fog)"},
        };
        List<SabreAlert> out = new ArrayList<>();
        long nowSec = System.currentTimeMillis() / 1000L;
        double lonScale = 0.00025 / Math.cos(Math.toRadians(lat));
        for (int i = 0; i < specs.length; i++) {
            // Put the HAZARD/WEATHER types ~120m north (ahead) and the POLICE/ACCIDENT
            // types ~3.3km north (out of the immediate card range) so the hazards can be
            // observed in isolation, without police/accident outranking them.
            boolean isHazard = specs[i][0].startsWith("HAZARD");
            double aLat = lat + (isHazard ? 0.0011 : 0.0300);
            double aLon = lon + (i - specs.length / 2.0) * lonScale; // spread E-W
            out.add(new SabreAlert("chp_TEST" + i, SabreResponseBuilder.SOURCE_CHP,
                    specs[i][0], aLat, aLon, 0.0, specs[i][1], nowSec));
        }
        return out;
    }

    private void sendFetchResponse(String responseAction, String requestId,
                                    List<SabreAlert> alerts) throws JSONException {
        String responseJson = SabreResponseBuilder.build(requestId, alerts);
        Intent intent = new Intent(responseAction);
        intent.putExtra("data", responseJson);
        sendBroadcast(intent);
        Log.d(TAG, "Response sent to: " + responseAction);
        Log.d(TAG, "Response JSON: " + responseJson);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "CHP + Waze SABRE", NotificationManager.IMPORTANCE_LOW);
            ch.enableVibration(false);
            ch.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildForegroundNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setOngoing(true)
         .setSmallIcon(android.R.drawable.ic_menu_compass)
         .setContentTitle("CHP + Waze SABRE")
         .setContentText("Providing CHP and Waze alerts to Highway Radar")
         .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
}
