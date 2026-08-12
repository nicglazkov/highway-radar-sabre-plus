package app.sabre.wzsabre.waze;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Pure geometry for the road-snap match: computeHeading and findMatchingSegment.
 * Ported from wzsabre 2.2 wazemo.GeoUtils / SegmentMatch.
 */
public class RoadGeoTest {

    private static RoadSegment eastWestSegment() {
        List<LatLon> points = new ArrayList<>();
        points.add(new LatLon(37.80, -122.28));
        points.add(new LatLon(37.80, -122.26));
        int heading = RoadGeo.computeHeading(points.get(0), points.get(1));
        return new RoadSegment(1L, 100L, 200L, heading, points);
    }

    @Test
    public void computeHeadingEastIsAbout90Degrees() {
        int heading = RoadGeo.computeHeading(new LatLon(37.80, -122.28), new LatLon(37.80, -122.26));
        assertTrue("expected heading near 90, got " + heading, Math.abs(heading - 90) <= 2);
    }

    @Test
    public void findMatchingSegmentSnapsNearbyPointOnForwardHeading() {
        RoadSegment segment = eastWestSegment();
        LatLon pos = new LatLon(37.8001, -122.27); // ~11m north of the segment, midpoint

        SegmentMatch match = RoadGeo.findMatchingSegment(
                pos, 90.0, Collections.singletonList(segment), 15.0, 50.0);

        assertNotNull(match);
        assertEquals(segment.segmentId, match.segment.segmentId);
        assertFalse(match.reverse);
        assertEquals(segment.fromNode, match.fromNodeDirectional());
        assertEquals(segment.toNode, match.toNodeDirectional());
    }

    @Test
    public void findMatchingSegmentReturnsNullBeyondMaxDist() {
        RoadSegment segment = eastWestSegment();
        LatLon farPos = new LatLon(37.802, -122.27); // ~221m north: beyond 50m

        SegmentMatch match = RoadGeo.findMatchingSegment(
                farPos, 90.0, Collections.singletonList(segment), 15.0, 50.0);

        assertNull(match);
    }

    @Test
    public void findMatchingSegmentReverseHeadingSwapsDirectionalNodes() {
        RoadSegment segment = eastWestSegment();
        LatLon pos = new LatLon(37.8001, -122.27);

        SegmentMatch match = RoadGeo.findMatchingSegment(
                pos, 270.0, Collections.singletonList(segment), 15.0, 50.0);

        assertNotNull(match);
        assertTrue(match.reverse);
        assertEquals(segment.toNode, match.fromNodeDirectional());
        assertEquals(segment.fromNode, match.toNodeDirectional());
    }
}
