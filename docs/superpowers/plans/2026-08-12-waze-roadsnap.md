# Waze Road-Snap Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make user reports actually accepted by live Waze by porting the official app's GPS-to-road-segment snap (tile fetch + WZDF decode + segment match) and the full `submitReport` command sequence, so the report carries `SegmentNodes`.

**Architecture:** Extend the existing `app.sabre.wzsabre.waze` package. Add a tile-server GET, a WZDF binary tile decoder, segment-snap geometry, and an `At`/`simulateDriving` command sequence; rewrite `WazeSession.submitReport` to snap the point and send directional `SegmentNodes`. No protobuf changes (the report proto tree already exists).

**Tech Stack:** Java, protobuf-javalite (unchanged), OkHttp (WazeHttpClient), JUnit (JVM unit tests, no Robolectric).

## Global Constraints
- Port faithfully from the readable jadx reference `C:\Users\live\Documents\code\wzDecomp\wzsabre-2.2-jadx\sources\app\sabre\wzsabre\wazemo\`. The authoritative recon (exact wire formats, field offsets, URL params) is `.superpowers/sdd/2026-08-12-hr-reports-to-waze/roadsnap-recon.md` — read it first every task.
- Reference package is `app.sabre.wzsabre.wazemo`; OUR package is `app.sabre.wzsabre.waze`. Adapt names to our existing classes (`WazeSession`, `WazeRtCodec`, `WazeConstants`, `WazeHttpClient`, `WazeProto`, `WazeReportCodec`).
- No new Android permission (tile GET uses existing INTERNET). No new protobuf. No em dashes, no attribution.
- Tile-local node indices (u16 & 0x7FFF) are what go on the wire as from_node/to_node — port section indexing EXACTLY.
- All WZDF integers are little-endian. Reproduce `variation=PARTIAL_SIMPLIFICATION`, `p0=42`, `v0=0`, and the epoch `if-modified-since` literally.
- Windows: run gradle via PowerShell with `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat <task>`.

---

## Task R1: Tile constants, tile-id math, URL builder, HTTP GET

**Files:**
- Modify: `app/src/main/java/app/sabre/wzsabre/waze/WazeConstants.java` (add tile hosts + `TILE_NUM_ROWS`), `app/src/main/java/app/sabre/wzsabre/waze/WazeHttpClient.java` (add `get`)
- Create: `app/src/main/java/app/sabre/wzsabre/waze/WazeTileCodec.java` (tile-id math + URL builder; pure)
- Test: `app/src/test/java/app/sabre/wzsabre/waze/WazeTileCodecTest.java`

**Interfaces:**
- Produces: `WazeConstants.tileHost(String region)`; `WazeConstants.TILE_NUM_ROWS = 18000`; `WazeHttpClient.HttpResult get(String url, Map<String,String> headers)` (mirror the existing `post` return type); `WazeTileCodec.coordToTileId(double lon, double lat) -> int`; `WazeTileCodec.buildTileUrl(String tileHost, long serverSessionId, String secretKey, int tileId) -> String` (serverSessionId==0/secretKey==null => omit session part).

- [ ] **Step 1: Read** `roadsnap-recon.md` section A, and jadx `WazeTileParser.java:35-50`, `WazeConstants.java:23,39-42`, `WazeHttpClient.java:65-71`.
- [ ] **Step 2: Write failing tests** for `coordToTileId` and `buildTileUrl`:
  - `coordToTileId(-122.2712, 37.8044)` equals `(((int)(-122.2712*1e6)+180000000)/10000)*18000 + (((int)(37.8044*1e6)+90000000)/10000)`. Compute the expected int literal and assert it.
  - `buildTileUrl("ctilesgcs-am.waze.com", 833, "abc", 12345)` equals `https://ctilesgcs-am.waze.com/TileServer/multi-get?reqtype=tileBatch&protocol=2&sessionid=833&cookie=abc&num=1&variation=PARTIAL_SIMPLIFICATION&t0=12345&v0=0&p0=42`.
  - `buildTileUrl("h",0,null,7)` omits the session part: `...&protocol=2&num=1&variation=...&t0=7&v0=0&p0=42`.
