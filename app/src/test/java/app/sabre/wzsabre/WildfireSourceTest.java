package app.sabre.wzsabre;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Tests parsing of the WFIGS ArcGIS incident-locations response and SABRE mapping. */
public class WildfireSourceTest {

    // Shape mirrors the real WFIGS_Incident_Locations_Current query (f=json, outSR=4326):
    // two valid fires plus one feature missing geometry (must be skipped).
    private static final String JSON =
        "{\"geometryType\":\"esriGeometryPoint\",\"spatialReference\":{\"wkid\":4326}," +
        "\"features\":[" +
        "{\"attributes\":{\"IncidentName\":\"PARK FIRE\",\"IncidentSize\":1200.0," +
        "\"PercentContained\":40,\"FireDiscoveryDateTime\":1782000000000," +
        "\"UniqueFireIdentifier\":\"2026-CABTU-001234\"},\"geometry\":{\"x\":-121.5,\"y\":39.8}}," +
        "{\"attributes\":{\"IncidentName\":\"RIDGE FIRE\",\"IncidentSize\":null," +
        "\"PercentContained\":null,\"FireDiscoveryDateTime\":null," +
        "\"UniqueFireIdentifier\":\"2026-CAXXX-000001\"},\"geometry\":{\"x\":-120.0,\"y\":38.0}}," +
        "{\"attributes\":{\"IncidentName\":\"NO GEOM\",\"UniqueFireIdentifier\":\"2026-CAYYY-000002\"}}" +
        "]}";

    @Test
    public void parse_readsFieldsAndSkipsMissingGeometry() throws Exception {
        List<WildfireSource.Fire> fires = WildfireSource.parse(JSON);
        assertEquals("feature without geometry is skipped", 2, fires.size());

        WildfireSource.Fire a = fires.get(0);
        assertEquals("2026-CABTU-001234", a.id);
        assertEquals("PARK FIRE", a.name);
        assertEquals(39.8, a.lat, 1e-6);
        assertEquals(-121.5, a.lon, 1e-6);
        assertEquals(1200.0, a.sizeAcres, 1e-6);
        assertEquals(40.0, a.pctContained, 1e-6);
        assertEquals(1782000000L, a.reportTs);   // ms -> s
    }

    @Test
    public void parse_nullFieldsFallBack() throws Exception {
        WildfireSource.Fire b = WildfireSource.parse(JSON).get(1);
        assertEquals(-1.0, b.sizeAcres, 0.0);       // unknown size
        assertEquals(-1.0, b.pctContained, 0.0);    // unknown containment
        long nowSec = System.currentTimeMillis() / 1000L;
        assertTrue("missing discovery time falls back to now",
                b.reportTs > nowSec - 60 && b.reportTs <= nowSec + 1);
    }

    @Test
    public void parse_emptyOrMissingFeatures() throws Exception {
        assertEquals(0, WildfireSource.parse("{\"features\":[]}").size());
        assertEquals(0, WildfireSource.parse("{}").size());
    }

    @Test(expected = Exception.class)
    public void parse_arcgisErrorBody_throws() throws Exception {
        // ArcGIS returns HTTP 200 with an error body on a bad query — must NOT be
        // treated as "0 fires" (which would hide the outage in diagnostics).
        WildfireSource.parse("{\"error\":{\"code\":400,\"message\":\"Invalid field\"}}");
    }

    @Test
    public void parse_skipsNonWildfireType() throws Exception {
        String json = "{\"features\":[" +
            "{\"attributes\":{\"IncidentName\":\"PRESCRIBED\",\"IncidentTypeCategory\":\"RX\"," +
            "\"UniqueFireIdentifier\":\"2026-CA-RX1\"},\"geometry\":{\"x\":-121.0,\"y\":38.0}}," +
            "{\"attributes\":{\"IncidentName\":\"REAL FIRE\",\"IncidentTypeCategory\":\"WF\"," +
            "\"UniqueFireIdentifier\":\"2026-CA-WF1\"},\"geometry\":{\"x\":-121.0,\"y\":38.0}}" +
            "]}";
        List<WildfireSource.Fire> fires = WildfireSource.parse(json);
        assertEquals("prescribed burn (RX) dropped client-side", 1, fires.size());
        assertEquals("REAL FIRE", fires.get(0).name);
    }

