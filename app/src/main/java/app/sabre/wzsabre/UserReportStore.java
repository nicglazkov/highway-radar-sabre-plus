package app.sabre.wzsabre;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Holds the user's own HR reports for a while so every HR fetch echoes them back
 * and HR draws the pin, independent of whether Waze accepts the report. In-memory,
 * thread-safe, entries expire after {@link #TTL_MS}.
 */
public final class UserReportStore {
    public static final long TTL_MS = 30 * 60 * 1000L;
    private static final double MATCH_RADIUS_M = 120.0;

    private static final class Entry {
        final SabreAlert alert;
        final long addedMs;
        Entry(SabreAlert a, long t) { alert = a; addedMs = t; }
    }

    private final List<Entry> entries = new ArrayList<>();

    public synchronized void add(ReportRequest r, long nowMs) {
        String type = AlertMapper.renderableEchoType(r.type);
        if (type == null) return; // nothing HR would draw
        // The response builder drops any type not in its VALID_TYPES set. Guarantee the
        // echo renders by falling back to a known-valid renderable type when the mapped
        // type is renderable-prefixed but not in that set.
        if (!SabreResponseBuilder.isValidType(type)) type = "HAZARD_ON_ROAD_DEBRIS";
        String id = "alert-0/userreport-" + nowMs;
        long reportTs = (nowMs / 1000L) - r.timeDeltaS;
        SabreAlert a = new SabreAlert(id, SabreResponseBuilder.SOURCE_WAZE, type,
                r.lat, r.lon, r.headingDeg, null, reportTs, null, 0);
        entries.add(new Entry(a, nowMs));
    }

    public synchronized List<SabreAlert> activeAlerts(double lat, double lon,
                                                      double radiusM, long nowMs) {
        purge(nowMs);
        List<SabreAlert> out = new ArrayList<>();
        for (Entry e : entries) {
            if (distanceM(lat, lon, e.alert.lat, e.alert.lon) <= radiusM) out.add(e.alert);
        }
        return out;
    }

    public synchronized void removeNear(double lat, double lon, long nowMs) {
        purge(nowMs);
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (distanceM(lat, lon, e.alert.lat, e.alert.lon) <= MATCH_RADIUS_M) it.remove();
        }
    }

    private void purge(long nowMs) {
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            if (nowMs - it.next().addedMs > TTL_MS) it.remove();
        }
    }

    private static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double mPerDegLat = 110574.0;
        double mPerDegLon = 111320.0 * Math.cos(Math.toRadians((lat1 + lat2) / 2.0));
        double dy = (lat1 - lat2) * mPerDegLat;
        double dx = (lon1 - lon2) * mPerDegLon;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
