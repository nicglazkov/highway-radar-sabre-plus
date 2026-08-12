# Bidirectional User Reports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make reports a user creates in Highway Radar (HR) appear on HR's map and get submitted to Waze's live server, closing the gap between SABRE Plus and the original wzsabre.

**Architecture:** HR fires `REPORT`/`CONFIRM`/`DISCARD` broadcasts at `MainBroadcastReceiver`; they are forwarded to `SabreService` like fetches are. A REPORT is (a) added to an in-memory `UserReportStore` that `handleFetchRequest` merges into every HR response for ~30 min (guarantees the HR pin) and (b) submitted to Waze via a new `WazeReporter` using the `AddUserReportedAlertRequest` protobuf. CONFIRM/DISCARD send Waze's `ThumbsUp,<id>` / `ReportRmAlert,<id>` text commands.

**Tech Stack:** Java, Android, protobuf-javalite (`app/src/main/proto/waze.proto` -> `WazeProto`), OkHttp (`WazeHttpClient`), JUnit + org.json for JVM unit tests (no Robolectric).

## Global Constraints

- Target version at ship: `versionName = "1.10.0"`, `versionCode = 27` (from 1.9.6 / 26). Bump only at the ship task, after emulator validation.
- No new Android permission. Reporting and any tile fetch use existing INTERNET only. (Hard constraint from project rules.)
- No em dashes in any user-facing string (Toast/notification/CHANGELOG/release notes). Use colons/commas/periods.
- No user attribution anywhere (CHANGELOG/commits/release notes): never "one user reported".
- `alert_source` on any echoed alert MUST be one of the advertised ids: `chp`/`waze`/`lcs`/`fire`/`chains`. Echoed reports use `waze`.
- An echoed alert's `type` MUST start with `POLICE`/`HAZARD`/`ACCIDENT` or HR's renderer silently drops it.
- The HR fetch response is exactly 9 fields (locked by `SabreProtocolTest`). Do not change it; the echo store only adds `SabreAlert`s to the existing list.
- Protobuf field numbers are a wire spec. Use EXACTLY the numbers below; a wrong number breaks silently.
- JVM unit tests cannot use `android.util.Base64` (returns null under `unitTests.isReturnDefaultValues = true`). Keep Base64 framing out of unit-tested code paths; test proto assembly via generated `.toByteArray()`.

## Exact Waze reporting wire spec (recovered from decompiled wzsabre 2.2)

Element members: `add_user_reported_alert_request = 2737`, `add_user_reported_alert_response = 2738`.
Confirm command: `ThumbsUp,<alertId>` (decimal long). Discard command: `ReportRmAlert,<alertId>`.
HR report `type` string -> Waze detail message + subtype enum:

| HR type (prefix) | AlertDetails member | subtype enum value |
|---|---|---|
| `POLICE_VISIBLE` | police | POLICE_DEFAULT (1) |
| `POLICE_HIDDEN`/`POLICE_HIDING` | police | POLICE_HIDDEN (2) |
| `ACCIDENT_MAJOR` | crash | CRASH_REPORT_MAJOR (3) |
| `ACCIDENT_MINOR` (or other ACCIDENT) | crash | CRASH_REPORT_MINOR (4) |
| `JAM_STAND_STILL_TRAFFIC` | traffic | TRAFFIC_REPORT_STANDSTILL (2) |
| `JAM_LIGHT_TRAFFIC` | traffic | TRAFFIC_REPORT_LIGHT (3) |
| `JAM_MODERATE_TRAFFIC` | traffic | TRAFFIC_REPORT_MODERATE (4) |
| `JAM_HEAVY_TRAFFIC` (or other JAM) | traffic | TRAFFIC_REPORT_HEAVY (5) |
| `HAZARD_ON_ROAD_CONSTRUCTION` | hazard_on_road | HAZARD_REPORT_CONSTRUCTION (2) |
| `HAZARD_ON_ROAD_CAR_STOPPED` | hazard_on_road | HAZARD_REPORT_VEHICLE_STOPPED (3) |
| `HAZARD_ON_ROAD_OBJECT` | hazard_on_road | HAZARD_REPORT_OBJECT_ON_ROAD (4) |
| `HAZARD_ON_ROAD_POT_HOLE` | hazard_on_road | HAZARD_REPORT_POTHOLE (5) |
| `HAZARD_ON_ROAD_TRAFFIC_LIGHT_FAULT` | hazard_on_road | HAZARD_REPORT_BROKEN_TRAFFIC_LIGHT (6) |
| `HAZARD_ON_ROAD_OIL` | hazard_on_road | HAZARD_REPORT_OIL (7) |
| `HAZARD_ON_SHOULDER_ANIMALS` | hazard_on_road | HAZARD_REPORT_ANIMALS (8) |
| `HAZARD_ON_SHOULDER_MISSING_SIGN` | hazard_on_road | HAZARD_REPORT_MISSING_SIGN (9) |
| `HAZARD_ON_ROAD_ROAD_KILL` | hazard_on_road | HAZARD_REPORT_ROAD_KILL (10) |
| `HAZARD_ON_SHOULDER_CAR_STOPPED` | hazard_on_road | HAZARD_REPORT_SHOULDER_VEHICLE_STOPPED (12) |
| `HAZARD_ON_SHOULDER` (bare) | hazard_on_road | HAZARD_REPORT_SHOULDER (11) |
| any other `HAZARD*` | hazard_on_road | HAZARD_REPORT_DEFAULT (1) |
| anything else | (reject Waze submit; echo still shows) | n/a |

---

## File Structure

- `app/src/main/proto/waze.proto` — add reporting messages + 2 Element fields (Task 1).
- `app/src/main/java/app/sabre/wzsabre/ReportRequest.java` — NEW, parsed REPORT payload (Task 3).
- `app/src/main/java/app/sabre/wzsabre/ConfirmDiscardRequest.java` — NEW, parsed CONFIRM/DISCARD payload + Waze-id recovery (Task 3).
- `app/src/main/java/app/sabre/wzsabre/UserReportStore.java` — NEW, echo store (Task 4).
- `app/src/main/java/app/sabre/wzsabre/AlertMapper.java` — add `renderableEchoType` + `wazeReportSubtype` (Task 2).
- `app/src/main/java/app/sabre/wzsabre/MainBroadcastReceiver.java` — `classifyReportAction` + dispatch (Task 5).
- `app/src/main/java/app/sabre/wzsabre/waze/WazeReportCodec.java` — NEW, assemble `AddUserReportedAlertRequest` from a ReportRequest, returns proto (Task 6).
- `app/src/main/java/app/sabre/wzsabre/waze/WazeSession.java` — add `submitReport`/`confirmAlert`/`discardAlert` (Task 7).
- `app/src/main/java/app/sabre/wzsabre/waze/WazeReporter.java` — NEW, session mgmt + selfTest (Task 8).
- `app/src/main/java/app/sabre/wzsabre/SabreService.java` — dispatch + merge store (Task 9).
- Tests under `app/src/test/java/app/sabre/wzsabre/` and `.../waze/`.

---

## Task 1: Reporting protobuf messages

**Files:**
- Modify: `app/src/main/proto/waze.proto` (add to `message Element`; append reporting messages at the reporting section)
- Test: `app/src/test/java/app/sabre/wzsabre/waze/WazeReportProtoTest.java` (new)

**Interfaces:**
- Produces: generated `WazeProto.AddUserReportedAlertRequest`, `.AddUserReportedAlertResponse`, `.UserPosition`, `.GpsPosition`, `.CoordinateWithAlt`, `.SegmentNodes`, `.Timestamp`, `.ReportingManner`, `.AlertDetails`, `.PoliceDetails`, `.CrashDetails`, `.TrafficDetails`, `.HazardOnRoadDetails` and their subtype enums; `WazeProto.Element` gains `getAddUserReportedAlertRequest()`/`setAddUserReportedAlertRequest()`/`hasAddUserReportedAlertResponse()`/`getAddUserReportedAlertResponse()`.

- [ ] **Step 1: Add the two Element members.** In `waze.proto`, inside `message Element { ... }`, after `optional AddAlertAction add_alert_action = 2708;` add:

```proto
  optional AddUserReportedAlertRequest add_user_reported_alert_request = 2737;
  optional AddUserReportedAlertResponse add_user_reported_alert_response = 2738;
```

- [ ] **Step 2: Append the reporting messages** at the end of `waze.proto` (before EOF), copying this block verbatim (field numbers are the wire spec):

