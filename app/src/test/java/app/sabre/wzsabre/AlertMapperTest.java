package app.sabre.wzsabre;

import org.junit.Test;
import static org.junit.Assert.*;

/** Tests that CHP and Waze alert types map to valid SABRE type constants. */
public class AlertMapperTest {

    // Valid SABRE types that HR knows how to render
    private static final String[] VALID_TYPES = {
        "POLICE_VISIBLE", "POLICE_HIDDEN",
        "ACCIDENT_MAJOR", "ACCIDENT_MINOR",
        "HAZARD_ON_ROAD_DEBRIS", "HAZARD_ON_ROAD_CONGESTION",
        "HAZARD_ON_ROAD_SLIPPERY", "HAZARD_ON_ROAD_POT_HOLE",
        "HAZARD_WEATHER_FOG", "HAZARD_WEATHER_RAIN", "HAZARD_WEATHER_SNOW",
        "HAZARD_WEATHER_WIND", "HAZARD_WEATHER_STORM", "HAZARD_WEATHER_HAIL"
    };

    private static boolean isValidSabreType(String t) {
        if (t == null) return false;
        for (String v : VALID_TYPES) if (v.equals(t)) return true;
        return false;
    }

    // ── Waze type mapping ─────────────────────────────────────────────────────

    @Test public void waze_police_visible()  { assertEquals("POLICE_VISIBLE",  AlertMapper.fromWazeType("POLICE", "")); }
    @Test public void waze_police_hidden()   { assertEquals("POLICE_HIDDEN",   AlertMapper.fromWazeType("POLICE", "POLICE_HIDDEN")); }
    @Test public void waze_police_hiding()   { assertEquals("POLICE_HIDDEN",   AlertMapper.fromWazeType("POLICE", "POLICE_HIDING")); }
    @Test public void waze_police_camera()   { assertEquals("POLICE_HIDDEN",   AlertMapper.fromWazeType("POLICE", "POLICE_WITH_MOBILE_CAMERA")); }
    @Test public void waze_camera_type()     { assertEquals("POLICE_HIDDEN",   AlertMapper.fromWazeType("CAMERA", "")); }
    @Test public void waze_accident_major()  { assertEquals("ACCIDENT_MAJOR",  AlertMapper.fromWazeType("ACCIDENT", "ACCIDENT_MAJOR")); }
    @Test public void waze_accident_minor()  { assertEquals("ACCIDENT_MINOR",  AlertMapper.fromWazeType("ACCIDENT", "")); }
    @Test public void waze_hazard_debris()   { assertEquals("HAZARD_ON_ROAD_DEBRIS",      AlertMapper.fromWazeType("HAZARD", "")); }
    @Test public void waze_hazard_congestion(){ assertEquals("HAZARD_ON_ROAD_CONGESTION", AlertMapper.fromWazeType("HAZARD", "HAZARD_ON_ROAD_CONGESTION")); }
    @Test public void waze_hazard_slippery() { assertEquals("HAZARD_ON_ROAD_SLIPPERY",   AlertMapper.fromWazeType("HAZARD", "HAZARD_ON_ROAD_ICE")); }
    @Test public void waze_hazard_pothole()  { assertEquals("HAZARD_ON_ROAD_POT_HOLE",   AlertMapper.fromWazeType("HAZARD", "HAZARD_ON_ROAD_POT_HOLE")); }
    @Test public void waze_weather_fog()     { assertEquals("HAZARD_WEATHER_FOG",   AlertMapper.fromWazeType("HAZARD", "HAZARD_WEATHER_FOG")); }
    @Test public void waze_weather_rain()    { assertEquals("HAZARD_WEATHER_RAIN",  AlertMapper.fromWazeType("HAZARD", "HAZARD_WEATHER_RAIN")); }
    @Test public void waze_weather_snow()    { assertEquals("HAZARD_WEATHER_SNOW",  AlertMapper.fromWazeType("HAZARD", "HAZARD_WEATHER_SNOW")); }
    @Test public void waze_weather_wind()    { assertEquals("HAZARD_WEATHER_WIND",  AlertMapper.fromWazeType("HAZARD", "HAZARD_WEATHER_WIND")); }
    @Test public void waze_weather_storm()   { assertEquals("HAZARD_WEATHER_STORM", AlertMapper.fromWazeType("HAZARD", "HAZARD_WEATHER_STORM")); }
    @Test public void waze_weather_hail()    { assertEquals("HAZARD_WEATHER_HAIL",  AlertMapper.fromWazeType("HAZARD", "HAZARD_WEATHER_HAIL")); }
    @Test public void waze_jam_isCongestion(){ assertEquals("HAZARD_ON_ROAD_CONGESTION", AlertMapper.fromWazeType("JAM", "")); }
    @Test public void waze_roadClosed()      { assertEquals("HAZARD_ON_ROAD_CONGESTION", AlertMapper.fromWazeType("ROAD_CLOSED", "")); }
    @Test public void waze_unknown_returnsNull() { assertNull(AlertMapper.fromWazeType("WEATHERHAZARD", "")); }
    @Test public void waze_null_returnsNull()    { assertNull(AlertMapper.fromWazeType(null, "")); }

