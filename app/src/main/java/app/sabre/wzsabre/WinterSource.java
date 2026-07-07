package app.sabre.wzsabre;

import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/**
 * Caltrans chain-control (winter) requirements, from the per-district feeds at
 * {@code https://cwwp2.dot.ca.gov/data/d<N>/cc/ccStatusD<NN>.xml} — the same CWWP
 * portal and per-district shape as {@link LcsSource}, so district selection and the
 * async-cache pattern are shared.
 *
 * <p>Each record is a single point on a mountain route with a status: {@code R-0}
 * (none), {@code R-1} (chains or snow tires), {@code R-2} (chains except 4WD w/ snow
 * tires), {@code R-3} (chains all vehicles). Records with an active level (not R-0)
 * are shown as a slippery-road hazard. Off-season the feed is all R-0, so nothing
 * shows.
 */
public class WinterSource {
    private static final String TAG = "WinterSource";

    private static final long CACHE_TTL_MS       = 5 * 60_000L;
    private static final long CACHE_MAX_SERVE_MS = 30 * 60_000L;
    private static final int  TIMEOUT_MS         = 25_000;

    private static final TimeZone TZ_PACIFIC = TimeZone.getTimeZone("America/Los_Angeles");

    static String feedUrl(int district) {
        return String.format(Locale.US,
                "https://cwwp2.dot.ca.gov/data/d%d/cc/ccStatusD%02d.xml", district, district);
    }

    // ── Parsed record ─────────────────────────────────────────────────────────

    static final class ChainControl {
        String index;
        double lat, lon;
        String route = "", locationName = "", nearbyPlace = "";
        String status = "", statusDescription = "";
        String statusDate = "", statusTime = "";
        boolean inService;
    }

    private static final class CacheEntry {
        final List<ChainControl> records;
        final long timeMs;
        CacheEntry(List<ChainControl> records, long timeMs) {
            this.records = records;
            this.timeMs = timeMs;
        }
    }

    private final Map<Integer, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicBoolean> refreshing = new ConcurrentHashMap<>();
    private final ExecutorService refreshExec = Executors.newSingleThreadExecutor();

    /** Active chain controls within the radius, served from the district caches. */
    public List<SabreAlert> fetchAlerts(double lat, double lon, double radiusMeters) {
        long now = System.currentTimeMillis();
        List<SabreAlert> out = new ArrayList<>();
        for (int district : LcsSource.districtsFor(lat, lon, radiusMeters)) {
            CacheEntry entry = cache.get(district);
            if (entry == null || (now - entry.timeMs) > CACHE_TTL_MS) scheduleRefresh(district);
            if (entry == null || (now - entry.timeMs) > CACHE_MAX_SERVE_MS) continue;
            for (ChainControl c : entry.records) {
                if (!isActive(c)) continue;
                if (CHPSource.haversineMeters(lat, lon, c.lat, c.lon) > radiusMeters) continue;
                out.add(toAlert(c));
            }
        }
        return out;
    }

    /** Warm the caches for a location at service start. */
    public void prewarm(double lat, double lon, double radiusMeters) {
        for (int district : LcsSource.districtsFor(lat, lon, radiusMeters)) scheduleRefresh(district);
    }

    public void shutdown() { refreshExec.shutdownNow(); }

