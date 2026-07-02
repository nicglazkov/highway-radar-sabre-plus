package app.sabre.wzsabre;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/**
 * Active California wildfires, from the interagency WFIGS "Current Wildland Fire
 * Incident Locations" ArcGIS feature service (hosted by NIFC). Each fire is a point
 * (point of origin) with name, size, and containment — shown as a road hazard so a
 * driver gets a heads-up that there's an active fire near the route.
 *
 * <p>Like the CHP and LCS sources, this is served from a background-refreshed cache
 * and never fetched on the Highway Radar request path. The CAL FIRE incidents feed
 * is Akamai-blocked to non-browser clients, so WFIGS is used instead — it is the
 * authoritative interagency source and is openly queryable.
 *
 * <p>NOTE: a fire's point is its origin, not a precise road hazard; a large fire may
 * affect roads miles away. The pin is an awareness cue, like the LCS closure pins.
 */
public class WildfireSource {
    private static final String TAG = "WildfireSource";

    // WFIGS_Incident_Locations_Current, layer 0. where: California + wildfire type +
    // active. outSR=4326 so geometry comes back as WGS84 lon/lat.
    private static final String QUERY_URL =
            "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/"
            + "WFIGS_Incident_Locations_Current/FeatureServer/0/query"
            + "?where=" + "POOState%3D%27US-CA%27%20AND%20IncidentTypeCategory%3D%27WF%27"
            + "%20AND%20ActiveFireCandidate%3D1"
            + "&outFields=IncidentName%2CIncidentSize%2CPercentContained%2C"
            + "FireDiscoveryDateTime%2CUniqueFireIdentifier%2CIncidentTypeCategory"
            + "&returnGeometry=true&outSR=4326&f=json";

    private static final long CACHE_TTL_MS       = 5 * 60_000L;   // fires update slowly
    private static final long CACHE_MAX_SERVE_MS = 60 * 60_000L;  // never serve staler than this
    private static final int  CONNECT_TIMEOUT_MS = 8_000;
    private static final int  READ_TIMEOUT_MS    = 8_000;

    // ── Parsed fire record (pre-radius) ───────────────────────────────────────

    static final class Fire {
        final String id, name;
        final double lat, lon;
        final double sizeAcres;      // <0 if unknown
        final double pctContained;   // <0 if unknown
        final long   reportTs;       // unix seconds
        Fire(String id, String name, double lat, double lon,
             double sizeAcres, double pctContained, long reportTs) {
            this.id = id; this.name = name; this.lat = lat; this.lon = lon;
            this.sizeAcres = sizeAcres; this.pctContained = pctContained; this.reportTs = reportTs;
        }
    }

    /** Immutable {list, timestamp} pair so a reader never sees a fresh list with a
     *  stale time (which would wrongly treat a just-refreshed cache as expired). */
    private static final class Snapshot {
        final List<Fire> fires;
        final long timeMs;
        Snapshot(List<Fire> fires, long timeMs) { this.fires = fires; this.timeMs = timeMs; }
    }

    private final ExecutorService refreshExec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private volatile Snapshot cache = null;

    /**
     * Active wildfires within the radius, served from the cache. Never blocks.
     * Fires with a known size below {@code minAcres} are hidden (0 = show all;
     * unknown-size fires are always shown).
     */
    public List<SabreAlert> fetchAlerts(double lat, double lon, double radiusMeters, int minAcres) {
        triggerRefreshIfStale();
        Snapshot snap = cache;
        if (snap == null || (System.currentTimeMillis() - snap.timeMs) > CACHE_MAX_SERVE_MS) {
            return new ArrayList<>();
        }
        List<SabreAlert> out = new ArrayList<>();
        for (Fire f : snap.fires) {
            if (minAcres > 0 && f.sizeAcres >= 0 && f.sizeAcres < minAcres) continue;
            if (CHPSource.haversineMeters(lat, lon, f.lat, f.lon) > radiusMeters) continue;
            out.add(toAlert(f));
        }
        return out;
    }

    /** Warm the cache at service start. */
    public void prewarm() { triggerRefreshIfStale(); }

    /** Release the background refresh thread when the owning service is destroyed. */
    public void shutdown() { refreshExec.shutdownNow(); }