```proto
// ---------------------------------------------------------------------------
// Alert REPORTING (write) path. Field numbers from *_FIELD_NUMBER smali
// constants cross-checked against generated writeTo() (plain int32/int64/enum/
// double/message, no zig-zag). Built by WazeReportCodec.
// ---------------------------------------------------------------------------

message CoordinateWithAlt {
  optional int32 lon_times1000000 = 101;
  optional int32 lat_times1000000 = 102;
  optional int32 alt_times1000000 = 103;
}

message SegmentNodes {
  optional int64 from_node = 1;
  optional int64 to_node = 2;
}

message GpsPosition {
  optional CoordinateWithAlt coordinate = 1;
  optional double horizontal_accuracy_meters = 2;
  optional int64 time_epoch_ms = 3;
}

message UserPosition {
  optional GpsPosition gps_position = 1;
  optional SegmentNodes segment_nodes = 2;
}

message Timestamp {
  optional int64 seconds = 1;
  optional int32 nanos = 2;
}

enum ReportingManner {
  REPORTING_MANNER_UNSPECIFIED = 0;
  REPORTING_MANNER_DEFAULT = 1;
  REPORTING_MANNER_VOICE = 2;
  REPORTING_MANNER_VOICE_CONVERSATION = 3;
}

enum PoliceAlertSubType {
  POLICE_UNSPECIFIED = 0;
  POLICE_DEFAULT = 1;
  POLICE_HIDDEN_REPORT = 2;
  POLICE_MOBILE_CAMERA = 3;
}
message PoliceDetails { optional PoliceAlertSubType type = 1; }

enum CrashReportSubType {
  CRASH_REPORT_UNSPECIFIED = 0;
  CRASH_REPORT_DEFAULT = 1;
  CRASH_REPORT_PILE_UP = 2;
  CRASH_REPORT_MAJOR = 3;
  CRASH_REPORT_MINOR = 4;
}
message CrashDetails { optional CrashReportSubType type = 1; }

enum TrafficReportSubType {
  TRAFFIC_REPORT_UNSPECIFIED = 0;
  TRAFFIC_REPORT_DEFAULT = 1;
  TRAFFIC_REPORT_STANDSTILL = 2;
  TRAFFIC_REPORT_LIGHT = 3;
  TRAFFIC_REPORT_MODERATE = 4;
  TRAFFIC_REPORT_HEAVY = 5;
}
message TrafficDetails { optional TrafficReportSubType type = 1; }

enum HazardReportSubType {
  HAZARD_REPORT_UNSPECIFIED = 0;
  HAZARD_REPORT_DEFAULT = 1;
  HAZARD_REPORT_CONSTRUCTION = 2;
  HAZARD_REPORT_VEHICLE_STOPPED = 3;
  HAZARD_REPORT_OBJECT_ON_ROAD = 4;
  HAZARD_REPORT_POTHOLE = 5;
  HAZARD_REPORT_BROKEN_TRAFFIC_LIGHT = 6;
  HAZARD_REPORT_OIL = 7;
  HAZARD_REPORT_ANIMALS = 8;
  HAZARD_REPORT_MISSING_SIGN = 9;
  HAZARD_REPORT_ROAD_KILL = 10;
  HAZARD_REPORT_SHOULDER = 11;
  HAZARD_REPORT_SHOULDER_VEHICLE_STOPPED = 12;
  HAZARD_REPORT_EMERGENCY_VEHICLE = 13;
}
message HazardOnRoadDetails { optional HazardReportSubType type = 1; }

message AlertDetails {
  oneof details {
    TrafficDetails traffic = 1;
    PoliceDetails police = 2;
    CrashDetails crash = 3;
    HazardOnRoadDetails hazard_on_road = 4;
  }
}

message AddUserReportedAlertRequest {
  optional UserPosition user_position = 1;
  optional int32 azymuth = 2;
  optional AlertDetails alert_details = 3;
  optional SegmentDirection segment_direction = 4;
  optional Timestamp report_time = 5;
  optional bool is_offline_delayed_report = 6;
  optional ReportingManner reporting_manner = 7;
}

message AddUserReportedAlertResponse {
  enum AddAlertStatus {
    STATUS_UNSPECIFIED = 0;
    SUCCESS = 1;
    FAILURE = 2;
  }
  optional AddAlertStatus status = 1;
  optional int32 received_points_count = 2;
  optional int64 client_alert_id = 3;
  optional AlertDetails alert_details = 4;
  optional string alert_uuid = 5;
}
```

Note: enum constant names must be globally unique in proto2. `POLICE_HIDDEN` already exists in `AlertSubType` (value 202), so the police-report value is named `POLICE_HIDDEN_REPORT` here; likewise these report enums are new names and do not collide with the fetch-path `AlertSubType`. `SegmentDirection` is the enum already defined in this file (reused).

- [ ] **Step 3: Write the failing test** `WazeReportProtoTest.java`:

```java
package app.sabre.wzsabre.waze;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.Test;

/** Locks the report protobuf wire format: field numbers and round-trip. */
public class WazeReportProtoTest {

    @Test
    public void requestEncodesAndRoundTrips() throws InvalidProtocolBufferException {
        WazeProto.CoordinateWithAlt coord = WazeProto.CoordinateWithAlt.newBuilder()
                .setLonTimes1000000(-122271200)
                .setLatTimes1000000(37804400)
                .setAltTimes1000000(0)
                .build();
        WazeProto.GpsPosition gps = WazeProto.GpsPosition.newBuilder()
                .setCoordinate(coord).setHorizontalAccuracyMeters(10.0).setTimeEpochMs(1_700_000_000_000L)
                .build();
        WazeProto.AddUserReportedAlertRequest req = WazeProto.AddUserReportedAlertRequest.newBuilder()
                .setUserPosition(WazeProto.UserPosition.newBuilder().setGpsPosition(gps).build())
                .setAzymuth(90)
                .setAlertDetails(WazeProto.AlertDetails.newBuilder()
                        .setPolice(WazeProto.PoliceDetails.newBuilder()
                                .setType(WazeProto.PoliceAlertSubType.POLICE_DEFAULT).build()).build())
                .setSegmentDirection(WazeProto.SegmentDirection.SEGMENT_DIRECTION_FORWARD)
                .setReportTime(WazeProto.Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                .setReportingManner(WazeProto.ReportingManner.REPORTING_MANNER_DEFAULT)
                .build();

        byte[] bytes = req.toByteArray();
        WazeProto.AddUserReportedAlertRequest back =
                WazeProto.AddUserReportedAlertRequest.parseFrom(bytes);

        assertEquals(-122271200, back.getUserPosition().getGpsPosition().getCoordinate().getLonTimes1000000());
        assertEquals(90, back.getAzymuth());
        assertEquals(WazeProto.PoliceAlertSubType.POLICE_DEFAULT, back.getAlertDetails().getPolice().getType());
        assertEquals(WazeProto.ReportingManner.REPORTING_MANNER_DEFAULT, back.getReportingManner());
    }

    @Test
    public void requestRidesOnElement2737() throws InvalidProtocolBufferException {
        WazeProto.AddUserReportedAlertRequest req = WazeProto.AddUserReportedAlertRequest.newBuilder()
                .setAzymuth(7).build();
        WazeProto.Element el = WazeProto.Element.newBuilder()
                .setAddUserReportedAlertRequest(req).build();
        byte[] bytes = el.toByteArray();
        WazeProto.Element back = WazeProto.Element.parseFrom(bytes);
        assertEquals(7, back.getAddUserReportedAlertRequest().getAzymuth());
        // Response parses from the 2738 member.
        WazeProto.Element resp = WazeProto.Element.newBuilder()
                .setAddUserReportedAlertResponse(WazeProto.AddUserReportedAlertResponse.newBuilder()
                        .setAlertUuid("abc").setReceivedPointsCount(6).build()).build();
        WazeProto.Element rback = WazeProto.Element.parseFrom(resp.toByteArray());
        assertEquals("abc", rback.getAddUserReportedAlertResponse().getAlertUuid());
        assertEquals(6, rback.getAddUserReportedAlertResponse().getReceivedPointsCount());
    }
}
```

- [ ] **Step 4: Run and verify it fails to compile** (proto not yet generated with these types).

Run: `./gradlew :app:testDebugUnitTest --tests "app.sabre.wzsabre.waze.WazeReportProtoTest"`
Expected: FAIL (compile: symbols not found) before Step 1/2, PASS after. On Windows use `gradlew.bat`.

- [ ] **Step 5: Build to regenerate proto and run the test.**

