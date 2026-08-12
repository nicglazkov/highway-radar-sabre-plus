package app.sabre.wzsabre.waze;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pure math for the Waze tile GET: tile-id computation and URL building.
 * Ported from wzsabre 2.2 wazemo.WazeTileParser (buildTileUrl/coordToTileId).
 */
public class WazeTileCodecTest {

    @Test
    public void coordToTileIdMatchesExpectedLiteral() {
        // lon=-122.2712 -> lon*1e6 = -122271200; +180000000 = 57728800; /10000 = 5772
        // lat=37.8044   -> lat*1e6 =   37804400; +90000000  = 127804400; /10000 = 12780
        // tileId = 5772*18000 + 12780 = 103896000 + 12780 = 103908780
        assertEquals(103908780, WazeTileCodec.coordToTileId(-122.2712, 37.8044));
    }

    @Test
    public void buildTileUrlIncludesSessionPartWhenLoggedIn() {
        String url = WazeTileCodec.buildTileUrl("ctilesgcs-am.waze.com", 833, "abc", 12345);
        assertEquals(
                "https://ctilesgcs-am.waze.com/TileServer/multi-get?reqtype=tileBatch&protocol=2"
                        + "&sessionid=833&cookie=abc&num=1&variation=PARTIAL_SIMPLIFICATION"
                        + "&t0=12345&v0=0&p0=42",
                url);
    }

    @Test
    public void buildTileUrlOmitsSessionPartWhenNotLoggedIn() {
        String url = WazeTileCodec.buildTileUrl("h", 0, null, 7);
        assertEquals(
                "https://h/TileServer/multi-get?reqtype=tileBatch&protocol=2"
                        + "&num=1&variation=PARTIAL_SIMPLIFICATION&t0=7&v0=0&p0=42",
                url);
    }
}
