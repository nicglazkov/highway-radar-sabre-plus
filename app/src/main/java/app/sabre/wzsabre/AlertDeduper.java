package app.sabre.wzsabre;

import java.util.ArrayList;
import java.util.List;

/**
 * Collapses duplicate pins that DIFFERENT sources report for the same real-world
 * event: e.g. a CHP accident and a Waze accident at the same intersection, or a
 * CHP closure and a Caltrans LCS closure on the same segment.
 *
 * <p>Two alerts are duplicates when they come from different sources, share an alert
 * "family" (accident / police / camera / sos / closure-congestion / construction /
 * weather / hazard / fire), and are within {@value #DEDUPE_METERS} m of each other.
 * The higher-priority source's alert is kept (CHP &gt; LCS &gt; Fire &gt; Waze) and the
 * dropped alert's crowd-confirmation count is folded in.
 *
 * <p>Only cross-source pairs are ever merged: two alerts from the SAME source are
 * always kept, because in a safety app two co-located same-family reports may be two
 * genuinely distinct incidents and must not be silently dropped. Distance is a real
 * great-circle check (not a grid), so there's no cell-boundary blind spot.
 */
final class AlertDeduper {
    private AlertDeduper() {}

    private static final double DEDUPE_METERS = 120.0;

    static List<SabreAlert> dedupe(List<SabreAlert> alerts) {
        if (alerts == null || alerts.size() < 2) {
            return alerts == null ? new ArrayList<>() : new ArrayList<>(alerts);
        }
        List<SabreAlert> survivors = new ArrayList<>();
        for (SabreAlert a : alerts) {
            if (a == null) continue;
            boolean merged = false;
            for (int i = 0; i < survivors.size(); i++) {
                SabreAlert s = survivors.get(i);
                // Cross-source only: never merge two alerts from the same source.
                if (a.alertSource == null || a.alertSource.equals(s.alertSource)) continue;
                if (!family(a).equals(family(s))) continue;
                if (CHPSource.haversineMeters(a.lat, a.lon, s.lat, s.lon) > DEDUPE_METERS) continue;
                SabreAlert winner = priority(a) >= priority(s) ? a : s;
                SabreAlert loser  = winner == a ? s : a;
                survivors.set(i, foldConfirmations(winner, loser));
                merged = true;
                break;
            }
            if (!merged) survivors.add(a);
        }
        return survivors;
    }

    private static String family(SabreAlert a) {
        // Fires are their own family so a wildfire is never merged with a co-located
        // generic HAZARD_ON_ROAD (which is the same type string).
        if (SabreResponseBuilder.SOURCE_FIRE.equals(a.alertSource)) return "fire";
        String t = a.type == null ? "?" : a.type;
        if (t.startsWith("POLICE"))     return "police";
        if (t.contains("CAMERA"))       return "camera";
        if (t.startsWith("SOS"))        return "sos";
        if (t.startsWith("ACCIDENT"))   return "accident";
        // "CLOS" catches ROAD_CLOSED / TURN_CLOSED / LANE_CLOSURE; checked before
        // construction so ROAD_CLOSED_CONSTRUCTION groups as a closure.
        if (t.contains("CLOS") || t.contains("CONGESTION") || t.startsWith("JAM")) return "closure";
        if (t.contains("CONSTRUCTION")) return "construction";
        if (t.startsWith("HAZARD_WEATHER") || t.contains("BAD_WEATHER")) return "weather";
        return "hazard";
    }

    /** Source trust order for which duplicate to keep. */
    private static int priority(SabreAlert a) {
        if (SabreResponseBuilder.SOURCE_CHP.equals(a.alertSource))  return 4;
        if (SabreResponseBuilder.SOURCE_LCS.equals(a.alertSource))  return 3;
        if (SabreResponseBuilder.SOURCE_FIRE.equals(a.alertSource)) return 2;
        if (SabreResponseBuilder.SOURCE_WAZE.equals(a.alertSource)) return 1;
        return 0;
    }

    /** Keep the winner, but carry over the higher crowd-confirmation signal. */
    private static SabreAlert foldConfirmations(SabreAlert w, SabreAlert l) {
        int cc = Math.max(w.confirmCount, l.confirmCount);
        Long cts = w.confirmTs != null ? w.confirmTs : l.confirmTs;
        boolean sameCts = (cts == null) ? (w.confirmTs == null) : cts.equals(w.confirmTs);
        if (cc == w.confirmCount && sameCts) return w;   // nothing to change
        return new SabreAlert(w.alertId, w.alertSource, w.type, w.lat, w.lon, w.headingDeg,
                w.streetName, w.reportTs, cts, cc);
    }
}