Run: `./gradlew :app:testDebugUnitTest --tests "app.sabre.wzsabre.waze.WazeReportProtoTest"`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/proto/waze.proto app/src/test/java/app/sabre/wzsabre/waze/WazeReportProtoTest.java
git commit -m "Add Waze report protobuf messages (AddUserReportedAlertRequest)"
```

---

## Task 2: AlertMapper report helpers

**Files:**
- Modify: `app/src/main/java/app/sabre/wzsabre/AlertMapper.java`
- Test: `app/src/test/java/app/sabre/wzsabre/AlertMapperTest.java` (add cases)

**Interfaces:**
- Produces:
  - `static String renderableEchoType(String hrType)` — returns a type starting with POLICE/HAZARD/ACCIDENT (for the HR echo), or null if unusable.
  - `static WazeReportSubtype wazeReportSubtype(String hrType)` — a small value object naming the AlertDetails member + enum, or null if the type cannot be reported to Waze. Defined as a public static nested class in AlertMapper.

- [ ] **Step 1: Write the failing tests** (append to `AlertMapperTest.java`):

```java
    @Test
    public void renderableEchoType_keepsRenderablePrefixes() {
        assertEquals("POLICE_VISIBLE", AlertMapper.renderableEchoType("POLICE_VISIBLE"));
        assertEquals("ACCIDENT_MAJOR", AlertMapper.renderableEchoType("ACCIDENT_MAJOR"));
        assertEquals("HAZARD_ON_ROAD_POT_HOLE", AlertMapper.renderableEchoType("HAZARD_ON_ROAD_POT_HOLE"));
    }

    @Test
    public void renderableEchoType_remapsJamsToCongestion() {
        assertEquals("HAZARD_ON_ROAD_CONGESTION", AlertMapper.renderableEchoType("JAM_HEAVY_TRAFFIC"));
        assertEquals("HAZARD_ON_ROAD_CONGESTION", AlertMapper.renderableEchoType("ROAD_CLOSED"));
    }

    @Test
    public void renderableEchoType_nullForUnusable() {
        assertNull(AlertMapper.renderableEchoType(null));
        assertNull(AlertMapper.renderableEchoType(""));
    }

    @Test
    public void wazeReportSubtype_mapsKnownTypes() {
        AlertMapper.WazeReportSubtype p = AlertMapper.wazeReportSubtype("POLICE_HIDDEN");
        assertEquals(AlertMapper.WazeReportSubtype.Kind.POLICE, p.kind);
        assertEquals(2, p.subtypeNumber); // POLICE_HIDDEN_REPORT
        assertEquals(AlertMapper.WazeReportSubtype.Kind.CRASH, AlertMapper.wazeReportSubtype("ACCIDENT_MAJOR").kind);
        assertEquals(5, AlertMapper.wazeReportSubtype("ACCIDENT_MAJOR").subtypeNumber == 3 ? 5 : AlertMapper.wazeReportSubtype("JAM_HEAVY_TRAFFIC").subtypeNumber);
        assertEquals(AlertMapper.WazeReportSubtype.Kind.HAZARD, AlertMapper.wazeReportSubtype("HAZARD_ON_ROAD_CAR_STOPPED").kind);
        assertEquals(3, AlertMapper.wazeReportSubtype("HAZARD_ON_ROAD_CAR_STOPPED").subtypeNumber);
    }

    @Test
    public void wazeReportSubtype_nullForUnreportable() {
        assertNull(AlertMapper.wazeReportSubtype("SOS_MEDICAL_HELP"));
        assertNull(AlertMapper.wazeReportSubtype(null));
    }
```

(Add `import static org.junit.Assert.assertNull;` if absent.)

- [ ] **Step 2: Run to verify failure.**

Run: `./gradlew :app:testDebugUnitTest --tests "app.sabre.wzsabre.AlertMapperTest"`
Expected: FAIL (methods/class not found).

- [ ] **Step 3: Implement** in `AlertMapper.java` (append before the closing brace):

```java
    /**
     * The render-safe SABRE type to echo a user's own HR report back as, so HR
     * draws the pin. HR's `type` is already a SABRE type; keep it if it starts with
     * POLICE/HAZARD/ACCIDENT, otherwise remap (e.g. JAM_* / ROAD_CLOSED ->
     * HAZARD_ON_ROAD_CONGESTION). Null if unusable.
     */
    public static String renderableEchoType(String hrType) {
        if (hrType == null || hrType.isEmpty()) return null;
        String u = hrType.toUpperCase(Locale.US);
        if (u.startsWith("POLICE") || u.startsWith("HAZARD") || u.startsWith("ACCIDENT")) return hrType;
        String mapped = fromWazeType(topLevel(u), u); // reuse the fetch remap
        return mapped != null ? mapped : null;
    }

    /** Best-effort top-level Waze type name from a subtype string, for reuse of fromWazeType. */
    private static String topLevel(String u) {
        if (u.startsWith("JAM")) return "JAM";
        if (u.startsWith("ROAD_CLOSED")) return "ROAD_CLOSED";
        if (u.startsWith("POLICE")) return "POLICE";
        if (u.startsWith("ACCIDENT")) return "ACCIDENT";
        return "HAZARD";
    }

    /** Which Waze AlertDetails member + subtype enum number a reported HR type maps to. */
    public static WazeReportSubtype wazeReportSubtype(String hrType) {
        if (hrType == null) return null;
        String u = hrType.toUpperCase(Locale.US);
        if (u.equals("POLICE_HIDDEN") || u.equals("POLICE_HIDING"))
            return new WazeReportSubtype(WazeReportSubtype.Kind.POLICE, 2);
        if (u.startsWith("POLICE"))
            return new WazeReportSubtype(WazeReportSubtype.Kind.POLICE, 1);
        if (u.equals("ACCIDENT_MAJOR"))
            return new WazeReportSubtype(WazeReportSubtype.Kind.CRASH, 3);
        if (u.startsWith("ACCIDENT"))
            return new WazeReportSubtype(WazeReportSubtype.Kind.CRASH, 4);
        if (u.equals("JAM_STAND_STILL_TRAFFIC"))
            return new WazeReportSubtype(WazeReportSubtype.Kind.TRAFFIC, 2);
        if (u.equals("JAM_LIGHT_TRAFFIC"))
            return new WazeReportSubtype(WazeReportSubtype.Kind.TRAFFIC, 3);
        if (u.equals("JAM_MODERATE_TRAFFIC"))
            return new WazeReportSubtype(WazeReportSubtype.Kind.TRAFFIC, 4);
        if (u.startsWith("JAM"))
            return new WazeReportSubtype(WazeReportSubtype.Kind.TRAFFIC, 5);
        if (u.startsWith("HAZARD")) {
            int sub = hazardSubtypeNumber(u);
            return new WazeReportSubtype(WazeReportSubtype.Kind.HAZARD, sub);
        }
        return null; // not reportable to Waze (SOS, weather-only, etc.)
    }

    private static int hazardSubtypeNumber(String u) {
        if (u.equals("HAZARD_ON_ROAD_CONSTRUCTION")) return 2;
        if (u.equals("HAZARD_ON_ROAD_CAR_STOPPED")) return 3;
        if (u.equals("HAZARD_ON_ROAD_OBJECT")) return 4;
        if (u.equals("HAZARD_ON_ROAD_POT_HOLE")) return 5;
        if (u.equals("HAZARD_ON_ROAD_TRAFFIC_LIGHT_FAULT")) return 6;
        if (u.equals("HAZARD_ON_ROAD_OIL")) return 7;
        if (u.equals("HAZARD_ON_SHOULDER_ANIMALS")) return 8;
        if (u.equals("HAZARD_ON_SHOULDER_MISSING_SIGN")) return 9;
        if (u.equals("HAZARD_ON_ROAD_ROAD_KILL")) return 10;
        if (u.equals("HAZARD_ON_SHOULDER_CAR_STOPPED")) return 12;
        if (u.equals("HAZARD_ON_SHOULDER")) return 11;
        return 1; // HAZARD_REPORT_DEFAULT
    }

    /** Names the Waze AlertDetails member and subtype enum number for a reported type. */
    public static final class WazeReportSubtype {
        public enum Kind { POLICE, CRASH, TRAFFIC, HAZARD }
        public final Kind kind;
        public final int subtypeNumber;
        public WazeReportSubtype(Kind kind, int subtypeNumber) {
            this.kind = kind; this.subtypeNumber = subtypeNumber;
        }
    }
```

- [ ] **Step 4: Run to verify pass.**

Run: `./gradlew :app:testDebugUnitTest --tests "app.sabre.wzsabre.AlertMapperTest"`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/app/sabre/wzsabre/AlertMapper.java app/src/test/java/app/sabre/wzsabre/AlertMapperTest.java
git commit -m "Add report type mapping helpers to AlertMapper"
```

---

## Task 3: Report payload parsers

**Files:**
- Create: `app/src/main/java/app/sabre/wzsabre/ReportRequest.java`, `.../ConfirmDiscardRequest.java`
- Test: `app/src/test/java/app/sabre/wzsabre/ReportRequestTest.java` (new)

**Interfaces:**
- Produces:
  - `ReportRequest.fromJson(String) -> ReportRequest` with fields `double lat, lon, headingDeg, altitudeM; String type; boolean isOpposite; int timeDeltaS;` throws `org.json.JSONException` on missing lat/lon/type.
  - `ConfirmDiscardRequest.fromJson(String) -> ConfirmDiscardRequest` with `double lat, lon; String alertId; boolean test;` and `long wazeAlertId()` returning the numeric Waze id parsed from `alertId` (strip `alert-`, split `/`, parse element 0), or `-1` if not parseable.

- [ ] **Step 1: Write the failing tests** `ReportRequestTest.java`:

```java
package app.sabre.wzsabre;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

public class ReportRequestTest {

    @Test
    public void parsesReportPayload() throws JSONException {
        ReportRequest r = ReportRequest.fromJson(
            "{\"lat\":37.8,\"lon\":-122.27,\"heading_deg\":90.0,\"altitude_m\":12.0," +
            "\"type\":\"POLICE_VISIBLE\",\"is_opposite\":true,\"time_delta_s\":30}");
        assertEquals(37.8, r.lat, 1e-9);
        assertEquals(-122.27, r.lon, 1e-9);
        assertEquals(90.0, r.headingDeg, 1e-9);
        assertEquals("POLICE_VISIBLE", r.type);
        assertTrue(r.isOpposite);
        assertEquals(30, r.timeDeltaS);
    }

    @Test
    public void reportDefaultsOptionalFields() throws JSONException {
        ReportRequest r = ReportRequest.fromJson("{\"lat\":1.0,\"lon\":2.0,\"type\":\"ACCIDENT_MINOR\"}");
        assertEquals(0, r.timeDeltaS);
        assertFalse(r.isOpposite);
    }

    @Test(expected = JSONException.class)
    public void reportRejectsMissingType() throws JSONException {
        ReportRequest.fromJson("{\"lat\":1.0,\"lon\":2.0}");
    }

    @Test
    public void confirmRecoversWazeId() throws JSONException {
        ConfirmDiscardRequest c = ConfirmDiscardRequest.fromJson(
            "{\"lat\":1.0,\"lon\":2.0,\"alert_id\":\"alert-123456/uuid-abc\",\"test\":false}");
        assertEquals(123456L, c.wazeAlertId());
        assertFalse(c.test);
    }

    @Test
    public void confirmMalformedIdReturnsNegative() throws JSONException {
        ConfirmDiscardRequest c = ConfirmDiscardRequest.fromJson(
            "{\"lat\":1.0,\"lon\":2.0,\"alert_id\":\"userreport-99\"}");
        assertEquals(-1L, c.wazeAlertId());
    }
}
```

- [ ] **Step 2: Run to verify failure.**

Run: `./gradlew :app:testDebugUnitTest --tests "app.sabre.wzsabre.ReportRequestTest"`
Expected: FAIL (classes not found).

- [ ] **Step 3: Implement `ReportRequest.java`:**

```java
package app.sabre.wzsabre;

import org.json.JSONException;
import org.json.JSONObject;

/** A user-created report forwarded from HR (app.sabre.wzsabre.REPORT). */
public final class ReportRequest {
    public final double lat, lon, headingDeg, altitudeM;
    public final String type;
    public final boolean isOpposite;
    public final int timeDeltaS;

    private ReportRequest(double lat, double lon, double headingDeg, double altitudeM,
                          String type, boolean isOpposite, int timeDeltaS) {
        this.lat = lat; this.lon = lon; this.headingDeg = headingDeg; this.altitudeM = altitudeM;
        this.type = type; this.isOpposite = isOpposite; this.timeDeltaS = timeDeltaS;
    }

    public static ReportRequest fromJson(String data) throws JSONException {
        JSONObject o = new JSONObject(data);
        double lat = o.has("lat") ? o.getDouble("lat") : o.getDouble("latitude");
        double lon = o.has("lon") ? o.getDouble("lon") : o.getDouble("longitude");
        String type = o.getString("type");
        return new ReportRequest(lat, lon,
                o.optDouble("heading_deg", -720.0),
                o.optDouble("altitude_m", 0.0),
                type,
                o.optBoolean("is_opposite", false),
                o.optInt("time_delta_s", 0));
    }
}
```

- [ ] **Step 4: Implement `ConfirmDiscardRequest.java`:**

```java
package app.sabre.wzsabre;

import org.json.JSONException;
import org.json.JSONObject;

/** A confirm (thumbs-up) or discard (not-there) forwarded from HR. */
public final class ConfirmDiscardRequest {
    public final double lat, lon;
    public final String alertId;
    public final boolean test;

    private ConfirmDiscardRequest(double lat, double lon, String alertId, boolean test) {
        this.lat = lat; this.lon = lon; this.alertId = alertId; this.test = test;
    }

    public static ConfirmDiscardRequest fromJson(String data) throws JSONException {
        JSONObject o = new JSONObject(data);
        double lat = o.has("lat") ? o.getDouble("lat") : o.getDouble("latitude");
        double lon = o.has("lon") ? o.getDouble("lon") : o.getDouble("longitude");
        return new ConfirmDiscardRequest(lat, lon, o.optString("alert_id", null),
                o.optBoolean("test", false));
    }

    /** Numeric Waze alert id embedded in alertId ("alert-<id>/<uuid>"), or -1. */
    public long wazeAlertId() {
        if (alertId == null) return -1L;
        String s = alertId.startsWith("alert-") ? alertId.substring("alert-".length()) : alertId;
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return -1L; }
    }
}
```

- [ ] **Step 5: Run to verify pass.**

Run: `./gradlew :app:testDebugUnitTest --tests "app.sabre.wzsabre.ReportRequestTest"`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/app/sabre/wzsabre/ReportRequest.java app/src/main/java/app/sabre/wzsabre/ConfirmDiscardRequest.java app/src/test/java/app/sabre/wzsabre/ReportRequestTest.java
git commit -m "Parse HR REPORT/CONFIRM/DISCARD payloads"
```

---

## Task 4: UserReportStore (HR map echo)

**Files:**
- Create: `app/src/main/java/app/sabre/wzsabre/UserReportStore.java`
- Test: `app/src/test/java/app/sabre/wzsabre/UserReportStoreTest.java` (new)

**Interfaces:**
- Consumes: `SabreAlert`, `AlertMapper.renderableEchoType`, `ReportRequest`.
- Produces:
  - `void add(ReportRequest r, long nowMs)`
  - `List<SabreAlert> activeAlerts(double lat, double lon, double radiusM, long nowMs)`
  - `void removeNear(double lat, double lon, long nowMs)` (for DISCARD)
  - TTL constant `TTL_MS = 30 * 60 * 1000L`. Store keyed by generated id; radius filter uses simple equirectangular distance. Thread-safe (synchronized).

- [ ] **Step 1: Write the failing tests** `UserReportStoreTest.java`:

```java
package app.sabre.wzsabre;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

import java.util.List;

public class UserReportStoreTest {

    private static ReportRequest police(double lat, double lon) throws JSONException {
        return ReportRequest.fromJson("{\"lat\":" + lat + ",\"lon\":" + lon +
                ",\"type\":\"POLICE_VISIBLE\"}");
    }

    @Test
    public void addedReportAppearsInRadiusWithRenderableType() throws JSONException {
        UserReportStore s = new UserReportStore();
        s.add(police(37.8, -122.27), 1000L);
        List<SabreAlert> out = s.activeAlerts(37.8, -122.27, 5000, 2000L);
        assertEquals(1, out.size());
        assertEquals("POLICE_VISIBLE", out.get(0).type);
        assertEquals("waze", out.get(0).alertSource);
        assertTrue(out.get(0).alertId.contains("userreport"));
    }

    @Test
    public void expiredReportsAreDropped() throws JSONException {
        UserReportStore s = new UserReportStore();
        s.add(police(37.8, -122.27), 1000L);
        long later = 1000L + UserReportStore.TTL_MS + 1;
        assertTrue(s.activeAlerts(37.8, -122.27, 5000, later).isEmpty());
    }

    @Test
    public void outOfRadiusReportsAreFiltered() throws JSONException {
        UserReportStore s = new UserReportStore();
        s.add(police(37.8, -122.27), 1000L);
        // ~100km away
        assertTrue(s.activeAlerts(38.7, -122.27, 5000, 2000L).isEmpty());
    }

    @Test
    public void removeNearDropsMatchingReport() throws JSONException {
        UserReportStore s = new UserReportStore();
        s.add(police(37.8, -122.27), 1000L);
        s.removeNear(37.8001, -122.2701, 1500L);
        assertTrue(s.activeAlerts(37.8, -122.27, 5000, 2000L).isEmpty());
    }
}
```

- [ ] **Step 2: Run to verify failure.**

Run: `./gradlew :app:testDebugUnitTest --tests "app.sabre.wzsabre.UserReportStoreTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `UserReportStore.java`:**

```java
package app.sabre.wzsabre;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Holds the user's own HR reports for a while so every HR fetch echoes them back
 * and HR draws the pin, independent of whether Waze accepts the report. In-memory,
 * thread-safe, entries expire after {@link #TTL_MS}.
 */
public final class UserReportStore {
    public static final long TTL_MS = 30 * 60 * 1000L;
    private static final double MATCH_RADIUS_M = 120.0;

    private static final class Entry {
        final SabreAlert alert;
        final long addedMs;
        Entry(SabreAlert a, long t) { alert = a; addedMs = t; }
    }

    private final List<Entry> entries = new ArrayList<>();

    public synchronized void add(ReportRequest r, long nowMs) {
        String type = AlertMapper.renderableEchoType(r.type);
        if (type == null) return; // nothing HR would draw
        String id = "alert-0/userreport-" + nowMs;
        long reportTs = (nowMs / 1000L) - r.timeDeltaS;
        SabreAlert a = new SabreAlert(id, SabreResponseBuilder.SOURCE_WAZE, type,
                r.lat, r.lon, r.headingDeg, null, reportTs, null, 0);
        entries.add(new Entry(a, nowMs));
    }

    public synchronized List<SabreAlert> activeAlerts(double lat, double lon,
                                                      double radiusM, long nowMs) {
        purge(nowMs);
        List<SabreAlert> out = new ArrayList<>();
        for (Entry e : entries) {
            if (distanceM(lat, lon, e.alert.lat, e.alert.lon) <= radiusM) out.add(e.alert);
        }
        return out;
    }

    public synchronized void removeNear(double lat, double lon, long nowMs) {
        purge(nowMs);
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (distanceM(lat, lon, e.alert.lat, e.alert.lon) <= MATCH_RADIUS_M) it.remove();
        }
    }

    private void purge(long nowMs) {
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            if (nowMs - it.next().addedMs > TTL_MS) it.remove();
        }
    }

    private static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double mPerDegLat = 110574.0;
        double mPerDegLon = 111320.0 * Math.cos(Math.toRadians((lat1 + lat2) / 2.0));
        double dy = (lat1 - lat2) * mPerDegLat;
        double dx = (lon1 - lon2) * mPerDegLon;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
```

