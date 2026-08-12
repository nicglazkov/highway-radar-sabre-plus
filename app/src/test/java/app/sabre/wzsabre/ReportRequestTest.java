package app.sabre.wzsabre;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

public class ReportRequestTest {

    @Test
    public void parsesReportPayload() throws JSONException {
        ReportRequest r = ReportRequest.fromJson(
            "{\"lat\":37.8,\"lon\":-122.27,\"heading_deg\":90.0,\"altitude_m\":12.0," +
            "\"type\":\"POLICE_VISIBLE\",\"is_opposite\":true,\"time_delta_s\":30}");
        assertEquals(37.8, r.lat, 1e-9);
        assertEquals(-122.27, r.lon, 1e-9);
        assertEquals(90.0, r.headingDeg, 1e-9);
        assertEquals("POLICE_VISIBLE", r.type);
        assertTrue(r.isOpposite);
        assertEquals(30, r.timeDeltaS);
    }

    @Test
    public void reportDefaultsOptionalFields() throws JSONException {
        ReportRequest r = ReportRequest.fromJson("{\"lat\":1.0,\"lon\":2.0,\"type\":\"ACCIDENT_MINOR\"}");
        assertEquals(0, r.timeDeltaS);
        assertFalse(r.isOpposite);
    }

    @Test(expected = JSONException.class)
    public void reportRejectsMissingType() throws JSONException {
        ReportRequest.fromJson("{\"lat\":1.0,\"lon\":2.0}");
    }

    @Test
    public void confirmRecoversWazeId() throws JSONException {
        ConfirmDiscardRequest c = ConfirmDiscardRequest.fromJson(
            "{\"lat\":1.0,\"lon\":2.0,\"alert_id\":\"alert-123456/uuid-abc\",\"test\":false}");
        assertEquals(123456L, c.wazeAlertId());
        assertFalse(c.test);
    }

    @Test
    public void confirmMalformedIdReturnsNegative() throws JSONException {
        ConfirmDiscardRequest c = ConfirmDiscardRequest.fromJson(
            "{\"lat\":1.0,\"lon\":2.0,\"alert_id\":\"userreport-99\"}");
        assertEquals(-1L, c.wazeAlertId());
    }

    @Test
    public void reportAcceptsLatitudeLongitudeFallback() throws JSONException {
        ReportRequest r = ReportRequest.fromJson(
            "{\"latitude\":1.5,\"longitude\":-2.5,\"type\":\"POLICE_VISIBLE\"}");
        assertEquals(1.5, r.lat, 1e-9);
        assertEquals(-2.5, r.lon, 1e-9);
    }

    @Test
    public void confirmAcceptsLatitudeLongitudeFallback() throws JSONException {
        ConfirmDiscardRequest c = ConfirmDiscardRequest.fromJson(
            "{\"latitude\":1.5,\"longitude\":-2.5,\"alert_id\":\"alert-7/u\"}");
        assertEquals(1.5, c.lat, 1e-9);
        assertEquals(-2.5, c.lon, 1e-9);
        assertEquals(7L, c.wazeAlertId());
    }

    @Test
    public void confirmAlertPrefixedNoSlashParses() throws JSONException {
        ConfirmDiscardRequest c = ConfirmDiscardRequest.fromJson(
            "{\"lat\":1.0,\"lon\":2.0,\"alert_id\":\"alert-654321\"}");
        assertEquals(654321L, c.wazeAlertId());
    }
}