    private void triggerRefreshIfStale() {
        Snapshot snap = cache;
        boolean stale = snap == null || (System.currentTimeMillis() - snap.timeMs) > CACHE_TTL_MS;
        if (stale && refreshing.compareAndSet(false, true)) {
            refreshExec.submit(() -> {
                try {
                    List<Fire> parsed = fetchAndParse();
                    cache = new Snapshot(parsed, System.currentTimeMillis());
                    Log.d(TAG, "Wildfires refreshed: " + parsed.size() + " active in CA");
                    SourceStatus.success(SabreResponseBuilder.SOURCE_FIRE, parsed.size());
                } catch (Exception e) {
                    // Keep serving the last good parse on a transient failure.
                    Log.w(TAG, "Wildfire refresh failed (serving cache): "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                    SourceStatus.failure(SabreResponseBuilder.SOURCE_FIRE, e.getClass().getSimpleName());
                } finally {
                    refreshing.set(false);
                }
            });
        }
    }

    private List<Fire> fetchAndParse() throws Exception {
        URL url = new URL(QUERY_URL);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
        conn.setRequestProperty("Accept", "application/json");
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            }
            return parse(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    /** Parse the ArcGIS query JSON into fire records (skips features missing geometry). */
    static List<Fire> parse(String json) throws Exception {
        List<Fire> out = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        // ArcGIS reports query failures as HTTP 200 with an {"error":...} body (e.g.
        // a renamed field). Treat that as a failure so it surfaces in diagnostics
        // instead of looking like "0 active fires".
        if (root.has("error")) {
            throw new Exception("WFIGS query error: " + root.optJSONObject("error"));
        }
        if (root.optBoolean("exceededTransferLimit", false)) {
            // Server capped the result set — we'd be silently dropping fires. Very
            // unlikely for active CA wildfires, but log it rather than hide it.
            Log.w(TAG, "WFIGS response hit the record cap — some fires may be omitted");
        }
        JSONArray features = root.optJSONArray("features");
        if (features == null) return out;
        long nowSec = System.currentTimeMillis() / 1000L;
        for (int i = 0; i < features.length(); i++) {
            JSONObject feat = features.optJSONObject(i);
            if (feat == null) continue;
            JSONObject geom = feat.optJSONObject("geometry");
            JSONObject attr = feat.optJSONObject("attributes");
            if (geom == null || attr == null) continue;
            // Defense-in-depth: only real wildfires (WF), never prescribed burns (RX),
            // even if the server-side type filter ever stops applying.
            if (!"WF".equals(attr.optString("IncidentTypeCategory", "WF"))) continue;
            // ArcGIS omits geometry keys when absent; require both.
            if (!geom.has("x") || !geom.has("y")) continue;
            double lon = geom.optDouble("x", Double.NaN);
            double lat = geom.optDouble("y", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
            if (lat == 0.0 && lon == 0.0) continue;

            String id = attr.optString("UniqueFireIdentifier", null);
            if (id == null || id.isEmpty()) id = "of" + attr.optString("OBJECTID", String.valueOf(i));
            String name = attr.optString("IncidentName", "").trim();

            double size = attr.isNull("IncidentSize") ? -1 : attr.optDouble("IncidentSize", -1);
            double pct  = attr.isNull("PercentContained") ? -1 : attr.optDouble("PercentContained", -1);
            long discMs = attr.isNull("FireDiscoveryDateTime") ? 0L
                    : attr.optLong("FireDiscoveryDateTime", 0L);
            long reportTs = discMs > 0 ? discMs / 1000L : nowSec;

            out.add(new Fire(id, name, lat, lon, size, pct, reportTs));
        }
        return out;
    }

    static SabreAlert toAlert(Fire f) {
        return new SabreAlert(
                "fire_" + f.id,
                SabreResponseBuilder.SOURCE_FIRE,
                "HAZARD_ON_ROAD",              // HR has no fire type; generic on-road hazard
                f.lat, f.lon,
                SabreResponseBuilder.HEADING_UNKNOWN,
                describe(f), f.reportTs);
    }

    static String describe(Fire f) {
        StringBuilder sb = new StringBuilder("Wildfire");
        if (!f.name.isEmpty()) sb.append(": ").append(f.name);
        if (f.sizeAcres >= 0) sb.append(" · ").append(formatAcres(f.sizeAcres)).append(" ac");
        if (f.pctContained >= 0) sb.append(" · ").append((int) Math.round(f.pctContained)).append("% contained");
        return sb.toString();
    }

    private static String formatAcres(double acres) {
        if (acres >= 1000) return String.format(Locale.US, "%,d", Math.round(acres));
        if (acres == Math.floor(acres)) return String.valueOf((long) acres);
        return String.format(Locale.US, "%.1f", acres);
    }
}
