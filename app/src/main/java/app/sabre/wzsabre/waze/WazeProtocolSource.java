package app.sabre.wzsabre.waze;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import app.sabre.wzsabre.AlertMapper;
import app.sabre.wzsabre.SabreAlert;
import app.sabre.wzsabre.SabreResponseBuilder;

/**
 * Waze data source backed by the Waze mobile "RT" protocol (replaces the georss
 * approach Waze now blocks with HTTP 403). Mints an anonymous Waze account, logs
 * in, and queries crowd-sourced alerts.
 *
 * <p>Because an RT query long-polls (up to ~10.5s) — longer than HR's response
 * budget — fetches are served from a cache that is refreshed asynchronously in the
 * background. The first HR request after a cold start returns no Waze data; once
 * the background refresh completes (~10s) every subsequent request is served
 * instantly from the warm cache. {@link #prewarm} kicks that refresh off at
 * service start so HR's first real fetch is already warm.
 *
 * <p>The RT {@code /command} endpoint is session-stateful (each alert is sent once
 * per session, then a "RmAlert," removal when it clears), so query results are
 * MERGED into a persistent {@link WazeAlertCache} rather than replacing it — see
 * that class for why the previous replace-each-time behaviour lost alerts mid-drive.
 *
 * <p>The account credentials + device fingerprint are persisted and the live
 * session is reused, so we register at most once (Waze caps anonymous accounts
 * per day).
 */
public final class WazeProtocolSource {
    private static final String TAG   = "WazeRT";
    private static final String PREFS = "waze_rt";

    private static final long   CACHE_TTL_MS      = 12_000L;  // refresh if older than this
    private static final double REFRESH_MOVE_KM   = 4.0;      // ...or if the center moved this far
    private static final double CACHE_DISCARD_KM  = 25.0;     // don't serve (or keep) a cache from far away
    // Don't serve a cache that hasn't been refreshable for this long (e.g. network
    // outage / Waze down) — stale police/accident alerts presented as current are
    // worse than no Waze data.
    private static final long   CACHE_MAX_SERVE_AGE_MS = 10 * 60_000L;

    // Zoom levels queried per refresh (WazeAlertFetcher default maxSteps=5) and the
    // total wall-clock budget for the box loop (mirrors the official's per-slot
    // withTimeout of ~10s — whatever boxes complete in time are merged, the rest
    // wait for the next refresh).
    private static final int  SHRINK_STEPS    = 5;
    private static final long QUERY_BUDGET_MS = 10_000L;

    private final Context ctx;
    private final ExecutorService refreshExec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    private WazeSession session;
    private final WazeAlertCache alertCache = new WazeAlertCache();

    private volatile long   cacheTimeMs = 0L;
    private volatile double cacheLat, cacheLon;

