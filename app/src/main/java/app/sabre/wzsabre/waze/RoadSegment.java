package app.sabre.wzsabre.waze;

import java.util.List;

/**
 * A road-graph segment decoded from a WZDF tile: two tile-local node indices
 * and the polyline geometry between them. Ported from wzsabre 2.2
 * wazemo.RoadSegment (point type adapted from Coord to our LatLon).
 */
final class RoadSegment {
    public final long segmentId;
    public final long fromNode;
    public final long toNode;
    public final int heading;
    public final List<LatLon> points;

    RoadSegment(long segmentId, long fromNode, long toNode, int heading, List<LatLon> points) {
        this.segmentId = segmentId;
        this.fromNode = fromNode;
        this.toNode = toNode;
        this.heading = heading;
        this.points = points;
    }
}
