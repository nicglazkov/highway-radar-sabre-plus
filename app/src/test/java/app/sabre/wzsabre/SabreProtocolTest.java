package app.sabre.wzsabre;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Validates that SabreResponseBuilder produces JSON that exactly matches Highway
 * Radar's kotlinx.serialization schema. HR parses strictly (never sets
 * ignoreUnknownKeys), so the alert object must contain EXACTLY these nine fields
 * and no others, or HR rejects the whole batch and shows no data.
 *
 * HR 3.2's SabreFetchResponseAlert (verified by decompiling HR):
 *   0  alert_source   String  non-null
 *   1  alert_id       String  non-null
 *   2  type           String  non-null
 *   3  lat            Double
 *   4  lon            Double
 *   5  heading_deg    Double
 *   6  street_name    String? nullable
 *   7  report_ts      Int     (not Long)
 *   8  confirm_ts     Int?    nullable
 *
 * The old wzsabre 2.2 model also had user_id and confirm_count; HR dropped both,
 * so sending them now breaks HR (this was the "plugin detected but no data" bug).
 */
public class SabreProtocolTest {

    private static final long NOW_SECONDS = System.currentTimeMillis() / 1000;

    // ── helpers ──────────────────────────────────────────────────────────────

    private SabreAlert chpAlert() {
        return new SabreAlert(
                "chp_12345",
                SabreResponseBuilder.SOURCE_CHP,
                "POLICE_VISIBLE",
                37.7749, -122.4194, 0.0,
                "I-80 (San Francisco)",
                NOW_SECONDS);
    }

    private SabreAlert wazeAlert() {
        return new SabreAlert(
                "waze_alert-9876543210/abcdef123",
                SabreResponseBuilder.SOURCE_WAZE,
                "HAZARD_ON_ROAD_DEBRIS",
                34.0522, -118.2437, 45.0,
                "I-405",
                NOW_SECONDS);
    }

    private SabreAlert wazeAlertNullStreet() {
        return new SabreAlert(
                "waze_alert-111/xyz",
                SabreResponseBuilder.SOURCE_WAZE,
                "ACCIDENT_MINOR",
                33.9, -117.9, 0.0,
                null,  // street_name is nullable
                NOW_SECONDS);
    }

    private JSONObject parseResponse(List<SabreAlert> alerts) throws Exception {
        return new JSONObject(SabreResponseBuilder.build("req-1", alerts));
    }

    private JSONObject firstAlert(JSONObject root) throws Exception {
        return root.getJSONObject("response").getJSONArray("alerts").getJSONObject(0);
    }

    // ── top-level structure ───────────────────────────────────────────────────

    @Test
    public void topLevel_hasRequiredFields() throws Exception {
        JSONObject r = parseResponse(Collections.singletonList(chpAlert()));
        assertTrue("request_id missing",    r.has("request_id"));
        assertTrue("error_message missing", r.has("error_message"));
        assertTrue("response missing",      r.has("response"));
    }

    @Test
    public void topLevel_errorMessageIsNull() throws Exception {
        JSONObject r = parseResponse(Collections.singletonList(chpAlert()));
        assertTrue("error_message must be JSON null", r.isNull("error_message"));
    }

    @Test
    public void topLevel_requestIdMatches() throws Exception {
        String json = SabreResponseBuilder.build("my-req-id", Collections.emptyList());
        assertEquals("my-req-id", new JSONObject(json).getString("request_id"));
    }

    @Test
    public void responseData_hasRequiredFields() throws Exception {
        JSONObject data = parseResponse(Collections.emptyList()).getJSONObject("response");
        assertTrue("n_batches missing", data.has("n_batches"));
        assertTrue("batch_id missing",  data.has("batch_id"));
        assertTrue("alerts missing",    data.has("alerts"));
    }

    @Test
    public void responseData_nBatchesAndBatchId() throws Exception {
        JSONObject data = parseResponse(Collections.emptyList()).getJSONObject("response");
        assertEquals(1, data.getInt("n_batches"));
        assertEquals(0, data.getInt("batch_id"));
    }

    @Test
    public void emptyAlerts_returnsEmptyArray() throws Exception {
        JSONArray arr = parseResponse(Collections.emptyList())
                .getJSONObject("response").getJSONArray("alerts");
        assertEquals(0, arr.length());
    }

    // ── alert: exactly HR's nine fields, no extras ───────────────────────────

