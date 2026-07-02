package app.sabre.wzsabre;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class SabreService extends Service {
    private static final String TAG = "SABREService";
    private static final String CHANNEL_ID = "SabreServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    // Update-check (sideloaded app, no Play auto-update): at most once/day, and a
    // notification at most once per new version — deliberately not spammy.
    private static final String UPDATE_CHANNEL_ID = "SabreUpdateChannel";
    private static final int    UPDATE_NOTIFICATION_ID = 2;
    private static final long   UPDATE_CHECK_INTERVAL_MS = 24 * 3600_000L;

    /** Live while the service exists — lets MainBroadcastReceiver decide whether a
     *  SHUTDOWN needs forwarding (no point starting the service just to stop it). */
    static volatile boolean RUNNING = false;

    // ── Lifecycle: run only while Highway Radar is using us ───────────────────
    // HR polls (FETCH_REQUEST) every few seconds while active and sends SHUTDOWN
    // when it closes. The service stops itself when HR goes quiet so it isn't a
    // permanent background service (which also keeps it clear of the Android 15
    // background-FGS runtime limits). Every non-shutdown start re-arms the idle
    // timer; a SHUTDOWN schedules a shorter grace stop that a new session cancels.
    private static final long IDLE_TIMEOUT_MS   = 5 * 60_000L;  // no fetch this long → HR is gone
    private static final long SHUTDOWN_GRACE_MS = 20_000L;      // HR said bye; brief wait for a re-open
    private final Handler lifecycleHandler = new Handler(Looper.getMainLooper());
    private final Runnable stopRunnable = () -> {
        Log.d(TAG, "Highway Radar idle/closed — stopping service");
        RUNNING = false;   // stop accepting SHUTDOWN forwards that would resurrect us
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    };

    // Requests and source fetches run on SEPARATE executors. If they shared one
    // pool, stacked-up requests (HR retrying) could occupy every thread with
    // handlers blocked on fetch futures that no free thread can run, and every
    // response would come back empty until the queue drained.
    private ExecutorService requestExecutor;
    private ExecutorService fetchExecutor;
    private CHPSource chpSource;
    private LcsSource lcsSource;
    private WildfireSource fireSource;
    private app.sabre.wzsabre.waze.WazeProtocolSource wazeSource;

    // Last fetch location, persisted so the Waze session can be pre-warmed at the
    // next service start (HR's handshake starts the service before its first
    // FETCH_REQUEST). Without this, a cold start has to register/login/query Waze
    // while HR is already waiting, so police alerts took 10-15s to appear vs the
    // official's <2s. See WazeProtocolSource.prewarm.
    private static final String STATE_PREFS = "sabre_state";

    @Override
    public void onCreate() {
        super.onCreate();
        RUNNING = true;
        requestExecutor = Executors.newSingleThreadExecutor();
        fetchExecutor   = Executors.newFixedThreadPool(4);
        chpSource  = new CHPSource();
        lcsSource  = new LcsSource();
        fireSource = new WildfireSource();
        wazeSource = new app.sabre.wzsabre.waze.WazeProtocolSource(this);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildForegroundNotification());
        chpSource.prewarm();          // statewide feed — no location needed
        // Only warm wildfires if the source is enabled — otherwise it's a wasted
        // network fetch on every service start for a disabled source.
        if (ChpConfig.load(this).fireEnabled) fireSource.prewarm();
        prewarmFromLastLocation();    // Waze — needs last known location
        maybeCheckForUpdate();        // throttled; notifies once per new version
        armIdleStop();                // stop if no HR activity arrives
        Log.d(TAG, "Service started");
    }

    /** (Re)arm the watchdog that stops the service after a stretch of no HR activity. */
    private void armIdleStop() { scheduleStop(IDLE_TIMEOUT_MS); }

    private void scheduleStop(long delayMs) {
        lifecycleHandler.removeCallbacks(stopRunnable);
        lifecycleHandler.postDelayed(stopRunnable, delayMs);
    }

    /** Kick off a Waze refresh for the last known location so the cache is warm
     *  by the time HR sends its first FETCH_REQUEST after a (re)start. */
    private void prewarmFromLastLocation() {
        android.content.SharedPreferences p = getSharedPreferences(STATE_PREFS, MODE_PRIVATE);
        if (!p.contains("last_lat")) return;
        double lat = Double.longBitsToDouble(p.getLong("last_lat", 0));
        double lon = Double.longBitsToDouble(p.getLong("last_lon", 0));
        double radius = Double.longBitsToDouble(p.getLong("last_radius", 0));
        if (radius <= 0) radius = 80_000;
        Log.d(TAG, String.format("Pre-warming Waze for last location %.4f,%.4f", lat, lon));
        wazeSource.prewarm(lat, lon, radius);
    }

    private void saveLastLocation(double lat, double lon, double radius) {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
                .putLong("last_lat", Double.doubleToRawLongBits(lat))
                .putLong("last_lon", Double.doubleToRawLongBits(lon))
                .putLong("last_radius", Double.doubleToRawLongBits(radius))
                .apply();
    }

    /** At most once per day, check GitHub for a newer release and, if there is one we
     *  haven't already flagged, post a single low-importance notification. */
    private void maybeCheckForUpdate() {
        android.content.SharedPreferences p = getSharedPreferences(STATE_PREFS, MODE_PRIVATE);
        if (System.currentTimeMillis() - p.getLong("update_check_ms", 0) < UPDATE_CHECK_INTERVAL_MS) return;
        new Thread(() -> {
            UpdateChecker.Result r = UpdateChecker.fetchLatest();
            android.content.SharedPreferences pp = getSharedPreferences(STATE_PREFS, MODE_PRIVATE);
            pp.edit().putLong("update_check_ms", System.currentTimeMillis()).apply();
            if (r == null) return;
            if (!UpdateChecker.isNewer(r.latestVersion, BuildConfig.VERSION_NAME)) return;
            if (r.latestVersion.equals(pp.getString("update_notified_version", null))) return;
            postUpdateNotification(r);
            pp.edit().putString("update_notified_version", r.latestVersion).apply();
        }).start();
    }

    private void postUpdateNotification(UpdateChecker.Result r) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(UPDATE_CHANNEL_ID,
                    "Plugin updates", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(r.htmlUrl)),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, UPDATE_CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
         .setContentTitle("CHP + Waze SABRE update available")
         .setContentText("Version " + r.latestVersion + " — tap to download")
         .setAutoCancel(true)
         .setContentIntent(pi);
        nm.notify(UPDATE_NOTIFICATION_ID, b.build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getStringExtra("action") : null;

        // HR is ending the session. Wait a short grace period in case it immediately
        // opens a new one (its normal behaviour), then stop — a fresh FETCH/handshake
        // within the window re-arms the idle timer and cancels this stop.
        if (action != null && action.contains("SHUTDOWN")) {
            Log.d(TAG, "SHUTDOWN received — stopping after grace period");
            scheduleStop(SHUTDOWN_GRACE_MS);
            return START_STICKY;
        }

        // Any other start (fetch, handshake pre-warm, boot, or a system restart with
        // a null intent) means HR still wants us — cancel any pending stop and
        // re-arm the idle watchdog.
        armIdleStop();
        if (action != null && action.contains("FETCH_REQUEST")) {
            handleFetchRequest(intent.getStringExtra("data"));
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // Android 15+ may time out a long-running foreground service. specialUse has no
    // such cap today, but if that ever changes, stop cleanly instead of being
    // ANR-killed. Both overloads exist across API 34 (int) and 35 (int,int).
    @Override
    public void onTimeout(int startId) {
        Log.w(TAG, "FGS onTimeout — stopping cleanly");
        RUNNING = false;
        lifecycleHandler.removeCallbacks(stopRunnable);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        onTimeout(startId);
    }

    @Override
    public void onDestroy() {
        RUNNING = false;
        lifecycleHandler.removeCallbacks(stopRunnable);
        if (requestExecutor != null) requestExecutor.shutdownNow();
        if (fetchExecutor   != null) fetchExecutor.shutdownNow();
        if (wazeSource != null) wazeSource.shutdown();
        if (lcsSource  != null) lcsSource.shutdown();
        if (chpSource  != null) chpSource.shutdown();
        if (fireSource != null) fireSource.shutdown();
        super.onDestroy();
    }

    // Hard deadline: respond to HR within this many ms regardless of Waze status.
    // HR shows "Crowd-Sourced Alert Problems" if we exceed its internal timeout.
    private static final long RESPONSE_BUDGET_MS = 8_000;

    private void handleFetchRequest(String data) {
        requestExecutor.submit(() -> {
            // Parsed up front so the catch block can still answer HR with an error
            // response — an unanswered request makes HR show "plugin not responding".
            String requestId = null, responseAction = null;
            try {
                JSONObject req = new JSONObject(data);
                requestId      = req.getString("request_id");
                responseAction = req.getString("response_action");
                double lat    = req.has("lat")      ? req.getDouble("lat")      : req.getDouble("latitude");
                double lon    = req.has("lon")      ? req.getDouble("lon")      : req.getDouble("longitude");
                double radius = req.has("radius_m") ? req.getDouble("radius_m") : req.getDouble("radius");

                Log.d(TAG, String.format("Fetch: lat=%.4f lon=%.4f radius=%.0fm", lat, lon, radius));
                saveLastLocation(lat, lon, radius);

                long deadline = System.currentTimeMillis() + RESPONSE_BUDGET_MS;

                // Load config fresh on each request so changes take effect immediately
                ChpConfig chpConfig = ChpConfig.load(SabreService.this);

                // All three sources serve from background-refreshed caches and return
                // in milliseconds; none blocks on the network on the HR request path.
                Future<List<SabreAlert>> chpFuture  = fetchExecutor.submit(() -> chpSource.fetchAlerts(lat, lon, radius, chpConfig));
                Future<List<SabreAlert>> wazeFuture = fetchExecutor.submit(() -> wazeSource.fetchAlerts(lat, lon, radius));
                Future<List<SabreAlert>> lcsFuture  = chpConfig.lcsEnabled
                        ? fetchExecutor.submit(() -> lcsSource.fetchAlerts(lat, lon, radius))
                        : null;
                Future<List<SabreAlert>> fireFuture = chpConfig.fireEnabled
                        ? fetchExecutor.submit(() -> fireSource.fetchAlerts(lat, lon, radius))
                        : null;

                List<SabreAlert> allAlerts = new ArrayList<>();

                // Each source serves from cache and returns in ms. Any failure of one
                // source (timeout OR an unexpected error) must never discard the
                // alerts already collected from the others, so each get() is guarded
                // independently and only that source is dropped on failure.
                try {
                    allAlerts.addAll(chpFuture.get(5, TimeUnit.SECONDS));
                    Log.d(TAG, "CHP: " + allAlerts.size() + " alerts");
                } catch (Exception e) {
                    Log.w(TAG, "CHP unavailable this cycle: " + e.getClass().getSimpleName());
                    chpFuture.cancel(true);
                }

                // Give Waze whatever budget remains; cancel if exceeded
                long wazeMs = deadline - System.currentTimeMillis();
                if (wazeMs > 500) {
                    try {
                        List<SabreAlert> wazeAlerts = wazeFuture.get(wazeMs, TimeUnit.MILLISECONDS);
                        allAlerts.addAll(wazeAlerts);
                        Log.d(TAG, "Waze: " + wazeAlerts.size() + " alerts");
                    } catch (Exception e) {
                        Log.w(TAG, "Waze unavailable this cycle: " + e.getClass().getSimpleName());
                        wazeFuture.cancel(true);
                    }
                } else {
                    Log.w(TAG, "No budget left for Waze");
                    wazeFuture.cancel(true);
                }

                // LCS serves from cache and never blocks; 1s is generous
                if (lcsFuture != null) {
                    try {
                        List<SabreAlert> lcsAlerts = lcsFuture.get(1, TimeUnit.SECONDS);
                        allAlerts.addAll(lcsAlerts);
                        Log.d(TAG, "LCS: " + lcsAlerts.size() + " alerts");
                    } catch (Exception e) {
                        Log.w(TAG, "LCS unavailable this cycle: " + e.getClass().getSimpleName());
                        lcsFuture.cancel(true);
                    }
                }

                // Wildfires serve from cache and never block; 1s is generous
                if (fireFuture != null) {
                    try {
                        List<SabreAlert> fireAlerts = fireFuture.get(1, TimeUnit.SECONDS);
                        allAlerts.addAll(fireAlerts);
                        Log.d(TAG, "Wildfire: " + fireAlerts.size() + " alerts");
                    } catch (Exception e) {
                        Log.w(TAG, "Wildfire unavailable this cycle: " + e.getClass().getSimpleName());
                        fireFuture.cancel(true);
                    }
                }

                if (BuildConfig.DEBUG && injectTestAlerts) {
                    allAlerts.addAll(buildTestAlerts(testLat, testLon));
                }

                // Collapse duplicate pins across sources (e.g. CHP + Waze accident at
                // the same spot) before sending.
                int rawCount = allAlerts.size();
                List<SabreAlert> finalAlerts = AlertDeduper.dedupe(allAlerts);
                Log.d(TAG, "Sending " + finalAlerts.size() + " alerts (" + rawCount + " before dedupe)");
                sendFetchResponse(responseAction, requestId, finalAlerts);
            } catch (Exception e) {
                Log.e(TAG, "Error handling fetch request", e);
                if (requestId != null && responseAction != null) {
                    try {
                        sendErrorResponse(responseAction, requestId, e);
                    } catch (Exception e2) {
                        Log.e(TAG, "Failed to send error response", e2);
                    }
                }
            }
        });
    }

    private void sendErrorResponse(String responseAction, String requestId, Exception cause)
            throws JSONException {
        JSONObject root = new JSONObject();
        root.put("request_id", requestId);
        root.put("error_message", "plugin error: " + cause.getClass().getSimpleName());
        root.put("response", JSONObject.NULL);
        Intent intent = new Intent(responseAction);
        intent.setPackage(SabreResponseBuilder.HR_PACKAGE);   // deliver only to HR
        intent.putExtra("data", root.toString());
        sendBroadcast(intent);
        Log.w(TAG, "Error response sent to: " + responseAction);
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
        // Split into batches of MAX_ALERTS_PER_BATCH, each its own broadcast, with
        // n_batches/batch_id set — mirrors the official wzsabre. Always ≥1 batch
        // (an empty list still sends one empty batch so HR sees a response).
        int n = alerts.size();
        int batchSize = SabreResponseBuilder.MAX_ALERTS_PER_BATCH;
        int nBatches = Math.max(1, (n + batchSize - 1) / batchSize);
        for (int i = 0; i < nBatches; i++) {
            int from = i * batchSize;
            int to = Math.min(n, from + batchSize);
            String responseJson = SabreResponseBuilder.build(
                    requestId, alerts.subList(from, to), nBatches, i);
            Intent intent = new Intent(responseAction);
            intent.setPackage(SabreResponseBuilder.HR_PACKAGE);   // deliver only to HR
            intent.putExtra("data", responseJson);
            sendBroadcast(intent);
            Log.d(TAG, "Response batch " + (i + 1) + "/" + nBatches + " sent to: " + responseAction);
            // The full payload (driver-centered alert list) is only logged in debug
            // builds — it's noise and a minor privacy leak into bugreports otherwise.
            if (BuildConfig.DEBUG) Log.d(TAG, "Response JSON: " + responseJson);
        }
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
        // setForegroundServiceBehavior is API 31 (S), not Q — guarding at Q threw
        // NoSuchMethodError on Android 10/11, killing the service on those devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
}
