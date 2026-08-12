package app.sabre.wzsabre.waze;

/**
 * Minimal lat/lon value type for road-snap geometry (WZDF tile decode, segment
 * matching). Pure Java, no Android APIs, so it stays plain-JVM testable.
 */
final class LatLon {
    public final double lat;
    public final double lon;

    LatLon(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
    }
}
