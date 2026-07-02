package app.sabre.wzsabre;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/** Tests Caltrans chain-control (cc) feed parsing, active filtering, and SABRE mapping. */
public class WinterSourceTest {

    private static String record(String index, double lat, double lon, String route,
                                 String locName, String nearby, boolean inService, String status) {
        return "<cc><index>" + index + "</index>" +
            "<recordTimestamp><recordDate>2026-01-05</recordDate><recordTime>08:00:00</recordTime></recordTimestamp>" +
            "<location>" +
            "<district>10</district>" +
            "<locationName>" + locName + "</locationName>" +
            "<nearbyPlace>" + nearby + "</nearbyPlace>" +
            "<longitude>" + lon + "</longitude>" +
            "<latitude>" + lat + "</latitude>" +
            "<elevation>7000</elevation>" +
            "<direction>West</direction>" +
            "<county>Alpine</county>" +
            "<route>" + route + "</route>" +
            "</location>" +
            "<inService>" + inService + "</inService>" +
            "<statusData>" +
            "<statusTimestamp><statusDate>2026-01-05</statusDate><statusTime>07:30:00</statusTime></statusTimestamp>" +
            "<status>" + status + "</status>" +
            "<statusDescription>Chain control desc.</statusDescription>" +
            "</statusData></cc>";
    }

    private static String feed(String... records) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><data>");
        for (String r : records) sb.append(r);
        return sb.append("</data>").toString();
    }

    private static List<WinterSource.ChainControl> parse(String xml) throws Exception {
        return WinterSource.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    public void parse_readsFields() throws Exception {
        WinterSource.ChainControl c = parse(feed(
                record("10-ALP-4-0.65-W", 38.4605, -120.0448, "SR-4", "BEAR VALLEY", "Arnold", true, "R-2")))
                .get(0);
        assertEquals("10-ALP-4-0.65-W", c.index);
        assertEquals(38.4605, c.lat, 1e-6);
        assertEquals(-120.0448, c.lon, 1e-6);
        assertEquals("SR-4", c.route);
        assertEquals("BEAR VALLEY", c.locationName);
        assertEquals("Arnold", c.nearbyPlace);
        assertTrue(c.inService);
        assertEquals("R-2", c.status);
    }

    @Test
    public void isActive_levelAboveR0_whenInService() throws Exception {
        WinterSource.ChainControl c = parse(feed(
                record("i", 38.46, -120.04, "SR-4", "X", "Y", true, "R-2"))).get(0);
        assertTrue(WinterSource.isActive(c));
    }

    @Test
    public void isActive_r0_isInactive() throws Exception {
        WinterSource.ChainControl c = parse(feed(
                record("i", 38.46, -120.04, "SR-4", "X", "Y", true, "R-0"))).get(0);
        assertFalse("R-0 = no chain control, must not alert", WinterSource.isActive(c));
    }

    @Test
    public void isActive_notInService_isInactive() throws Exception {
        WinterSource.ChainControl c = parse(feed(
                record("i", 38.46, -120.04, "SR-4", "X", "Y", false, "R-2"))).get(0);
        assertFalse(WinterSource.isActive(c));
    }

    @Test
    public void isActive_missingCoords_isInactive() throws Exception {
        WinterSource.ChainControl c = parse(feed(
                record("i", 0.0, 0.0, "SR-4", "X", "Y", true, "R-2"))).get(0);
        assertFalse(WinterSource.isActive(c));
    }

    @Test
    public void toAlert_validSabreFields() throws Exception {
        WinterSource.ChainControl c = parse(feed(
                record("10-ALP-4-0.65-W", 38.4605, -120.0448, "SR-4", "BEAR VALLEY", "Arnold", true, "R-2")))
                .get(0);
        SabreAlert a = WinterSource.toAlert(c);
        assertEquals("cc_10-ALP-4-0.65-W", a.alertId);
        assertEquals(SabreResponseBuilder.SOURCE_CHAINS, a.alertSource);
        assertEquals("HAZARD_ON_ROAD_SLIPPERY", a.type);
        assertTrue(SabreResponseBuilder.isValidType(a.type));
        assertEquals(SabreResponseBuilder.HEADING_UNKNOWN, a.headingDeg, 0.0);
        assertTrue("report_ts parsed from statusTimestamp", a.reportTs > 0);
        assertTrue(a.reportTs <= Integer.MAX_VALUE);
    }

    @Test
    public void describe_format() throws Exception {
        WinterSource.ChainControl c = parse(feed(
                record("i", 38.46, -120.04, "SR-4", "BEAR VALLEY", "Arnold", true, "R-2"))).get(0);
        assertEquals("Chains R-2 · SR-4 @ BEAR VALLEY (Arnold)", WinterSource.describe(c));
    }
}
