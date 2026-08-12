package app.sabre.wzsabre.waze;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.sabre.wzsabre.ReportRequest;
import org.json.JSONException;
import org.junit.Test;

public class WazeReportCodecTest {

    private static ReportRequest req(String type) throws JSONException {
        // lon = -122.2712 so getLonTimes1000000() == -122271200 below (matches the
        // Task 1 WazeReportProtoTest fixture coordinate exactly).
        return ReportRequest.fromJson("{\"lat\":37.8,\"lon\":-122.2712,\"heading_deg\":90.0," +
                "\"type\":\"" + type + "\",\"time_delta_s\":10}");
    }

    @Test public void buildsPoliceRequestPositionOnly() throws JSONException {
        WazeProto.AddUserReportedAlertRequest r =
                WazeReportCodec.buildRequest(req("POLICE_VISIBLE"), 1_700_000_000_000L, 0, 0);
        assertEquals(WazeProto.PoliceAlertSubType.POLICE_DEFAULT, r.getAlertDetails().getPolice().getType());
        assertEquals(90, r.getAzymuth());
        assertEquals(1_700_000_000L - 10, r.getReportTime().getSeconds());
        assertEquals(WazeProto.ReportingManner.REPORTING_MANNER_DEFAULT, r.getReportingManner());
        assertEquals(-122271200, r.getUserPosition().getGpsPosition().getCoordinate().getLonTimes1000000());
        assertTrue(!r.getUserPosition().hasSegmentNodes()); // position-only
    }

    @Test public void buildsWithSegmentNodesWhenProvided() throws JSONException {
        WazeProto.AddUserReportedAlertRequest r =
                WazeReportCodec.buildRequest(req("HAZARD_ON_ROAD_POT_HOLE"), 1_700_000_000_000L, 111, 222);
        assertEquals(111, r.getUserPosition().getSegmentNodes().getFromNode());
        assertEquals(222, r.getUserPosition().getSegmentNodes().getToNode());
        assertEquals(WazeProto.HazardReportSubType.HAZARD_REPORT_POTHOLE,
                r.getAlertDetails().getHazardOnRoad().getType());
    }

    @Test public void oppositeDirectionSetsBackward() throws JSONException {
        ReportRequest opp = ReportRequest.fromJson("{\"lat\":37.8,\"lon\":-122.27," +
                "\"type\":\"ACCIDENT_MAJOR\",\"is_opposite\":true}");
        WazeProto.AddUserReportedAlertRequest r = WazeReportCodec.buildRequest(opp, 1L, 0, 0);
        assertEquals(WazeProto.SegmentDirection.SEGMENT_DIRECTION_BACKWARD, r.getSegmentDirection());
        assertEquals(WazeProto.CrashReportSubType.CRASH_REPORT_MAJOR, r.getAlertDetails().getCrash().getType());
    }

    @Test public void nullForUnreportableType() throws JSONException {
        assertNull(WazeReportCodec.buildRequest(req("SOS_MEDICAL_HELP"), 1L, 0, 0));
    }

    // Unknown heading defaults to the -720 sentinel (ReportRequest.fromJson); the
    // azymuth sent to Waze must be normalized to [0,360), matching
    // WazeSession.submitReport's normalizeAngle360 for the At command, not sent
    // raw as an invalid -720 compass value.
    @Test public void unknownHeadingNormalizesAzymuthToZero() throws JSONException {
        ReportRequest noHeading = ReportRequest.fromJson("{\"lat\":37.8,\"lon\":-122.2712," +
                "\"type\":\"POLICE_VISIBLE\",\"time_delta_s\":10}");
        WazeProto.AddUserReportedAlertRequest r =
                WazeReportCodec.buildRequest(noHeading, 1_700_000_000_000L, 0, 0);
        assertEquals(0, r.getAzymuth());
    }

    @Test public void normalHeadingAzymuthUnchanged() throws JSONException {
        ReportRequest h27 = ReportRequest.fromJson("{\"lat\":37.8,\"lon\":-122.2712," +
                "\"heading_deg\":27.0,\"type\":\"POLICE_VISIBLE\",\"time_delta_s\":10}");
        WazeProto.AddUserReportedAlertRequest r =
                WazeReportCodec.buildRequest(h27, 1_700_000_000_000L, 0, 0);
        assertEquals(27, r.getAzymuth());
    }

