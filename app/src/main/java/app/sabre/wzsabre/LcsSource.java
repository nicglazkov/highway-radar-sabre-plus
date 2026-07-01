package app.sabre.wzsabre;

import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/**
 * Caltrans LCS (Lane Closure System) source — live lane and road closures from
 * the per-district feeds at
 * {@code https://cwwp2.dot.ca.gov/data/d<N>/lcs/lcsStatusD<NN>.xml}.
 *
 * A closure is reported only when it is physically IN PLACE: CHP code 1097
 * (closure established) set, 1098 (picked up) and 1022 (canceled) not set.
 * Scheduled-but-not-yet-established closures are skipped to avoid false alarms.
 *
 * The district feeds are large (~4 MB), so they are NEVER fetched on the HR
 * request path: each district has a parsed cache that a background thread
 * refreshes when stale, exactly like the Waze source. The first request after
 * a cold start returns no closures; the cache is warm a few seconds later.
 */
public class LcsSource {
    private static final String TAG = "LcsSource";

    private static final long CACHE_TTL_MS       = 5 * 60_000L;   // refresh cadence
    private static final long CACHE_MAX_SERVE_MS = 30 * 60_000L;  // never serve staler than this
    private static final int  TIMEOUT_MS         = 25_000;
    /** Closures whose schedule ended more than this long ago are treated as ghosts. */
    private static final long END_OVERRUN_GRACE_SEC = 4 * 3600L;
    /** Emit a second pin at the end point when a closure spans farther than this. */
    private static final double END_PIN_MIN_METERS = 2_000.0;

    // ── District selection ───────────────────────────────────────────────────

    /** Rough bounding boxes for the 12 Caltrans districts (generous, may overlap). */
    private static final double[][] DISTRICT_BOXES = {
        // {district, latMin, latMax, lonMin, lonMax}
        { 1, 38.70, 42.10, -124.60, -122.40},
        { 2, 39.30, 42.10, -123.20, -119.95},
        { 3, 38.00, 40.60, -122.60, -119.80},
        { 4, 36.85, 38.95, -123.70, -121.15},
        { 5, 34.25, 37.45, -122.50, -119.30},
        { 6, 34.75, 37.70, -121.10, -117.55},
        { 7, 33.60, 35.10, -119.70, -117.50},
        { 8, 33.35, 35.85, -118.20, -114.05},
        { 9, 35.75, 38.80, -119.30, -116.90},
        {10, 36.95, 38.95, -121.70, -119.10},
        {11, 32.45, 33.65, -118.20, -114.30},
        {12, 33.30, 34.00, -118.35, -117.35},
    };