- [ ] **Step 4: Run to verify pass.**

Run: `./gradlew :app:testDebugUnitTest --tests "app.sabre.wzsabre.UserReportStoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/app/sabre/wzsabre/UserReportStore.java app/src/test/java/app/sabre/wzsabre/UserReportStoreTest.java
git commit -m "Add UserReportStore for the HR map echo"
```

---

## Task 5: Receiver action classification + dispatch

**Files:**
- Modify: `app/src/main/java/app/sabre/wzsabre/MainBroadcastReceiver.java`
- Test: `app/src/test/java/app/sabre/wzsabre/MainBroadcastReceiverTest.java` (new)

**Interfaces:**
- Produces: `static String classifyReportAction(String action)` returning `"REPORT"`, `"CONFIRM"`, `"DISCARD"`, or `null`. Pure (no Android). Dispatch in `onReceive` forwards via `ForegroundServiceStarter.start(context, classified, intent.getStringExtra("data"))`.

- [ ] **Step 1: Write the failing test** `MainBroadcastReceiverTest.java`:

```java
package app.sabre.wzsabre;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class MainBroadcastReceiverTest {
    @Test public void classifiesOfficialActions() {
        assertEquals("REPORT",  MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.REPORT"));
        assertEquals("CONFIRM", MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.CONFIRM"));
        assertEquals("DISCARD", MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.DISCARD"));
    }
    @Test public void classifiesLegacyNames() {
        assertEquals("REPORT",  MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.SUBMIT_REPORT"));
        assertEquals("CONFIRM", MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.CONFIRM_REPORT"));
        assertEquals("DISCARD", MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.DISCARD_REPORT"));
    }
    @Test public void ignoresFetchAndShutdownAndNull() {
        assertNull(MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.REQUEST"));
        assertNull(MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.FETCH_REQUEST"));
        assertNull(MainBroadcastReceiver.classifyReportAction("app.sabre.wzsabre.SHUTDOWN"));
        assertNull(MainBroadcastReceiver.classifyReportAction(null));
    }
}
```

- [ ] **Step 2: Run to verify failure.**

Run: `./gradlew :app:testDebugUnitTest --tests "app.sabre.wzsabre.MainBroadcastReceiverTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `classifyReportAction`** in `MainBroadcastReceiver.java` (add method; note order — CONFIRM/DISCARD before REPORT so `CONFIRM_REPORT`/`DISCARD_REPORT` do not match the REPORT branch):

```java
    /**
     * Classify an HR report-channel action to a normalized label, or null if the
     * action is not a report/confirm/discard. CONFIRM/DISCARD are checked before
     * REPORT so the legacy CONFIRM_REPORT/DISCARD_REPORT names do not fall into the
     * REPORT branch (all three end in "REPORT").
     */
    static String classifyReportAction(String action) {
        if (action == null) return null;
        if (action.contains("CONFIRM")) return "CONFIRM";
        if (action.contains("DISCARD")) return "DISCARD";
        if (action.endsWith("REPORT"))  return "REPORT";
        return null;
    }