    @Test
    public void waze_allMappedTypesAreValid() {
        String[] wazeTypes   = {"POLICE", "ACCIDENT", "HAZARD", "JAM", "ROAD_CLOSED"};
        String[] wazeSubtypes = {"", "POLICE_HIDDEN", "ACCIDENT_MAJOR", "HAZARD_ON_ROAD_ICE",
                                 "HAZARD_ON_ROAD_CONGESTION", "HAZARD_ON_ROAD_POT_HOLE",
                                 "HAZARD_WEATHER_FOG", "HAZARD_WEATHER_RAIN",
                                 "HAZARD_WEATHER_SNOW", "HAZARD_WEATHER_WIND",
                                 "HAZARD_WEATHER_STORM", "HAZARD_WEATHER_HAIL"};
        for (String t : wazeTypes) {
            for (String s : wazeSubtypes) {
                String mapped = AlertMapper.fromWazeType(t, s);
                if (mapped != null) {
                    assertTrue("Waze(" + t + "," + s + ") → '" + mapped + "' is not a known SABRE type",
                            isValidSabreType(mapped));
                }
            }
        }
    }

    // ── CHP type mapping ──────────────────────────────────────────────────────

    @Test public void chp_fatalAccident()   { assertEquals("ACCIDENT_MAJOR",  AlertMapper.fromChpLogType("1144 FATAL COLLISION")); }
    @Test public void chp_injuryAccident()  { assertEquals("ACCIDENT_MAJOR",  AlertMapper.fromChpLogType("1179 INJURY TRAFFIC COLLISION")); }
    @Test public void chp_sigAlert()        { assertEquals("ACCIDENT_MAJOR",  AlertMapper.fromChpLogType("SIG ALERT")); }
    @Test public void chp_nonInjury()       { assertEquals("ACCIDENT_MINOR",  AlertMapper.fromChpLogType("1182 NON-INJURY TC")); }
    @Test public void chp_hitAndRun()       { assertEquals("ACCIDENT_MINOR",  AlertMapper.fromChpLogType("20002 HIT AND RUN")); }

    // Real feed LogType strings for every collision code — must map to the correct
    // ACCIDENT severity, not fall through to the HAZARD_ON_ROAD_DEBRIS catch-all.
    // (Regression: 1180/1181 injury collisions were rendering as road debris.)
    @Test public void chp_1179_realString()   { assertEquals("ACCIDENT_MAJOR", AlertMapper.fromChpLogType("1179-Trfc Collision-1141 Enrt")); }
    @Test public void chp_1180_majorInjury()  { assertEquals("ACCIDENT_MAJOR", AlertMapper.fromChpLogType("1180-Trfc Collision-Major Inj")); }
    @Test public void chp_1181_minorInjury()  { assertEquals("ACCIDENT_MAJOR", AlertMapper.fromChpLogType("1181-Trfc Collision-Minor Inj")); }
    @Test public void chp_1182_realString()   { assertEquals("ACCIDENT_MINOR", AlertMapper.fromChpLogType("1182-Trfc Collision-No Inj")); }
    @Test public void chp_1183_unknownInjury(){ assertEquals("ACCIDENT_MAJOR", AlertMapper.fromChpLogType("1183-Trfc Collision-Unkn Inj")); }
    @Test public void chp_20001_injuryHitRun(){ assertEquals("ACCIDENT_MAJOR", AlertMapper.fromChpLogType("20001-Hit and Run w/ Injuries")); }
    @Test public void chp_20002_realString()  { assertEquals("ACCIDENT_MINOR", AlertMapper.fromChpLogType("20002-Hit and Run No Injuries")); }
    @Test public void chp_cat_1180_isMajor()  { assertEquals(ChpCategory.MAJOR_ACCIDENT, AlertMapper.categoryFor("1180-Trfc Collision-Major Inj")); }
    @Test public void chp_cat_1181_isMajor()  { assertEquals(ChpCategory.MAJOR_ACCIDENT, AlertMapper.categoryFor("1181-Trfc Collision-Minor Inj")); }
    @Test public void chp_cat_1182_isMinor()  { assertEquals(ChpCategory.MINOR_ACCIDENT, AlertMapper.categoryFor("1182-Trfc Collision-No Inj")); }

    @Test public void chp_trafficControl()  { assertEquals("POLICE_VISIBLE",  AlertMapper.fromChpLogType("1184 PROVIDE TRAFFIC CONTROL")); }
    @Test public void chp_debris()          { assertEquals("HAZARD_ON_ROAD_DEBRIS",     AlertMapper.fromChpLogType("1125 DEBRIS IN ROAD")); }
    @Test public void chp_fire()            { assertEquals("HAZARD_ON_ROAD_DEBRIS",     AlertMapper.fromChpLogType("VEHICLE FIRE")); }
    @Test public void chp_closure()         { assertEquals("HAZARD_ON_ROAD_CONGESTION", AlertMapper.fromChpLogType("ROAD CLOSURE")); }
    @Test public void chp_wind()            { assertEquals("HAZARD_WEATHER_WIND", AlertMapper.fromChpLogType("HIGH WIND ADVISORY")); }
    @Test public void chp_fog()             { assertEquals("HAZARD_WEATHER_FOG",  AlertMapper.fromChpLogType("FOG WARNING")); }
    @Test public void chp_snow()            { assertEquals("HAZARD_ON_ROAD_SLIPPERY", AlertMapper.fromChpLogType("SNOW")); }
    @Test public void chp_null_returnsNull(){ assertNull(AlertMapper.fromChpLogType(null)); }

    @Test
    public void chp_allMappedTypesAreValid() {
        String[] logTypes = {
            "1179", "1141", "1183", "1144", "FATAL", "SIG ALERT",
            "1182", "20002", "1184", "CZP", "MZP", "1125", "FIRE",
            "CLOSURE", "TADV", "WIND", "FOG", "SNOW", "ICE", "CHAIN",
            "1013", "WEATHER", "ROAD CONDITION", "ESCORT"
        };
        for (String t : logTypes) {
            String mapped = AlertMapper.fromChpLogType(t);
            if (mapped != null) {
                assertTrue("CHP('" + t + "') → '" + mapped + "' is not a known SABRE type",
                        isValidSabreType(mapped));
            }
        }
    }
}
