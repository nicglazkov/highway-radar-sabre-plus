package app.sabre.wzsabre.waze;

/**
 * Tile-id math and tile-server URL building for the Waze mobile-app "RT" protocol.
 * Pure Java (no Android APIs) so it is plain-JVM testable.
 * Ported from wzsabre 2.2 wazemo.WazeTileParser (buildTileUrl/coordToTileId).
 */
final class WazeTileCodec {
    private WazeTileCodec() {}

    /** Global tile id for a coordinate, using WazeConstants.TILE_NUM_ROWS row stride. */
    static int coordToTileId(double lon, double lat) {
        int lonTile = (((int) (lon * 1_000_000.0)) + 180_000_000) / 10000;
        int latTile = (((int) (lat * 1_000_000.0)) + 90_000_000) / 10000;
        return (lonTile * WazeConstants.TILE_NUM_ROWS) + latTile;
    }

    /**
     * Tile-server multi-get URL for a single tile. When serverSessionId==0 or
     * secretKey==null (not logged in yet), the session part is omitted.
     */
    static String buildTileUrl(String tileHost, long serverSessionId, String secretKey, int tileId) {
        String sessionPart = (serverSessionId != 0 && secretKey != null)
                ? "&sessionid=" + serverSessionId + "&cookie=" + secretKey
                : "";
        return "https://" + tileHost + "/TileServer/multi-get?reqtype=tileBatch&protocol=2"
                + sessionPart + "&num=1&variation=PARTIAL_SIMPLIFICATION&t0=" + tileId + "&v0=0&p0=42";
    }
}