    @Test
    public void alert_hasExactly9HrFields() throws Exception {
        JSONObject a = firstAlert(parseResponse(Collections.singletonList(chpAlert())));
        String[] required = {
            "alert_source", "alert_id", "type", "lat", "lon",
            "heading_deg", "street_name", "report_ts", "confirm_ts"
        };
        for (String field : required) {
            assertTrue("Missing required field: " + field, a.has(field));
        }
        // HR parses the response strictly (never sets ignoreUnknownKeys), so ANY
        // extra key makes it reject the whole batch and show no data. HR 3.2's
        // SabreFetchResponseAlert model is exactly these nine fields: user_id and
        // confirm_count (present in the old wzsabre 2.2 model) were dropped. Lock
        // the field set to exactly nine so a regression can't silently break HR.
        assertEquals("alert must have exactly HR's 9 fields (no user_id/confirm_count)",
                9, a.length());
        assertFalse("user_id must NOT be sent (HR rejects unknown keys)", a.has("user_id"));
        assertFalse("confirm_count must NOT be sent (HR rejects unknown keys)", a.has("confirm_count"));
    }

    // ── alert_source must match handshake source IDs ──────────────────────────

    @Test
    public void alert_sourceChpIsLowercase() throws Exception {
        JSONObject a = firstAlert(parseResponse(Collections.singletonList(chpAlert())));
        assertEquals("alert_source must match declared source id 'chp' (case-sensitive)",
                SabreResponseBuilder.SOURCE_CHP, a.getString("alert_source"));
    }

    @Test
    public void alert_sourceWazeIsLowercase() throws Exception {
        JSONObject a = firstAlert(parseResponse(Collections.singletonList(wazeAlert())));
        assertEquals("alert_source must match declared source id 'waze' (case-sensitive)",
                SabreResponseBuilder.SOURCE_WAZE, a.getString("alert_source"));
    }

    @Test
    public void alert_sourceNotCapitalized() throws Exception {
        // HR may NPE if alert_source doesn't match a declared SabreDiscoveryResponseSource id
        JSONObject a = firstAlert(parseResponse(Collections.singletonList(chpAlert())));
        assertNotEquals("'CHP' would not match declared source id 'chp'", "CHP",
                a.getString("alert_source"));
        assertNotEquals("'Waze' would not match declared source id 'waze'", "Waze",
                a.getString("alert_source"));
    }

    // ── report_ts must fit in Int ─────────────────────────────────────────────

