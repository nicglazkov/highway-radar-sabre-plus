# Privacy Policy

_Last updated: 2026-08-04_

SABRE Plus is a free, open-source Highway Radar plugin. It has **no servers, no accounts, and no analytics**, and it **collects no personal data**. Everything it does runs on your own device. This document explains, in plain terms, exactly what stays on your device, what leaves it, and what never happens.

## The short version

- The app runs entirely on your phone. There is no server of ours for your data to reach.
- We do not use analytics, crash-reporting services, advertising, or trackers of any kind.
- We do not create accounts, assign user IDs, or read your device's advertising ID.
- We do not store your location history, and we never sell or share data.

## What is stored on your device

All of this is kept locally in the app's private storage and never transmitted by us:

- **Your settings** (which sources and categories are on, the age filter, wildfire size, and so on).
- **An anonymous Waze session.** To use the Waze feature, the app registers an anonymous session with Waze (no name, email, phone, or account). A session token is cached on your device so it does not have to re-register every time.
- **A short diagnostics log.** The app keeps a small in-memory record of recent activity (counts, category names, source health, and errors) to help troubleshooting. It is only ever sent anywhere if **you** tap **Share diagnostics** and choose to share it. It contains no location, street names, alert IDs, or personal data.
- **A local crash log.** If the app crashes, a summary is written to a private file so you can optionally include it when reporting a bug. It stays on your device unless you choose to share it.

## What leaves your device

To show you road conditions, the app fetches data from public and third-party sources. These requests go directly from your device to those services; they do not pass through any server of ours.

| Service | What is requested | Is your location sent? |
|---|---|---|
| **California Highway Patrol** (`media.chp.ca.gov`) | The statewide incident feed | No. The whole-state feed is fetched and filtered on your device. |
| **Caltrans** (`dot.ca.gov`) | Lane-closure and chain-control feeds for the districts near you | No. Which district feeds to fetch is worked out on your device; your coordinates are not sent. |
| **Wildfire feed / NIFC** (`arcgis.com`) | Active California wildfires | No. All active California fires are fetched and filtered on your device. |
| **Waze** (`waze.com`) | Nearby crowd-sourced alerts | **Yes.** To return alerts near you, the app sends your **approximate location** (a map area around you) to Waze over an anonymous session. |
| **GitHub** (`api.github.com`) | A check for a newer version of the app | No. This is a standard version check and sends no location. |

As with any app that uses the internet, each service you contact can see your device's public **IP address**, and their use of it is governed by their own privacy policies. The Waze feature is the only case where your approximate location leaves the device. If you prefer not to send any location to Waze, you can turn the Waze source off in Highway Radar's settings.

## What we never do

- No analytics or usage tracking.
- No advertising or ad identifiers.
- No accounts, sign-ins, or user identifiers.
- No location history or profiles.
- No selling, renting, or sharing of data with anyone.

## Permissions

The app requests only what it needs to relay alerts while you drive (internet access, a foreground-service notification, wake lock, exact alarms to keep the service reachable, and an optional battery-optimization exemption). It does **not** request access to your contacts, camera, microphone, photos, or precise device location; the location it uses for filtering comes from Highway Radar's request, not from a location permission of its own.

## Children

This app is a navigation utility for drivers and is not directed at children.

## Changes

If this policy changes, the update will be committed to this file in the public repository, and the "Last updated" date above will change.

## Contact

Questions or concerns: open an issue at
https://github.com/nicglazkov/highway-radar-sabre-plus/issues

This policy is also published as a web page at
https://nicglazkov.github.io/highway-radar-sabre-plus/privacy.html
