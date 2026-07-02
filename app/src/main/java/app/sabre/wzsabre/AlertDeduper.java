package app.sabre.wzsabre;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collapses duplicate pins that different sources report for the same real-world
 * event — e.g. a CHP accident and a Waze accident at the same intersection, or a
 * CHP closure and a Caltrans LCS closure on the same segment.
 *
 * <p>Two alerts are treated as duplicates when they share an alert "family"
 * (accident / police / closure-congestion / weather / hazard / fire) AND fall in
 * the same ~100&nbsp;m grid cell. The higher-priority source's alert is kept (CHP >
 * LCS > Fire > Waze), and the dropped alert's crowd-confirmation count is folded in
 * so that signal isn't lost.
 *
 * <p>Deliberately conservative: grid bucketing under-merges across cell boundaries
 * (it never merges two events that are actually distinct), and fires get their own
 * family so a real wildfire is never hidden by a co-located generic hazard.
 */
final class AlertDeduper {
    private AlertDeduper() {}

    /** ~0.001° ≈ 111 m of latitude (a bit less in longitude at CA latitudes). */
    private static final double CELL_DEG = 0.001;

    static List<SabreAlert> dedupe(List<SabreAlert> alerts) {
        if (alerts == null || alerts.size() < 2) {
            return alerts == null ? new ArrayList<>() : new ArrayList<>(alerts);
        }
        Map<String, SabreAlert> kept = new LinkedHashMap<>();
        for (SabreAlert a : alerts) {
            if (a == null) continue;
            String key = family(a) + "@" + Math.round(a.lat / CELL_DEG)
                    + "," + Math.round(a.lon / CELL_DEG);
            SabreAlert prev = kept.get(key);
            if (prev == null) {
                kept.put(key, a);
            } else {
                SabreAlert winner = priority(a) >= priority(prev) ? a : prev;
                SabreAlert loser  = winner == a ? prev : a;
                kept.put(key, foldConfirmations(winner, loser));
            }
        }
        return new ArrayList<>(kept.values());
    }

    private static String family(SabreAlert a) {
        // Fires are their own family so a wildfire is never merged with a co-located
        // generic HAZARD_ON_ROAD (which is the same type string).
        if (SabreResponseBuilder.SOURCE_FIRE.equals(a.alertSource)) return "fire";
        String t = a.type == null ? "?" : a.type;
        if (t.startsWith("POLICE"))   return "police";
        if (t.startsWith("ACCIDENT")) return "accident";
        if (t.contains("CONGESTION") || t.startsWith("JAM") || t.contains("CLOS")
                || t.contains("LANE_CLOSURE")) return "closure";
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
