# Design: Bidirectional user reports (HR to plugin, plugin to Waze + HR map)

Date: 2026-08-12
Target version: v1.10.0 (versionCode 27), up from 1.9.6 (26)
Status: approved design, pending spec review

## Problem

A user reports that reports they create inside Highway Radar (HR) do not reflect on
the map, unlike the original SABRE (wzsabre) app.

Root cause, confirmed by decompiling both apps:

- The original wzsabre is **bidirectional**. When a user creates a report in HR, HR
  fires `app.sabre.wzsabre.REPORT` (and `CONFIRM` / `DISCARD` for thumbs-up /
  not-there) at the plugin's broadcast receiver. wzsabre handles all three and
  **submits them to Waze's real RT server** (REPORT via the `AddUserReportedAlertRequest`
  protobuf; CONFIRM via a `ThumbsUp,<id>` text command; DISCARD via `ReportRmAlert,<id>`).
- SABRE Plus **declares** those actions in its manifest and even **advertises**
  `report_action` / `confirm_action` / `discard_action` to HR in the handshake, so HR
  believes the plugin accepts reports. But `MainBroadcastReceiver.onReceive` has no
  branch for them: every REPORT/CONFIRM/DISCARD falls through and is silently dropped.
  The Waze integration is also read-only (register/login/query); the reporting protobuf
  messages are deliberately omitted from `waze.proto`.

So the user's reports go nowhere: not to the HR map, not to Waze.

## Goals

1. **HR map pin — guaranteed.** A report the user makes in HR must appear as a pin on
   HR's map. This is fully under our control and must be 100% reliable.
2. **Push to Waze.** The report must be submitted to Waze's live RT server using the
   same protocol the original uses, and verified to be accepted by live Waze before we
   ship. (Bounded by Waze's own server behavior: anonymous-account caps, rate limits,
   road-snap availability. We guarantee correct submission and verified acceptance on
   the emulator, not that Waze never rate-limits a future report.)
3. **Full parity:** handle REPORT, CONFIRM (thumbs up), and DISCARD (not there).

## Non-goals

- No new Android permission (report submission and tile fetch both use existing
  INTERNET). This is a hard constraint.
- No change to the existing read/fetch path except the merge in goal 1.
- No user attribution and no em dashes in any user-facing string (Toast, CHANGELOG,
  release notes).

## Architecture

Data flow added (control + write directions; the existing read path is unchanged):

```
HR  --REPORT/CONFIRM/DISCARD broadcast-->  MainBroadcastReceiver
        --forward action+data-->  SabreService.onStartCommand (worker thread)
              REPORT  -> UserReportStore.add (echo)  +  WazeReporter.submit
              CONFIRM -> WazeReporter.confirm(alertId)
              DISCARD -> UserReportStore.removeMatch  +  WazeReporter.discard(alertId)

HR  --REQUEST (fetch)-->  SabreService.handleFetchRequest
              merges UserReportStore entries into the alert list -> HR draws the pin
```

### 1. Inbound receiver (`MainBroadcastReceiver`)

Add branches, checked in this order so legacy `*_REPORT` names disambiguate correctly:

- `action.contains("CONFIRM")` -> forward as `"CONFIRM"` (covers `CONFIRM` and legacy `CONFIRM_REPORT`)
- else `action.contains("DISCARD")` -> forward as `"DISCARD"` (covers `DISCARD` and legacy `DISCARD_REPORT`)
- else `action.endsWith("REPORT")` -> forward as `"REPORT"` (covers `REPORT` and legacy `SUBMIT_REPORT`)

These must be evaluated before nothing else swallows them; the existing
`endsWith("REQUEST")` and `contains("SHUTDOWN")` branches do not match any of the three.
Each branch calls `ForegroundServiceStarter.start(context, "<ACTION>", intent.getStringExtra("data"))`,
identical to the FETCH_REQUEST path, so the Android 15/16 service-start hardening applies.

### 2. Service dispatch (`SabreService.onStartCommand`)

Add `action` cases `REPORT` / `CONFIRM` / `DISCARD`, each running on a worker thread
(these do network I/O; must not block the main thread). They re-arm the idle timer like
any other start. A new `ReportHandler` (or methods on the service) owns parsing + routing.

### 3. Payload parsing

- REPORT `data` JSON: `lat` (double), `lon` (double), `heading_deg` (double),
  `altitude_m` (double), `type` (string), `is_opposite` (bool), `time_delta_s` (int).
- CONFIRM / DISCARD `data` JSON: `lat` (double), `lon` (double), `alert_id` (string),
  `test` (bool). `test=true` short-circuits the Waze network call (parity with original).

Recover the numeric Waze alert id from `alert_id`: strip a leading `"alert-"`, split on
`"/"`, take element 0 as a long. Our waze source already emits `alert_id = "alert-" + id + "/" + uuid`
(`WazeProtocolSource.toSabreAlert`), so CONFIRM/DISCARD work with the ids we already send.

### 4. HR map guarantee: `UserReportStore`

