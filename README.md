# 🛡️ AEGIS Android Client

Android client for **AEGIS**, the autonomous disaster-information assistant.
Written in Kotlin with **zero external dependencies**: data access uses only
`HttpURLConnection` plus an in-repo JSON parser, and the UI is built with the
platform View toolkit. Disaster information (earthquakes, EEW, tsunami,
weather warnings, typhoons, and the other event kinds) comes exclusively
from the **A.A.S (Aegis Alert System)** hub; weather forecasts are fetched
directly from JMA and Open-Meteo, as before.

| Item | Detail |
|---|---|
| Version | v1.0.0 |
| Status | Rebuilt from scratch (re-implementation of the former v1.15.x client) |
| Distribution | GitHub Releases |
| Implementation | Kotlin (platform Views, no external libraries) |
| Changelog | [CHANGELOG.md](CHANGELOG.md) |

Japanese README: [README.ja.md](README.ja.md) — Design notes:
[DESIGN.md](DESIGN.md)

## 📖 Overview

- Package: `jp.aegis.client` / App name: AEGIS
- Requires Android 8.0 (API 26) or later, target API 34
- Default places: Takasaki (Gunma) and Omiya (Saitama); more can be added

## 📡 How it connects to A.A.S

Earthquake, EEW, tsunami, warning and typhoon information comes **only**
from A.A.S — there is no direct P2P / Wolfx fallback.

- **Opt-in by settings**: the client is active only when both the A.A.S base
  URL and a client token are saved in the Settings tab (stored in
  SharedPreferences; authentication is `Authorization: Bearer <token>`).
  No real endpoint or token is ever baked into the code or defaults.
- **SSE is the primary path while the app is visible**: `GET /v1/stream`.
  Events replayed before `event: ready` are treated as initial sync and are
  never turned into notifications. Reconnects back off 5s → 10s → 30s → 60s.
- **Background sync uses REST**: `GET /v1/events?since=<last updatedAt>`
  recovers anything missed; deduplication is by `key` + `updatedAt`
  (EEW identity uses `baseKey`, which survives cancellations).
- **When A.A.S is unset or unreachable**, the disaster sections simply stay
  empty; the app keeps working (weather and other non-A.A.S features are
  unaffected).

## ✨ Features

- **Briefing tab**: "current summary" card (active warnings per place,
  tsunami state, latest quake, typhoon), a pinned fresh-EEW card, and
  per-place weather (JMA forecast + Open-Meteo current conditions)
- **Events tab**: all received events (14 A.A.S kinds), newest first, with
  detail dialogs; live-updated over SSE while visible
- **Settings tab**: A.A.S URL/token, connection test (`GET /v1/status`),
  background sync (interval, quake scale threshold, warning mode),
  notification test, and custom places (name / coordinates / area codes)
- **Notifications**: AlarmManager-chained background sync with two channels
  (urgent: EEW & tsunami / notice: quakes & warnings), stable notification
  IDs so updates overwrite instead of duplicating, and a boot receiver that
  restores the schedule after reboot

## 🔨 Build

The supported build is the offline manual pipeline (no Gradle/AGP, no
downloads): unit tests → aapt2 → kotlinc → D8 → sign.

```bash
# Requires JDK 17+ and Android SDK (platforms;android-34 / build-tools;34.0.0)
./build.sh            # -> dist/aegis-client.apk (debug signing)
# or, on Windows: build.bat (runs the same pipeline through WSL)
```

Release signing (keys are passed only via environment variables):

```bash
RELEASE_KEYSTORE=/path/to/release.keystore \
RELEASE_KEYSTORE_PASS=... \
RELEASE_KEY_ALIAS=... \
./build-apk.sh release   # -> dist/aegis-client-release.apk
```

A Gradle / Android Studio route (`app/build.gradle`, AGP 8.7.3) is also
kept for convenience.

## 🧪 Tests

```bash
./tests/run-tests.sh   # JVM unit tests for the pure data layer (87 tests)
```

CI runs the same unit tests only — deliberately lightweight.

## 📁 Layout

```
app/src/main/java/jp/aegis/client/
  MainActivity.kt     Screens (briefing / events / settings)
  Settings.kt         SharedPreferences wrapper (A.A.S config, sync, places)
  Sync.kt             Background sync, scheduling, notification posting
  SyncReceiver.kt     AlarmManager entry point
  BootReceiver.kt     Reschedules sync after reboot
  json/MiniJson.kt    Zero-dependency JSON parser
  a2s/A2sModels.kt    Event envelope + per-kind payloads, scale labels
  a2s/A2sParse.kt     JSON -> event conversion, validation, URL building
  a2s/EventStore.kt   Dedup / merge / release handling, since= tracking
  a2s/SseParser.kt    SSE frame parser + reconnect backoff
  a2s/DisplayText.kt  Titles / bodies / EEW action wording
  a2s/NotifyPlanner.kt Notification decisions (thresholds, freshness)
  a2s/A2sHttp.kt      REST fetch + foreground SSE client
  weather/WeatherParse.kt  JMA forecast / Open-Meteo parsing, places
tests/                JVM unit tests + run-tests.sh
```

## 🔗 Related

- A.A.S hub: https://github.com/aegis-app/aegis-alert-system
