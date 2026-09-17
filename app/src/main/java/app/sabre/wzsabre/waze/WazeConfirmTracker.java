package app.sabre.wzsabre.waze;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Derives an alert's {@code confirm_ts} the way the original wzsabre plugin
 * does: the Waze RT feed has no explicit
 * confirmation timestamp, so it is inferred as the moment an alert's thumbs-up
 * count was last seen to INCREASE. State is held per alert id in a map whose
 * entries expire an hour after they were last touched, so it self-trims for
 * alerts that scroll out of view.
 *
 * <p>{@code confirm_count} is simply the thumbs-up count and is read directly off
 * the alert; only the timestamp needs this history.
 */
final class WazeConfirmTracker {
    private static final long TTL_MS = 60 * 60_000L;

    private static final class Meta {
        Long confirmMs;   // null until the thumbs-up count first increases
        int  thumbs;
        long expiry;
    }

    private final Map<String, Meta> seen = new HashMap<>();

    /**
     * Records this sighting's thumbs-up count and returns the confirm timestamp in
     * SECONDS (nullable). First sighting → null; later sighting with a higher count
     * → now; otherwise the previously recorded time (which may be null).
     */
    synchronized Long confirmTsSeconds(String id, Integer nThumbsUp) {
        long now = System.currentTimeMillis();
        purge(now);
        int thumbs = nThumbsUp != null ? nThumbsUp : 0;
        Meta m = seen.get(id);
        if (m == null) {
            m = new Meta();
            m.confirmMs = null;
            m.thumbs = thumbs;
            m.expiry = now + TTL_MS;
            seen.put(id, m);
            return null;
        }
        m.expiry = now + TTL_MS;   // touched → refresh TTL
        if (thumbs > m.thumbs) {
            m.confirmMs = now;
            m.thumbs = thumbs;
            return now / 1000L;
        }
        return m.confirmMs != null ? m.confirmMs / 1000L : null;
    }

    private void purge(long now) {
        Iterator<Map.Entry<String, Meta>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiry <= now) it.remove();
        }
    }
}