```

- [ ] **Step 4: Wire dispatch into `onReceive`.** Insert a branch AFTER the `endsWith("REQUEST")` branch and BEFORE the `contains("SHUTDOWN")` branch (a report action never ends with REQUEST, and SHUTDOWN never matches classifyReportAction, so ordering is safe; placing it here keeps fetch fast-pathed):

```java
            } else if (classifyReportAction(action) != null) {
                String kind = classifyReportAction(action);
                Log.d(TAG, "Report-channel action: " + action + " -> " + kind);
                ForegroundServiceStarter.start(context, kind, intent.getStringExtra("data"));
```

- [ ] **Step 5: Run tests + build.**

Run: `./gradlew :app:testDebugUnitTest --tests "app.sabre.wzsabre.MainBroadcastReceiverTest"`
Expected: PASS. Then `./gradlew :app:assembleDebug` to confirm it compiles.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/app/sabre/wzsabre/MainBroadcastReceiver.java app/src/test/java/app/sabre/wzsabre/MainBroadcastReceiverTest.java
git commit -m "Dispatch HR REPORT/CONFIRM/DISCARD to the service"
```

Note: `ForegroundServiceStarter.start(context, kind, data)` puts `kind` as the `action` extra and `data` as the `data` extra; `SabreService.onStartCommand` handles them in Task 9. Until Task 9, these forwards are harmless no-ops (the service just re-arms its idle timer).

---

## Task 6: WazeReportCodec (proto assembly, testable)

**Files:**
- Create: `app/src/main/java/app/sabre/wzsabre/waze/WazeReportCodec.java`
- Test: `app/src/test/java/app/sabre/wzsabre/waze/WazeReportCodecTest.java` (new)

**Interfaces:**
- Consumes: `ReportRequest`, `AlertMapper.wazeReportSubtype`, `WazeProto`.
- Produces:
  - `static WazeProto.AddUserReportedAlertRequest buildRequest(ReportRequest r, long nowMs, long fromNode, long toNode)` — builds the full request; if `fromNode`/`toNode` are both 0, omits `SegmentNodes` (position-only). Returns null if the type is not reportable (`wazeReportSubtype` null).
  - `static String reportUuidFrom(WazeProto.Batch batch)` — response `alert_uuid` or null.
  - `static int reportPointsFrom(WazeProto.Batch batch)` — response `received_points_count` or -1.

This is `WazeProto`-only (no `android.util.Base64`), so it is fully JVM-testable. Base64 framing stays in `WazeRtCodec.protoBase64Line` and is exercised on the emulator.

- [ ] **Step 1: Write the failing test** `WazeReportCodecTest.java`:

```java
package app.sabre.wzsabre.waze;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.sabre.wzsabre.ReportRequest;
import org.json.JSONException;
import org.junit.Test;

public class WazeReportCodecTest {

    private static ReportRequest req(String type) throws JSONException {
        return ReportRequest.fromJson("{\"lat\":37.8,\"lon\":-122.27,\"heading_deg\":90.0," +
                "\"type\":\"" + type + "\",\"time_delta_s\":10}");
    }

    @Test public void buildsPoliceRequestPositionOnly() throws JSONException {
        WazeProto.AddUserReportedAlertRequest r =
                WazeReportCodec.buildRequest(req("POLICE_VISIBLE"), 1_700_000_000_000L, 0, 0);
        assertEquals(WazeProto.PoliceAlertSubType.POLICE_DEFAULT, r.getAlertDetails().getPolice().getType());
        assertEquals(90, r.getAzymuth());
        assertEquals(1_700_000_000L - 10, r.getReportTime().getSeconds());
        assertEquals(WazeProto.ReportingManner.REPORTING_MANNER_DEFAULT, r.getReportingManner());
        assertEquals(-122271200, r.getUserPosition().getGpsPosition().getCoordinate().getLonTimes1000000());
        assertTrue(!r.getUserPosition().hasSegmentNodes()); // position-only
    }

    @Test public void buildsWithSegmentNodesWhenProvided() throws JSONException {
        WazeProto.AddUserReportedAlertRequest r =
                WazeReportCodec.buildRequest(req("HAZARD_ON_ROAD_POT_HOLE"), 1_700_000_000_000L, 111, 222);
        assertEquals(111, r.getUserPosition().getSegmentNodes().getFromNode());
        assertEquals(222, r.getUserPosition().getSegmentNodes().getToNode());
        assertEquals(WazeProto.HazardReportSubType.HAZARD_REPORT_POTHOLE,
                r.getAlertDetails().getHazardOnRoad().getType());
    }

    @Test public void oppositeDirectionSetsBackward() throws JSONException {
        ReportRequest opp = ReportRequest.fromJson("{\"lat\":37.8,\"lon\":-122.27," +
                "\"type\":\"ACCIDENT_MAJOR\",\"is_opposite\":true}");
        WazeProto.AddUserReportedAlertRequest r = WazeReportCodec.buildRequest(opp, 1L, 0, 0);
        assertEquals(WazeProto.SegmentDirection.SEGMENT_DIRECTION_BACKWARD, r.getSegmentDirection());
        assertEquals(WazeProto.CrashReportSubType.CRASH_REPORT_MAJOR, r.getAlertDetails().getCrash().getType());
    }

    @Test public void nullForUnreportableType() throws JSONException {
        assertNull(WazeReportCodec.buildRequest(req("SOS_MEDICAL_HELP"), 1L, 0, 0));
    }

    @Test public void parsesResponseUuidAndPoints() {
        WazeProto.Batch batch = WazeProto.Batch.newBuilder()
                .addElement(WazeProto.Element.newBuilder()
                        .setAddUserReportedAlertResponse(WazeProto.AddUserReportedAlertResponse.newBuilder()
                                .setAlertUuid("uuid-xyz").setReceivedPointsCount(6).build()).build())
                .build();
        assertEquals("uuid-xyz", WazeReportCodec.reportUuidFrom(batch));
        assertEquals(6, WazeReportCodec.reportPointsFrom(batch));
    }
}
```

- [ ] **Step 2: Run to verify failure.**

Run: `./gradlew :app:testDebugUnitTest --tests "app.sabre.wzsabre.waze.WazeReportCodecTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `WazeReportCodec.java`:**

```java
package app.sabre.wzsabre.waze;

import app.sabre.wzsabre.AlertMapper;
import app.sabre.wzsabre.ReportRequest;

/**
 * Assembles the Waze AddUserReportedAlertRequest protobuf from a user's HR report,
 * and reads the response uuid/points. Pure WazeProto (no android.util.Base64), so
 * it is JVM-testable; the Base64 line framing lives in WazeRtCodec.
 */
final class WazeReportCodec {
    private WazeReportCodec() {}

    static WazeProto.AddUserReportedAlertRequest buildRequest(
            ReportRequest r, long nowMs, long fromNode, long toNode) {
        AlertMapper.WazeReportSubtype sub = AlertMapper.wazeReportSubtype(r.type);
        if (sub == null) return null;

        WazeProto.CoordinateWithAlt coord = WazeProto.CoordinateWithAlt.newBuilder()
                .setLonTimes1000000((int) Math.round(r.lon * 1_000_000.0))
                .setLatTimes1000000((int) Math.round(r.lat * 1_000_000.0))
                .setAltTimes1000000((int) Math.round(r.altitudeM * 1_000_000.0))
                .build();
        WazeProto.GpsPosition gps = WazeProto.GpsPosition.newBuilder()
                .setCoordinate(coord)
                .setHorizontalAccuracyMeters(10.0)
                .setTimeEpochMs(nowMs)
                .build();
        WazeProto.UserPosition.Builder pos = WazeProto.UserPosition.newBuilder().setGpsPosition(gps);
        if (fromNode != 0 || toNode != 0) {
            pos.setSegmentNodes(WazeProto.SegmentNodes.newBuilder()
                    .setFromNode(fromNode).setToNode(toNode).build());
        }

        WazeProto.AddUserReportedAlertRequest.Builder b =
                WazeProto.AddUserReportedAlertRequest.newBuilder()
                .setUserPosition(pos.build())
                .setAzymuth((int) Math.round(r.headingDeg))
                .setAlertDetails(buildDetails(sub))
                .setSegmentDirection(r.isOpposite
                        ? WazeProto.SegmentDirection.SEGMENT_DIRECTION_BACKWARD
                        : WazeProto.SegmentDirection.SEGMENT_DIRECTION_FORWARD)
                .setReportTime(WazeProto.Timestamp.newBuilder()
                        .setSeconds((nowMs / 1000L) - r.timeDeltaS).build())
                .setReportingManner(WazeProto.ReportingManner.REPORTING_MANNER_DEFAULT);
        return b.build();
    }

    private static WazeProto.AlertDetails buildDetails(AlertMapper.WazeReportSubtype sub) {
        WazeProto.AlertDetails.Builder d = WazeProto.AlertDetails.newBuilder();
        switch (sub.kind) {
            case POLICE:
                d.setPolice(WazeProto.PoliceDetails.newBuilder()
                        .setType(WazeProto.PoliceAlertSubType.forNumber(sub.subtypeNumber)));
                break;
            case CRASH:
                d.setCrash(WazeProto.CrashDetails.newBuilder()
                        .setType(WazeProto.CrashReportSubType.forNumber(sub.subtypeNumber)));
                break;
            case TRAFFIC:
                d.setTraffic(WazeProto.TrafficDetails.newBuilder()
                        .setType(WazeProto.TrafficReportSubType.forNumber(sub.subtypeNumber)));
                break;
            case HAZARD:
                d.setHazardOnRoad(WazeProto.HazardOnRoadDetails.newBuilder()
                        .setType(WazeProto.HazardReportSubType.forNumber(sub.subtypeNumber)));
                break;
        }
        return d.build();
    }

    static String reportUuidFrom(WazeProto.Batch batch) {
        for (WazeProto.Element el : batch.getElementList()) {
            if (el.hasAddUserReportedAlertResponse()) {
                String u = el.getAddUserReportedAlertResponse().getAlertUuid();
                if (u != null && !u.isEmpty()) return u;
            }
        }
        return null;
    }

    static int reportPointsFrom(WazeProto.Batch batch) {
        for (WazeProto.Element el : batch.getElementList()) {
            if (el.hasAddUserReportedAlertResponse())
                return el.getAddUserReportedAlertResponse().getReceivedPointsCount();
        }
        return -1;
    }
}
```

- [ ] **Step 4: Run to verify pass.**

Run: `./gradlew :app:testDebugUnitTest --tests "app.sabre.wzsabre.waze.WazeReportCodecTest"`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/app/sabre/wzsabre/waze/WazeReportCodec.java app/src/test/java/app/sabre/wzsabre/waze/WazeReportCodecTest.java
git commit -m "Assemble Waze report protobuf from an HR report"
```

---

## Task 7: WazeSession report commands

**Files:**
- Modify: `app/src/main/java/app/sabre/wzsabre/waze/WazeSession.java`
- Modify: `app/src/main/java/app/sabre/wzsabre/waze/WazeRtCodec.java` (add `reportPayload` framing helper)

**Interfaces:**
- Consumes: `WazeReportCodec.buildRequest`, existing `command()`, `prepareForArea()`.
- Produces (package-private on `WazeSession`):
  - `ReportResult submitReport(ReportRequest r, long nowMs)` — prepares the area, sends the report, returns `ReportResult{boolean accepted; String uuid; int points; String error}`. Position-only for now (fromNode/toNode = 0); tile-snap deferred to Task 11.
  - `void confirmAlert(long id)` — `command("ThumbsUp," + id)`.
  - `void discardAlert(long id)` — `command("ReportRmAlert," + id)`.
  - `ReportResult` is a small package-private static class in the `waze` package (new file `ReportResult.java`).

This task's methods do network I/O, so they are validated on the emulator (Task 11), not by JVM unit tests. The unit-testable pieces (request assembly, response parse) are already covered in Task 6.

- [ ] **Step 1: Add the Base64 framing helper** to `WazeRtCodec.java` (below `protoBase64Line`, keeping Base64 here):

```java
    /** One report line: ProtoBase64-framed AddUserReportedAlertRequest on an Element. */
    static String reportPayload(WazeProto.AddUserReportedAlertRequest req) {
        return protoBase64Line(WazeProto.Element.newBuilder()
                .setAddUserReportedAlertRequest(req).build());
    }
```

- [ ] **Step 2: Create `ReportResult.java`:**

```java
package app.sabre.wzsabre.waze;

/** Outcome of a Waze report submission. */
final class ReportResult {
    final boolean accepted;
    final String uuid;
    final int points;
    final String error;
    private ReportResult(boolean a, String u, int p, String e) {
        accepted = a; uuid = u; points = p; error = e;
    }
    static ReportResult ok(String uuid, int points) { return new ReportResult(true, uuid, points, null); }
    static ReportResult fail(String error) { return new ReportResult(false, null, -1, error); }
}
```

- [ ] **Step 3: Add the methods to `WazeSession.java`** (after `queryBox`):

```java
    /**
     * Submit a user report to Waze. Prepares the area (handshake), sends the
     * AddUserReportedAlertRequest, and reads the response uuid/points. Position-only
     * (no road-snap SegmentNodes) unless a future tile-snap step supplies nodes.
     */
    ReportResult submitReport(app.sabre.wzsabre.ReportRequest r, long nowMs) throws Exception {
        WazeProto.AddUserReportedAlertRequest req =
                WazeReportCodec.buildRequest(r, nowMs, 0L, 0L);
        if (req == null) return ReportResult.fail("unreportable type: " + r.type);
        prepareForArea(r.lat, r.lon);
        WazeProto.Batch batch = command(WazeRtCodec.reportPayload(req));
        String uuid = WazeReportCodec.reportUuidFrom(batch);
        int points = WazeReportCodec.reportPointsFrom(batch);
        if (uuid != null) {
            Log.d(TAG, "Report accepted: uuid=" + uuid + " pts=" + points);
            return ReportResult.ok(uuid, points);
        }
        Log.w(TAG, "No report response in batch");
        return ReportResult.fail("no report response");
    }

    /** Thumbs-up an existing Waze alert by its numeric id. */
    void confirmAlert(long id) throws Exception {
        prepareForArealess();
        command("ThumbsUp," + id);
        Log.d(TAG, "Confirmed alert " + id);
    }

    /** Remove/deny an existing Waze alert by its numeric id ("not there"). */
    void discardAlert(long id) throws Exception {
        prepareForArealess();
        command("ReportRmAlert," + id);
        Log.d(TAG, "Discarded alert " + id);
    }

    /** Ensure logged in for a bare command that needs no MapDisplayed handshake. */
    private void prepareForArealess() throws Exception {
        // confirm/discard carry lat/lon in the HR payload; callers that have them
        // should use prepareForArea. When absent, ensure the session is at least
        // registered+logged-in using the last known position is not available here,
        // so require the caller to have prepared. If no session, this throws, and the
        // caller (WazeReporter) will have called prepareForArea first.
        if (session == null) throw new WazeExceptions.SessionExpiredException("confirm/discard before login");
    }
```

Note: `WazeReporter` (Task 8) calls `prepareForArea(lat, lon)` before `confirmAlert`/`discardAlert` using the lat/lon from the CONFIRM/DISCARD payload, so `prepareForArealess` only guards against a missing session.

- [ ] **Step 4: Build to confirm compilation.**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/app/sabre/wzsabre/waze/WazeSession.java app/src/main/java/app/sabre/wzsabre/waze/WazeRtCodec.java app/src/main/java/app/sabre/wzsabre/waze/ReportResult.java
git commit -m "Add Waze submitReport/confirmAlert/discardAlert to WazeSession"
```

---

## Task 8: WazeReporter (session management + selfTest)

**Files:**
- Create: `app/src/main/java/app/sabre/wzsabre/waze/WazeReporter.java`

**Interfaces:**
- Consumes: `WazeSession`, `WazeProtocolSource`'s persistence pattern (SharedPreferences "waze_rt"), `ReportRequest`, `ConfirmDiscardRequest`.
- Produces (public API used by `SabreService`):
  - `WazeReporter(Context ctx)`
  - `boolean submit(ReportRequest r)` — runs on the caller's worker thread; returns accepted.
  - `void confirm(double lat, double lon, long wazeId)`
  - `void discard(double lat, double lon, long wazeId)`
  - `static void selfTest(Context ctx, double lat, double lon, String type)` — DEBUG helper.

Reuse the read-path account: read the same persisted `community`/`secret`/device from SharedPreferences that `WazeProtocolSource` writes, so reporting shares the anonymous account instead of registering another (respects Waze's per-day cap). If none persisted, register a fresh session (and persist it under the same keys).

- [ ] **Step 1: Read `WazeProtocolSource.ensureSession` and its PREFS constants** (`app/src/main/java/app/sabre/wzsabre/waze/WazeProtocolSource.java` around lines 320-360) to copy the exact SharedPreferences keys (`PREFS`, `community`, `secret`, `dev_mfr`, `dev_model`, ...). Match them so the account is shared.

- [ ] **Step 2: Implement `WazeReporter.java`** using the same region() selection and persistence. Core shape:

```java
package app.sabre.wzsabre.waze;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import app.sabre.wzsabre.ConfirmDiscardRequest;
import app.sabre.wzsabre.ReportRequest;

/** Owns a Waze RT session for the WRITE path (report/confirm/discard). */
public final class WazeReporter {
    private static final String TAG = "WazeRT";
    private static final String PREFS = "waze_rt"; // must match WazeProtocolSource

    private final Context ctx;
    private WazeSession session;

    public WazeReporter(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public synchronized boolean submit(ReportRequest r) {
        try {
            WazeSession s = ensureSession(r.lat, r.lon);
            ReportResult res = s.submitReport(r, System.currentTimeMillis());
            if (!res.accepted) Log.w(TAG, "Report not accepted: " + res.error);
            return res.accepted;
        } catch (Exception e) {
            Log.w(TAG, "submit failed: " + e.getMessage());
            invalidateOnAuthError(e);
            return false;
        }
    }

    public synchronized void confirm(double lat, double lon, long wazeId) {
        if (wazeId < 0) return;
        try { WazeSession s = ensureSession(lat, lon); s.prepareForArea(lat, lon); s.confirmAlert(wazeId); }
        catch (Exception e) { Log.w(TAG, "confirm failed: " + e.getMessage()); invalidateOnAuthError(e); }
    }

    public synchronized void discard(double lat, double lon, long wazeId) {
        if (wazeId < 0) return;
        try { WazeSession s = ensureSession(lat, lon); s.prepareForArea(lat, lon); s.discardAlert(wazeId); }
        catch (Exception e) { Log.w(TAG, "discard failed: " + e.getMessage()); invalidateOnAuthError(e); }
    }

    private WazeSession ensureSession(double lat, double lon) throws Exception {
        if (session != null) return session;
        String region = WazeProtocolSource.region(lat, lon);
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String community = p.getString("community", null);
        String secret = p.getString("secret", null);
        WazeCredentials creds = (community != null && secret != null)
                ? new WazeCredentials(community, secret) : null;
        DeviceIdentity dev = new DeviceIdentity(
                p.getString("dev_mfr", "Google"), p.getString("dev_model", "Pixel 8"),
                p.getString("dev_os", "Android 15"),
                p.getInt("dev_w", 1080), p.getInt("dev_h", 2400),
                p.getString("dev_iid", java.util.UUID.randomUUID().toString()));
        WazeSession s = new WazeSession(region, dev, creds);
        s.prepareForArea(lat, lon); // registers if needed + logs in + handshake
        // Persist any newly minted account under the SAME keys as the read path.
        WazeCredentials got = s.getCredentials();
        if (got != null && (community == null || secret == null)) {
            p.edit().putString("community", got.community).putString("secret", got.secret)
             .putString("dev_mfr", dev.manufacturer).putString("dev_model", dev.model)
             .putString("dev_os", dev.osVersion).putInt("dev_w", dev.screenW).putInt("dev_h", dev.screenH)
             .putString("dev_iid", dev.installationId).apply();
        }
        session = s;
        return s;
    }

    private void invalidateOnAuthError(Exception e) {
        if (e instanceof WazeExceptions.AccountRejectedException) {
            session = null;
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
               .remove("community").remove("secret").apply();
        } else if (e instanceof WazeExceptions.SessionExpiredException && session != null) {
            session.invalidateSession();
        }
    }

    /** DEBUG-only: exercise the write path end to end. Logcat tag WazeRT. */
    public static void selfTest(Context ctx, double lat, double lon, String type) {
        try {
            ReportRequest r = ReportRequest.fromJson(
                "{\"lat\":" + lat + ",\"lon\":" + lon + ",\"type\":\"" + type + "\"}");
            boolean ok = new WazeReporter(ctx).submit(r);
            Log.d(TAG, "selfTest report accepted=" + ok);
        } catch (Exception e) { Log.w(TAG, "selfTest failed: " + e.getMessage()); }
    }
}
```

- [ ] **Step 3: Adjust visibility as needed.** `WazeProtocolSource.region(...)` is currently `static` package-private (confirmed in source) so `WazeReporter` in the same package can call it. If `WazeCredentials`/`DeviceIdentity` constructors differ from the shape above, match the actual constructors (read `WazeCredentials.java`, `DeviceIdentity.java` before writing). This step is "read the two files, align the constructor calls."

- [ ] **Step 4: Build.**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/app/sabre/wzsabre/waze/WazeReporter.java
git commit -m "Add WazeReporter owning the write-path session"
```

---

## Task 9: SabreService dispatch + fetch merge

**Files:**
- Modify: `app/src/main/java/app/sabre/wzsabre/SabreService.java`

**Interfaces:**
- Consumes: `UserReportStore`, `WazeReporter`, `ReportRequest`, `ConfirmDiscardRequest`.
- Produces: service handles `action` values `REPORT`/`CONFIRM`/`DISCARD`; `handleFetchRequest` merges `userReportStore.activeAlerts(...)` into the response.

- [ ] **Step 1: Add fields + init.** In `SabreService`, add:

```java
    private final UserReportStore userReportStore = new UserReportStore();
    private WazeReporter wazeReporter;
```

and in `onCreate` (where sources are constructed): `wazeReporter = new WazeReporter(this);`

- [ ] **Step 2: Dispatch in `onStartCommand`.** After the existing `FETCH_REQUEST` handling, add (each on the request executor so it never blocks the main thread):

```java
        if (action != null && action.equals("REPORT")) {
            final String data = intent.getStringExtra("data");
            requestExecutor.submit(() -> handleReport(data));
        } else if (action != null && action.equals("CONFIRM")) {
            final String data = intent.getStringExtra("data");
            requestExecutor.submit(() -> handleConfirmDiscard(data, true));
        } else if (action != null && action.equals("DISCARD")) {
            final String data = intent.getStringExtra("data");
            requestExecutor.submit(() -> handleConfirmDiscard(data, false));
        }
```

- [ ] **Step 3: Add the handlers:**

```java
    private void handleReport(String data) {
        try {
            ReportRequest r = ReportRequest.fromJson(data);
            // 1) Echo to the HR map immediately (guaranteed pin, independent of Waze).
            userReportStore.add(r, System.currentTimeMillis());
            // 2) Push to Waze (best-effort).
            boolean ok = wazeReporter.submit(r);
            Log.d(TAG, "Report handled: type=" + r.type + " wazeAccepted=" + ok);
        } catch (Exception e) {
            Log.w(TAG, "Bad REPORT payload: " + e.getMessage());
        }
    }

    private void handleConfirmDiscard(String data, boolean confirm) {
        try {
            ConfirmDiscardRequest c = ConfirmDiscardRequest.fromJson(data);
            if (c.test) { Log.d(TAG, "test " + (confirm ? "confirm" : "discard") + ", not sending"); return; }
            long id = c.wazeAlertId();
            if (confirm) {
                wazeReporter.confirm(c.lat, c.lon, id);
            } else {
                wazeReporter.discard(c.lat, c.lon, id);
                userReportStore.removeNear(c.lat, c.lon, System.currentTimeMillis()); // drop own echo if any
            }
        } catch (Exception e) {
            Log.w(TAG, "Bad CONFIRM/DISCARD payload: " + e.getMessage());
        }
    }
```

- [ ] **Step 4: Merge the echo into the fetch response.** In `handleFetchRequest`, after all sources are collected into `allAlerts` and before dedupe/send, add:

```java
                allAlerts.addAll(userReportStore.activeAlerts(lat, lon, radius, System.currentTimeMillis()));
```

(Place it alongside the other `allAlerts.addAll(...)` calls, before the dedupe step near `SabreService.java:348`.)

- [ ] **Step 5: Build + run the full unit suite** (nothing here is unit-tested directly, but confirm nothing else broke).

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all existing + new tests).

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/app/sabre/wzsabre/SabreService.java
git commit -m "Handle REPORT/CONFIRM/DISCARD and echo reports to the HR map"
```

---

## Task 10: Debug broadcast harness

**Files:**
- Modify: `app/src/main/java/app/sabre/wzsabre/MainBroadcastReceiver.java` (add DEBUG-only `.REPORT_TEST`)

**Interfaces:**
- Produces: a DEBUG-only action `app.sabre.wzsabre.REPORT_TEST` that calls `WazeReporter.selfTest(context, lat, lon, type)` so the write path can be exercised via adb without HR.

- [ ] **Step 1: Add the branch** in `onReceive` (alongside the existing DEBUG `.WAZE_TEST`):

```java
            } else if (BuildConfig.DEBUG && action != null && action.endsWith(".REPORT_TEST")) {
                final double lat = numberExtra(intent, "lat", 37.8044);
                final double lon = numberExtra(intent, "lon", -122.2712);
                final String type = intent.hasExtra("type") ? intent.getStringExtra("type") : "POLICE_VISIBLE";
                new Thread(() -> app.sabre.wzsabre.waze.WazeReporter.selfTest(context, lat, lon, type)).start();
```

- [ ] **Step 2: Build the debug APK.**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/app/sabre/wzsabre/MainBroadcastReceiver.java
git commit -m "Add DEBUG REPORT_TEST broadcast for the Waze write path"
```

---

## Task 11: Spike + emulator end-to-end validation (the risk-killer)

This task decides the position-only vs tile-snap question and validates both guarantees on the A15 emulator against LIVE Waze and LIVE Highway Radar. No code is written until the spike result is known.

- [ ] **Step 1: Boot the A15 emulator and install the debug APK** (per [[emulator_test_harness]]).

Run: `./gradlew :app:installDebug`

- [ ] **Step 2: Set the emulator GPS** to a real freeway location with live Waze activity (use `adb emu geo fix <lon> <lat>` or the extended controls).

- [ ] **Step 3: Fire the position-only report spike:**

```bash
adb shell am broadcast -a app.sabre.wzsabre.REPORT_TEST \
  -n app.sabre.wzsabre/.MainBroadcastReceiver \
  --ef lat 37.8044 --ef lon -122.2712 --es type POLICE_VISIBLE
```

Then `adb logcat -s WazeRT` and look for either `Report accepted: uuid=... pts=...` (SUCCESS: position-only works, skip the tile-snap entirely) or a rejection / `No report response`.

- [ ] **Step 4: Branch on the spike result.**
  - **If accepted:** position-only is sufficient. No tile-snap needed. Proceed to Step 5.
  - **If rejected/misplaced:** implement the tile-snap: port `fetchTileSegments` (Waze tile server GET) + a minimal `WazeTileParser` to recover `fromNode`/`toNode`, add an `At,...`/`MapDisplayed` step, and pass real nodes into `WazeReportCodec.buildRequest`. Reference: decompiled `WazeSession.smali` (`submitReport` ~5478, `fetchTileSegments` ~4598) and `WazeTileParser.smali`. This is a sub-plan of its own; write it as `docs/superpowers/plans/2026-08-12-waze-tile-snap.md` if reached, keeping this release's REPORT->Waze gated on it. (The HR echo + confirm/discard can still ship without it.)

- [ ] **Step 5: Verify the report landed on live Waze.** Open the Waze app / live-map at the reported coordinate and confirm the pin appears (police icon). Record the observed uuid/points from logcat.

- [ ] **Step 6: Verify the HR map echo (goal 1).** With HR installed and the plugin discovered, fire a real HR-shaped REPORT to our receiver:

```bash
adb shell am broadcast -a app.sabre.wzsabre.REPORT \
  -n app.sabre.wzsabre/.MainBroadcastReceiver \
  --es data '{"lat":37.8044,"lon":-122.2712,"heading_deg":90.0,"type":"POLICE_VISIBLE","is_opposite":false,"time_delta_s":0}'
```

Wait for HR's next fetch (~15s) and confirm the pin appears on HR's map. Confirm the same report also appears in the next fetch response JSON (logcat) with a `alert-0/userreport-*` id and a POLICE type.

- [ ] **Step 7: Verify CONFIRM and DISCARD** against a real nearby Waze alert: read a live alert's id from a fetch response, then:

```bash
adb shell am broadcast -a app.sabre.wzsabre.CONFIRM -n app.sabre.wzsabre/.MainBroadcastReceiver --es data '{"lat":<lat>,"lon":<lon>,"alert_id":"alert-<id>/<uuid>","test":false}'
adb shell am broadcast -a app.sabre.wzsabre.DISCARD -n app.sabre.wzsabre/.MainBroadcastReceiver --es data '{"lat":<lat>,"lon":<lon>,"alert_id":"alert-<id>/<uuid>","test":false}'
```

Confirm logcat shows "Confirmed alert <id>" / "Discarded alert <id>" and no session errors.

- [ ] **Step 8: Record results** in the plan (check the boxes) and note the position-only-vs-tile-snap decision.

---

## Task 12: Ship v1.10.0 (only after Task 11 passes both guarantees)

**Files:**
- Modify: `app/build.gradle.kts` (version), `CHANGELOG` (find exact path/name in repo root), release notes.

- [ ] **Step 1: Bump version.** In `app/build.gradle.kts`: `versionCode = 27`, `versionName = "1.10.0"`.

- [ ] **Step 2: Add a CHANGELOG entry** for v1.10.0 (no em dashes, no attribution). Example copy:

```
## v1.10.0
- Reports you make in Highway Radar now show on the map and are sent to Waze.
- Confirming an alert (thumbs up) and marking one "not there" now reach Waze.
```

- [ ] **Step 3: Run the full suite + assemble release.**

Run: `./gradlew :app:testDebugUnitTest` then `./gradlew :app:assembleRelease`
Expected: PASS + BUILD SUCCESSFUL. Confirm total test count grew from 242.

- [ ] **Step 4: Commit, then open a PR** (main is protected; do not push to main directly). Do NOT tag/release until the PR is merged and the user approves the release.

```bash
git add app/build.gradle.kts CHANGELOG
git commit -m "v1.10.0: user reports reach the HR map and Waze"
```

- [ ] **Step 5: Push the branch and open the PR** with `gh pr create`, summarizing the two guarantees and the emulator validation evidence from Task 11.

---

## Self-Review

**Spec coverage:**
- Inbound REPORT/CONFIRM/DISCARD wired: Tasks 5, 9. ✓
- HR map pin guarantee (echo store + merge): Tasks 4, 9. ✓
- Waze write path (AddUserReportedAlertRequest + ThumbsUp/ReportRmAlert): Tasks 1, 6, 7, 8. ✓
- Type mapping (render-safe echo + Waze subtype): Task 2. ✓
- alert_id -> Waze id recovery: Task 3. ✓
- Spike-first tile-snap decision: Task 11. ✓
- Testing (unit + emulator) and ship gate: Tasks 11, 12. ✓
- Constraints (no permission, no em dash, no attribution, 9-field response, source id): Global Constraints + enforced per task. ✓

**Placeholder scan:** No TBD/TODO in code steps; all code blocks are concrete. Task 11 Step 4's tile-snap branch is intentionally conditional (a spike outcome), with a named follow-up plan file, not a placeholder in the shipped path.

**Type consistency:** `WazeReportSubtype`/`.Kind` used consistently (Tasks 2, 6). `ReportResult` ok/fail used consistently (Tasks 7, 8). `ReportRequest`/`ConfirmDiscardRequest` field names match across Tasks 3, 6, 8, 9. `SabreResponseBuilder.SOURCE_WAZE` used for the echo source (Task 4) matches the existing waze source id. Proto enum name `POLICE_HIDDEN_REPORT` chosen to avoid collision with existing `AlertSubType.POLICE_HIDDEN`.