    @Test
    public void toAlert_producesValidSabreAlert() throws Exception {
        WildfireSource.Fire a = WildfireSource.parse(JSON).get(0);
        SabreAlert alert = WildfireSource.toAlert(a);
        assertEquals("fire_2026-CABTU-001234", alert.alertId);
        assertEquals(SabreResponseBuilder.SOURCE_FIRE, alert.alertSource);
        assertEquals("HAZARD_ON_ROAD", alert.type);
        assertTrue("type must be one HR accepts", SabreResponseBuilder.isValidType(alert.type));
        assertEquals(39.8, alert.lat, 1e-6);
        assertEquals("fires are directionless → -720 heading sentinel",
                SabreResponseBuilder.HEADING_UNKNOWN, alert.headingDeg, 0.0);
    }

    @Test
    public void describe_formatsSizeAndContainment() throws Exception {
        List<WildfireSource.Fire> fires = WildfireSource.parse(JSON);
        assertEquals("Wildfire: PARK FIRE · 1,200 ac · 40% contained",
                WildfireSource.describe(fires.get(0)));
        // Unknown size/containment → name only
        assertEquals("Wildfire: RIDGE FIRE", WildfireSource.describe(fires.get(1)));
    }

    // ── Selection: which parsed fires actually get drawn ──────────────────────
    //
    // WFIGS leaves ActiveFireCandidate=1 on records long after the fire is out, so
    // the raw feed carries fully contained fires, months-old leftovers, and
    // non-incident records (training exercises, false alarms). Drawing those as live
    // road hazards is the bug these cover. Observed live on 2026-08-04: 9 of 91
    // "active" CA records were noise, including a 69,352-acre fire 100% contained
    // 16 days earlier.

    private static final double LAT = 39.0, LON = -121.0;   // request centre
    private static final long NOW_MS = 1_785_000_000_000L;
    private static final long DAY_MS = 86_400_000L;

    /** Fire at the request centre, so radius never decides these cases. */
    private static WildfireSource.Fire fire(
            String name, double acres, double pctContained, long ageDays) {
        return new WildfireSource.Fire("id-" + name, name, LAT, LON, acres, pctContained,
                (NOW_MS - ageDays * DAY_MS) / 1000L);
    }

    private static List<SabreAlert> select(WildfireSource.Fire... fires) {
        return WildfireSource.selectAlerts(
                java.util.Arrays.asList(fires), LAT, LON, 50_000, 0, NOW_MS);
    }

    @Test
    public void select_dropsFullyContainedFire() {
        assertEquals("a 100% contained fire is not a live road hazard",
                0, select(fire("BISCAR", 69352, 100, 16)).size());
    }

    @Test
    public void select_keepsNearlyContainedFire() {
        assertEquals("99% contained is still burning",
                1, select(fire("LOOMIS", 656, 99, 23)).size());
    }

    @Test
    public void select_keepsFireWithUnknownContainment() {
        assertEquals("unknown containment must not be read as contained",
                1, select(fire("GREEN", 10, -1, 1)).size());
    }

    @Test
    public void select_dropsNonIncidentRecords() {
        assertEquals("training exercises and false alarms are not fires", 0,
                select(fire("WILDFIRE TRAINING", 0.01, -1, 208),
                       fire("FALSE ALARM", 0.1, -1, 3)).size());
    }

    @Test
    public void select_dropsStaleTinyRecord() {
        assertEquals("a months-old sub-acre leftover is not a live hazard",
                0, select(fire("GRADE", 0.1, -1, 60)).size());
    }

    @Test
    public void select_keepsLongBurningLargeFire() {
        // Big fires legitimately burn for months (2024 Park Fire: 64 days to
        // containment). Age alone must never drop one.
        assertEquals("large uncontained fire survives regardless of age",
                1, select(fire("PARK", 429603, 65, 60)).size());
    }

    @Test
    public void select_keepsFreshUnknownSizeRecord() {
        // Most WFIGS records report no size for the first day or two.
        assertEquals("a new fire with no size yet is still shown",
                1, select(fire("LAC-272069", -1, -1, 1)).size());
    }

    @Test
    public void select_appliesMinAcresButKeepsUnknownSize() {
        List<SabreAlert> out = WildfireSource.selectAlerts(
                java.util.Arrays.asList(
                        fire("SMALL", 5, -1, 1),
                        fire("BIG", 5000, -1, 1),
                        fire("UNSIZED", -1, -1, 1)),
                LAT, LON, 50_000, 100, NOW_MS);
        assertEquals("below-threshold dropped, unknown-size kept", 2, out.size());
    }

    @Test
    public void select_appliesRadius() {
        WildfireSource.Fire far = new WildfireSource.Fire(
                "far", "FAR", LAT + 2.0, LON, 500, 10, NOW_MS / 1000L);
        assertEquals("fire outside the radius is dropped",
                0, WildfireSource.selectAlerts(
                        java.util.Arrays.asList(far), LAT, LON, 50_000, 0, NOW_MS).size());
    }
}
