package app.sabre.wzsabre.waze;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import app.sabre.wzsabre.SabreAlert;
import app.sabre.wzsabre.AlertMapper;
import app.sabre.wzsabre.SabreResponseBuilder;
import app.sabre.wzsabre.SourceStatus;

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

    // Backoff after a Waze account/login rejection so a persistent 4xx (purged
    // account, rate-limit, IP flag) can't spin the register→login loop and burn the
    // anonymous-account daily cap in seconds. Exponential 30s→10min; combined with
    // MAX_ACCOUNTS_PER_DAY this hard-caps how fast/often we mint new accounts.
    private static final long BACKOFF_BASE_MS = 30_000L;
    private static final long BACKOFF_MAX_MS  = 10 * 60_000L;
    // Short backoff after a non-rejection failure (network flap, server 5xx, transient
    // login INTERNAL_ISSUES) so those don't retry at HR's poll cadence (~2-5s).
    private static final long GENERIC_FAIL_BACKOFF_MS = 15_000L;
    private static final long DAY_MS          = 24 * 3600_000L;

    private final Context ctx;
    private final ExecutorService refreshExec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    private WazeSession session;
    private final WazeAlertCache alertCache = new WazeAlertCache();
    private final WazeConfirmTracker confirmTracker = new WazeConfirmTracker();

    // elapsedRealtime-based so a wall-clock/NTP step can't freeze refreshes.
    private volatile long   cacheTimeMs = 0L;
    private volatile double cacheLat, cacheLon;

    // Rejection backoff state. consecutiveRejections is written/read only on the
    // refresh thread; backoffUntilMs is also READ from fetch/main threads in
    // triggerRefreshIfStale, so it must be volatile (and a plain long can tear on 32-bit).
    private int           consecutiveRejections = 0;
    private volatile long backoffUntilMs        = 0L;
    // The community last written to prefs — avoids rewriting identical credentials
    // on every ~12s refresh.
    private String persistedCommunity = null;

    public WazeProtocolSource(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /**
     * Returns the cached Waze alerts immediately and kicks off a background refresh
     * if the cache is stale or the center moved. Never blocks on the network.
     */
    public List<SabreAlert> fetchAlerts(double lat, double lon, double radiusMeters) {
        triggerRefreshIfStale(lat, lon, radiusMeters);

        long now = SystemClock.elapsedRealtime();
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

    /** Release the background refresh thread when the owning service is destroyed. */
    public void shutdown() {
        refreshExec.shutdownNow();
    }

    private void triggerRefreshIfStale(double lat, double lon, double radiusMeters) {
        long now = SystemClock.elapsedRealtime();
        if (now < backoffUntilMs) return;   // in rejection backoff — don't hammer Waze
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
                    cacheTimeMs = SystemClock.elapsedRealtime();
                    cacheLat = lat;
                    cacheLon = lon;
                } catch (Exception e) {
                    Log.w(TAG, "Waze refresh failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    SourceStatus.failure(SabreResponseBuilder.SOURCE_WAZE, e.getClass().getSimpleName());
                    // Don't retry a failure at poll cadence. A rejection already set a
                    // longer backoff in handleAccountRejected; only add the short generic
                    // one if we aren't already backing off, so we never shorten it.
                    long t = SystemClock.elapsedRealtime();
                    if (backoffUntilMs <= t) backoffUntilMs = t + GENERIC_FAIL_BACKOFF_MS;
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
            handleAccountRejected(e, lat, lon, radiusMeters);
        } catch (WazeExceptions.SessionExpiredException e) {
            Log.w(TAG, "Session expired — re-logging in: " + e.getMessage());
            if (session != null) session.invalidateSession();   // keep creds, just re-login
            queryArea(ensureSession(lat, lon), lat, lon, radiusMeters);
        }
        // Reached only on a fully successful refresh — clear any rejection backoff.
        consecutiveRejections = 0;
        backoffUntilMs = 0L;
        SourceStatus.success(SabreResponseBuilder.SOURCE_WAZE, alertCache.size());
        Log.d(TAG, "Waze cache: " + alertCache.size() + " alerts near "
                + String.format(Locale.US, "%.4f,%.4f", lat, lon));
    }

    /**
     * Recover from a rejected account. Backs off before the next refresh regardless,
     * and only mints a replacement account while under the per-day cap — so a
     * persistent rejection can't burn the anonymous-account quota. Once the cap or
     * the consecutive-rejection ceiling is hit, gives up this cycle (cache unchanged)
     * and lets the growing backoff throttle retries.
     */
    private void handleAccountRejected(Exception e, double lat, double lon, double radiusMeters)
            throws Exception {
        // Once the 24h registration window rolls over, forgive the consecutive-rejection
        // ceiling — otherwise a run of rejections could lock Waze out for the rest of
        // the service's life even after the daily cap has reset.
        if (regWindowRolledOver()) consecutiveRejections = 0;
        consecutiveRejections++;
        setBackoff();
        if (!canRegisterToday() || consecutiveRejections > WazeConstants.MAX_CONSECUTIVE_REJECTIONS) {
            Log.w(TAG, "Account rejected; registration cap/limit reached (" + consecutiveRejections
                    + " consecutive) — backing off without re-registering: " + e.getMessage());
            throw e;
        }
        Log.w(TAG, "Account rejected — re-registering (attempt " + consecutiveRejections + "): "
                + e.getMessage());
        session = null;
        clearPersisted();
        recordRegistration();
        queryArea(ensureSession(lat, lon), lat, lon, radiusMeters);
    }

    private void setBackoff() {
        int step = Math.min(Math.max(consecutiveRejections, 1), 5);
        long delay = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * (1L << (step - 1)));
        backoffUntilMs = SystemClock.elapsedRealtime() + delay;
        Log.d(TAG, "Waze refresh backing off " + (delay / 1000) + "s");
    }

    private boolean canRegisterToday() {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (regWindowRolledOver()) return true;   // 24h window rolled over
        return p.getInt("reg_count", 0) < WazeConstants.MAX_ACCOUNTS_PER_DAY;
    }

    private boolean regWindowRolledOver() {
        long windowStart = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong("reg_window_start", 0L);
        return System.currentTimeMillis() - windowStart > DAY_MS;
    }

    private void recordRegistration() {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long windowStart = p.getLong("reg_window_start", 0L);
        int count = p.getInt("reg_count", 0);
        if (now - windowStart > DAY_MS) { windowStart = now; count = 0; }
        p.edit().putLong("reg_window_start", windowStart).putInt("reg_count", count + 1).apply();
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
        long sessionBefore = s.currentServerSessionId();
        s.prepareForArea(lat, lon);
        // Credentials exist now (register/login just ran) — persist immediately so a
        // failure in the box loop below can't lose a freshly minted account and force
        // a wasteful re-register on the next run. Only write when they actually changed.
        WazeCredentials creds = s.getCredentials();
        if (creds != null && !creds.community.equals(persistedCommunity)) {
            persist();
            persistedCommunity = creds.community;
        }
        // A new server session re-sends all currently-active alerts for the area but
        // sends no RmAlert for alerts that cleared while we were logged out, so the
        // stale cache must be dropped to avoid ghosts in Highway Radar. Defer that
        // clear until the FIRST box query succeeds: if the box loop fails right after
        // a re-login (cell dead zone — exactly when re-logins happen), we keep serving
        // the old cache instead of blanking Waze for up to CACHE_MAX_SERVE_AGE_MS.
        boolean sessionChanged = s.currentServerSessionId() != sessionBefore;
        boolean cleared = false;
        double[][] zoomBoxes = GeoBoxes.shrinkingBoxes(lon, lat, radiusMeters, SHRINK_STEPS);
        long deadline = SystemClock.elapsedRealtime() + QUERY_BUDGET_MS;
        for (double[] zoom : zoomBoxes) {
            if (SystemClock.elapsedRealtime() >= deadline) break;   // best-effort, like the official
            WazeProto.Batch batch = s.queryBox(GeoBoxes.shrink(zoom, 0.75));
            if (sessionChanged && !cleared) {   // first successful box → safe to reset
                alertCache.clear();
                cleared = true;
            }
            alertCache.submit(new AlertQueryResult(
                    WazeRtCodec.parseAlerts(batch), WazeRtCodec.parseRemovedAlertIds(batch)));
        }
    }

    /**
     * Map a cached Waze alert to a SABRE alert. The SABRE type is the raw Waze
     * subtype (or type when the subtype is empty), which keeps the most specific
     * category for HR's icon.
     *
     * HR 3.2 only draws a crowd alert whose type starts with POLICE, HAZARD, or
     * ACCIDENT (verified in HR's decompiled renderer) and silently drops the rest,
     * including the very common JAM_* (traffic) and ROAD_CLOSED. So when the raw
     * subtype does not start with one of those, we remap it via
     * {@link AlertMapper#fromWazeType} (jams/closures become HAZARD_ON_ROAD_CONGESTION)
     * so the alert renders instead of vanishing. Renderable subtypes are passed
     * through unchanged so HR still gets the precise icon.
     */
    private SabreAlert toSabreAlert(WazeAlert wa) {
        String type = AlertMapper.wazeRenderableType(wa.type, wa.subtype);
        if (type == null || type.isEmpty()) return null;
        String id = "alert-" + wa.id + "/" + wa.uuid;   // user_id is parsed from this
        int confirmCount = wa.nThumbsUp != null ? wa.nThumbsUp : 0;
        Long confirmTs = confirmTracker.confirmTsSeconds(wa.uuid, wa.nThumbsUp);
        return new SabreAlert(id, SabreResponseBuilder.SOURCE_WAZE, type,
                wa.lat, wa.lon, wa.magvar, wa.street, wa.pubMillis / 1000L,
                confirmTs, confirmCount);
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

    /** Clear only the credential/device keys — the registration-rate counter
     *  (reg_count / reg_window_start) must survive so the per-day cap still holds. */
    private void clearPersisted() {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("community").remove("secret")
                .remove("dev_mfr").remove("dev_model").remove("dev_os")
                .remove("dev_w").remove("dev_h").remove("dev_iid")
                .apply();
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
