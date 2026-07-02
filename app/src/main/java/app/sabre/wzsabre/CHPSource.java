package app.sabre.wzsabre;

import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/**
 * Fetches and parses the CHP statewide live incident XML feed.
 * Filters by radius, incident age, and the per-category settings in ChpConfig.
 *
 * <p>Like the Waze and LCS sources, CHP is served from a background-refreshed cache
 * and NEVER fetched on the Highway Radar request path. Earlier this was a blocking
 * network call: a slow/hung CHP fetch (the feed sits behind a flaky load balancer)
 * could occupy the shared fetch thread pool and starve Waze/LCS, and any single
 * failure returned zero CHP alerts for that cycle — so incidents flapped in and out
 * while driving through cell dead zones. Now {@link #fetchAlerts} returns the last
 * good parse instantly and a stale fetch only affects freshness, never availability.
 */
public class CHPSource {
    private static final String TAG     = "CHPSource";
    private static final String CHP_URL = "https://media.chp.ca.gov/sa_xml/sa.xml";
    // Background thread, so the timeouts don't gate the HR response — but still bounded
    // so a hung fetch can't pin the refresh thread indefinitely.
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS    = 8_000;

    private static final long CACHE_TTL_MS       = 60_000L;       // CHP updates ~1/min
    private static final long CACHE_MAX_SERVE_MS = 10 * 60_000L;  // never serve staler than this

    // CHP times are Pacific; parse in that zone so age comparisons are correct
    private static final TimeZone TZ_PACIFIC = TimeZone.getTimeZone("America/Los_Angeles");

    // ── Parsed statewide incident (pre-radius, pre-config) ────────────────────

    static final class Incident {
        final String logId, logType, logTime, location, area;
        final double lat, lon;
        Incident(String logId, String logType, String logTime, String location, String area,
                 double lat, double lon) {
            this.logId = logId; this.logType = logType; this.logTime = logTime;
            this.location = location; this.area = area; this.lat = lat; this.lon = lon;
        }
    }

    private final ExecutorService refreshExec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private volatile List<Incident> cache = null;
    private volatile long cacheTimeMs = 0L;
    // Conditional-GET validators so an unchanged feed isn't re-downloaded (~300KB).
    private volatile String etag = null;
    private volatile String lastModified = null;

    /**
     * Returns CHP incidents within the radius from the cache, filtered by config.
     * Never blocks on the network; a stale/first cache triggers a background refresh.
     */
    public List<SabreAlert> fetchAlerts(double centerLat, double centerLon,
                                         double radiusMeters, ChpConfig config) {
        triggerRefreshIfStale();
        List<Incident> snap = cache;
        if (snap == null || (System.currentTimeMillis() - cacheTimeMs) > CACHE_MAX_SERVE_MS) {
            return new ArrayList<>();
        }
        return filter(snap, centerLat, centerLon, radiusMeters, config);
    }

    /** Overload kept for tests that don't use config (passes null → all defaults). */
    public List<SabreAlert> fetchAlerts(double centerLat, double centerLon,
                                         double radiusMeters) {
        return fetchAlerts(centerLat, centerLon, radiusMeters, null);
    }

    /** Warm the cache at service start so HR's first request isn't empty. */
    public void prewarm() {
        triggerRefreshIfStale();
    }

    /** Release the background refresh thread when the owning service is destroyed. */
    public void shutdown() {
        refreshExec.shutdownNow();
    }