- In-memory store (held by `SabreService`, or a singleton), thread-safe, TTL ~30 min.
- On REPORT: build a synthetic `SabreAlert` immediately and add it:
  - `alert_source = "waze"` (must be one of the advertised source ids).
  - `type`: map the HR report `type` to a render-safe SABRE type via `AlertMapper`
    (must start with POLICE/HAZARD/ACCIDENT or HR's renderer drops it).
  - `alert_id`: a stable synthetic id, e.g. `alert-0/userreport-<epochMs>` so it never
    collides with a real Waze id and so DISCARD can match it.
  - `heading_deg`: the reported heading, or the -720 unknown sentinel.
  - `report_ts`: now (minus `time_delta_s`).
- `handleFetchRequest`: after collecting the five sources and before dedupe/send, add
  `userReportStore.activeAlerts(lat, lon, radius)` (drops expired, filters to radius).
  The pin then appears on HR's next poll (~15s) and persists up to the TTL.
- On DISCARD: remove any store entry near the report coordinate (and, when the id maps
  to a store entry, by id).

### 5. Waze write path

Extend `waze.proto` (reporting sub-tree, omitted today):

- `AddUserReportedAlertRequest` with `UserPosition { GpsPosition { CoordinateWithAlt,
  horizontalAccuracyMeters, timeEpochMs }, SegmentNodes { fromNode, toNode } }`,
  `azymuth`, `alert_details` (AlertDetails), `segment_direction`, `report_time`
  (Timestamp), `reporting_manner` (ReportingManner enum).
- `AddUserReportedAlertResponse` with `alert_uuid` and `received_points_count`.
- `AlertDetails` and the sub-detail messages actually used by the mapped types
  (crash / traffic / hazard-on-road / police). Field numbers and wire types taken from
  the decompiled `WazeProtocol` generated `writeTo` / `*_FIELD_NUMBER`, same method used
  for the existing fetch messages.
- The Element field numbers for the report request/response.

`WazeSession` gains:

- `confirmAlert(long id)` -> `command("ThumbsUp," + id)`.
- `discardAlert(long id)` -> `command("ReportRmAlert," + id)`.
- `submitReport(...)` -> the sequence the original uses: SeeMe/Location/MapDisplayed
  handshake, optional road snap (see risk below), `buildReportLine`
  (`AddUserReportedAlertRequest`), `command()` POST, then parse the response for
  `alert_uuid` + `received_points_count` and log "Report accepted: uuid=… pts=…" (or a
  specific failure). All three go through the existing single `command()` POST to
  `/rtserver/distrib/command`, so no new endpoint or permission.

Type mapping (HR `type` string -> Waze subtype), from the original's
`WazeAlertReporter.buildAlertDetails`:
`POLICE_VISIBLE`->POLICE_DEFAULT, `POLICE_HIDING`->POLICE_HIDDEN,
`ACCIDENT_*`->CrashDetails, `JAM_*`->TrafficDetails,
`HAZARD_ON_ROAD*` / `HAZARD_ON_SHOULDER*`->HazardOnRoadDetails. Unknown -> reject the
Waze submit (the HR echo still shows, so the pin is never lost).

A new `WazeReporter` (parallel to the read-path `WazeProtocolSource`) owns report
sessions and reuses the persisted anonymous account + login where possible.

### 6. The one real risk: road snap

The original snaps the report's GPS point to a Waze road *segment* via a separate
tile-server protocol (`fetchTileSegments` -> `WazeTileParser`, a large surface) and
sends `SegmentNodes { fromNode, toNode }`.

**Spike first, before porting any tile code.** Implement `submitReport` with a
position-only `AddUserReportedAlertRequest` (no `SegmentNodes`) and test against live
Waze on the emulator:

- If Waze accepts the position-only report (returns `alert_uuid` + points), we skip the
  entire tile-parser port. This is the preferred outcome (much smaller surface).
- If Waze rejects it or misplaces it, port `fetchTileSegments` + `WazeTileParser` +
  the At/MapDisplayed/simulateDriving steps and send full `SegmentNodes`.

This decision is made empirically in implementation step 1, not now.

## Testing / verification

Unit (JVM, grow the 242-test suite):

- Action disambiguation in the receiver (CONFIRM_REPORT -> confirm, SUBMIT_REPORT ->
  report, etc.).
- REPORT/CONFIRM/DISCARD JSON parsing incl. missing/renamed keys and `test=true`.
- `alert_id` -> numeric Waze id recovery (and rejection of malformed ids).
- HR type -> render-safe echo type, and HR type -> Waze subtype mapping.
- `AddUserReportedAlertRequest` golden-bytes encoding against a fixture captured from
  the decompiled builder (locks the wire format).
- `UserReportStore` TTL expiry, radius filter, and DISCARD removal.

End-to-end (A15 emulator, per the established harness):

1. Fire a simulated HR report: `adb shell am broadcast -a app.sabre.wzsabre.REPORT
   -n app.sabre.wzsabre/.MainBroadcastReceiver --es data '{...}'`.
2. Confirm the next fetch response (logcat) includes the echoed pin (goal 1).
3. Confirm logcat shows Waze accepting the report ("Report accepted: uuid=… pts=…").
4. Confirm the pin on real Waze (live-map / Waze app) at that coordinate.
5. Repeat for CONFIRM and DISCARD against a real nearby Waze alert.

**Ship gate:** tag and release v1.10.0 only after goals 1 and 2 are both verified on the
emulator against live Waze and live HR. If the position-only Waze path cannot be made to
work and the tile-snap port is out of scope for this release, ship goal 1 (HR map pin,
fully guaranteed) plus CONFIRM/DISCARD, and split the REPORT->Waze push into a follow-up
rather than shipping something unverified.

## Files touched (anticipated)

- `MainBroadcastReceiver.java` — new REPORT/CONFIRM/DISCARD branches.
- `SabreService.java` — dispatch + merge `UserReportStore` into fetch response.
- New `ReportHandler.java` (or methods), `UserReportStore.java`.
- New `waze/WazeReporter.java`; extend `waze/WazeSession.java`, `waze/WazeRtCodec.java`.
- `app/src/main/proto/waze.proto` — reporting messages.
- `AlertMapper.java` — HR type -> render-safe echo type + HR type -> Waze subtype (if not
  already covered).
- Tests under `app/src/test/java/app/sabre/wzsabre/`.
- `CHANGELOG` / release notes / `build.gradle.kts` version bump at ship time.