- [ ] **Step 3: Run to verify failure.** `.\gradlew.bat :app:testDebugUnitTest --tests "app.sabre.wzsabre.waze.WazeTileCodecTest"`
- [ ] **Step 4: Implement** `WazeConstants.tileHost`/`TILE_NUM_ROWS`, `WazeHttpClient.get` (port `WazeHttpClient.java:65-71`: `.url(url)` + header loop + `.get()`, returning the same `HttpResult{code, body}` the `post` path builds), and `WazeTileCodec` (coordToTileId + buildTileUrl per recon A).
- [ ] **Step 5: Run to verify pass.** Same command → PASS.
- [ ] **Step 6: Commit.** `Add Waze tile-id math, URL builder, and HTTP GET`

---

## Task R2: WZDF tile decoder + RoadSegment

**Files:**
- Create: `app/src/main/java/app/sabre/wzsabre/waze/RoadSegment.java`, `app/src/main/java/app/sabre/wzsabre/waze/WazeTileParser.java`
- Test: `app/src/test/java/app/sabre/wzsabre/waze/WazeTileParserTest.java`

**Interfaces:**
- Consumes: a `Coord`-like point. Use a simple `double[]{lat,lon}` or a small `LatLon` value type — check if the package already has one; if not, create a minimal `LatLon` (fields `lat`, `lon`). Do NOT depend on Android.
- Produces: `RoadSegment` (fields `long segmentId, long fromNode, long toNode, int heading, List<LatLon> points`); `WazeTileParser.parse(byte[] tileBytes) -> List<RoadSegment>` (empty list if no WZDF header / too few sections).

