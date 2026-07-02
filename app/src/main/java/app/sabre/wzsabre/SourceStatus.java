package app.sabre.wzsabre;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide, in-memory health record for each data source, so the settings
 * screen can show a diagnostics panel (last update, item count, last error) even
 * though the sources live inside {@link SabreService}. Both run in the same
 * process, so a static map is sufficient; state is lost on process death, which
 * is fine — it reflects the current session.
 *
 * <p>Each {@link Entry} is immutable and swapped in atomically, so a reader always
 * sees a self-consistent snapshot (never, say, a fresh count next to a stale error).
 */
public final class SourceStatus {
    private SourceStatus() {}

    public static final class Entry {
        public final boolean ran;          // attempted at least one refresh
        public final long    lastUpdateMs; // wall-clock of last SUCCESSFUL refresh (0 if none)
        public final int     count;        // items held as of the last success
        public final String  lastError;    // non-null if the most recent refresh failed

        Entry(boolean ran, long lastUpdateMs, int count, String lastError) {
            this.ran = ran;
            this.lastUpdateMs = lastUpdateMs;
            this.count = count;
            this.lastError = lastError;
        }
    }

    private static final ConcurrentHashMap<String, Entry> MAP = new ConcurrentHashMap<>();

    /** Record a successful refresh holding {@code count} items. */
    public static void success(String source, int count) {
        MAP.put(source, new Entry(true, System.currentTimeMillis(), count, null));
    }

    /** Record a failed refresh (keeps the previous count / lastUpdateMs). */
    public static void failure(String source, String error) {
        Entry prev = MAP.get(source);
        long lastUpdate = prev != null ? prev.lastUpdateMs : 0L;
        int count = prev != null ? prev.count : 0;
        MAP.put(source, new Entry(true, lastUpdate, count, error));
    }

    /** @return the current snapshot for a source, or null if it has never run. */
    public static Entry get(String source) {
        return MAP.get(source);
    }
}
