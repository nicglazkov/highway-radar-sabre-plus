package app.sabre.wzsabre.waze;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decoder for the Waze tile-server's binary "WZDF" tile format: the road-graph
 * geometry (nodes + segments) used to snap a GPS point to a road segment before
 * submitting a report. The tile response is NOT protobuf. Pure Java (no Android
 * APIs), so it stays plain-JVM testable.
 *
 * Ported nearly line-for-line from wzsabre 2.2 wazemo.WazeTileParser (parse/
 * parseSections/readU32/readU16/readI16/alignOffset/section), including the
 * section-directory addressing scheme: each of the {@code numSections} u32
 * values stored after the 8-byte header is the CUMULATIVE end offset (relative
 * to the start of the section body) of that section, not a per-section length;
 * a section's start is {@code alignOffset} of the previous section's end value.
 * {@code findBytes} is re-expressed as a standard substring scan (see the R2
 * report for why: the jadx decompilation of that helper has a control-flow
 * artifact that doesn't reflect correct byte-search behavior).
 */
final class WazeTileParser {
    private static final byte[] MAGIC = {87, 90, 68, 70, 1, 0, 0, 0, 0, 0, 3, 0}; // "WZDF" 01000000 00000300

    private WazeTileParser() {}

    /**
     * Parses raw tile-server response bytes into road segments. Returns an empty
     * list if the WZDF magic is absent or the tile has too few sections (index 26,
     * the tile header, must exist). Throws if the magic is present but the payload
     * is otherwise corrupt (matches the reference; the caller, WazeSession.fetchTileSegments,
     * is expected to catch broadly around the whole GET+parse and fail soft).
     */
    static List<RoadSegment> parse(byte[] data) {
        int magicAt = findBytes(data, MAGIC);
        if (magicAt < 0) {
            return new ArrayList<>();
        }
        int compressedLen = readU32(data, magicAt + 12);
        int uncompressedLen = readU32(data, magicAt + 16);
        Inflater inflater = new Inflater();
        inflater.setInput(data, magicAt + 20, compressedLen);
        byte[] sections = new byte[uncompressedLen];
        int inflated;
        try {
            inflated = inflater.inflate(sections);
        } catch (DataFormatException e) {
            inflater.end();
            throw new RuntimeException("WZDF inflate failed", e);
        }
        inflater.end();
        if (inflated != uncompressedLen) {
            throw new IllegalStateException("Decompressed " + inflated + " != expected " + uncompressedLen);
        }
        return parseSections(sections);
    }

    private static List<RoadSegment> parseSections(byte[] data) {
        int numSections = readU32(data, 0);
        int alignBits = readU32(data, 4);
        if (numSections <= 26) {
            return new ArrayList<>();
        }

        // Section directory: numSections u32 values read sequentially starting at
        // byte 8. Each value is the cumulative end offset (relative to `base`, the
        // byte right after the directory) of that section; a section's start is
        // alignOffset() of the PREVIOUS section's end value (offset[0] = alignOffset(0)).
        int[] sectionOffset = new int[numSections];
        int[] sectionEnd = new int[numSections];
        int cursor = 8;
        int running = 0;
        for (int i = 0; i < numSections; i++) {
            sectionOffset[i] = alignOffset(running, alignBits);
            int value = readU32(data, cursor);
            cursor += 4;
            sectionEnd[i] = value;
            running = value;
        }
        int base = cursor;

        // Section 26 = tile header: first u32 = tileId.
        byte[] header = section(data, base, sectionOffset[26], sectionEnd[26]);
        if (header.length < 12) {
            return new ArrayList<>();
        }
        int tileId = readU32(header, 0);
        int lonIdx = tileId / WazeConstants.TILE_NUM_ROWS;
        int latIdx = tileId % WazeConstants.TILE_NUM_ROWS;

        // Section 13 = NODES: 4 bytes/entry, u16 lonOff, u16 latOff.
        byte[] nodesSection = section(data, base, sectionOffset[13], sectionEnd[13]);
        List<LatLon> nodes = new ArrayList<>();
        for (int off = 0; off + 4 <= nodesSection.length; off += 4) {
            int lonOff = readU16(nodesSection, off);
            int latOff = readU16(nodesSection, off + 2);
            double lat = ((latIdx * 10000 - 90000000) + latOff) * 1.0e-6;
            double lon = ((lonIdx * 10000 - 180000000) + lonOff) * 1.0e-6;
            nodes.add(new LatLon(lat, lon));
        }

        // Section 8 = POINT DELTAS: 4 bytes/entry, signed i16 pair (dLon, dLat) microdegrees.
        byte[] deltasSection = section(data, base, sectionOffset[8], sectionEnd[8]);
        List<int[]> deltas = new ArrayList<>();
        for (int off = 0; off + 4 <= deltasSection.length; off += 4) {
            deltas.add(new int[]{readI16(deltasSection, off), readI16(deltasSection, off + 2)});
        }

        // Section 9 = SEGMENTS: 8 bytes/entry.
        byte[] segmentsSection = section(data, base, sectionOffset[9], sectionEnd[9]);
        List<RoadSegment> segments = new ArrayList<>();
        int segIndex = 0;
        for (int off = 0; off + 8 <= segmentsSection.length; off += 8) {
            int fromIdx = readU16(segmentsSection, off) & 0x7FFF;
            int toIdx = readU16(segmentsSection, off + 2) & 0x7FFF;
            int ptRef = readU16(segmentsSection, off + 4);
            // bytes off+6..off+7 unused
            if (fromIdx < nodes.size() && toIdx < nodes.size()) {
                List<LatLon> points = new ArrayList<>();
                points.add(nodes.get(fromIdx));
                double lon = nodes.get(fromIdx).lon;
                double lat = nodes.get(fromIdx).lat;
                if (ptRef != 0xFFFF && ptRef < deltas.size()) {
                    int count = deltas.get(ptRef)[1]; // ptRef entry's second field = count
                    int start = ptRef + 1;
                    int end = Math.min(count + start, deltas.size());
                    for (int k = start; k < end; k++) {
                        lon += deltas.get(k)[0] * 1.0e-6;
                        lat += deltas.get(k)[1] * 1.0e-6;
                        points.add(new LatLon(lat, lon));
                    }
                }
                points.add(nodes.get(toIdx));
                // GeoUtils.computeHeading (Task R3's RoadGeo.computeHeading exposes the
                // same formula publicly for the segment-snap geometry); inlined here
                // since it is self-contained (no other GeoUtils dependency).
                int heading = computeHeading(points.get(0), points.get(points.size() - 1));
                segments.add(new RoadSegment(
                        ((long) tileId) * 100000L + (long) segIndex,
                        fromIdx, toIdx, heading, points));
            }
            segIndex++;
        }
        return segments;
    }

    /** Ported from wzsabre 2.2 wazemo.GeoUtils.computeHeading. Bearing in [0,360) degrees. */
    private static int computeHeading(LatLon from, LatLon to) {
        double bearing = Math.toDegrees(Math.atan2(
                (to.lon - from.lon) * Math.cos(Math.toRadians((from.lat + to.lat) / 2.0)),
                to.lat - from.lat));
        return (int) Math.round((bearing + 360.0) % 360.0);
    }

    private static int alignOffset(int value, int bits) {
        int mask = (1 << bits) - 1;
        return (~mask) & (value + mask);
    }

    private static int readU32(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }

    private static int readU16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static int readI16(byte[] d, int o) {
        int u16 = readU16(d, o);
        return u16 >= 32768 ? u16 - 65536 : u16;
    }

    private static byte[] section(byte[] data, int base, int offset, int end) {
        int start = offset + base;
        int stop = base + end;
        if (start >= data.length || stop > data.length || start >= stop) {
            return new byte[0];
        }
        return Arrays.copyOfRange(data, start, stop);
    }

    private static int findBytes(byte[] haystack, byte[] needle) {
        int limit = haystack.length - needle.length;
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