    @Test
    public void alert_reportTsIsIntNotLong() throws Exception {
        JSONObject a = firstAlert(parseResponse(Collections.singletonList(chpAlert())));
        int ts = a.getInt("report_ts");  // throws if not parseable as Int
        assertTrue("report_ts must be > 0", ts > 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void alert_reportTsOverflowIsRejected() throws Exception {
        SabreAlert bad = new SabreAlert("id", "chp", "POLICE_VISIBLE",
                34.0, -118.0, 0.0, null,
                (long) Integer.MAX_VALUE + 1);
        SabreResponseBuilder.buildAlert(bad);
    }

    // ── build() must never let one bad alert take down the whole response ─────

    @Test
    public void build_dropsOverflowReportTs_keepsValidAlerts() throws Exception {
        SabreAlert bad = new SabreAlert("bad", "chp", "POLICE_VISIBLE",
                34.0, -118.0, 0.0, null, (long) Integer.MAX_VALUE + 1);
        JSONObject root = new JSONObject(SabreResponseBuilder.build("r",
                java.util.Arrays.asList(bad, chpAlert())));
        JSONArray alerts = root.getJSONObject("response").getJSONArray("alerts");
        assertEquals("bad alert dropped, valid alert kept", 1, alerts.length());
        assertEquals("chp_12345", alerts.getJSONObject(0).getString("alert_id"));
    }

    @Test
    public void build_dropsNaNCoords_keepsValidAlerts() throws Exception {
        SabreAlert bad = new SabreAlert("bad", "chp", "POLICE_VISIBLE",
                Double.NaN, -118.0, 0.0, null, NOW_SECONDS);
        JSONObject root = new JSONObject(SabreResponseBuilder.build("r",
                java.util.Arrays.asList(bad, chpAlert())));
        assertEquals(1, root.getJSONObject("response").getJSONArray("alerts").length());
    }

    @Test
    public void build_dropsNaNHeading_keepsValidAlerts() throws Exception {
        // JSONObject.put(double) throws a *checked* JSONException on a NaN heading;
        // that must be caught so one bad alert can't blank the whole batch.
        SabreAlert bad = new SabreAlert("bad", "chp", "POLICE_VISIBLE",
                34.0, -118.0, Double.NaN, null, NOW_SECONDS);
        JSONObject root = new JSONObject(SabreResponseBuilder.build("r",
                java.util.Arrays.asList(bad, chpAlert())));
        assertEquals("NaN-heading alert dropped, valid alert kept", 1,
                root.getJSONObject("response").getJSONArray("alerts").length());
    }

    @Test
    public void build_dropsUnknownType_keepsValidAlerts() throws Exception {
        // An unknown SABRE type string would crash HR's renderer — never send it.
        SabreAlert bad = new SabreAlert("bad", "chp", "TOTALLY_BOGUS_TYPE",
                34.0, -118.0, 0.0, null, NOW_SECONDS);
        SabreAlert badNull = new SabreAlert("bad2", "chp", null,
                34.0, -118.0, 0.0, null, NOW_SECONDS);
        JSONObject root = new JSONObject(SabreResponseBuilder.build("r",
                java.util.Arrays.asList(bad, badNull, chpAlert())));
        assertEquals(1, root.getJSONObject("response").getJSONArray("alerts").length());
    }

    @Test
    public void isValidType_acceptsCanonicalAndWazeVocabulary() {
        // Canonical SABRE types emitted by CHP/LCS.
        String[] canonical = {"POLICE_VISIBLE", "POLICE_HIDDEN", "ACCIDENT_MAJOR", "ACCIDENT_MINOR",
                "HAZARD_ON_ROAD_DEBRIS", "HAZARD_ON_ROAD_CONGESTION", "HAZARD_ON_ROAD_SLIPPERY",
                "HAZARD_ON_ROAD_POT_HOLE", "HAZARD_WEATHER_FOG", "HAZARD_WEATHER_RAIN",
                "HAZARD_WEATHER_SNOW", "HAZARD_WEATHER_WIND", "HAZARD_WEATHER_STORM",
                "HAZARD_WEATHER_HAIL"};
        for (String t : canonical) assertTrue(t, SabreResponseBuilder.isValidType(t));
        // Raw Waze type/subtype names now pass through (the official ships these to HR).
        String[] waze = {"POLICE", "ACCIDENT", "HAZARD", "JAM", "SOS",
                "POLICE_HIDING", "POLICE_WITH_MOBILE_CAMERA", "JAM_HEAVY_TRAFFIC",
                "HAZARD_ON_SHOULDER_CAR_STOPPED", "HAZARD_ON_ROAD_CAR_STOPPED",
                "SOS_MECHANICAL_PROBLEM", "DEFAULT_CAMERA"};
        for (String t : waze) assertTrue(t, SabreResponseBuilder.isValidType(t));
        // A garbage string or null is still rejected.
        assertFalse(SabreResponseBuilder.isValidType("TOTALLY_BOGUS_TYPE"));
        assertFalse(SabreResponseBuilder.isValidType(null));
    }

    @Test
    public void build_passesThroughWazeSubtypeStrings() throws Exception {
        // A stopped-vehicle-on-shoulder alert (previously dropped/flattened) survives.
        SabreAlert stopped = new SabreAlert("waze_alert-7/uuid", SabreResponseBuilder.SOURCE_WAZE,
                "HAZARD_ON_SHOULDER_CAR_STOPPED", 38.0, -122.0, 90.0, "I-80", NOW_SECONDS);
        JSONObject a = firstAlert(parseResponse(Collections.singletonList(stopped)));
        assertEquals("HAZARD_ON_SHOULDER_CAR_STOPPED", a.getString("type"));
    }

    // ── confirm fields ────────────────────────────────────────────────────────

    @Test
    public void alert_confirmFieldsForWaze() throws Exception {
        SabreAlert confirmed = new SabreAlert("waze_alert-5/uuid", SabreResponseBuilder.SOURCE_WAZE,
                "POLICE_VISIBLE", 38.0, -122.0, 0.0, "US-101", NOW_SECONDS,
                NOW_SECONDS, 4);
        JSONObject a = firstAlert(parseResponse(Collections.singletonList(confirmed)));
        assertFalse("confirm_ts must be present and non-null when set", a.isNull("confirm_ts"));
        assertEquals((int) NOW_SECONDS, a.getInt("confirm_ts"));
    }

    @Test
    public void alert_confirmTsNullWhenOutOfIntRange() throws Exception {
        // A confirm_ts that doesn't fit in Int must be sent as null, not overflowed.
        SabreAlert a = new SabreAlert("waze_alert-6/uuid", SabreResponseBuilder.SOURCE_WAZE,
                "POLICE_VISIBLE", 38.0, -122.0, 0.0, null, NOW_SECONDS,
                ((long) Integer.MAX_VALUE) + 100L, 2);
        JSONObject obj = firstAlert(parseResponse(Collections.singletonList(a)));
        assertTrue("oversized confirm_ts must be null", obj.isNull("confirm_ts"));
    }

    // ── batching ──────────────────────────────────────────────────────────────

    @Test
    public void build_singleBatchDefaults() throws Exception {
        JSONObject data = parseResponse(Collections.singletonList(chpAlert()))
                .getJSONObject("response");
        assertEquals(1, data.getInt("n_batches"));
        assertEquals(0, data.getInt("batch_id"));
    }

    @Test
    public void build_batchCarriesNBatchesAndBatchId() throws Exception {
        JSONObject root = new JSONObject(SabreResponseBuilder.build(
                "req-1", Collections.singletonList(chpAlert()), 3, 2));
        JSONObject data = root.getJSONObject("response");
        assertEquals(3, data.getInt("n_batches"));
        assertEquals(2, data.getInt("batch_id"));
        assertEquals(1, data.getJSONArray("alerts").length());
    }

    // ── nullable fields: must be present but may be null ─────────────────────

    @Test
    public void alert_streetNameNullableButPresent() throws Exception {
        JSONObject a = firstAlert(parseResponse(Collections.singletonList(wazeAlertNullStreet())));
        assertTrue("street_name must be present even when null", a.has("street_name"));
        assertTrue("street_name with null value should be JSON null", a.isNull("street_name"));
    }

    @Test
    public void alert_streetNameNonNullWhenSet() throws Exception {
        JSONObject a = firstAlert(parseResponse(Collections.singletonList(chpAlert())));
        assertFalse("street_name should not be JSON null when value is set",
                a.isNull("street_name"));
        assertEquals("I-80 (San Francisco)", a.getString("street_name"));
    }

    @Test
    public void alert_confirmTsIsNullButPresent() throws Exception {
        JSONObject a = firstAlert(parseResponse(Collections.singletonList(chpAlert())));
        assertTrue("confirm_ts must be present", a.has("confirm_ts"));
        assertTrue("confirm_ts should be JSON null", a.isNull("confirm_ts"));
    }

    // ── numeric fields: no NaN or Infinity ───────────────────────────────────

    @Test(expected = IllegalArgumentException.class)
    public void alert_nanLatIsRejected() throws Exception {
        SabreAlert bad = new SabreAlert("id", "chp", "POLICE_VISIBLE",
                Double.NaN, -118.0, 0.0, null, NOW_SECONDS);
        SabreResponseBuilder.buildAlert(bad);
    }

    @Test(expected = IllegalArgumentException.class)
    public void alert_infiniteLonIsRejected() throws Exception {
        SabreAlert bad = new SabreAlert("id", "chp", "POLICE_VISIBLE",
                34.0, Double.POSITIVE_INFINITY, 0.0, null, NOW_SECONDS);
        SabreResponseBuilder.buildAlert(bad);
    }

    // ── multiple alerts ───────────────────────────────────────────────────────

    @Test
    public void multipleAlerts_allPresent() throws Exception {
        JSONArray arr = parseResponse(Arrays.asList(chpAlert(), wazeAlert(), wazeAlertNullStreet()))
                .getJSONObject("response").getJSONArray("alerts");
        assertEquals(3, arr.length());
    }

    @Test
    public void multipleAlerts_eachHasExactly9Fields() throws Exception {
        String[] fields = {
            "alert_source", "alert_id", "type", "lat", "lon",
            "heading_deg", "street_name", "report_ts", "confirm_ts"
        };
        JSONArray arr = parseResponse(Arrays.asList(chpAlert(), wazeAlert()))
                .getJSONObject("response").getJSONArray("alerts");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.getJSONObject(i);
            for (String f : fields) {
                assertTrue("Alert[" + i + "] missing field: " + f, a.has(f));
            }
            assertEquals("Alert[" + i + "] must have exactly 9 fields", 9, a.length());
        }
    }
}