    @Test public void headingOver360WrapsAzymuth() throws JSONException {
        ReportRequest h370 = ReportRequest.fromJson("{\"lat\":37.8,\"lon\":-122.2712," +
                "\"heading_deg\":370.0,\"type\":\"POLICE_VISIBLE\",\"time_delta_s\":10}");
        WazeProto.AddUserReportedAlertRequest r =
                WazeReportCodec.buildRequest(h370, 1_700_000_000_000L, 0, 0);
        assertEquals(10, r.getAzymuth());
    }

    @Test public void parsesResponseUuidAndPoints() {
        WazeProto.Batch batch = WazeProto.Batch.newBuilder()
                .addElement(WazeProto.Element.newBuilder()
                        .setAddUserReportedAlertResponse(WazeProto.AddUserReportedAlertResponse.newBuilder()
                                .setAlertUuid("uuid-xyz").setReceivedPointsCount(6).build()).build())
                .build();
        assertEquals("uuid-xyz", WazeReportCodec.reportUuidFrom(batch));
        assertEquals(6, WazeReportCodec.reportPointsFrom(batch));
    }

    // Live-Waze finding: a snapped report (with SegmentNodes) comes back with
    // received_points_count=6, status=STATUS_UNSPECIFIED (the default), and an
    // EMPTY alert_uuid, on anonymous accounts. That is still an accepted report:
    // acceptance must be keyed off the presence of the response element (and it
    // not being an explicit FAILURE), not off a non-empty uuid.
    @Test public void acceptedWithPointsAndEmptyUuid() {
        WazeProto.Batch batch = WazeProto.Batch.newBuilder()
                .addElement(WazeProto.Element.newBuilder()
                        .setAddUserReportedAlertResponse(WazeProto.AddUserReportedAlertResponse.newBuilder()
                                .setReceivedPointsCount(6)
                                // status left unset -> defaults to STATUS_UNSPECIFIED
                                .build()).build())
                .build();
        assertTrue(WazeReportCodec.reportAccepted(batch));
        assertEquals(6, WazeReportCodec.reportPointsFrom(batch));
        assertNull(WazeReportCodec.reportUuidFrom(batch));
    }

    @Test public void acceptedSuccessWithUuid() {
        WazeProto.Batch batch = WazeProto.Batch.newBuilder()
                .addElement(WazeProto.Element.newBuilder()
                        .setAddUserReportedAlertResponse(WazeProto.AddUserReportedAlertResponse.newBuilder()
                                .setStatus(WazeProto.AddUserReportedAlertResponse.AddAlertStatus.SUCCESS)
                                .setAlertUuid("abc")
                                .setReceivedPointsCount(0)
                                .build()).build())
                .build();
        assertTrue(WazeReportCodec.reportAccepted(batch));
        assertEquals("abc", WazeReportCodec.reportUuidFrom(batch));
    }

    @Test public void rejectedOnExplicitFailure() {
        WazeProto.Batch batch = WazeProto.Batch.newBuilder()
                .addElement(WazeProto.Element.newBuilder()
                        .setAddUserReportedAlertResponse(WazeProto.AddUserReportedAlertResponse.newBuilder()
                                .setStatus(WazeProto.AddUserReportedAlertResponse.AddAlertStatus.FAILURE)
                                .build()).build())
                .build();
        assertFalse(WazeReportCodec.reportAccepted(batch));
    }

    @Test public void notAcceptedWhenNoResponseElement() {
        // An empty batch (no elements at all) mirrors the confirmed contrast case:
        // a position-only report (no SegmentNodes) gets no response element back.
        WazeProto.Batch empty = WazeProto.Batch.newBuilder().build();
        assertFalse(WazeReportCodec.reportAccepted(empty));

        WazeProto.Batch unrelated = WazeProto.Batch.newBuilder()
                .addElement(WazeProto.Element.newBuilder().setOldCommand("noop").build())
                .build();
        assertFalse(WazeReportCodec.reportAccepted(unrelated));
    }
}
