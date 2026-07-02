# Building from Source

## Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Ladybug (2024.2) or newer |
| Android SDK | API 35 |
| JDK | **17+** to run Gradle/AGP (the JBR bundled with Android Studio works). The app code itself targets Java 11. |

No NDK or additional toolchains needed. The Waze protobuf classes are generated automatically on first build from `app/src/main/proto/waze.proto` — no manual protoc step. Debug builds need no keystore; only `assembleRelease` requires a `keystore.properties` at the repo root.

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

All tests are JVM unit tests (no emulator required). Results land in `app/build/reports/tests/`. CI (`.github/workflows/ci.yml`) runs `test` + `lintDebug` and builds a debug APK on every push and PR.

## Cutting a release (maintainer)

Releases are automated. Push a version tag and `.github/workflows/release.yml` builds a signed APK and publishes the GitHub release with it attached:

```bash
git tag v1.6 && git push origin v1.6
```

This requires four repository secrets (**Settings → Secrets and variables → Actions**), set once:

| Secret | Value |
|--------|-------|
| `KEYSTORE_BASE64` | `base64 -w0 app/caltrans-sabre.keystore` |
| `KEYSTORE_PASSWORD` | keystore store password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

To build a signed APK **locally** instead, put a `keystore.properties` at the repo root (`storeFile`/`storePassword`/`keyAlias`/`keyPassword`; `storeFile` resolves under `app/`) and run `./gradlew assembleRelease`. Without it, `assembleRelease` produces an unsigned APK and warns.

Current test suites:

| Suite | Tests | What it covers |
|-------|-------|----------------|
| `AlertMapperTest` | 55 | CHP/Waze type → SABRE type (injury codes, locale independence, Waze coarse categories) |
| `ChpConfigTest` | 42 | Category toggles, type overrides, age filter, LogTime parsing (both CHP feed formats) |
| `SabreProtocolTest` | 38 | HR JSON schema — all 11 required alert fields, type whitelist, nullability, drop-bad-alert semantics |
| `LcsSourceTest` | 27 | Caltrans LCS parsing, 1097/1098/1022 state filtering, shoulder/aux skip, span pins, district selection |
| `CHPSourceTest` | 18 | XML parsing (incl. entity refs + real feed shape), radius filter, coordinate parsing, haversine |
| `WildfireSourceTest` | 7 | WFIGS ArcGIS JSON parse (incl. error body + RX filter), SABRE mapping |
| `WinterSourceTest` | 7 | Chain-control parse, R-0…R-3 active filtering, SABRE mapping |
| `AlertDeduperTest` | 7 | Cross-source-only pin de-duplication (family + proximity, confirm-fold) |
| `UpdateCheckerTest` | 4 | Version-comparison logic for the update check |
| Waze suites | 23 | RT cache delta-merge/soft-delete, in-band error classification, shrinking-box geometry, confirm-ts, RmAlert parsing |

## Project structure

```
app/src/main/java/app/sabre/wzsabre/
├── MainActivity.java            # Settings UI (category toggles, age picker) + permission prompts
│
├── MainBroadcastReceiver.java   # Receives HR intents, starts SabreService
├── SabreService.java            # Foreground service; orchestrates CHP + Waze + LCS fetches
├── ForegroundServiceStarter.java# Robust FGS start (startForegroundService → exact-alarm → WorkManager)
├── ServiceStartWorker.java      # WorkManager fallback for service start
├── BootReceiver.java            # Starts the service after device boot
├── AltStartupActivity.java      # Foreground-launch hook HR can use to start the service
│
├── CHPSource.java               # CHP XML fetch + parse + filter (truncation-tolerant)
├── LcsSource.java               # Caltrans LCS lane/road closures (per-district feeds, async cache)
├── AlertMapper.java             # CHP log type → SABRE type; Waze type → SABRE type
├── ChpCategory.java             # Enum of 6 CHP alert categories
├── ChpConfig.java               # User config (SharedPreferences), resolves final type
├── SabreResponseBuilder.java    # Builds the HR response JSON; enforces schema
├── SabreAlert.java              # Internal alert model
│
└── waze/                        # Waze mobile "RT" protocol (replaces the 403'd georss API)
    ├── WazeProtocolSource.java  #   public entry point: cache + async refresh + pre-warm + map to SabreAlert
    ├── WazeAlertCache.java      #   persistent uuid-keyed cache: merge adds + soft-delete removals
    ├── AlertQueryResult.java    #   one query's {newAlerts, removedIds} deltas
    ├── GeoBoxes.java            #   shrinking-box geometry so near-driver alerts aren't thinned
    ├── WazeConfirmTracker.java  #   infers confirm_ts from thumbs-up increases (1-hour map)
    ├── WazeSession.java         #   register → login → handshake → queryBox
    ├── WazeRtCodec.java         #   protobuf line framing, ClientInfo, commands, parseAlerts, parseRemovedAlertIds
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
Waze mobile app's binary "RT" protocol (package `app.sabre.wzsabre.waze`), reimplemented from
observed protocol behavior. No backend or pre-shared credentials are needed: the plugin registers
its own anonymous Waze account (`POST /rtserver/distrib/static`), logs in
(`/rtserver/distrib/login`), and queries alerts via protobuf (`/rtserver/distrib/command`,
with a `uid` auth header). Because an RT query long-polls (~10s), `WazeProtocolSource` serves
a cache and refreshes it on a background thread, and persists/reuses the account so it
registers at most once. The protobuf schema lives at `app/src/main/proto/waze.proto` and is
compiled with `protobuf-javalite` (gradle `com.google.protobuf` plugin).

The RT `/command` endpoint is session-stateful — it sends each alert once, then a
`"RmAlert,<uuid>"` `old_command` line when it clears — so `WazeAlertCache` **merges** query
deltas (adds upsert, removals soft-delete for 5 min) instead of replacing the cache, which is
what stops alerts from vanishing mid-drive. Each refresh queries a series of progressively
smaller boxes (`GeoBoxes`, a shrinking-bbox scan) so the server doesn't thin out
minor alerts near the driver; the session is pre-warmed at service start from the last known
location; and Waze subtype names are passed through to HR verbatim (no remap/whitelist), since
HR understands the full Waze vocabulary.

### Caltrans LCS closures
Lane/road closures come from the per-district Caltrans Lane Closure System feeds
(`https://cwwp2.dot.ca.gov/data/d<N>/lcs/lcsStatusD<NN>.xml`). `LcsSource` picks districts by
bounding box around the requested location, streams the ~4 MB XML (never held in memory as a
string), and keeps a parsed per-district cache (5-min TTL, 30-min max serve age) refreshed on
a background thread — the HR request path only ever reads the cache. A closure is reported
only when it is physically in place: CHP code **1097** (established) set, **1098** (picked up)
and **1022** (canceled) not set; shoulder-only closures are skipped, and closures spanning
more than 2 km get a pin at both ends.

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
