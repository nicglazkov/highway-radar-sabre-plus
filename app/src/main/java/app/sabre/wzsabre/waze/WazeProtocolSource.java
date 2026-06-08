package app.sabre.wzsabre.waze;

import android.util.Log;

import java.util.List;
import java.util.Locale;

/**
 * Public entry point for the Waze mobile-protocol fetch path. Mints an anonymous
 * Waze account, logs in, and queries crowd-sourced alerts around a point.
 *
 * This is the eventual replacement for the (now Waze-blocked) georss WazeSource.
 */
public final class WazeProtocolSource {
    private static final String TAG = "WazeRT";

    private WazeProtocolSource() {}

    static String region(double lat, double lon) {
        if (lon >= -170.0 && lon <= -52.0 && lat >= -15.0 && lat <= 73.0) return "na";
        if (lon >= 34.0 && lon <= 36.0 && lat >= 29.5 && lat <= 33.5) return "il";
        return "row";
    }

    /** Fetch raw Waze alerts near (lat,lon) within radiusMeters. One fresh session per call. */
    public static List<WazeAlert> fetchAlerts(double lat, double lon, double radiusMeters) throws Exception {
        WazeSession session = new WazeSession(region(lat, lon));
        return session.fetchArea(lat, lon, radiusMeters);
    }

    /** Live self-test (debug): register -> login -> query, returns a human-readable summary. */
    public static String selfTest(double lat, double lon) {
        long t0 = System.currentTimeMillis();
        try {
            List<WazeAlert> alerts = fetchAlerts(lat, lon, 10000);
            StringBuilder sb = new StringBuilder("Waze RT OK: " + alerts.size()
                    + " alerts in " + (System.currentTimeMillis() - t0) + "ms");
            int n = 0;
            for (WazeAlert a : alerts) {
                if (n++ >= 6) break;
                sb.append("\n  ").append(a.type).append('/').append(a.subtype)
                  .append(" @ ").append(String.format(Locale.US, "%.5f,%.5f", a.lat, a.lon))
                  .append(a.street != null ? " (" + a.street + ")" : "");
            }
            Log.d(TAG, sb.toString());
            return sb.toString();
        } catch (Exception e) {
            String msg = "Waze RT FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e(TAG, msg, e);
            return msg;
        }
    }
}