    private void scheduleRefresh(final int district) {
        AtomicBoolean flag = refreshing.computeIfAbsent(district, d -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) return;
        refreshExec.submit(() -> {
            try {
                List<ChainControl> parsed = fetchDistrict(district);
                cache.put(district, new CacheEntry(parsed, System.currentTimeMillis()));
                int total = 0;
                for (CacheEntry ce : cache.values()) total += ce.records.size();
                SourceStatus.success(SabreResponseBuilder.SOURCE_CHAINS, total);
                Log.d(TAG, "D" + district + " chain controls: " + parsed.size() + " records");
            } catch (Exception e) {
                SourceStatus.failure(SabreResponseBuilder.SOURCE_CHAINS,
                        "D" + district + " " + e.getClass().getSimpleName());
                Log.w(TAG, "D" + district + " chain-control refresh failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                flag.set(false);
            }
        });
    }

    private List<ChainControl> fetchDistrict(int district) throws Exception {
        URL url = new URL(feedUrl(district));
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
        try {
            int code = conn.getResponseCode();
            // Districts with no chain-control program (D4 Bay Area, D5 Central Coast,
            // D12 Orange County) return a non-200 for their cc feed. It used to be 404;
            // as of 2026 Caltrans returns 500. Treat ANY non-200 as "no chain controls
            // from this district": an empty, cached result, not an error. So it shows 0
            // instead of a red diagnostics error, and does not re-request every ~15s. A
            // real chain district with a transient 5xx just shows no controls until it
            // recovers, same as an empty feed. Genuine connectivity failures still throw
            // from getResponseCode() above and surface as an error.
            if (code != HttpsURLConnection.HTTP_OK) {
                Log.d(TAG, "D" + district + " chain-control feed HTTP " + code + "; treating as no data");
                return new ArrayList<>();
            }
            try (InputStream in = conn.getInputStream()) {
                return parse(in);
            }
        } finally {
            conn.disconnect();
        }
    }

    // ── Streaming XML parse ──────────────────────────────────────────────────

    static List<ChainControl> parse(InputStream in) throws Exception {
        List<ChainControl> out = new ArrayList<>();
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new InputStreamReader(in, "ISO-8859-1"));

        ChainControl c = null;
        StringBuilder text = new StringBuilder();
        String currentTag = null;

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case XmlPullParser.START_TAG:
                    currentTag = parser.getName();
                    text.setLength(0);
                    if ("cc".equals(currentTag)) c = new ChainControl();
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
                            case "index":             c.index = v; break;
                            case "latitude":          c.lat = parseDouble(v); break;
                            case "longitude":         c.lon = parseDouble(v); break;
                            case "route":             c.route = v; break;
                            case "locationName":      c.locationName = v; break;
                            case "nearbyPlace":       c.nearbyPlace = v; break;
                            case "inService":         c.inService = "true".equalsIgnoreCase(v); break;
                            case "status":            c.status = v; break;
                            case "statusDescription": c.statusDescription = v; break;
                            case "statusDate":        c.statusDate = v; break;
                            case "statusTime":        c.statusTime = v; break;
                        }
                    }
                    if ("cc".equals(tag)) {
                        if (c != null && c.index != null) out.add(c);
                        c = null;
                    }
                    break;
            }
            try {
                eventType = parser.next();
            } catch (Exception e) {
                Log.w(TAG, "chain-control feed parse error — salvaged " + out.size() + " records");
                break;
            }
        }
        return out;
    }

    private static double parseDouble(String v) {
        try { return Double.parseDouble(v); } catch (Exception e) { return 0.0; }
    }

    // ── Filtering + mapping ───────────────────────────────────────────────────

    /** Active = in service, has coordinates, and a chain-control level above R-0. */
    static boolean isActive(ChainControl c) {
        if (!c.inService) return false;
        if (c.lat == 0.0 && c.lon == 0.0) return false;
        if (c.status == null || c.status.isEmpty()) return false;
        return !c.status.equalsIgnoreCase("R-0");
    }

    static SabreAlert toAlert(ChainControl c) {
        long reportTs = parseStatusTs(c.statusDate, c.statusTime);
        if (reportTs <= 0) reportTs = System.currentTimeMillis() / 1000L;
        return new SabreAlert("cc_" + c.index, SabreResponseBuilder.SOURCE_CHAINS,
                "HAZARD_ON_ROAD_SLIPPERY", c.lat, c.lon,
                SabreResponseBuilder.HEADING_UNKNOWN, describe(c), reportTs);
    }

    static String describe(ChainControl c) {
        StringBuilder sb = new StringBuilder("Chains ").append(c.status);
        if (!c.route.isEmpty()) sb.append(" · ").append(c.route);
        if (!c.locationName.isEmpty()) sb.append(" @ ").append(c.locationName);
        if (!c.nearbyPlace.isEmpty()) sb.append(" (").append(c.nearbyPlace).append(')');
        return sb.toString();
    }

    private static long parseStatusTs(String date, String time) {
        if (date == null || date.isEmpty() || time == null || time.isEmpty()) return 0;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            sdf.setTimeZone(TZ_PACIFIC);
            sdf.setLenient(false);
            Date d = sdf.parse(date + " " + time);
            return d != null ? d.getTime() / 1000L : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