- [ ] **Step 1: Read** `roadsnap-recon.md` section B and jadx `WazeTileParser.java:52-245`, `RoadSegment.java:83-90`. Port the byte-level logic VERBATIM (magic scan, inflate, alignOffset, section directory, sections 26/13/9/8, node/segment/polyline decode, `& 0x7FFF`, little-endian readers). Use `java.util.zip.Inflater`.
- [ ] **Step 2: Write a structural failing test** (a real captured tile fixture may not exist yet, so test the decoder's guards deterministically):
  - `parse(new byte[]{0,1,2})` returns empty list (no WZDF magic).
  - Build a minimal valid buffer: `"WZDF"`+`01000000`+`00000300` header, then compressedLen/uncompressedLen for a deflate stream you construct in the test (use `java.util.zip.Deflater` to compress a hand-built sections buffer with `numSections<=26`), assert `parse` returns empty (the `numSections<=26` guard). This exercises magic-scan + inflate + the guard without needing a real tile.
  - If time permits, construct a tiny full sections buffer (numSections=27, sections 26/13/9/8 populated for ONE segment between two nodes with no polyline deltas) and assert one RoadSegment with the expected from/to indices and endpoint coords. This is the highest-value test; include it.
- [ ] **Step 3: Run to verify failure.**
- [ ] **Step 4: Implement** `RoadSegment` + `WazeTileParser` per recon B (verbatim port). `LatLon` if needed.
- [ ] **Step 5: Run to verify pass.**
- [ ] **Step 6: Commit.** `Add WZDF Waze tile decoder and RoadSegment`

---

## Task R3: Segment-snap geometry (GeoUtils + SegmentMatch)

**Files:**
- Create: `app/src/main/java/app/sabre/wzsabre/waze/RoadGeo.java` (snap math; named RoadGeo to avoid clashing if a GeoUtils/GeoBoxes exists), `app/src/main/java/app/sabre/wzsabre/waze/SegmentMatch.java`
- Test: `app/src/test/java/app/sabre/wzsabre/waze/RoadGeoTest.java`

**Interfaces:**
- Consumes: `RoadSegment`, `LatLon`.
- Produces: `SegmentMatch` (fields `RoadSegment segment`, `boolean reverse`; methods `long fromNodeDirectional()` = reverse?segment.toNode:segment.fromNode, `long toNodeDirectional()` = reverse?segment.fromNode:segment.toNode); `RoadGeo.findMatchingSegment(LatLon pos, double heading, List<RoadSegment> segs, double maxAngleDiff, double maxDistM) -> SegmentMatch|null`; `RoadGeo.computeHeading(LatLon a, LatLon b) -> int`; helper `pointToSegmentDistM`.

- [ ] **Step 1: Read** `roadsnap-recon.md` section C and jadx `GeoUtils.java:27-110`, `SegmentMatch.java:86-96`. Port the equirectangular-meters projection, `pointToSegmentDist`, `computeHeading`, and `findMatchingSegment` (nearest-by-distance among candidates within maxAngleDiff and maxDistM; `reverse = backAngleDiff < fwdAngleDiff`).
- [ ] **Step 2: Write failing tests** (pure geometry, fully deterministic):
  - A single east-west segment from (37.80,-122.28) to (37.80,-122.26); a point at (37.8001,-122.27) snaps to it with `dist` ~11m (< 50m) and returns a match; assert `match.segment.segmentId` and that `fromNodeDirectional/toNodeDirectional` match the non-reversed nodes when heading ~90°.
  - A point 200m away (37.802,-122.27) returns null (beyond 50m).
  - `computeHeading((37.80,-122.28),(37.80,-122.26))` is ~90° (east); assert within a degree or two.
  - Reverse: heading ~270° selects `reverse=true` and swaps directional nodes.
- [ ] **Step 3: Run to verify failure.**
- [ ] **Step 4: Implement** `RoadGeo` + `SegmentMatch` per recon C.
- [ ] **Step 5: Run to verify pass.**
- [ ] **Step 6: Commit.** `Add Waze segment-snap geometry`

---

## Task R4: At/SeeMe command builders + fetchTileSegments

**Files:**
- Modify: `app/src/main/java/app/sabre/wzsabre/waze/WazeRtCodec.java` (add `atCommand`, `seeMeCommand(int)`), `app/src/main/java/app/sabre/wzsabre/waze/WazeSession.java` (add `fetchTileSegments`)
- Test: `app/src/test/java/app/sabre/wzsabre/waze/WazeRtCodecTest.java` (add cases)

**Interfaces:**
- Produces: `WazeRtCodec.atCommand(double lon,double lat,int heading,long fromNode,long toNode) -> String` = `At,{lon},{lat},0,{heading},1,{fromNode},{toNode},T,0,-1,-1,0` (lon/lat formatted like the existing Location command — match `WazeProto.java:186-189`; use `-1` for from/to when no match); `WazeRtCodec.seeMeCommand(int mode) -> String` (mode 1 => existing `SeeMe,1,2,T,T,T,1,-1,1,7`; mode 2 => `SeeMe,2`); package-private `WazeSession.fetchTileSegments(double lat,double lon) -> List<RoadSegment>` (builds the tile URL from the live session id/secret + region, GETs, parses via WazeTileParser; empty list on any failure, logged).

- [ ] **Step 1: Read** `roadsnap-recon.md` sections A, D and jadx `WazeProto.java:186-205`, `WazeSession.java:599-668`.
- [ ] **Step 2: Write failing tests** for the pure command builders in `WazeRtCodecTest`:
  - `atCommand(-122.2712,37.8044,90,111,222)` equals `At,-122.2712,37.8044,0,90,1,111,222,T,0,-1,-1,0` (match the exact number formatting the existing `locationCommand` uses — verify against the current `WazeRtCodec.locationCommand`).
  - `atCommand(...,-1,-1)` for the no-match case.
  - `seeMeCommand(2)` equals `SeeMe,2`; `seeMeCommand(1)` equals the existing constant.
- [ ] **Step 3: Run to verify failure.**
- [ ] **Step 4: Implement** the builders, and `WazeSession.fetchTileSegments` (uses `WazeTileCodec.buildTileUrl` with `session.serverSessionId`/`session.secretKey`, `WazeConstants.tileHost(region)`, `http.get(...)`, then `WazeTileParser.parse`). `fetchTileSegments` is network I/O (no unit test); it just needs to compile and be covered by the live validation. Region: reuse `WazeProtocolSource.region(lat,lon)`.
- [ ] **Step 5: Run tests + build.** focused WazeRtCodecTest PASS; `.\gradlew.bat :app:assembleDebug` SUCCESS.
- [ ] **Step 6: Commit.** `Add At/SeeMe command builders and fetchTileSegments`

---

## Task R5: Rewrite submitReport to the full snap sequence

**Files:**
- Modify: `app/src/main/java/app/sabre/wzsabre/waze/WazeSession.java` (`submitReport`), possibly add a private `simulateDriving`

**Interfaces:**
- Consumes: `fetchTileSegments`, `RoadGeo.findMatchingSegment`, `WazeRtCodec.atCommand/seeMeCommand/handshake pieces`, `WazeReportCodec.buildRequest(r, nowMs, fromNode, toNode)` (already supports non-zero nodes -> SegmentNodes).
- Produces: `submitReport(ReportRequest r, long nowMs)` now performs the recon-D sequence and returns `ReportResult.ok(uuid,points)` on acceptance, `fail(...)` otherwise.

- [ ] **Step 1: Read** `roadsnap-recon.md` section D fully and jadx `WazeSession.java` submitReport orchestration + `WazeSession.smali:5478-7298` (for the exact command order).
- [ ] **Step 2: Implement** `submitReport`:
  1. `prepareForArea(r.lat, r.lon)` (ensures login + base handshake).
  2. Command A: one `command()` POST of `seeMeCommand(1) + "\n" + locationCommand(lon,lat) + "\n" + mapDisplayedCommand(circleToBox(lon,lat))`.
  3. `List<RoadSegment> segs = fetchTileSegments(r.lat, r.lon);`
  4. `SegmentMatch m = RoadGeo.findMatchingSegment(new LatLon(r.lat,r.lon), r.headingDeg, segs, 15.0, 50.0);` heading int = `normalizeAngle360(round(r.headingDeg))` (add a small helper).
  5. `long fromNode = m!=null ? m.fromNodeDirectional() : -1; long toNode = m!=null ? m.toNodeDirectional() : -1;`
  6. Command B: one POST of `atCommand(lon,lat,heading,fromNode,toNode) + "\n" + mapDisplayedCommand(...)`.
  7. If `m != null`: `simulateDriving(r.lat, r.lon, heading, m)` — 3 iterations, each steps the position ~1s along heading at 13.4 m/s (dLon=sin(rad)/mPerDegLon(lat)*13.4; dLat=cos(rad)/110574*13.4) and POSTs `atCommand(steppedLon,steppedLat,heading,fromNode,toNode) + "\n" + mapDisplayedCommand(stepped box)`. Do NOT Thread.sleep on a fresh emulator-blocking path longer than needed; a short (e.g. 200-1000ms) delay between steps is fine (responses unused). Wrap each in try/catch so a failed sim step does not abort the report.
  8. Command C (the report): build `WazeProto.AddUserReportedAlertRequest req = WazeReportCodec.buildRequest(r, nowMs, m!=null?fromNode:0, m!=null?toNode:0);` (pass 0/0 when no match so SegmentNodes is omitted, matching current behavior; pass the directional nodes when matched). POST `WazeRtCodec.reportPayload(req)`; keep THIS response batch.
  9. Command D: `command(seeMeCommand(2))` in a try/catch; ignore its result.
  10. Parse the Command-C batch: `WazeReportCodec.reportUuidFrom` / `reportPointsFrom`; uuid non-null => `ReportResult.ok`, log "Report accepted: uuid=.. pts=.."; else `ReportResult.fail("no report response")`.
  Log "Snapped to road {id} {FWD|REV} dist=" on match and "No road match ({n} candidates)" on null, mirroring the reference.
- [ ] **Step 3: Build.** `.\gradlew.bat :app:assembleDebug` SUCCESS and `.\gradlew.bat :app:testDebugUnitTest` green (no regressions).
- [ ] **Step 4: Commit.** `Rewrite submitReport to snap to a road segment before reporting`

---

## Task R6: Live emulator validation (manual — controller drives)

- [ ] Rebuild + install debug APK on the A15 emulator. Warm the Waze read account (fetch until "Waze cache: N alerts" persists an account).
- [ ] Fire ONE `HAZARD_ON_ROAD_POT_HOLE` report at a real road coordinate; confirm logcat shows "Snapped to road ..." then "Report accepted: uuid=.. pts=..". If accepted, retract via a DISCARD using the numeric id recovered from a follow-up fetch (or let it expire); confirm no crash.
- [ ] If still "No report response", capture the raw tile GET bytes and the report POST for diagnosis (WZDF decode correctness / node-index assumption) before iterating.
- [ ] Record the result in the ledger. Ship gate: only proceed to version bump/release once a real report is accepted by live Waze.

## Self-Review
- Coverage: tile GET+id (R1), WZDF decode (R2), snap (R3), commands+fetch (R4), submitReport sequence (R5), live gate (R6). ✓
- No new proto / no new permission / port-verbatim constraints stated per task. ✓
- Types: `LatLon`, `RoadSegment`, `SegmentMatch`, `WazeTileCodec`, `WazeTileParser`, `RoadGeo` names consistent across tasks. `WazeReportCodec.buildRequest(r,now,from,to)` reused (already exists). ✓
