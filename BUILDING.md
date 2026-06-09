# Building from Source

## Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Ladybug (2024.2) or newer |
| Android SDK | API 35 |
| JDK | 11 (bundled with Android Studio) |

No NDK or additional toolchains needed.

## Clone and build

```bash
git clone https://github.com/nicglazkov/caltrans-sabre.git
cd caltrans-sabre
```

**Debug APK** (for sideloading during development):
```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr" ./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

On Windows (Git Bash):
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

**Install directly to a connected device:**
```bash
./gradlew installDebug
```

## Running tests

```bash
./gradlew test
```

All tests are JVM unit tests (no emulator required). Results land in `app/build/reports/tests/`.

Current test suites:

| Suite | Tests | What it covers |
|-------|-------|----------------|
| `AlertMapperTest` | 46 | Every CHP log type and Waze type maps to the correct SABRE type (incl. injury-collision codes) |
| `ChpConfigTest` | 32 | Category toggles, type overrides, age filter, LogTime parsing |
| `SabreProtocolTest` | 28 | HR JSON schema — all 11 required alert fields, types, nullability |
| `CHPSourceTest` | 14 | XML parsing, radius filter, coordinate parsing, haversine distance |

## Project structure

```
app/src/main/java/app/sabre/wzsabre/
├── MainActivity.java            # Settings UI (category toggles, age picker) + permission prompts
│
├── MainBroadcastReceiver.java   # Receives HR intents, starts SabreService
├── SabreService.java            # Foreground service; orchestrates CHP + Waze fetches
├── ForegroundServiceStarter.java# Robust FGS start (startForegroundService → exact-alarm → WorkManager)
├── ServiceStartWorker.java      # WorkManager fallback for service start
├── BootReceiver.java            # Starts the service after device boot
├── AltStartupActivity.java      # Foreground-launch hook HR can use to start the service
│
├── CHPSource.java               # CHP XML fetch + parse + filter (truncation-tolerant)
├── AlertMapper.java             # CHP log type → SABRE type; Waze type → SABRE type
├── ChpCategory.java             # Enum of 6 CHP alert categories
├── ChpConfig.java               # User config (SharedPreferences), resolves final type
├── SabreResponseBuilder.java    # Builds the HR response JSON; enforces schema
├── SabreAlert.java              # Internal alert model
│
└── waze/                        # Waze mobile "RT" protocol (replaces the 403'd georss API)
    ├── WazeProtocolSource.java  #   public entry point: cache + async refresh + map to SabreAlert
    ├── WazeSession.java         #   register → login → handshake → query
    ├── WazeRtCodec.java         #   protobuf line framing, ClientInfo, commands, parseAlerts
    ├── WazeHttpClient.java      #   OkHttp binary POST + session cookie jar + retry
    ├── DeviceIdentity.java      #   synthetic device fingerprint pool
    ├── WazeConstants.java       #   hosts, paths, protocol/app versions
    └── WazeCredentials / WazeSessionInfo / WazeAlert / WazeExceptions

# Protobuf schema: app/src/main/proto/waze.proto  (generates app.sabre.wzsabre.waze.WazeProto)
```

## Key architecture decisions

### Package ID = `app.sabre.wzsabre`
Highway Radar's SABRE discovery whitelists this package ID. Keeping the same ID means HR finds this plugin without requiring any HR-side changes.

### SABRE protocol — `SabreFetchResponseAlert` schema
HR uses `kotlinx.serialization` with a bitmask that requires **all 11 fields** to be present on every alert object. Missing any field throws `MissingFieldException` in HR and crashes the crowdsourced-alert layer. `SabreResponseBuilder.buildAlert()` enforces this and rejects NaN coordinates and overflowing `report_ts` at build time.

The 11 fields: `alert_source`, `alert_id`, `user_id`, `type`, `lat`, `lon`, `heading_deg`, `street_name` (nullable), `report_ts` (Int, not Long), `confirm_ts` (nullable Int), `confirm_count`.

### Waze mobile "RT" protocol
Waze blocks the live-map/georss API with HTTP 403, so Waze data is fetched by emulating the
Waze mobile app's binary "RT" protocol (package `app.sabre.wzsabre.waze`, ported from the
official wzsabre 2.2). No backend or pre-shared credentials are needed: the plugin registers
its own anonymous Waze account (`POST /rtserver/distrib/static`), logs in
(`/rtserver/distrib/login`), and queries alerts via protobuf (`/rtserver/distrib/command`,
with a `uid` auth header). Because an RT query long-polls (~10s), `WazeProtocolSource` serves
a cache and refreshes it on a background thread, and persists/reuses the account so it
registers at most once. The protobuf schema lives at `app/src/main/proto/waze.proto` and is
compiled with `protobuf-javalite` (gradle `com.google.protobuf` plugin).

### Android 15/16 foreground service start
On Android 15/16 both a plain `startService()` (background service start) and a bare
`startForegroundService()` from a background broadcast receiver are denied. `ForegroundServiceStarter`
escalates: call `startForegroundService()`; on denial, schedule an **immediate exact alarm**
whose `PendingIntent.getForegroundService()` start is exempt from BFSL; fall back to a
`WorkManager` expedited task. When Highway Radar sends its broadcast it grants a temporary
FGS-start allowlist, so the direct call usually succeeds outright. A battery-optimization
exemption (prompted in `MainActivity`) and an `AltStartupActivity` (advertised to HR as
`alternative_startup_activity`, launched to the foreground) add further reliability. Exact
alarms need `SCHEDULE_EXACT_ALARM` + `USE_EXACT_ALARM` (auto-granted for sideloaded apps).

### Config hot-reload
`SabreService` calls `ChpConfig.load(context)` on every `FETCH_REQUEST`, so changes made in the settings UI take effect on the next HR map refresh without restarting the service.

## ADB shortcuts

```bash
# Install and launch
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.sabre.wzsabre/.MainActivity

# Watch plugin logs
adb logcat -s SABREProxy SABREService CHPSource WazeRT FGSStarter SABREBoot

# Simulate a FETCH_REQUEST (frozen-process caveat: use --receiver-foreground)
adb shell am broadcast --receiver-foreground \
  -a app.sabre.wzsabre.FETCH_REQUEST \
  -p app.sabre.wzsabre \
  --es data '{"request_id":"t1","response_action":"com.test.RESPONSE","lat":34.0522,"lon":-118.2437,"radius_m":5000}'
```
