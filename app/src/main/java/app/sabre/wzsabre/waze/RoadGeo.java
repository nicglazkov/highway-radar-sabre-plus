package app.sabre.wzsabre.waze;

import java.util.List;

/**
 * Segment-snap geometry: nearest-road matching for a GPS point + heading, used
 * to attach directional SegmentNodes to a Waze report before submission. Pure
 * Java (no Android APIs), so it stays plain-JVM testable.
 *
 * Ported from wzsabre 2.2 wazemo.GeoUtils (computeHeading / pointToSegmentDist /
 * minDistToPolyline / findMatchingSegment), adapted from Coord to our LatLon.
 * Named RoadGeo (not GeoUtils) to avoid clashing with any future GeoBoxes-style
 * general geo helper in this package.
 */
final class RoadGeo {
    private RoadGeo() {}

    /** Bearing from `a` to `b`, rounded to an int degree in [0,360). */
    static int computeHeading(LatLon a, LatLon b) {
        double bearing = Math.toDegrees(Math.atan2(
                (b.lon - a.lon) * Math.cos(Math.toRadians((a.lat + b.lat) / 2.0)),
                b.lat - a.lat));
        return (int) Math.round((bearing + 360.0) % 360.0);
    }

    /**
     * Nearest road segment to `pos` among `segs`: the candidate whose minimum
     * perpendicular distance to `pos` is smallest, restricted to segments whose
     * heading (forward OR reverse direction) is within `maxAngleDiff` degrees of
     * `heading` AND whose distance is within `maxDistM` meters. Returns null if
     * no segment qualifies. `SegmentMatch.reverse` is true when the segment's
     * reverse-direction heading is the closer match, so the caller can pick the
     * correct directional from/to node order.
     */
    static SegmentMatch findMatchingSegment(LatLon pos, double heading, List<RoadSegment> segs,
            double maxAngleDiff, double maxDistM) {
        SegmentMatch best = null;
        double bestDist = Double.MAX_VALUE;
        for (RoadSegment seg : segs) {
            double dist = minDistToPolylineM(pos, seg.points);
            double fwdAngleDiff = angleDiff180(seg.heading, heading);
            double backAngleDiff = angleDiff180(seg.heading + 180, heading);
            if (Math.min(fwdAngleDiff, backAngleDiff) <= maxAngleDiff
                    && dist <= maxDistM
                    && dist < bestDist) {
                best = new SegmentMatch(seg, backAngleDiff < fwdAngleDiff);
                bestDist = dist;
            }
        }
        return best;
    }

    /**
     * Absolute angular difference in [0,180] between headings `a` and `b`
     * degrees. Ported literally from the reference's Kotlin `%` normalization:
     * Kotlin's `%` (like Java's) can return a negative remainder for a negative
     * dividend, so the intermediate is folded back into [0,360) before taking
     * the distance from 180.
     */
    private static double angleDiff180(double a, double b) {
        double diff = ((a - b) + 180.0) % 360.0;
        if (diff != 0.0 && Math.signum(diff) != Math.signum(360.0)) {
            diff += 360.0;
        }
        return Math.abs(diff - 180.0);
    }

    /** Minimum perpendicular distance in meters from `point` to a polyline. */
    private static double minDistToPolylineM(LatLon point, List<LatLon> points) {
        if (points.isEmpty()) {
            return Double.MAX_VALUE;
        }
        if (points.size() == 1) {
            return pointToSegmentDistM(point, points.get(0), points.get(0));
        }
        double min = Double.MAX_VALUE;
        for (int i = 0; i < points.size() - 1; i++) {
            min = Math.min(min, pointToSegmentDistM(point, points.get(i), points.get(i + 1)));
        }
        return min;
    }

    /**
     * Perpendicular distance in meters from `point` to the segment
     * [segStart, segEnd], via an equirectangular meters projection local to
     * the segment's average latitude (matches wzsabre 2.2
     * GeoUtils.pointToSegmentDist, including its degenerate-segment fallback
     * to a straight point-to-point distance when segStart == segEnd).
     */
    private static double pointToSegmentDistM(LatLon point, LatLon segStart, LatLon segEnd) {
        double mPerDegLon = WazeConstants.mPerDegLon((segStart.lat + segEnd.lat) / 2.0);
        double ax = segStart.lon * mPerDegLon;
        double ay = segStart.lat * WazeConstants.M_PER_DEG_LAT;
        double bx = segEnd.lon * mPerDegLon;
        double by = segEnd.lat * WazeConstants.M_PER_DEG_LAT;
        double px = point.lon * mPerDegLon;
        double py = point.lat * WazeConstants.M_PER_DEG_LAT;

        double dx = bx - ax;
        double dy = by - ay;
        double lenSq = (dx * dx) + (dy * dy);
        if (lenSq == 0.0) {
            return Math.sqrt(Math.pow(px - ax, 2.0) + Math.pow(py - ay, 2.0));
        }
        double rx = px - ax;
        double ry = py - ay;
        double t = clamp(((rx * dx) + (ry * dy)) / lenSq, 0.0, 1.0);
        return Math.sqrt(Math.pow(rx - (dx * t), 2.0) + Math.pow(ry - (dy * t), 2.0));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
