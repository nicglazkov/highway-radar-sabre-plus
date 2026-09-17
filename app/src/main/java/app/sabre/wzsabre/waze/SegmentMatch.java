package app.sabre.wzsabre.waze;

/**
 * A road segment matched to a GPS position + heading, with the winning
 * direction (forward vs reverse) resolved by RoadGeo.findMatchingSegment.
 * Carries the directional node accessors the report request needs; the match
 * distance is not kept because nothing here displays it.
 */
final class SegmentMatch {
    public final RoadSegment segment;
    public final boolean reverse;

    SegmentMatch(RoadSegment segment, boolean reverse) {
        this.segment = segment;
        this.reverse = reverse;
    }

    /** Node the report should list as the segment's "from" (direction-aware). */
    long fromNodeDirectional() {
        return reverse ? segment.toNode : segment.fromNode;
    }

    /** Node the report should list as the segment's "to" (direction-aware). */
    long toNodeDirectional() {
        return reverse ? segment.fromNode : segment.toNode;
    }
}
