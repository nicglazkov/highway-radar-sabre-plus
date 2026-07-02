package app.sabre.wzsabre;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide, in-memory health record for each data source, so the settings
 * screen can show a diagnostics panel (last update, item count, last error) even
 * though the sources live inside {@link SabreService}. Both run in the same
 * process, so plain statics are sufficient; state is lost on process death, which
 * is fine — it reflects the current session.
 */
public final class SourceStatus {
    private SourceStatus() {}

    public static final class Entry {
        public volatile boolean ran;          // attempted at least one refresh
        public volatile long    lastUpdateMs; // wall-clock of last SUCCESSFUL refresh
        public volatile int     count;        // items the source currently holds
        public volatile String  lastError;    // non-null if the most recent refresh failed
    }

    private static final ConcurrentHashMap<String, Entry> MAP = new ConcurrentHashMap<>();

    private static Entry entry(String source) {
        return MAP.computeIfAbsent(source, k -> new Entry());
    }

    /** Record a successful refresh holding {@code count} items. */
    public static void success(String source, int count) {
        Entry e = entry(source);
        e.ran = true;
        e.count = count;
        e.lastUpdateMs = System.currentTimeMillis();
        e.lastError = null;
    }

    /** Record a failed refresh (keeps the previous count / lastUpdateMs). */
    public static void failure(String source, String error) {
        Entry e = entry(source);
        e.ran = true;
        e.lastError = error;
    }

    /** @return the entry for a source, or null if it has never run. */
    public static Entry get(String source) {
        return MAP.get(source);
    }
}
