package app.sabre.wzsabre;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Locks the privacy guarantee of the diagnostics buffer: it records only source ids,
 * alert-type category names, and counts, and can NEVER contain street names or
 * coordinates even when the alerts it summarizes carry them.
 */
public class DebugLogTest {

    @Before public void reset() { DebugLog.clear(); }

    private SabreAlert alert(String source, String type, String street) {
        // distinctive street + coordinates that must not appear in any shared output
        return new SabreAlert("id-" + type, source, type,
                37.775012345, -122.418698765, 90.0, street, 1_700_000_000L);
    }

    @Test
    public void recordFetch_countsBySourceAndType() {
        DebugLog.recordFetch(Arrays.asList(
                alert("chp", "ACCIDENT_MINOR", "SECRET STREET NAME"),
                alert("chp", "ACCIDENT_MINOR", "123 Private Ave"),
                alert("waze", "HAZARD_ON_ROAD_CONGESTION", "Confidential Blvd")
        ), 5);

        String summary = DebugLog.lastFetchSummary();
        assertTrue("total", summary.contains("sent 3 of 5"));
        assertTrue("per-source counts", summary.contains("chp2") && summary.contains("waze1"));

        Map<String, Integer> types = DebugLog.lastFetchTypes();
        assertEquals(Integer.valueOf(2), types.get("ACCIDENT_MINOR"));
        assertEquals(Integer.valueOf(1), types.get("HAZARD_ON_ROAD_CONGESTION"));
    }

    @Test
    public void recordFetch_neverLeaksStreetsOrCoordinates() {
        DebugLog.recordFetch(Arrays.asList(
                alert("chp", "ACCIDENT_MINOR", "SECRET STREET NAME"),
                alert("waze", "POLICE_VISIBLE", "Confidential Blvd 123 Private Ave")
        ), 2);

        String all = DebugLog.lastFetchSummary() + DebugLog.lastFetchTypes() + DebugLog.recentEvents();
        assertFalse("street name leaked", all.contains("SECRET STREET NAME"));
        assertFalse("street name leaked", all.contains("Confidential") || all.contains("Blvd") || all.contains("Ave"));
        assertFalse("latitude leaked",  all.contains("37.775"));
        assertFalse("longitude leaked", all.contains("122.418"));
    }

    @Test
    public void event_ringBufferCapsAndKeepsMostRecent() {
        for (int i = 0; i < 300; i++) DebugLog.event("evt" + i);
        List<String> ev = DebugLog.recentEvents();
        assertTrue("capped at 150", ev.size() <= 150);
        assertTrue("keeps most recent", ev.get(ev.size() - 1).contains("evt299"));
        assertFalse("drops oldest", ev.get(0).contains("evt0 "));
    }

    @Test
    public void fetchReceived_setsAge() {
        assertEquals(-1, DebugLog.lastFetchAgeMs());
        DebugLog.fetchReceived();
        assertTrue(DebugLog.lastFetchAgeMs() >= 0);
    }

    @Test
    public void handshakeReceived_countsAndLogsDiscovery() {
        assertEquals(0, DebugLog.handshakeCount());
        DebugLog.handshakeReceived();
        DebugLog.handshakeReceived();
        assertEquals("counts each discovery handshake", 2, DebugLog.handshakeCount());
        // a handshake also drops a curated event so the timeline shows re-discovery
        String events = DebugLog.recentEvents().toString();
        assertTrue("logs a discovery event", events.contains("handshake from HR"));
    }
}
