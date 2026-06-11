package app.sabre.wzsabre.waze;

/**
 * Bounding-box geometry for Waze RT area queries, ported from wzsabre 2.2
 * {@code GeoUtils} (circleToBox / shrinkBox) and {@code WazeAlertFetcher}
 * (getShrinkingBboxes).
 *
 * <p>A single MapDisplayed query over the full HR radius is thinned server-side by
 * viewport size, so minor/near-driver alerts (e.g. a car stopped on the shoulder)
 * get dropped. The official queries a series of progressively smaller boxes around
 * the driver; the smaller the viewport the less the server thins it, so the inner
 * detail comes through. Each box is shrunk to 0.75 before querying, exactly as the
 * official's {@code scanBoxes} does for the primary slot.
 *
 * <p>Boxes are {@code [lonMin, latMin, lonMax, latMax]}.
 */
final class GeoBoxes {
    private GeoBoxes() {}

    /** {@code GeoUtils.circleToBox}: a lon/lat box of half-width radiusM around the point. */
    static double[] circleToBox(double lon, double lat, double radiusM) {
        double dLat = radiusM / WazeConstants.M_PER_DEG_LAT;
        double dLon = radiusM / WazeConstants.mPerDegLon(lat);
        return new double[]{lon - dLon, lat - dLat, lon + dLon, lat + dLat};
    }

    /** {@code GeoUtils.shrinkBox}: same center, half-extent scaled by {@code factor}. */
    static double[] shrink(double[] box, double factor) {
        double cx = (box[0] + box[2]) / 2.0;
        double cy = (box[1] + box[3]) / 2.0;
        double hx = ((box[2] - box[0]) / 2.0) * factor;
        double hy = ((box[3] - box[1]) / 2.0) * factor;
        return new double[]{cx - hx, cy - hy, cx + hx, cy + hy};
    }

    /**
     * {@code WazeAlertFetcher.getShrinkingBboxes}: the full-radius box, then each
     * successive box halved, for {@code steps} zoom levels in all.
     */
    static double[][] shrinkingBoxes(double lon, double lat, double radiusM, int steps) {
        double[][] out = new double[steps][];
        out[0] = circleToBox(lon, lat, radiusM);
        for (int i = 1; i < steps; i++) {
            out[i] = shrink(out[i - 1], 0.5);
        }
        return out;
    }
}
