package app.sabre.wzsabre;

import android.net.Network;
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
import javax.net.ssl.HttpsURLConnection;

/**
 * Fetches and parses the CHP statewide live incident XML feed.
 * Filters by radius, incident age, and the per-category settings in ChpConfig.
 */
public class CHPSource {
    private static final String TAG     = "CHPSource";
    private static final String CHP_URL = "https://media.chp.ca.gov/sa_xml/sa.xml";
    private static final int TIMEOUT_MS = 12_000;

    // CHP times are Pacific; parse in that zone so age comparisons are correct
    private static final TimeZone TZ_PACIFIC = TimeZone.getTimeZone("America/Los_Angeles");

    public List<SabreAlert> fetchAlerts(double centerLat, double centerLon,
                                         double radiusMeters, ChpConfig config) {
        List<SabreAlert> results = new ArrayList<>();
        try {
            String xml = fetchXml(null);
            results = parseXml(xml, centerLat, centerLon, radiusMeters, config);
            Log.d(TAG, "CHP: " + results.size() + " alerts within radius");
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch CHP data: " + e.getMessage());
        }
        return results;
    }

    /** Overload kept for tests that don't use config (passes null → all defaults). */
    public List<SabreAlert> fetchAlerts(double centerLat, double centerLon,
                                         double radiusMeters) {
        return fetchAlerts(centerLat, centerLon, radiusMeters, null);
    }

    private String fetchXml(Network network) throws Exception {
        URL url = new URL(CHP_URL);
        HttpsURLConnection conn = network != null
                ? (HttpsURLConnection) network.openConnection(url)
                : (HttpsURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    List<SabreAlert> parseXml(String xml, double centerLat, double centerLon,
                               double radiusMeters, ChpConfig config) throws Exception {
        List<SabreAlert> alerts = new ArrayList<>();
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new StringReader(xml));

        String logId = null, logTime = null, logType = null;
        String location = null, area = null, latlon = null;
        String currentTag = null;

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case XmlPullParser.START_TAG:
                    currentTag = parser.getName();
                    if ("Log".equals(currentTag)) {
                        logId = parser.getAttributeValue(null, "ID");
                        logTime = null; logType = null;
                        location = null; area = null; latlon = null;
                    }
                    break;

                case XmlPullParser.TEXT:
                    String text = cleanValue(parser.getText());
                    if (text.isEmpty()) break;
                    if      ("LogTime".equals(currentTag))  logTime   = text;
                    else if ("LogType".equals(currentTag))  logType   = text;
                    else if ("Location".equals(currentTag)) location  = text;
                    else if ("Area".equals(currentTag))     area      = text;
                    else if ("LATLON".equals(currentTag))   latlon    = text;
                    break;

                case XmlPullParser.END_TAG:
                    if ("Log".equals(parser.getName()) && logId != null && latlon != null) {
                        SabreAlert alert = buildAlert(logId, logType, logTime, location, area,
                                latlon, centerLat, centerLon, radiusMeters, config);
                        if (alert != null) alerts.add(alert);
                    }
                    if ("Log".equals(parser.getName())) currentTag = null;
                    break;
            }
            try {
                eventType = parser.next();
            } catch (Exception e) {
                // CHP's IIS server caps the feed at ~80KB (Content-Length: 81920) and
                // truncates mid-record when statewide incident volume is high, leaving
                // malformed XML at the end. Salvage every complete <Log> parsed so far
                // instead of dropping all of them (which would yield zero alerts exactly
                // when the roads are busiest).
                Log.w(TAG, "CHP feed truncated mid-stream — salvaged " + alerts.size()
                        + " complete records before the cut");
                break;
            }
        }
        return alerts;
    }

    /** Kept for tests that call the old 4-arg overload. */
    List<SabreAlert> parseXml(String xml, double centerLat, double centerLon,
                               double radiusMeters) throws Exception {
        return parseXml(xml, centerLat, centerLon, radiusMeters, null);
    }

    private SabreAlert buildAlert(String logId, String logType, String logTime,
                                   String location, String area, String latlon,
                                   double centerLat, double centerLon, double radiusMeters,
                                   ChpConfig config) {
        // ── coordinate validation ───────────────────────────────────────────
        if (latlon == null || latlon.equals("0:0") || latlon.startsWith("0:")) return null;
        double[] coords;
        try { coords = parseLatLon(latlon); } catch (Exception e) { return null; }
        double lat = coords[0], lon = coords[1];
        if (lat == 0.0 && lon == 0.0) return null;
        if (haversineMeters(centerLat, centerLon, lat, lon) > radiusMeters) return null;

        // ── always-excluded types (admin, not traffic-relevant) ──────────────
        ChpCategory category = AlertMapper.categoryFor(logType);
        if (category == null) return null;  // SILVER / MISSING

        // ── incident age filter ──────────────────────────────────────────────
        long reportTs = parseLogTime(logTime);  // 0 if unparseable
        if (reportTs == 0) reportTs = System.currentTimeMillis() / 1000;

        if (config != null && config.maxAgeMinutes > 0) {
            long cutoffSecs = System.currentTimeMillis() / 1000 - config.maxAgeMinutes * 60L;
            if (reportTs > 0 && reportTs < cutoffSecs) return null;  // too old
        }

        // ── category filter + type resolution ────────────────────────────────
        String naturalType = AlertMapper.fromChpLogType(logType);
        String finalType;
        if (config != null) {
            finalType = config.resolveType(category, naturalType);
        } else {
            finalType = (category.defaultType != null) ? category.defaultType : naturalType;
        }
        if (finalType == null) return null;  // category disabled

        String streetName = buildStreetName(location, area);
        return new SabreAlert(
                "chp_" + logId,
                SabreResponseBuilder.SOURCE_CHP,
                finalType, lat, lon, 0.0, streetName, reportTs);
    }

    // ── Time parsing ──────────────────────────────────────────────────────────

    /**
     * Parses CHP LogTime.  CHP uses two observed formats:
     *   "03/25/2026 10:00 AM"   (12-hour)
     *   "03/25/2026 14:00"      (24-hour, less common)
     * Returns unix seconds (0 on failure).
     */
    static long parseLogTime(String logTime) {
        if (logTime == null || logTime.isEmpty()) return 0;
        String[] formats = { "MM/dd/yyyy hh:mm a", "MM/dd/yyyy HH:mm" };
        for (String fmt : formats) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.US);
                sdf.setTimeZone(TZ_PACIFIC);
                Date d = sdf.parse(logTime);
                if (d != null) return d.getTime() / 1000;
            } catch (Exception ignored) {}
        }
        return 0;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /** "37721302:122169832"  →  [37.721302, -122.169832] */
    static double[] parseLatLon(String latlon) {
        String[] parts = latlon.split(":");
        double lat = Long.parseLong(parts[0].trim()) / 1_000_000.0;
        double lon = -(Long.parseLong(parts[1].trim()) / 1_000_000.0);
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