    /** Districts whose bounding box intersects the request circle. */
    static List<Integer> districtsFor(double lat, double lon, double radiusMeters) {
        double latPad = radiusMeters / 110_574.0;
        double lonPad = radiusMeters / (111_320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
        List<Integer> out = new ArrayList<>();
        for (double[] b : DISTRICT_BOXES) {
            if (lat + latPad >= b[1] && lat - latPad <= b[2]
                    && lon + lonPad >= b[3] && lon - lonPad <= b[4]) {
                out.add((int) b[0]);
            }
        }
        return out;
    }

    static String feedUrl(int district) {
        return String.format(Locale.US,
                "https://cwwp2.dot.ca.gov/data/d%d/lcs/lcsStatusD%02d.xml", district, district);
    }

    // ── Parsed closure record ────────────────────────────────────────────────

    static final class Closure {
        String index;
        double beginLat, beginLon, endLat, endLon;
        String route = "", locationName = "", nearbyPlace = "";
        String typeOfClosure = "", lanesClosed = "";
        long startEpoch, endEpoch, epoch1097;
        boolean indefiniteEnd, is1097, is1098, is1022;
    }

    // ── Per-district cache ───────────────────────────────────────────────────

    private static final class CacheEntry {
        final List<Closure> closures;
        final long timeMs;
        CacheEntry(List<Closure> closures, long timeMs) {
            this.closures = closures;
            this.timeMs = timeMs;
        }
    }

    private final Map<Integer, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicBoolean> refreshing = new ConcurrentHashMap<>();
    private final ExecutorService refreshExec = Executors.newSingleThreadExecutor();

    /**
     * Returns active closures within the radius, served from the district caches.
     * Never blocks on the network; stale districts are refreshed in the background.
     */
    public List<SabreAlert> fetchAlerts(double lat, double lon, double radiusMeters) {
        long now = System.currentTimeMillis();
        List<SabreAlert> out = new ArrayList<>();
        for (int district : districtsFor(lat, lon, radiusMeters)) {
            CacheEntry entry = cache.get(district);
            if (entry == null || (now - entry.timeMs) > CACHE_TTL_MS) {
                scheduleRefresh(district);
            }
            if (entry == null || (now - entry.timeMs) > CACHE_MAX_SERVE_MS) continue;
            long nowSec = now / 1000L;
            for (Closure c : entry.closures) {
                if (!isActive(c, nowSec)) continue;
                boolean beginIn = CHPSource.haversineMeters(lat, lon, c.beginLat, c.beginLon) <= radiusMeters;
                boolean endIn   = hasEndPoint(c)
                        && CHPSource.haversineMeters(lat, lon, c.endLat, c.endLon) <= radiusMeters;
                if (!beginIn && !endIn) continue;
                out.addAll(toAlerts(c));
            }
        }
        return out;
    }

    /** Release the background refresh thread when the owning service is destroyed. */
    public void shutdown() {
        refreshExec.shutdownNow();
    }

    private void scheduleRefresh(final int district) {
        AtomicBoolean flag = refreshing.computeIfAbsent(district, d -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) return;
        refreshExec.submit(() -> {
            try {
                List<Closure> parsed = fetchDistrict(district);
                cache.put(district, new CacheEntry(parsed, System.currentTimeMillis()));
                Log.d(TAG, "D" + district + " refreshed: " + parsed.size() + " closure records");
            } catch (Exception e) {
                Log.w(TAG, "D" + district + " refresh failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                flag.set(false);
            }
        });
    }

    private List<Closure> fetchDistrict(int district) throws Exception {
        URL url = new URL(feedUrl(district));
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
        try (InputStream in = conn.getInputStream()) {
            return parse(in);
        } finally {
            conn.disconnect();
        }
    }

    // ── Streaming XML parse ──────────────────────────────────────────────────

    /**
     * Streaming parse — the feed is ~4 MB, so it is consumed directly from the
     * stream rather than read into a String. Field tag names in the schema are
     * globally unique (beginLatitude vs endLatitude etc.), so no section
     * tracking is needed; text accumulates per element like CHPSource.
     */
    static List<Closure> parse(InputStream in) throws Exception {
        List<Closure> out = new ArrayList<>();
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new InputStreamReader(in, "ISO-8859-1"));

        Closure c = null;
        StringBuilder text = new StringBuilder();
        String currentTag = null;

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case XmlPullParser.START_TAG:
                    currentTag = parser.getName();
                    text.setLength(0);
                    if ("lcs".equals(currentTag)) c = new Closure();
                    break;

                case XmlPullParser.TEXT:
                    text.append(parser.getText());
                    break;

                case XmlPullParser.END_TAG:
                    String tag = parser.getName();
                    String v = text.toString().trim();
                    text.setLength(0);
                    if (c != null && !v.isEmpty() && tag.equals(currentTag)) {
                        switch (tag) {
                            case "index":                 c.index = v; break;
                            case "beginLatitude":         c.beginLat = parseDouble(v); break;
                            case "beginLongitude":        c.beginLon = parseDouble(v); break;
                            case "endLatitude":           c.endLat = parseDouble(v); break;
                            case "endLongitude":          c.endLon = parseDouble(v); break;
                            case "beginRoute":            c.route = v; break;
                            case "beginLocationName":     c.locationName = v; break;
                            case "beginNearbyPlace":      c.nearbyPlace = v; break;
                            case "typeOfClosure":         c.typeOfClosure = v; break;
                            case "lanesClosed":           c.lanesClosed = v; break;
                            case "closureStartEpoch":     c.startEpoch = parseLong(v); break;
                            case "closureEndEpoch":       c.endEpoch = parseLong(v); break;
                            case "isClosureEndIndefinite": c.indefiniteEnd = "true".equals(v); break;
                            case "isCode1097":            c.is1097 = "true".equals(v); break;
                            case "isCode1098":            c.is1098 = "true".equals(v); break;
                            case "isCode1022":            c.is1022 = "true".equals(v); break;
                            case "code1097Epoch":         c.epoch1097 = parseLong(v); break;
                        }
                    }
                    if ("lcs".equals(tag)) {
                        if (c != null && c.index != null) out.add(c);
                        c = null;
                    }
                    break;
            }
            try {
                eventType = parser.next();
            } catch (Exception e) {
                Log.w(TAG, "LCS feed parse error — salvaged " + out.size() + " records");
                break;
            }
        }
        return out;
    }

    private static double parseDouble(String v) {
        try { return Double.parseDouble(v); } catch (Exception e) { return 0.0; }
    }

    private static long parseLong(String v) {
        try { return Long.parseLong(v); } catch (Exception e) { return 0L; }
    }

    // ── Filtering and mapping ────────────────────────────────────────────────

    /**
     * In place right now: 1097 established, not picked up (1098), not canceled
     * (1022), coordinates present, not a shoulder/median-only closure, and not a
     * ghost record whose scheduled end passed more than the grace period ago.
     */
    static boolean isActive(Closure c, long nowSec) {
        if (!c.is1097 || c.is1098 || c.is1022) return false;
        if (c.beginLat == 0.0 && c.beginLon == 0.0) return false;
        if (isShoulderOnly(c)) return false;
        if (!c.indefiniteEnd && c.endEpoch > 0 && nowSec > c.endEpoch + END_OVERRUN_GRACE_SEC)
            return false;
        return true;
    }

    /**
     * True when only shoulders/median are closed (no travel lane affected) —
     * e.g. lanesClosed = "RShoulder". "3, RShoulder", "All", "Left HOV" and
     * turn-lane closures still count as lane closures.
     */
    static boolean isShoulderOnly(Closure c) {
        String l = c.lanesClosed;
        if (l == null || l.isEmpty()) return false;
        String lower = l.toLowerCase(Locale.US);
        if (lower.contains("all") || lower.contains("hov") || lower.contains("turn")) return false;
        for (int i = 0; i < l.length(); i++) {
            if (Character.isDigit(l.charAt(i))) return false;
        }
        return true;
    }

    static boolean hasEndPoint(Closure c) {
        return !(c.endLat == 0.0 && c.endLon == 0.0);
    }

    /** Begin pin always; end pin too when the closure spans more than 2 km. */
    static List<SabreAlert> toAlerts(Closure c) {
        List<SabreAlert> out = new ArrayList<>(2);
        long reportTs = c.epoch1097 > 0 ? c.epoch1097 : c.startEpoch;
        String street = describe(c);
        out.add(new SabreAlert("lcs_" + c.index, SabreResponseBuilder.SOURCE_LCS,
                "HAZARD_ON_ROAD_CONGESTION", c.beginLat, c.beginLon,
                SabreResponseBuilder.HEADING_UNKNOWN, street, reportTs));
        if (hasEndPoint(c)
                && CHPSource.haversineMeters(c.beginLat, c.beginLon, c.endLat, c.endLon)
                        > END_PIN_MIN_METERS) {
            out.add(new SabreAlert("lcs_" + c.index + "_end", SabreResponseBuilder.SOURCE_LCS,
                    "HAZARD_ON_ROAD_CONGESTION", c.endLat, c.endLon,
                    SabreResponseBuilder.HEADING_UNKNOWN, "End: " + street, reportTs));
        }
        return out;
    }

    static String describe(Closure c) {
        String what = "Full".equalsIgnoreCase(c.typeOfClosure)
                ? "FULL CLOSURE"
                : c.typeOfClosure + " closure";
        StringBuilder sb = new StringBuilder();
        if (!c.route.isEmpty()) sb.append(c.route).append(' ');
        sb.append(what);
        if (!c.locationName.isEmpty()) sb.append(" @ ").append(c.locationName);
        if (!c.nearbyPlace.isEmpty()) sb.append(" (").append(c.nearbyPlace).append(')');
        if (!c.lanesClosed.isEmpty() && !"Full".equalsIgnoreCase(c.typeOfClosure))
            sb.append(" · lanes: ").append(c.lanesClosed);
        return sb.toString();
    }
}
