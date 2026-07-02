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
}
