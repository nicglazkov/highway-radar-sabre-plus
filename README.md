# CHP + Waze SABRE for Highway Radar

A drop-in replacement for **wzsabre** that brings CHP live incident alerts and Waze crowdsourced traffic data to [Highway Radar](https://www.highwayradar.com/) via the SABRE plugin protocol.

> **Package ID: `app.sabre.wzsabre`** — same as wzsabre, so Highway Radar discovers this plugin automatically without any reconfiguration.

---

## What it does

| Source | Data | Update cadence |
|--------|------|----------------|
| **CHP Live Feed** | Accidents, road closures, debris, officer on road, weather hazards — directly from the California Highway Patrol statewide XML feed | Every HR map refresh |
| **Waze** | Crowdsourced police, accidents, hazards, road closures | Every HR map refresh |
| **Caltrans Closures (LCS)** | Lane and road closures that are physically in place right now (CHP code 1097), from the per-district Caltrans Lane Closure System feeds | Cached, refreshed every 5 min |

All sources run in parallel and feed into the standard HR crowdsourced-alerts layer — the same map overlay that wzsabre used to power.

---

## Requirements

- Android **6.0+** (API 23)
- [Highway Radar](https://play.google.com/store/apps/details?id=com.highwayradar.app) installed
- Sideloading enabled on your device

---

## Installation

### Option A — Download the APK (recommended for most users)

1. Go to the [Releases](../../releases) page and download the latest `app-release.apk`.
2. On your Android device, open **Settings → Security** (or *Install unknown apps*) and allow installs from your browser or file manager.
3. Open the downloaded APK and tap **Install**.
4. Open the **CHP + Waze SABRE** app once — this wakes up the background service.
5. Open **Highway Radar → Settings → SABRE** and select **CHP + Waze SABRE**.

> **After a phone reboot:** Open the CHP + Waze SABRE app once before using Highway Radar, or simply tap the green start button in HR — the plugin will start automatically.

### Option B — Build from source

See [BUILDING.md](BUILDING.md).

---

## Configuration

Open the **CHP + Waze SABRE** app to access settings. All changes take effect immediately on the next HR map refresh — no restart needed.

### Alert Categories

Each CHP category has two controls:

- **Toggle (on/off)** — disabled categories are never sent to HR.
- **"Shows as" picker** — controls which Highway Radar icon is used for that category.

| Category | Default HR icon | What it covers |
|----------|----------------|----------------|
| Fatal & Injury Accidents | Accident (Major) | 1179, 1183, fatals, SIG alerts |
| Minor Accidents | Accident (Minor) | Non-injury collisions, hit-and-run |
| Officer on Road | Police Visible | Traffic control, construction escorts |
| Closures & Congestion | Road Closure | Road closures, traffic advisories |
| Debris & Road Hazards | Road Debris | Debris, vehicle fires, misc. hazards |
| Weather Hazards | *Natural* | Fog, wind, snow, ice, chain controls |

**Tip:** If you find the police icon distracting, set *Officer on Road → Shows as → Road Closure* to get a neutral congestion icon instead.

### Incident Age

Drops CHP alerts older than a configurable threshold using the incident's actual `LogTime` from the feed (not the time your phone fetched it). This prevents stale multi-hour incidents from cluttering the map.

Options: **No limit / 30 min / 1 hr / 2 hr / 4 hr / 8 hr** (default: 1 hour)

---

## Migrating from wzsabre

If you already have wzsabre installed:

1. **Uninstall wzsabre** (Settings → Apps → wzsabre → Uninstall).
2. Install this APK — it uses the same package ID (`app.sabre.wzsabre`) so HR picks it up without any changes to HR's settings.
3. Open the new app once to start the service.

> The package ID being identical to wzsabre is intentional — HR's plugin discovery whitelists `app.sabre.wzsabre`, and we reuse it so no HR-side changes are needed.

---

## Troubleshooting

**"Crowd-Sourced Alert Problems" banner in HR**
- Open the CHP + Waze SABRE app and check that the service status shows *"Plugin active"*.
- Tap the green start button in HR — this sends a fresh handshake.
- On Android 15: open this app first, then HR. The background service must be running before HR requests data.

**CHP alerts visible but no Waze alerts**
- Waze requires a real internet connection. On the very first use the plugin registers an anonymous Waze session in the background — Waze alerts can take ~10–20 seconds to appear that first time. After that the session and a live alert cache are kept warm and pre-loaded at start, so alerts appear within a second or two on subsequent sessions.
- Check that the app has network permission (it should request none explicitly; all network access is in the background service).

**No alerts at all**
- Confirm HR is using the correct plugin: HR → Settings → SABRE → should show "CHP + Waze SABRE".
- Check that no alert categories are all turned off in the app settings.

---

## How it works (brief)

```
Highway Radar  ──broadcast──▶  MainBroadcastReceiver
                                      │
                        startForegroundService()
                          (+ exact-alarm fallback)
                                      │
                               SabreService (foreground)
                              ┌───────┼─────────┐
                          CHP feed   Waze    Caltrans LCS
                          (XML)     (mobile  (per-district
                                    "RT"      closure XML,
                                    protocol, cached)
                                    protobuf)
                              └───────┼─────────┘
                               sendBroadcast(response)
                                      │
                               Highway Radar  ◀──────
```

- **CHP**: fetches `https://media.chp.ca.gov/sa_xml/sa.xml`, filters by radius and incident age, applies your category settings.
- **Waze**: emulates the Waze mobile app's binary "RT" protocol — it registers an anonymous Waze session, logs in, and queries crowd-sourced alerts over Waze's protobuf API (the older live-map/georss API is now blocked). This matches the approach in the current official wzsabre. The RT feed is session-stateful (each alert is sent once, then removed when it clears), so query results are merged into a persistent alert cache rather than replacing it — this keeps alerts from disappearing as you drive. A series of progressively smaller map viewports is queried so the server doesn't thin out minor alerts near you, the session is pre-warmed at start to cut first-load latency, and Waze alert subtypes (e.g. *car stopped on shoulder*, *heavy traffic*) are passed through to Highway Radar verbatim rather than flattened.
- **Caltrans LCS**: fetches the per-district lane-closure feeds (`https://cwwp2.dot.ca.gov/data/d<N>/lcs/lcsStatusD<NN>.xml`) for whichever districts cover your location. Only closures that are physically established (CHP code 1097 set, not picked up or canceled) are shown; shoulder-only closures are skipped. Closures longer than 2 km get a pin at each end. The ~4 MB feeds are parsed in the background and cached for 5 minutes, so they never delay a Highway Radar request.
- **SABRE protocol**: a broadcast-intent IPC protocol defined by Highway Radar. Our plugin responds to `FETCH_REQUEST` broadcasts with a JSON payload containing `SabreFetchResponseAlert` objects.

---

## Contributing

Pull requests welcome. Run the test suite before submitting:

```bash
./gradlew test
```

184 unit tests cover the SABRE response format, alert type mapping, the Waze alert cache (delta merge + soft-delete), shrinking-box geometry, crowd-confirmation tracking, CHP XML parsing, Caltrans LCS parsing and filtering, config filtering, and LogTime parsing. See [BUILDING.md](BUILDING.md) for full dev setup.

---

## License

[MIT](LICENSE)
