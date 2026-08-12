package app.sabre.wzsabre.waze;

import app.sabre.wzsabre.AlertMapper;
import app.sabre.wzsabre.ReportRequest;

/**
 * Assembles the Waze AddUserReportedAlertRequest protobuf from a user's HR report,
 * and reads the response uuid/points. Pure WazeProto (no android.util.Base64), so
 * it is JVM-testable; the Base64 line framing lives in WazeRtCodec.
 */
final class WazeReportCodec {
    private WazeReportCodec() {}

    static WazeProto.AddUserReportedAlertRequest buildRequest(
            ReportRequest r, long nowMs, long fromNode, long toNode) {
        AlertMapper.WazeReportSubtype sub = AlertMapper.wazeReportSubtype(r.type);
        if (sub == null) return null;

        WazeProto.CoordinateWithAlt coord = WazeProto.CoordinateWithAlt.newBuilder()
                .setLonTimes1000000((int) Math.round(r.lon * 1_000_000.0))
                .setLatTimes1000000((int) Math.round(r.lat * 1_000_000.0))
                .setAltTimes1000000((int) Math.round(r.altitudeM * 1_000_000.0))
                .build();
        WazeProto.GpsPosition gps = WazeProto.GpsPosition.newBuilder()
                .setCoordinate(coord)
                .setHorizontalAccuracyMeters(10.0)
                .setTimeEpochMs(nowMs)
                .build();
        WazeProto.UserPosition.Builder pos = WazeProto.UserPosition.newBuilder().setGpsPosition(gps);
        if (fromNode != 0 || toNode != 0) {
            pos.setSegmentNodes(WazeProto.SegmentNodes.newBuilder()
                    .setFromNode(fromNode).setToNode(toNode).build());
        }

        WazeProto.AddUserReportedAlertRequest.Builder b =
                WazeProto.AddUserReportedAlertRequest.newBuilder()
                .setUserPosition(pos.build())
                .setAzymuth((int) Math.round(r.headingDeg))
                .setAlertDetails(buildDetails(sub))
                .setSegmentDirection(r.isOpposite
                        ? WazeProto.SegmentDirection.SEGMENT_DIRECTION_BACKWARD
                        : WazeProto.SegmentDirection.SEGMENT_DIRECTION_FORWARD)
                .setReportTime(WazeProto.Timestamp.newBuilder()
                        .setSeconds((nowMs / 1000L) - r.timeDeltaS).build())
                .setReportingManner(WazeProto.ReportingManner.REPORTING_MANNER_DEFAULT);
        return b.build();
    }

    private static WazeProto.AlertDetails buildDetails(AlertMapper.WazeReportSubtype sub) {
        WazeProto.AlertDetails.Builder d = WazeProto.AlertDetails.newBuilder();
        switch (sub.kind) {
            case POLICE:
                d.setPolice(WazeProto.PoliceDetails.newBuilder()
                        .setType(WazeProto.PoliceAlertSubType.forNumber(sub.subtypeNumber)));
                break;
            case CRASH:
                d.setCrash(WazeProto.CrashDetails.newBuilder()
                        .setType(WazeProto.CrashReportSubType.forNumber(sub.subtypeNumber)));
                break;
            case TRAFFIC:
                d.setTraffic(WazeProto.TrafficDetails.newBuilder()
                        .setType(WazeProto.TrafficReportSubType.forNumber(sub.subtypeNumber)));
                break;
            case HAZARD:
                d.setHazardOnRoad(WazeProto.HazardOnRoadDetails.newBuilder()
                        .setType(WazeProto.HazardReportSubType.forNumber(sub.subtypeNumber)));
                break;
        }
        return d.build();
    }

    static String reportUuidFrom(WazeProto.Batch batch) {
        for (WazeProto.Element el : batch.getElementList()) {
            if (el.hasAddUserReportedAlertResponse()) {
                String u = el.getAddUserReportedAlertResponse().getAlertUuid();
                if (u != null && !u.isEmpty()) return u;
            }
        }
        return null;
    }

    static int reportPointsFrom(WazeProto.Batch batch) {
        for (WazeProto.Element el : batch.getElementList()) {
            if (el.hasAddUserReportedAlertResponse())
                return el.getAddUserReportedAlertResponse().getReceivedPointsCount();
        }
        return -1;
    }
}
