package app.sabre.wzsabre.waze;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

import org.junit.Test;

/**
 * WZDF binary tile decoder. Ported from wzsabre 2.2 wazemo.WazeTileParser.
 * The full-decode fixture below hand-derives the section directory's
 * (offset, cumulative-end) encoding directly from the byte layout in
 * roadsnap-recon.md section B; it does not reuse WazeTileParser's own code.
 */
public class WazeTileParserTest {

    private static final byte[] MAGIC = {87, 90, 68, 70, 1, 0, 0, 0, 0, 0, 3, 0};

    private static void putU16LE(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void putU32LE(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        byte[] buf = new byte[raw.length * 2 + 64];
        int n = deflater.deflate(buf);
        deflater.end();
        return Arrays.copyOf(buf, n);
    }

    private static byte[] wrapWzdf(byte[] sections) {
        byte[] compressed = deflate(sections);
        byte[] tile = new byte[12 + 4 + 4 + compressed.length];
        System.arraycopy(MAGIC, 0, tile, 0, 12);
        putU32LE(tile, 12, compressed.length);
        putU32LE(tile, 16, sections.length);
        System.arraycopy(compressed, 0, tile, 20, compressed.length);
        return tile;
    }

    @Test
    public void parseReturnsEmptyWhenNoWzdfMagic() {
        List<RoadSegment> segs = WazeTileParser.parse(new byte[]{0, 1, 2});
        assertTrue(segs.isEmpty());
    }

    @Test
    public void parseReturnsEmptyWhenNumSectionsAtOrBelow26() {
        // Minimal sections payload: just the 8-byte header (numSections, alignBits).
        // numSections<=26 must short-circuit before the directory/section reads.
        byte[] sections = new byte[8];
        putU32LE(sections, 0, 5);   // numSections
        putU32LE(sections, 4, 0);  // alignBits
        byte[] tile = wrapWzdf(sections);

        List<RoadSegment> segs = WazeTileParser.parse(tile);
        assertTrue(segs.isEmpty());
    }

    @Test
    public void parseDecodesOneSegmentBetweenTwoNodes() {
        // --- Hand-derive the section directory (recon B: alignOffset(v,bits) with
        // bits=0 is the identity function, so directory "value" fields are simply
        // the cumulative end-offset, relative to `base`, of each section in turn). ---
        // Sections used: 8 (point deltas, empty), 9 (segments, 1 entry = 8 bytes),
        // 13 (nodes, 2 entries = 8 bytes), 26 (tile header, 12 bytes). All others
        // are zero-length. numSections must be 27 so index 26 exists.
        final int numSections = 27;
        final int alignBits = 0;
        int[] dirValues = new int[numSections];
        for (int i = 0; i <= 8; i++) dirValues[i] = 0;      // sections 0-8 empty
        dirValues[9] = 8;                                    // segments: 0..8
        for (int i = 10; i <= 12; i++) dirValues[i] = 8;     // 10-12 empty (stay at 8)
        dirValues[13] = 16;                                  // nodes: 8..16
        for (int i = 14; i <= 25; i++) dirValues[i] = 16;    // 14-25 empty (stay at 16)
        dirValues[26] = 28;                                  // tile header: 16..28

        int base = 8 + numSections * 4;
        byte[] sections = new byte[base + 28];
        putU32LE(sections, 0, numSections);
        putU32LE(sections, 4, alignBits);
        for (int i = 0; i < numSections; i++) {
            putU32LE(sections, 8 + i * 4, dirValues[i]);
        }

        // Section 9: one segment, fromIdx=0, toIdx=1, ptRef=0xFFFF (no polyline deltas).
        int segOff = base;
        putU16LE(sections, segOff, 0);
        putU16LE(sections, segOff + 2, 1);
        putU16LE(sections, segOff + 4, 0xFFFF);
        putU16LE(sections, segOff + 6, 0);

        // Section 13: two nodes. node0 lonOff=0,latOff=0; node1 lonOff=20000,latOff=0.
        int nodesOff = base + 8;
        putU16LE(sections, nodesOff, 0);
        putU16LE(sections, nodesOff + 2, 0);
        putU16LE(sections, nodesOff + 4, 20000);
        putU16LE(sections, nodesOff + 6, 0);

        // Section 26: tile header, tileId only (remaining 8 bytes unused/zero).
        int lonIdx = 5772, latIdx = 12780;
        int tileId = lonIdx * 18000 + latIdx; // 103908780
        int headerOff = base + 16;
        putU32LE(sections, headerOff, tileId);

        byte[] tile = wrapWzdf(sections);

        List<RoadSegment> segs = WazeTileParser.parse(tile);
        assertEquals(1, segs.size());
        RoadSegment seg = segs.get(0);
        assertEquals(0L, seg.fromNode);
        assertEquals(1L, seg.toNode);
        assertEquals((long) tileId * 100000L, seg.segmentId);
        assertEquals(2, seg.points.size());

        // Expected coords use the same decode formula as recon B (double math must
        // match the implementation bit-for-bit since both compute it the same way).
        double lon0 = ((lonIdx * 10000 - 180000000) + 0) * 1e-6;
        double lat0 = ((latIdx * 10000 - 90000000) + 0) * 1e-6;
        double lon1 = ((lonIdx * 10000 - 180000000) + 20000) * 1e-6;
        double lat1 = ((latIdx * 10000 - 90000000) + 0) * 1e-6;
        assertEquals(lat0, seg.points.get(0).lat, 1e-9);
        assertEquals(lon0, seg.points.get(0).lon, 1e-9);
        assertEquals(lat1, seg.points.get(1).lat, 1e-9);
        assertEquals(lon1, seg.points.get(1).lon, 1e-9);
    }
}
