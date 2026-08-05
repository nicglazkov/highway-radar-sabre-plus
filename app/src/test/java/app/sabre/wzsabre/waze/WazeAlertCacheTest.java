package app.sabre.wzsabre.waze;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The cache is the core fix for "Waze alerts vanish mid-drive": the RT server
 * sends each alert once per session then a removal, so query results MUST be
 * merged, not replaced.
 */
public class WazeAlertCacheTest {

    private static WazeAlert alert(String uuid, int thumbs) {
        return new WazeAlert(uuid, 1L, "POLICE", "POLICE_VISIBLE",
                -122.0, 38.0, 0, 1_700_000_000_000L,
                thumbs > 0 ? thumbs : null, "I-80", "Vallejo");
    }

    private static AlertQueryResult adds(WazeAlert... a) {
        return new AlertQueryResult(new ArrayList<>(Arrays.asList(a)), new ArrayList<>());
    }

    private static List<String> uuids(List<WazeAlert> alerts) {
        List<String> out = new ArrayList<>();
        for (WazeAlert a : alerts) out.add(a.uuid);
        Collections.sort(out);
        return out;
    }

    @Test
    public void mergesDeltasAcrossQueries() {
        WazeAlertCache cache = new WazeAlertCache();
        cache.submit(adds(alert("A", 0), alert("B", 0)));
        // Second query is a DELTA, only the new alert. A and B must survive.
        cache.submit(adds(alert("C", 0)));
        assertEquals(Arrays.asList("A", "B", "C"), uuids(cache.snapshot()));
    }

    @Test
    public void removalSoftDeletesButKeepsOthers() {
        WazeAlertCache cache = new WazeAlertCache();
        cache.submit(adds(alert("A", 0), alert("B", 0)));
        cache.submit(new AlertQueryResult(new ArrayList<>(), new ArrayList<>(Collections.singletonList("A"))));
        List<String> visible = uuids(cache.snapshot());
        assertFalse("A is soft-deleted", visible.contains("A"));
        assertTrue("B remains", visible.contains("B"));
        assertEquals("soft-delete keeps the entry cached", 2, cache.size());
    }

    @Test
    public void reAddUndoesSoftDelete() {
        WazeAlertCache cache = new WazeAlertCache();
        cache.submit(adds(alert("A", 0)));
        cache.submit(new AlertQueryResult(new ArrayList<>(), new ArrayList<>(Collections.singletonList("A"))));
        assertTrue(cache.snapshot().isEmpty());
        cache.submit(adds(alert("A", 1)));   // server re-sends it
        assertEquals(Collections.singletonList("A"), uuids(cache.snapshot()));
    }

    @Test
    public void removalOfUnknownIdIsIgnored() {
        WazeAlertCache cache = new WazeAlertCache();
        cache.submit(adds(alert("A", 0)));
        cache.submit(new AlertQueryResult(new ArrayList<>(), new ArrayList<>(Collections.singletonList("ZZZ"))));
        assertEquals(Collections.singletonList("A"), uuids(cache.snapshot()));
    }

    @Test
    public void emptyUuidIsNotCached() {
        WazeAlertCache cache = new WazeAlertCache();
        cache.submit(adds(alert("", 0)));
        assertTrue(cache.snapshot().isEmpty());
    }
}
