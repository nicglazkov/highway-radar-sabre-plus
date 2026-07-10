# Changelog

All notable changes to this project. This project adheres to [semantic-ish versioning](https://semver.org/); dates are release dates.

## [1.9] - 2026-07-10

### Added
- **Share diagnostics** button in the settings screen. If something is not working, tap it to send a plain-text troubleshooting report (app and Highway Radar versions, alert counts and category names, recent plugin activity) without a computer or ADB. A popup lets you choose which of four categories to include, and the report never contains your location, street names, alert details, or any personal data.

## [1.8.5] - 2026-07-10

### Fixed
- **More alerts now show up on the Highway Radar map.** Highway Radar 3.2 only draws crowd alerts categorized as police, hazard, or accident and silently drops the rest, so common Waze traffic jams and road closures were never appearing. They now show as congestion hazards instead of being dropped. (CHP, Caltrans, wildfire, and chain alerts already used categories HR draws.)

## [1.8.4] - 2026-07-09

### Fixed
- **Highway Radar receives alert data again on the latest HR.** Highway Radar 3.2 tightened its plugin data format and removed two fields our alerts still sent (`user_id` and `confirm_count`). HR reads plugin responses strictly, so those extra fields made it silently reject every response: the plugin looked connected but no alerts appeared. Our alert and discovery payloads now match HR 3.2's format exactly.

## [1.8.3] - 2026-07-06

### Fixed
- Chain controls no longer show a false error in regions with no chain-control program (such as the Bay Area). Caltrans changed the "no feed here" response for those districts from 404 to 500; the plugin now treats any non-200 as "no chain controls here" (shown as 0) instead of an error.

## [1.8.2] - 2026-07-06

### Changed
- The published APK download is now named `SABRE-Plus-vX.apk` to match the app name.

## [1.8.1] - 2026-07-06

### Changed
- Renamed the app to **SABRE Plus** (shown on the launcher, in the settings screen, in notifications, and in Highway Radar's plugin list, where it appears as "SABRE Plus (CHP, Waze, Caltrans)"). No functional change; the package ID is unchanged, so an existing Highway Radar selection keeps working.

## [1.8] - 2026-07-06

### Changed
- Renamed the project to **highway-radar-sabre-plus**. The app, package ID, and settings are unchanged; the in-app update check and "report a bug" links now point at the new repository.

## [1.7.3] - 2026-07-06

### Changed
- Tidied wording across the app, README, and release notes.

## [1.7.2] - 2026-07-02

### Added
- **"Report a bug / send feedback" button** in the settings screen, opens the GitHub issue form in your browser, pre-filled with your plugin and Android version. (No new permissions.)

## [1.7.1] - 2026-07-02

Fix found from a real test drive.

### Fixed
- **Chain controls stop hammering feeds that don't exist.** In regions with no mountain routes (e.g. the Bay Area), Caltrans returns "not found" for the chain-control feed. The plugin was re-requesting those every few seconds all drive and showing a false error in Diagnostics; it now treats "not found" as "no chain controls here" and caches it like any other result.

## [1.7] - 2026-07-02

New source and quality-of-life controls.

### Added
- **Chain controls**: Caltrans winter chain requirements (R-1/R-2/R-3) on mountain routes, shown as slippery-road hazards. Settings toggle (on by default); off-season it shows nothing.
- **Waze filters**: coarse per-category toggles (police & cameras / accidents / hazards / traffic jams / road closures) to cut noise.
- **Wildfire size threshold**: optionally hide small fires (All / 10+ / 100+ / 1000+ acres).
- **Update-notification toggle**: silence the update notification while keeping the in-app banner.
- **Diagnostics: Refresh button + last-crash line**: poke a refresh and see the most recent crash (on-device only), which makes bug reports far more useful.

## [1.6.1] - 2026-07-02

Hardening from a post-release review of v1.6.

### Fixed
- Wildfire outages no longer look like "no active fires", a broken feed now surfaces as an error in diagnostics, and prescribed burns can't slip in as wildfires.
- Duplicate-pin merging now only collapses pins from *different* sources, so two genuinely separate incidents at the same spot are never dropped (also restores the debug "one of each type" harness).
- The update check no longer leaks the settings screen on rotation and no longer calls GitHub on every open.
- Automated release is now re-runnable, requires all signing secrets before it attempts to sign, and skips cleanly otherwise.
- Minor: diagnostics status is now a consistent snapshot; the wildfire source isn't fetched when disabled.

## [1.6] - 2026-07-02

New data source, in-app updates, and quality-of-life.

### Added
- **Wildfires**: active California wildfires (name, size, containment) from the interagency WFIGS feed, shown as road hazards near the fire. New settings toggle (on by default).
- **Update checking**: the settings screen shows an "update available" banner, and you get a single notification per new version (no spam). README documents an Obtainium option for hands-off auto-update.
- **Diagnostics panel**: the settings screen shows each source's last update time, item count, and last error.
- **Automated releases**: pushing a version tag builds a signed APK and publishes the GitHub release via CI.

### Changed
- Duplicate pins reported by more than one source (e.g. a CHP and a Waze accident at the same spot) are now merged into one.
- CI actions updated; the debug/release build is unchanged.

## [1.5.1] - 2026-07-01

Follow-up hardening from an independent post-release review of 1.5, plus repo hygiene.

### Fixed
- **Waze in-band errors**: a benign informational error attached to a normal response no longer fails every refresh; only real server errors (HTTP 500-class) or session/credential errors trigger recovery.
- **Waze rejection backoff**: the backoff timer is now visible across threads (it wasn't, so the throttle could be skipped); non-rejection failures back off briefly instead of retrying at poll cadence; a bad account recovers after the 24-hour registration window rolls over.
- **Waze ghost prevention**: the cache is only reset once a new session's first query succeeds, so a re-login followed by a dropped query no longer blanks Waze.
- **CHP conditional fetch**: cache validators are committed only after a successful download+parse, so a mid-download failure can't leave the cache empty.
- **Service lifecycle**: a stopping service can't be resurrected by a late shutdown; the full alert payload is no longer logged in release builds; the exported startup activity only accepts callbacks to Highway Radar; two rapid requests can't clobber each other's start alarm.

### Added
- GitHub Actions CI (unit tests + lint + debug APK on every push/PR).
- App icon (road + radar beacon).

### Changed
- Pinned the Gradle wrapper distribution checksum; `assembleRelease` without a keystore now produces an unsigned APK with a clear warning instead of a cryptic failure.

## [1.5] - 2026-07-01

Data-reliability release: keeps Waze and CHP alerts accurate and always flowing.

### Fixed
- **Waze can no longer lock itself out**: a run of connection rejections used to mint a new anonymous account every couple of seconds; now bounded by exponential backoff and a per-day cap.
- **Waze recovers instead of going silent** when the server invalidates a session mid-drive (in-band error detection).
- **No more stale "ghost" alerts** lingering after they clear (cache cleared on session change).
- **CHP alerts stop flapping**: served from a background last-good cache with conditional GETs; a slow CHP feed can no longer delay Waze/closure alerts.
- A bad heading can't blank a whole update; correct behavior on non-English device locales; Caltrans auxiliary-lane closures are now shown; LCS closures never emit a 1970 timestamp.

## [1.4] - 2026-07-01

Lifecycle, crashes, and privacy.

### Fixed
- **No more daily crash on Android 15/16**: the background service ran into Android 15's ~6-hour data-sync limit; it now runs as a `specialUse` service with no such cap.
- **Fixed a crash on Android 10 and 11** that stopped the plugin from working at all on those versions.
- **Closed a privacy leak**: alert replies are delivered only to Highway Radar (previously any app on the device could listen in).
- Waze credentials are no longer included in device backups.

### Added
- **Stops when Highway Radar closes**: the plugin now runs only while Highway Radar is open and shuts down cleanly afterward.

### Changed
- Minimum Android version is now 7.0.

## [1.3] - 2026-06-10

Waze RT faithful-port: delta-merge cache, shrinking-box queries, session pre-warm, `-720` directionless heading, raw subtype passthrough, crowd-confirmation fields, and 200-alert response batching.

## [1.2] - 2026-06-09

Added Caltrans lane/road closures (LCS) as a data source, plus reliability fixes.

## [1.1] - 2026-06-09

Restored the plugin on Android 15/16 (foreground-service start fixes) and rewrote the Waze integration onto the mobile RT protocol after the georss API was blocked.

## [1.0] - 2026-03-26

Initial release: CHP live incidents + Waze crowdsourced alerts for Highway Radar.

[1.9]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.9
[1.8.5]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.8.5
[1.8.4]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.8.4
[1.8.3]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.8.3
[1.8.2]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.8.2
[1.8.1]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.8.1
[1.8]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.8
[1.7.3]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.7.3
[1.7.2]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.7.2
[1.7.1]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.7.1
[1.7]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.7
[1.6.1]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.6.1
[1.6]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.6
[1.5.1]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.5.1
[1.5]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.5
[1.4]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.4
[1.3]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.3
[1.2]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.2
[1.1]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.1
[1.0]: https://github.com/nicglazkov/highway-radar-sabre-plus/releases/tag/v1.0
