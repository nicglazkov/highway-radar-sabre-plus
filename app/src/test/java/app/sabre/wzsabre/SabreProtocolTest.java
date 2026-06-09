package app.sabre.wzsabre;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Validates that SabreResponseBuilder produces JSON that exactly matches
 * HR's kotlinx.serialization schema (compatible with the wzsabre SABRE protocol).
 *
 * SabreFetchResponseAlert requires ALL 11 fields (bitmask 2047 = 0b11111111111):
 *   0  alert_source   String  non-null
 *   1  alert_id       String  non-null
 *   2  user_id        String  non-null   ← was missing before; caused MissingFieldException crash
 *   3  type           String  non-null
 *   4  lat            Double
 *   5  lon            Double
 *   6  heading_deg    Double
 *   7  street_name    String? nullable
 *   8  report_ts      Int     (not Long)
 *   9  confirm_ts     Int?    nullable
 *  10  confirm_count  Int
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

    // ── alert: all 11 fields present ─────────────────────────────────────────

    @Test
    public void alert_hasAll11RequiredFields() throws Exception {
        JSONObject a = firstAlert(parseResponse(Collections.singletonList(chpAlert())));
        String[] required = {
            "alert_source", "alert_id", "user_id", "type",
            "lat", "lon", "heading_deg", "street_name",
            "report_ts", "confirm_ts", "confirm_count"
        };
        for (String field : required) {
            assertTrue("Missing required field: " + field, a.has(field));
        }
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

    // ── user_id: required non-null String ────────────────────────────────────

    @Test
    public void alert_userIdPresentAndNonNull() throws Exception {
        for (SabreAlert alert : Arrays.asList(chpAlert(), wazeAlert(), wazeAlertNullStreet())) {
            JSONObject a = firstAlert(parseResponse(Collections.singletonList(alert)));
            assertTrue("user_id must be present", a.has("user_id"));
            assertFalse("user_id must not be JSON null", a.isNull("user_id"));
            assertNotNull("user_id must not be null", a.getString("user_id"));
        }
    }

    @Test
    public void alert_userIdExtractedFromWazeId() throws Exception {
        JSONObject a = firstAlert(parseResponse(Collections.singletonList(wazeAlert())));
        assertEquals("user_id should be extracted from 'alert-9876543210/abcdef123'",
                "9876543210", a.getString("user_id"));
    }

    @Test
    public void alert_userIdFallsBackToZeroForChp() throws Exception {
        JSONObject a = firstAlert(parseResponse(Collections.singletonList(chpAlert())));
        assertEquals("CHP alerts have no user_id, should be '0'",
                "0", a.getString("user_id"));
    }

    @Test
    public void alert_userIdFallsBackToZeroForAnonymousWaze() throws Exception {
        // Waze UUID format (no "alert-<digits>/" prefix)
        SabreAlert anon = new SabreAlert(
                "waze_some-uuid-without-digits",
                SabreResponseBuilder.SOURCE_WAZE,
                "JAM", 34.0, -118.0, 0.0, null, NOW_SECONDS);
        JSONObject a = firstAlert(parseResponse(Collections.singletonList(anon)));
        assertEquals("0", a.getString("user_id"));
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
        SabreResponseBuilder.build("r", Collections.singletonList(bad));
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

    // ── confirm_count is 0 ────────────────────────────────────────────────────

    @Test
    public void alert_confirmCountIsZero() throws Exception {
        JSONObject a = firstAlert(parseResponse(Collections.singletonList(chpAlert())));
        assertEquals(0, a.getInt("confirm_count"));
    }

    // ── multiple alerts ───────────────────────────────────────────────────────

    @Test
    public void multipleAlerts_allPresent() throws Exception {
        JSONArray arr = parseResponse(Arrays.asList(chpAlert(), wazeAlert(), wazeAlertNullStreet()))
                .getJSONObject("response").getJSONArray("alerts");
        assertEquals(3, arr.length());
    }

    @Test
    public void multipleAlerts_eachHasAll11Fields() throws Exception {
        String[] fields = {
            "alert_source", "alert_id", "user_id", "type",
            "lat", "lon", "heading_deg", "street_name",
            "report_ts", "confirm_ts", "confirm_count"
        };
        JSONArray arr = parseResponse(Arrays.asList(chpAlert(), wazeAlert()))
                .getJSONObject("response").getJSONArray("alerts");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.getJSONObject(i);
            for (String f : fields) {
                assertTrue("Alert[" + i + "] missing field: " + f, a.has(f));
            }
        }
    }

    // ── user_id extraction edge cases ─────────────────────────────────────────

    @Test
    public void extractUserId_fromWazeNativeId() {
        assertEquals("1234567890",
                SabreResponseBuilder.extractUserId("alert-1234567890/somehash"));
    }

    @Test
    public void extractUserId_fromWazePrefixedId() {
        assertEquals("9876543210",
                SabreResponseBuilder.extractUserId("waze_alert-9876543210/abc"));
    }

    @Test
    public void extractUserId_emptyDigitsReturnsZero() {
        assertEquals("0",
                SabreResponseBuilder.extractUserId("alert-/nohash"));
    }

    @Test
    public void extractUserId_noMatchReturnsZero() {
        assertEquals("0", SabreResponseBuilder.extractUserId("chp_12345"));
        assertEquals("0", SabreResponseBuilder.extractUserId(null));
        assertEquals("0", SabreResponseBuilder.extractUserId(""));
    }
}
