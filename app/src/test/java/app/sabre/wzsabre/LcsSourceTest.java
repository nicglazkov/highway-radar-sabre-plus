package app.sabre.wzsabre;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/** Tests Caltrans LCS feed parsing, active-closure filtering, and SABRE mapping. */
public class LcsSourceTest {

    private static final long NOW = 1781041500L; // 2026-06-09 ~14:45 PDT

    /** One <lcs> record in the real feed shape (subset of fields the parser reads). */
    private static String record(String index, double bLat, double bLon,
                                 double eLat, double eLon, String type, String lanes,
                                 long startEpoch, long endEpoch, boolean indefinite,
                                 boolean c1097, boolean c1098, boolean c1022) {
        return "<lcs><index>" + index + "</index>" +
            "<location><travelFlowDirection>North / South</travelFlowDirection>" +
            "<begin>" +
            "<beginLocationName>San Bruno Ave</beginLocationName>" +
            "<beginNearbyPlace>Half Moon Bay</beginNearbyPlace>" +
            "<beginLongitude>" + bLon + "</beginLongitude>" +
            "<beginLatitude>" + bLat + "</beginLatitude>" +
            "<beginCounty>San Mateo</beginCounty>" +
            "<beginRoute>SR-1</beginRoute>" +
            "</begin><end>" +
            "<endLocationName>Stage Road</endLocationName>" +
            "<endLongitude>" + eLon + "</endLongitude>" +
            "<endLatitude>" + eLat + "</endLatitude>" +
            "<endRoute>SR-1</endRoute>" +
            "</end></location>" +
            "<closure><closureID>C1TB</closureID><logNumber>2</logNumber>" +
            "<closureTimestamp>" +
            "<closureStartEpoch>" + startEpoch + "</closureStartEpoch>" +
            "<closureEndEpoch>" + endEpoch + "</closureEndEpoch>" +
            "<isClosureEndIndefinite>" + indefinite + "</isClosureEndIndefinite>" +
            "</closureTimestamp>" +
            "<facility>Conventional Hwy</facility>" +
            "<typeOfClosure>" + type + "</typeOfClosure>" +
            "<typeOfWork>Drainage Work</typeOfWork>" +
            "<estimatedDelay>5</estimatedDelay>" +
            "<lanesClosed>" + lanes + "</lanesClosed>" +
            "<totalExistingLanes>2</totalExistingLanes>" +
            "<code1097><isCode1097>" + c1097 + "</isCode1097>" +
            "<code1097Timestamp><code1097Epoch>" + (c1097 ? startEpoch + 60 : "") + "</code1097Epoch></code1097Timestamp></code1097>" +
            "<code1098><isCode1098>" + c1098 + "</isCode1098>" +
            "<code1098Timestamp><code1098Epoch></code1098Epoch></code1098Timestamp></code1098>" +
            "<code1022><isCode1022>" + c1022 + "</isCode1022>" +
            "<code1022Timestamp><code1022Epoch></code1022Epoch></code1022Timestamp></code1022>" +
            "</closure></lcs>";
    }

