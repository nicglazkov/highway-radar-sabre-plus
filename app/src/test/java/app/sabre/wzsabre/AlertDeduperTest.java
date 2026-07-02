package app.sabre.wzsabre;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Tests cross-source pin de-duplication. */
public class AlertDeduperTest {

    private static SabreAlert a(String id, String source, String type,
                               double lat, double lon, int confirmCount) {
        return new SabreAlert(id, source, type, lat, lon,
                SabreResponseBuilder.HEADING_UNKNOWN, "St", 1_700_000_000L, null, confirmCount);
    }

    private static final double LAT = 38.4015, LON = -121.8000;

    @Test
    public void mergesCoLocatedSameFamilyAcrossSources() {
        // CHP + Waze accident at essentially the same spot → one pin, CHP kept.
        List<SabreAlert> in = Arrays.asList(
                a("waze_alert-1/u", SabreResponseBuilder.SOURCE_WAZE, "ACCIDENT", LAT, LON, 5),
                a("chp_1", SabreResponseBuilder.SOURCE_CHP, "ACCIDENT_MAJOR", LAT + 0.0001, LON, 0));
        List<SabreAlert> out = AlertDeduper.dedupe(in);
        assertEquals(1, out.size());
        assertEquals("CHP kept over Waze", SabreResponseBuilder.SOURCE_CHP, out.get(0).alertSource);
        assertEquals("Waze crowd-confirmations folded in", 5, out.get(0).confirmCount);
    }

    @Test
    public void keepsDistinctFamiliesAtSameSpot() {
        // A police report and an accident at the same corner are different events.
        List<SabreAlert> in = Arrays.asList(
                a("waze_alert-1/u", SabreResponseBuilder.SOURCE_WAZE, "POLICE_VISIBLE", LAT, LON, 0),
                a("chp_1", SabreResponseBuilder.SOURCE_CHP, "ACCIDENT_MAJOR", LAT, LON, 0));
        assertEquals(2, AlertDeduper.dedupe(in).size());
    }

    @Test
    public void keepsSameFamilyFarApart() {
        List<SabreAlert> in = Arrays.asList(
                a("chp_1", SabreResponseBuilder.SOURCE_CHP, "ACCIDENT_MAJOR", LAT, LON, 0),
                a("chp_2", SabreResponseBuilder.SOURCE_CHP, "ACCIDENT_MAJOR", LAT + 0.02, LON, 0));
        assertEquals(2, AlertDeduper.dedupe(in).size());
    }

    @Test
    public void wildfireNotMergedWithGenericHazard() {
        // Fire and a debris hazard share the HAZARD_ON_ROAD type string but must not merge.
        List<SabreAlert> in = Arrays.asList(
                a("fire_x", SabreResponseBuilder.SOURCE_FIRE, "HAZARD_ON_ROAD", LAT, LON, 0),
                a("chp_1", SabreResponseBuilder.SOURCE_CHP, "HAZARD_ON_ROAD_DEBRIS", LAT, LON, 0));
        assertEquals(2, AlertDeduper.dedupe(in).size());
    }

    @Test
    public void handlesEmptyAndSingle() {
        assertEquals(0, AlertDeduper.dedupe(new ArrayList<>()).size());
        assertEquals(0, AlertDeduper.dedupe(null).size());
        assertEquals(1, AlertDeduper.dedupe(Arrays.asList(
                a("chp_1", SabreResponseBuilder.SOURCE_CHP, "ACCIDENT_MAJOR", LAT, LON, 0))).size());
    }
}
