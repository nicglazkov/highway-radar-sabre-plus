package app.sabre.wzsabre;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

import java.util.List;

public class UserReportStoreTest {

    private static ReportRequest police(double lat, double lon) throws JSONException {
        return ReportRequest.fromJson("{\"lat\":" + lat + ",\"lon\":" + lon +
                ",\"type\":\"POLICE_VISIBLE\"}");
    }

    @Test
    public void addedReportAppearsInRadiusWithRenderableType() throws JSONException {
        UserReportStore s = new UserReportStore();
        s.add(police(37.8, -122.27), 1000L);
        List<SabreAlert> out = s.activeAlerts(37.8, -122.27, 5000, 2000L);
        assertEquals(1, out.size());
        assertEquals("POLICE_VISIBLE", out.get(0).type);
        assertEquals("waze", out.get(0).alertSource);
        assertTrue(out.get(0).alertId.contains("userreport"));
    }

    @Test
    public void expiredReportsAreDropped() throws JSONException {
        UserReportStore s = new UserReportStore();
        s.add(police(37.8, -122.27), 1000L);
        long later = 1000L + UserReportStore.TTL_MS + 1;
        assertTrue(s.activeAlerts(37.8, -122.27, 5000, later).isEmpty());
    }

    @Test
    public void outOfRadiusReportsAreFiltered() throws JSONException {
        UserReportStore s = new UserReportStore();
        s.add(police(37.8, -122.27), 1000L);
        // ~100km away
        assertTrue(s.activeAlerts(38.7, -122.27, 5000, 2000L).isEmpty());
    }

    @Test
    public void removeNearDropsMatchingReport() throws JSONException {
        UserReportStore s = new UserReportStore();
        s.add(police(37.8, -122.27), 1000L);
        s.removeNear(37.8001, -122.2701, 1500L);
        assertTrue(s.activeAlerts(37.8, -122.27, 5000, 2000L).isEmpty());
    }

    @Test
    public void renderableButInvalidTypeFallsBackToValid() throws JSONException {
        // HAZARD_ON_SHOULDER_CONSTRUCTION is renderable-prefixed (starts with HAZARD, so
        // AlertMapper.renderableEchoType passes it through verbatim) but is NOT a member of
        // SabreResponseBuilder.VALID_TYPES, unlike its siblings HAZARD_ON_SHOULDER_CAR_STOPPED /
        // _ANIMALS / _MISSING_SIGN. Proves the fallback is doing real work.
        String outOfSetType = "HAZARD_ON_SHOULDER_CONSTRUCTION";
        assertTrue(!SabreResponseBuilder.isValidType(outOfSetType));

        UserReportStore s = new UserReportStore();
        ReportRequest r = ReportRequest.fromJson("{\"lat\":37.8,\"lon\":-122.27,\"type\":\"" +
                outOfSetType + "\"}");
        s.add(r, 1000L);
        List<SabreAlert> out = s.activeAlerts(37.8, -122.27, 5000, 2000L);
        assertEquals(1, out.size());
        assertTrue(SabreResponseBuilder.isValidType(out.get(0).type));
    }

    @Test
    public void validTypePassesThroughUnchanged() throws JSONException {
        UserReportStore s = new UserReportStore();
        s.add(police(37.8, -122.27), 1000L);
        List<SabreAlert> out = s.activeAlerts(37.8, -122.27, 5000, 2000L);
        assertEquals(1, out.size());
        assertEquals("POLICE_VISIBLE", out.get(0).type);
    }
}