    private void triggerRefreshIfStale() {
        boolean stale = cache == null || (System.currentTimeMillis() - cacheTimeMs) > CACHE_TTL_MS;
        if (stale && refreshing.compareAndSet(false, true)) {
            refreshExec.submit(() -> {
                try {
                    List<Incident> parsed = fetchAndParse();
                    if (parsed != null) {   // null = 304 Not Modified → keep last good
                        cache = parsed;
                        cacheTimeMs = System.currentTimeMillis();
                        Log.d(TAG, "CHP refreshed: " + parsed.size() + " statewide incidents");
                    } else {
                        cacheTimeMs = System.currentTimeMillis();   // fresh enough, unchanged
                    }
                    SourceStatus.success(SabreResponseBuilder.SOURCE_CHP,
                            cache == null ? 0 : cache.size());
                } catch (Exception e) {
                    // Keep serving the last good parse — a transient failure must not
                    // blank CHP for the whole cycle.
                    Log.w(TAG, "CHP refresh failed (serving cache): "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                    SourceStatus.failure(SabreResponseBuilder.SOURCE_CHP,
                            e.getClass().getSimpleName());
                } finally {
                    refreshing.set(false);
                }
            });
        }
    }

    /** @return parsed incidents, or {@code null} when the server replies 304 Not Modified. */
    private List<Incident> fetchAndParse() throws Exception {
        URL url = new URL(CHP_URL);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
        // Only send validators when we actually hold a cached parse — otherwise a 304
        // would leave us with nothing to serve.
        if (cache != null) {
            if (etag != null)         conn.setRequestProperty("If-None-Match", etag);
            if (lastModified != null) conn.setRequestProperty("If-Modified-Since", lastModified);
        }
        try {
            if (conn.getResponseCode() == HttpsURLConnection.HTTP_NOT_MODIFIED) return null;
            String newEtag = conn.getHeaderField("ETag");
            String newLastModified = conn.getHeaderField("Last-Modified");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            }
            List<Incident> parsed = parseAll(sb.toString());
            // Commit the validators only after a successful read+parse — if the body
            // read throws mid-stream, the old cache and its (still-matching) validators
            // stay in sync, so the next request re-fetches rather than 304-ing onto a
            // cache that was never updated.
            etag = newEtag;
            lastModified = newLastModified;
            return parsed;
        } finally {
            conn.disconnect();
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    /** Parse every complete statewide incident with valid coordinates (no filtering). */
    static List<Incident> parseAll(String xml) throws Exception {
        List<Incident> incidents = new ArrayList<>();
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new StringReader(xml));

        String logId = null, logTime = null, logType = null;
        String location = null, area = null, latlon = null;
        String currentTag = null;
        // Text is accumulated per element because the parser may deliver a single
        // text node as multiple TEXT events (e.g. around entity references like
        // &amp; in Location values); assigning on each event would keep only the
        // last chunk.
        StringBuilder text = new StringBuilder();

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case XmlPullParser.START_TAG:
                    currentTag = parser.getName();
                    text.setLength(0);
                    if ("Log".equals(currentTag)) {
                        logId = parser.getAttributeValue(null, "ID");
                        logTime = null; logType = null;
                        location = null; area = null; latlon = null;
                    }
                    break;

                case XmlPullParser.TEXT:
                    text.append(parser.getText());
                    break;

                case XmlPullParser.END_TAG:
                    String endTag = parser.getName();
                    String value = cleanValue(text.toString());
                    text.setLength(0);
                    if (!value.isEmpty() && endTag.equals(currentTag)) {
                        if      ("LogTime".equals(endTag))  logTime   = value;
                        else if ("LogType".equals(endTag))  logType   = value;
                        else if ("Location".equals(endTag)) location  = value;
                        else if ("Area".equals(endTag))     area      = value;
                        else if ("LATLON".equals(endTag))   latlon    = value;
                    }
                    if ("Log".equals(endTag)) {
                        Incident inc = toIncident(logId, logType, logTime, location, area, latlon);
                        if (inc != null) incidents.add(inc);
                        currentTag = null;
                    }
                    break;
            }
            try {
                eventType = parser.next();
            } catch (Exception e) {
                // CHP's IIS server caps the feed (~80KB historically, but the size now
                // varies) and truncates mid-record when statewide incident volume is
                // high, leaving malformed XML at the end. Salvage every complete <Log>
                // parsed so far instead of dropping all of them (which would yield zero
                // alerts exactly when the roads are busiest).
                Log.w(TAG, "CHP feed truncated mid-stream — salvaged " + incidents.size()
                        + " complete records before the cut");
                break;
            }
        }
        return incidents;
    }

    /** Build an Incident from raw fields, or null if coordinates are missing/invalid. */
    private static Incident toIncident(String logId, String logType, String logTime,
                                       String location, String area, String latlon) {
        if (logId == null || latlon == null) return null;
        if (latlon.equals("0:0") || latlon.startsWith("0:")) return null;
        double[] coords;
        try { coords = parseLatLon(latlon); } catch (Exception e) { return null; }
        if (coords[0] == 0.0 && coords[1] == 0.0) return null;
        return new Incident(logId, logType, logTime, location, area, coords[0], coords[1]);
    }

    // ── Filtering (radius + config) ───────────────────────────────────────────

    private static List<SabreAlert> filter(List<Incident> incidents, double centerLat,
                                            double centerLon, double radiusMeters, ChpConfig config) {
        List<SabreAlert> out = new ArrayList<>();
        long nowSec = System.currentTimeMillis() / 1000;
        for (Incident inc : incidents) {
            SabreAlert a = buildAlert(inc, centerLat, centerLon, radiusMeters, config, nowSec);
            if (a != null) out.add(a);
        }
        return out;
    }

    private static SabreAlert buildAlert(Incident inc, double centerLat, double centerLon,
                                         double radiusMeters, ChpConfig config, long nowSec) {
        if (haversineMeters(centerLat, centerLon, inc.lat, inc.lon) > radiusMeters) return null;

        // ── always-excluded types (admin, not traffic-relevant) ──────────────
        ChpCategory category = AlertMapper.categoryFor(inc.logType);
        if (category == null) return null;  // SILVER / MISSING

        // ── incident age filter ──────────────────────────────────────────────
        long reportTs = parseLogTime(inc.logTime);   // 0 if unparseable
        if (reportTs == 0) reportTs = nowSec;

        if (config != null && config.maxAgeMinutes > 0) {
            long cutoffSecs = nowSec - config.maxAgeMinutes * 60L;
            if (reportTs < cutoffSecs) return null;  // too old
        }

        // ── category filter + type resolution ────────────────────────────────
        String naturalType = AlertMapper.fromChpLogType(inc.logType);
        String finalType = (config != null)
                ? config.resolveType(category, naturalType)
                : (category.defaultType != null ? category.defaultType : naturalType);
        if (finalType == null) return null;  // category disabled

        String streetName = buildStreetName(inc.location, inc.area);
        return new SabreAlert(
                "chp_" + inc.logId,
                SabreResponseBuilder.SOURCE_CHP,
                finalType, inc.lat, inc.lon, SabreResponseBuilder.HEADING_UNKNOWN, streetName, reportTs);
    }

    // ── Test-facing parse+filter entry points (kept for CHPSourceTest) ────────

    List<SabreAlert> parseXml(String xml, double centerLat, double centerLon,
                              double radiusMeters, ChpConfig config) throws Exception {
        return filter(parseAll(xml), centerLat, centerLon, radiusMeters, config);
    }

    List<SabreAlert> parseXml(String xml, double centerLat, double centerLon,
                              double radiusMeters) throws Exception {
        return parseXml(xml, centerLat, centerLon, radiusMeters, null);
    }

    // ── Time parsing ──────────────────────────────────────────────────────────

    /**
     * Parses CHP LogTime.  Observed formats:
     *   "Jun  9 2026  2:05PM"   (current feed: month name, space-padded day,
     *                            12-hour time with no space before AM/PM)
     *   "03/25/2026 10:00 AM"   (older 12-hour)
     *   "03/25/2026 14:00"      (older 24-hour)
     * Runs of whitespace are collapsed before parsing (the feed pads single-digit
     * days/hours with extra spaces). Returns unix seconds (0 on failure).
     */
    static long parseLogTime(String logTime) {
        if (logTime == null) return 0;
        String normalized = logTime.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) return 0;
        String[] formats = {
                "MMM d yyyy h:mma",
                "MMM d yyyy h:mm a",
                "MM/dd/yyyy hh:mm a",
                "MM/dd/yyyy HH:mm",
        };
        for (String fmt : formats) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.US);
                sdf.setTimeZone(TZ_PACIFIC);
                sdf.setLenient(false);
                Date d = sdf.parse(normalized);
                if (d != null) return d.getTime() / 1000;
            } catch (Exception ignored) {}
        }
        return 0;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * "37721302:122169832"  →  [37.721302, -122.169832]
     * The feed encodes western longitude as a positive value; negate via abs so
     * an explicit minus sign (if CHP ever adds one) doesn't flip it eastward.
     */
    static double[] parseLatLon(String latlon) {
        String[] parts = latlon.split(":");
        double lat = Long.parseLong(parts[0].trim()) / 1_000_000.0;
        double lon = -Math.abs(Long.parseLong(parts[1].trim()) / 1_000_000.0);
        return new double[]{lat, lon};
    }

    /** Strip surrounding quotes that CHP wraps values in */
    private static String cleanValue(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("^\"|\"$", "").trim();
    }

    private static String buildStreetName(String location, String area) {
        if (location != null && !location.isEmpty() && area != null && !area.isEmpty())
            return location + " (" + area + ")";
        if (location != null && !location.isEmpty()) return location;
        if (area != null && !area.isEmpty()) return area;
        return "Unknown";
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