    public WazeProtocolSource(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /**
     * Returns the cached Waze alerts immediately and kicks off a background refresh
     * if the cache is stale or the center moved. Never blocks on the network.
     */
    public List<SabreAlert> fetchAlerts(double lat, double lon, double radiusMeters) {
        triggerRefreshIfStale(lat, lon, radiusMeters);

        long now = System.currentTimeMillis();
        if (cacheTimeMs == 0
                || (now - cacheTimeMs) > CACHE_MAX_SERVE_AGE_MS
                || haversineKm(lat, lon, cacheLat, cacheLon) > CACHE_DISCARD_KM) {
            return new ArrayList<>();
        }
        // Serve the merged cache, mapped to SABRE and clipped to the request radius.
        List<SabreAlert> out = new ArrayList<>();
        for (WazeAlert wa : alertCache.snapshot()) {
            if (haversineKm(lat, lon, wa.lat, wa.lon) * 1000.0 > radiusMeters) continue;
            SabreAlert sa = toSabreAlert(wa);
            if (sa != null) out.add(sa);
        }
        return out;
    }

    /**
     * Warm the session + cache ahead of HR's first fetch (called at service start
     * with the last known fetch location). Mirrors the official's pre-warmed
     * session pool, which is why wzsabre shows alerts in &lt;2s while a cold start
     * here used to take 10-15s.
     */
    public void prewarm(double lat, double lon, double radiusMeters) {
        triggerRefreshIfStale(lat, lon, radiusMeters);
    }

    private void triggerRefreshIfStale(double lat, double lon, double radiusMeters) {
        long now = System.currentTimeMillis();
        boolean moved = cacheTimeMs != 0 && haversineKm(lat, lon, cacheLat, cacheLon) > REFRESH_MOVE_KM;
        boolean stale = cacheTimeMs == 0 || (now - cacheTimeMs) > CACHE_TTL_MS || moved;
        if (stale && refreshing.compareAndSet(false, true)) {
            final double radius = Math.max(radiusMeters, 8000);
            // A large jump (app reopened in a new area) invalidates the whole cache;
            // start fresh rather than show alerts from the old location.
            final boolean discard = cacheTimeMs != 0
                    && haversineKm(lat, lon, cacheLat, cacheLon) > CACHE_DISCARD_KM;
            refreshExec.submit(() -> {
                try {
                    if (discard) alertCache.clear();
                    refresh(lat, lon, radius);
                    cacheTimeMs = System.currentTimeMillis();
                    cacheLat = lat;
                    cacheLon = lon;
                } catch (Exception e) {
                    Log.w(TAG, "Waze refresh failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    refreshing.set(false);
                }
            });
        }
    }

    /** Synchronous prepare + query + merge into the cache. Runs off the HR path. */
    private synchronized void refresh(double lat, double lon, double radiusMeters) throws Exception {
        try {
            queryArea(ensureSession(lat, lon), lat, lon, radiusMeters);
        } catch (WazeExceptions.AccountRejectedException e) {
            Log.w(TAG, "Account rejected — re-registering: " + e.getMessage());
            session = null;
            clearPersisted();
            queryArea(ensureSession(lat, lon), lat, lon, radiusMeters);
        } catch (WazeExceptions.SessionExpiredException e) {
            Log.w(TAG, "Session expired — re-logging in: " + e.getMessage());
            session = null;                 // keep credentials, just re-login
            queryArea(ensureSession(lat, lon), lat, lon, radiusMeters);
        }
        persist();
        Log.d(TAG, "Waze cache: " + alertCache.size() + " alerts near "
                + String.format(Locale.US, "%.4f,%.4f", lat, lon));
    }

    /**
     * Prepare the session, then query a series of progressively smaller boxes
     * around the driver, merging each into the cache. Mirrors the official's
     * one-account path: getShrinkingBboxes (full radius, then halved each step),
     * each box shrunk to 0.75 as scanBoxes does, queried big→small on one session
     * within a total time budget. The smaller viewports defeat the server-side
     * thinning that drops minor/near-driver alerts from a single large query.
     */
    private void queryArea(WazeSession s, double lat, double lon, double radiusMeters) throws Exception {
        s.prepareForArea(lat, lon);
        double[][] zoomBoxes = GeoBoxes.shrinkingBoxes(lon, lat, radiusMeters, SHRINK_STEPS);
        long deadline = System.currentTimeMillis() + QUERY_BUDGET_MS;
        for (double[] zoom : zoomBoxes) {
            if (System.currentTimeMillis() >= deadline) break;   // best-effort, like the official
            WazeProto.Batch batch = s.queryBox(GeoBoxes.shrink(zoom, 0.75));
            alertCache.submit(new AlertQueryResult(
                    WazeRtCodec.parseAlerts(batch), WazeRtCodec.parseRemovedAlertIds(batch)));
        }
    }

    /** Map a cached Waze alert to a SABRE alert, or null for a type we don't carry. */
    private SabreAlert toSabreAlert(WazeAlert wa) {
        String type = AlertMapper.fromWazeType(wa.type, wa.subtype);
        if (type == null) return null;
        String id = "alert-" + wa.id + "/" + wa.uuid;   // user_id is parsed from this
        return new SabreAlert(id, SabreResponseBuilder.SOURCE_WAZE, type,
                wa.lat, wa.lon, wa.magvar, wa.street, wa.pubMillis / 1000L);
    }

    static String region(double lat, double lon) {
        if (lon >= -170.0 && lon <= -52.0 && lat >= -15.0 && lat <= 73.0) return "na";
        if (lon >= 34.0 && lon <= 36.0 && lat >= 29.5 && lat <= 33.5) return "il";
        return "row";
    }

    private WazeSession ensureSession(double lat, double lon) {
        if (session != null) return session;
        String region = region(lat, lon);
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String community = p.getString("community", null);
        String secret    = p.getString("secret", null);
        if (community != null && secret != null) {
            DeviceIdentity dev = new DeviceIdentity(
                    p.getString("dev_mfr", "Google"),
                    p.getString("dev_model", "Pixel 8"),
                    p.getString("dev_os", "15-SDK35"),
                    p.getInt("dev_w", 1080),
                    p.getInt("dev_h", 2400),
                    p.getString("dev_iid", UUID.randomUUID().toString()));
            session = new WazeSession(region, dev, new WazeCredentials(community, secret));
        } else {
            session = new WazeSession(region);
        }
        return session;
    }

    private void persist() {
        if (session == null) return;
        WazeCredentials c = session.getCredentials();
        if (c == null) return;
        DeviceIdentity d = session.getDevice();
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("community", c.community)
                .putString("secret", c.secret)
                .putString("dev_mfr", d.manufacturer)
                .putString("dev_model", d.model)
                .putString("dev_os", d.osVersion)
                .putInt("dev_w", d.screenW)
                .putInt("dev_h", d.screenH)
                .putString("dev_iid", d.installationId)
                .apply();
    }

    private void clearPersisted() {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ── Debug self-test (used by the WAZE_TEST broadcast hook) ────────────────

    public static String selfTest(double lat, double lon) {
        long t0 = System.currentTimeMillis();
        try {
            List<WazeAlert> alerts = new WazeSession(region(lat, lon)).fetchArea(lat, lon, 10000);
            String summary = "Waze RT OK: " + alerts.size()
                    + " alerts in " + (System.currentTimeMillis() - t0) + "ms";
            Log.d(TAG, summary);
            for (WazeAlert a : alerts) {
                Log.d(TAG, "  alert " + a.type + '/' + a.subtype
                        + " @ " + String.format(Locale.US, "%.5f,%.5f", a.lat, a.lon)
                        + (a.street != null ? " (" + a.street + ")" : ""));
            }
            return summary;
        } catch (Exception e) {
            String msg = "Waze RT FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e(TAG, msg, e);
            return msg;
        }
    }
}
