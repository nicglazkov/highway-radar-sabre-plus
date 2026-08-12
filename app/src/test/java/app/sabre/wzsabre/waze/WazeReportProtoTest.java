package app.sabre.wzsabre.waze;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.Test;

/** Locks the report protobuf wire format: field numbers and round-trip. */
public class WazeReportProtoTest {

    @Test
    public void requestEncodesAndRoundTrips() throws InvalidProtocolBufferException {
        WazeProto.CoordinateWithAlt coord = WazeProto.CoordinateWithAlt.newBuilder()
                .setLonTimes1000000(-122271200)
                .setLatTimes1000000(37804400)
                .setAltTimes1000000(0)
                .build();
        WazeProto.GpsPosition gps = WazeProto.GpsPosition.newBuilder()
                .setCoordinate(coord).setHorizontalAccuracyMeters(10.0).setTimeEpochMs(1_700_000_000_000L)
                .build();
        WazeProto.AddUserReportedAlertRequest req = WazeProto.AddUserReportedAlertRequest.newBuilder()
                .setUserPosition(WazeProto.UserPosition.newBuilder().setGpsPosition(gps).build())
                .setAzymuth(90)
                .setAlertDetails(WazeProto.AlertDetails.newBuilder()
                        .setPolice(WazeProto.PoliceDetails.newBuilder()
                                .setType(WazeProto.PoliceAlertSubType.POLICE_DEFAULT).build()).build())
                .setSegmentDirection(WazeProto.SegmentDirection.SEGMENT_DIRECTION_FORWARD)
                .setReportTime(WazeProto.Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                .setReportingManner(WazeProto.ReportingManner.REPORTING_MANNER_DEFAULT)
                .build();

        byte[] bytes = req.toByteArray();
        WazeProto.AddUserReportedAlertRequest back =
                WazeProto.AddUserReportedAlertRequest.parseFrom(bytes);

        assertEquals(-122271200, back.getUserPosition().getGpsPosition().getCoordinate().getLonTimes1000000());
        assertEquals(90, back.getAzymuth());
        assertEquals(WazeProto.PoliceAlertSubType.POLICE_DEFAULT, back.getAlertDetails().getPolice().getType());
        assertEquals(WazeProto.ReportingManner.REPORTING_MANNER_DEFAULT, back.getReportingManner());
    }

    @Test
    public void requestRidesOnElement2737() throws InvalidProtocolBufferException {
        WazeProto.AddUserReportedAlertRequest req = WazeProto.AddUserReportedAlertRequest.newBuilder()
                .setAzymuth(7).build();
        WazeProto.Element el = WazeProto.Element.newBuilder()
                .setAddUserReportedAlertRequest(req).build();
        byte[] bytes = el.toByteArray();
        WazeProto.Element back = WazeProto.Element.parseFrom(bytes);
        assertEquals(7, back.getAddUserReportedAlertRequest().getAzymuth());
        // Response parses from the 2738 member.
        WazeProto.Element resp = WazeProto.Element.newBuilder()
                .setAddUserReportedAlertResponse(WazeProto.AddUserReportedAlertResponse.newBuilder()
                        .setAlertUuid("abc").setReceivedPointsCount(6).build()).build();
        WazeProto.Element rback = WazeProto.Element.parseFrom(resp.toByteArray());
        assertEquals("abc", rback.getAddUserReportedAlertResponse().getAlertUuid());
        assertEquals(6, rback.getAddUserReportedAlertResponse().getReceivedPointsCount());
    }
}
