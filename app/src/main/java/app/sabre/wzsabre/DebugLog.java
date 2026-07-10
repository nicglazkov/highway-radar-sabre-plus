package app.sabre.wzsabre;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small, privacy-safe, in-memory ring buffer of curated diagnostic events, plus
 * the last fetch's per-source and per-type counts. It powers the user-facing
 * "Share diagnostics" report so a user can troubleshoot without ADB.
 *
 * <p><b>This is deliberately separate from {@code android.util.Log} (logcat).</b>
 * Logcat lines carry GPS coordinates and alert street names, which must never be
 * shared. Nothing recorded here may contain location, street names, alert IDs, or
 * any personal data: only counts, source ids, alert-type category names, versions,
 * and error strings. {@link #recordFetch} reads only {@code alertSource} and
 * {@code type} off each alert, so it cannot leak where the user is.
 */
public final class DebugLog {
    private DebugLog() {}

    private static final int MAX_EVENTS = 60;

    private static final class Ev {
        final long ts; final String msg;
        Ev(long ts, String msg) { this.ts = ts; this.msg = msg; }
    }

    private static final Deque<Ev> EVENTS = new ArrayDeque<>();
    private static volatile long lastFetchReceivedMs = 0;
    private static volatile String lastFetchSummary = null;
    private static final Map<String, Integer> lastFetchTypes = new LinkedHashMap<>();

    /** Record a curated event. Callers MUST NOT pass coordinates, street names, or PII. */
    public static synchronized void event(String msg) {
        EVENTS.addLast(new Ev(System.currentTimeMillis(), msg));
        while (EVENTS.size() > MAX_EVENTS) EVENTS.removeFirst();
    }

    /** Note that Highway Radar asked us for data (drives "HR last requested Ns ago"). */
    public static synchronized void fetchReceived() {
        lastFetchReceivedMs = System.currentTimeMillis();
    }

    /**
     * Record a fetch response as per-source and per-type counts. Reads only
     * {@code alertSource} and {@code type}, never lat/lon/street, so it is incapable
     * of leaking location or personal data.
     */
    public static synchronized void recordFetch(List<SabreAlert> sent, int rawCount) {
        Map<String, Integer> perSource = new LinkedHashMap<>();
        Map<String, Integer> perType = new LinkedHashMap<>();
        for (SabreAlert a : sent) {
            bump(perSource, a.alertSource);
            bump(perType, a.type);
        }
        StringBuilder src = new StringBuilder();
        for (Map.Entry<String, Integer> e : perSource.entrySet()) {
            if (src.length() > 0) src.append(' ');
            src.append(e.getKey()).append(e.getValue());
        }
        lastFetchSummary = "sent " + sent.size() + " of " + rawCount
                + (src.length() > 0 ? " (" + src + ")" : "");
        lastFetchTypes.clear();
        lastFetchTypes.putAll(perType);
        event("fetch: " + lastFetchSummary);
    }

    private static void bump(Map<String, Integer> m, String key) {
        String k = (key != null) ? key : "?";
        Integer n = m.get(k);
        m.put(k, (n == null) ? 1 : n + 1);
    }

    public static synchronized long lastFetchAgeMs() {
        return (lastFetchReceivedMs == 0) ? -1 : System.currentTimeMillis() - lastFetchReceivedMs;
    }

    public static synchronized String lastFetchSummary() { return lastFetchSummary; }

    public static synchronized Map<String, Integer> lastFetchTypes() {
        return new LinkedHashMap<>(lastFetchTypes);
    }

    /** Recent events as display lines, e.g. "  12s ago  fetch: sent 59 (chp8 waze46 ...)". */
    public static synchronized List<String> recentEvents() {
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>();
        for (Ev e : EVENTS) {
            long ageS = Math.max(0, (now - e.ts) / 1000);
            out.add(String.format(Locale.US, "%5ds ago  %s", ageS, e.msg));
        }
        return out;
    }

    /** Test/reset hook. */
    static synchronized void clear() {
        EVENTS.clear();
        lastFetchReceivedMs = 0;
        lastFetchSummary = null;
        lastFetchTypes.clear();
    }
}
