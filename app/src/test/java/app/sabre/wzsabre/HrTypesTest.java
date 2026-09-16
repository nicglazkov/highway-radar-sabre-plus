package app.sabre.wzsabre;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Highway Radar 3.2 (update 7) draws a plugin alert only when its type is POLICE*,
 * ACCIDENT_MAJOR/MINOR/ACCIDENT, or one of six exact hazard names. Every other
 * HAZARD_* string is silently dropped, so the wire type must be translated to
 * that vocabulary at the response boundary. Verified against the live app on the
 * emulator on 2026-09-16 (two-column synthetic injection).
 */
public class HrTypesTest {

    @Test
    public void policeSuffixesPassThroughUnchanged() {
        assertEquals("POLICE_VISIBLE", HrTypes.toHrType("POLICE_VISIBLE"));
        assertEquals("POLICE_HIDDEN", HrTypes.toHrType("POLICE_HIDDEN"));
        assertEquals("POLICE_HIDING", HrTypes.toHrType("POLICE_HIDING"));
        assertEquals("POLICE_WITH_MOBILE_CAMERA", HrTypes.toHrType("POLICE_WITH_MOBILE_CAMERA"));
    }

    @Test
    public void accidentsPassThroughUnchanged() {
        assertEquals("ACCIDENT_MAJOR", HrTypes.toHrType("ACCIDENT_MAJOR"));
        assertEquals("ACCIDENT_MINOR", HrTypes.toHrType("ACCIDENT_MINOR"));
        assertEquals("ACCIDENT", HrTypes.toHrType("ACCIDENT"));
    }

    @Test
    public void highwayRadarNativeHazardsPassThroughUnchanged() {
        for (String t : new String[]{
                "HAZARD_ON_ROAD_OBJECT", "HAZARD_ON_ROAD_POT_HOLE", "HAZARD_ON_ROAD_CAR_STOPPED",
                "HAZARD_ON_ROAD_ROAD_KILL", "HAZARD_ON_SHOULDER_CAR_STOPPED", "HAZARD_ON_SHOULDER_ANIMALS"}) {
            assertEquals(t, HrTypes.toHrType(t));
        }
    }

    @Test
    public void debrisBecomesObjectOnRoad() {
        assertEquals("HAZARD_ON_ROAD_OBJECT", HrTypes.toHrType("HAZARD_ON_ROAD_DEBRIS"));
    }

    @Test
    public void congestionBecomesVehicleStoppedOnRoad() {
        assertEquals("HAZARD_ON_ROAD_CAR_STOPPED", HrTypes.toHrType("HAZARD_ON_ROAD_CONGESTION"));
    }

    @Test
    public void everyOtherHazardBecomesGenericObjectOnRoad() {
        for (String t : new String[]{
                "HAZARD_ON_ROAD_SLIPPERY", "HAZARD_ON_ROAD", "HAZARD_WEATHER_FOG",
                "HAZARD_WEATHER_SNOW", "HAZARD_ON_ROAD_ICE", "HAZARD_ON_ROAD_CONSTRUCTION",
                "HAZARD_ON_ROAD_LANE_CLOSED", "HAZARD_ON_SHOULDER", "HAZARD_ON_SHOULDER_MISSING_SIGN",
                "HAZARD_WEATHER", "HAZARD"}) {
            assertEquals(t, "HAZARD_ON_ROAD_OBJECT", HrTypes.toHrType(t));
        }
    }

    @Test
    public void nonRenderablePrefixesAreLeftAlone() {
        // HR drops these by design (no POLICE/HAZARD/ACCIDENT prefix); do not invent a hazard.
        assertEquals("CHIT_CHAT", HrTypes.toHrType("CHIT_CHAT"));
        assertEquals("JAM_HEAVY_TRAFFIC", HrTypes.toHrType("JAM_HEAVY_TRAFFIC"));
        assertNull(HrTypes.toHrType(null));
    }
}