    private static String feed(String... records) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><data>");
        for (String r : records) sb.append(r);
        return sb.append("</data>").toString();
    }

    private static List<LcsSource.Closure> parse(String xml) throws Exception {
        return LcsSource.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private static LcsSource.Closure activeClosure() {
        try {
            return parse(feed(record("A-1", 37.329044, -122.395559, 37.337593, -122.394162,
                    "Full", "All", NOW - 3600, NOW + 3600, false, true, false, false))).get(0);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ── parsing ───────────────────────────────────────────────────────────────

    @Test
    public void parse_readsAllFields() throws Exception {
        LcsSource.Closure c = activeClosure();
        assertEquals("A-1", c.index);
        assertEquals(37.329044, c.beginLat, 1e-6);
        assertEquals(-122.395559, c.beginLon, 1e-6);
        assertEquals(37.337593, c.endLat, 1e-6);
        assertEquals("SR-1", c.route);
        assertEquals("San Bruno Ave", c.locationName);
        assertEquals("Half Moon Bay", c.nearbyPlace);
        assertEquals("Full", c.typeOfClosure);
        assertEquals("All", c.lanesClosed);
        assertEquals(NOW - 3600, c.startEpoch);
        assertEquals(NOW + 3600, c.endEpoch);
        assertTrue(c.is1097);
        assertFalse(c.is1098);
        assertFalse(c.is1022);
        assertEquals(NOW - 3600 + 60, c.epoch1097);
    }

    @Test
    public void parse_multipleRecords() throws Exception {
        List<LcsSource.Closure> all = parse(feed(
            record("A-1", 37.3, -122.4, 37.31, -122.41, "Full", "All", NOW - 100, NOW + 100, false, true, false, false),
            record("A-2", 38.0, -121.9, 38.01, -121.91, "Lane", "1",  NOW - 100, NOW + 100, false, false, false, false)));
        assertEquals(2, all.size());
        assertEquals("A-2", all.get(1).index);
        assertFalse(all.get(1).is1097);
    }

    @Test
    public void parse_truncatedFeed_salvagesCompleteRecords() throws Exception {
        String good = record("A-1", 37.3, -122.4, 37.31, -122.41, "Full", "All",
                NOW - 100, NOW + 100, false, true, false, false);
        String xml = "<?xml version=\"1.0\"?><data>" + good + "<lcs><index>A-2</index><locati";
        List<LcsSource.Closure> all = parse(xml);
        assertEquals(1, all.size());
        assertEquals("A-1", all.get(0).index);
    }

    // ── active filter ─────────────────────────────────────────────────────────

    @Test
    public void isActive_inPlaceNow() {
        assertTrue(LcsSource.isActive(activeClosure(), NOW));
    }

    @Test
    public void isActive_not1097_isInactive() throws Exception {
        LcsSource.Closure c = parse(feed(record("A-1", 37.3, -122.4, 37.31, -122.41,
                "Full", "All", NOW - 100, NOW + 100, false, false, false, false))).get(0);
        assertFalse("scheduled but not established (no 1097) must not alert",
                LcsSource.isActive(c, NOW));
    }

    @Test
    public void isActive_pickedUp1098_isInactive() throws Exception {
        LcsSource.Closure c = parse(feed(record("A-1", 37.3, -122.4, 37.31, -122.41,
                "Full", "All", NOW - 100, NOW + 100, false, true, true, false))).get(0);
        assertFalse(LcsSource.isActive(c, NOW));
    }

    @Test
    public void isActive_canceled1022_isInactive() throws Exception {
        LcsSource.Closure c = parse(feed(record("A-1", 37.3, -122.4, 37.31, -122.41,
                "Full", "All", NOW - 100, NOW + 100, false, true, false, true))).get(0);
        assertFalse(LcsSource.isActive(c, NOW));
    }

    @Test
    public void isActive_ghostRecordLongPastEnd_isInactive() throws Exception {
        // 1097'd but the scheduled end passed >4h ago and never 1098'd
        LcsSource.Closure c = parse(feed(record("A-1", 37.3, -122.4, 37.31, -122.41,
                "Full", "All", NOW - 86400, NOW - 6 * 3600, false, true, false, false))).get(0);
        assertFalse(LcsSource.isActive(c, NOW));
    }

    @Test
    public void isActive_overrunWithinGrace_staysActive() throws Exception {
        // ended 1h ago by schedule but still 1097 — crews running late
        LcsSource.Closure c = parse(feed(record("A-1", 37.3, -122.4, 37.31, -122.41,
                "Full", "All", NOW - 86400, NOW - 3600, false, true, false, false))).get(0);
        assertTrue(LcsSource.isActive(c, NOW));
    }

    @Test
    public void isActive_indefiniteEnd_staysActive() throws Exception {
        LcsSource.Closure c = parse(feed(record("A-1", 37.3, -122.4, 37.31, -122.41,
                "Full", "All", NOW - 86400, 0, true, true, false, false))).get(0);
        assertTrue(LcsSource.isActive(c, NOW));
    }

    @Test
    public void isActive_zeroCoords_isInactive() throws Exception {
        LcsSource.Closure c = parse(feed(record("A-1", 0.0, 0.0, 0.0, 0.0,
                "Full", "All", NOW - 100, NOW + 100, false, true, false, false))).get(0);
        assertFalse(LcsSource.isActive(c, NOW));
    }

    // ── shoulder-only filtering ───────────────────────────────────────────────

    @Test
    public void shoulderOnly_isSkipped() throws Exception {
        LcsSource.Closure c = parse(feed(record("A-1", 37.3, -122.4, 37.31, -122.41,
                "Lane", "RShoulder", NOW - 100, NOW + 100, false, true, false, false))).get(0);
        assertTrue(LcsSource.isShoulderOnly(c));
        assertFalse("shoulder-only closure must not alert", LcsSource.isActive(c, NOW));
    }

    @Test
    public void laneWithShoulder_isKept() {
        LcsSource.Closure c = activeClosure();
        c.lanesClosed = "3, RShoulder";
        assertFalse(LcsSource.isShoulderOnly(c));
        c.lanesClosed = "Median, 1";
        assertFalse(LcsSource.isShoulderOnly(c));
        c.lanesClosed = "Left HOV";
        assertFalse(LcsSource.isShoulderOnly(c));
        c.lanesClosed = "1, Left Turn";
        assertFalse(LcsSource.isShoulderOnly(c));
        c.lanesClosed = "All";
        assertFalse(LcsSource.isShoulderOnly(c));
        c.lanesClosed = "Median";
        assertTrue(LcsSource.isShoulderOnly(c));
    }

    // ── SABRE mapping ─────────────────────────────────────────────────────────

    @Test
    public void toAlerts_validSabreFields() {
        List<SabreAlert> alerts = LcsSource.toAlerts(activeClosure());
        assertFalse(alerts.isEmpty());
        SabreAlert a = alerts.get(0);
        assertEquals("lcs_A-1", a.alertId);
        assertEquals(SabreResponseBuilder.SOURCE_LCS, a.alertSource);
        assertEquals("HAZARD_ON_ROAD_CONGESTION", a.type);
        assertTrue(SabreResponseBuilder.isValidType(a.type));
        assertEquals(37.329044, a.lat, 1e-6);
        assertTrue("report_ts should be the 1097 epoch", a.reportTs > 0);
        assertTrue(a.reportTs <= Integer.MAX_VALUE);
    }

    @Test
    public void toAlerts_shortSpan_singlePin() throws Exception {
        // begin and end ~950m apart → one pin
        LcsSource.Closure c = parse(feed(record("A-1", 37.3290, -122.3956, 37.3375, -122.3942,
                "Full", "All", NOW - 100, NOW + 100, false, true, false, false))).get(0);
        c.endLat = 37.3320; c.endLon = -122.3950;
        assertEquals(1, LcsSource.toAlerts(c).size());
    }

    @Test
    public void toAlerts_longSpan_beginAndEndPins() {
        LcsSource.Closure c = activeClosure();
        c.endLat = c.beginLat + 0.05;  // ~5.5km away
        List<SabreAlert> alerts = LcsSource.toAlerts(c);
        assertEquals(2, alerts.size());
        assertEquals("lcs_A-1_end", alerts.get(1).alertId);
        assertTrue(alerts.get(1).streetName.startsWith("End: "));
    }

    @Test
    public void describe_fullClosure() {
        LcsSource.Closure c = activeClosure();
        assertEquals("SR-1 FULL CLOSURE @ San Bruno Ave (Half Moon Bay)", LcsSource.describe(c));
    }

    @Test
    public void describe_laneClosure_includesLanes() {
        LcsSource.Closure c = activeClosure();
        c.typeOfClosure = "Lane";
        c.lanesClosed = "2, RShoulder";
        assertEquals("SR-1 Lane closure @ San Bruno Ave (Half Moon Bay) · lanes: 2, RShoulder",
                LcsSource.describe(c));
    }

    // ── district selection ────────────────────────────────────────────────────

    @Test
    public void districtsFor_bayArea_includesD4() {
        List<Integer> d = LcsSource.districtsFor(37.7749, -122.4194, 50_000);
        assertTrue("SF must map to district 4", d.contains(4));
    }

    @Test
    public void districtsFor_sacramento_includesD3() {
        assertTrue(LcsSource.districtsFor(38.58, -121.49, 50_000).contains(3));
    }

    @Test
    public void districtsFor_tahoeTruckee_includesD3() {
        assertTrue(LcsSource.districtsFor(39.33, -120.20, 50_000).contains(3));
    }

    @Test
    public void districtsFor_stockton_includesD10() {
        assertTrue(LcsSource.districtsFor(37.95, -121.29, 50_000).contains(10));
    }

    @Test
    public void districtsFor_borderArea_returnsMultiple() {
        // Livermore sits near the D4/D10 boundary; a big radius should pull both
        List<Integer> d = LcsSource.districtsFor(37.70, -121.77, 80_000);
        assertTrue(d.contains(4));
        assertTrue(d.contains(10));
    }

    @Test
    public void districtsFor_outOfState_isEmpty() {
        assertTrue(LcsSource.districtsFor(45.0, -100.0, 50_000).isEmpty());
    }

    @Test
    public void feedUrl_zeroPadsDistrict() {
        assertEquals("https://cwwp2.dot.ca.gov/data/d4/lcs/lcsStatusD04.xml", LcsSource.feedUrl(4));
        assertEquals("https://cwwp2.dot.ca.gov/data/d10/lcs/lcsStatusD10.xml", LcsSource.feedUrl(10));
    }
}
