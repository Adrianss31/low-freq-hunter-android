# Dashboard PC LFH — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Proposed execution: native, in the current chat.

**Goal:** Deliver the PC dashboard from the supplied prototype, backed by real Android data and usable when recording is stopped.

**Architecture:** Keep the existing NanoHTTPD server, Room data and vanilla JavaScript/canvas approach. A separate foreground service owns the optional LAN server, independently of microphone capture. Add bounded night queries and metadata; preserve existing APIs, recording formats and signing identity.

**Tech Stack:** Kotlin, Room, NanoHTTPD, Android foreground services; HTML/CSS, vanilla JavaScript and canvas; JUnit/Robolectric and Node test runner.

**Spec:** `docs/superpowers/specs/2026-10-04-dashboard-pc-design.md` (copia consultabile: `verifica-dashboard-pc.md`), approved by the user on 4 October 2026, together with `work/design_handoff_dashboard_pc/README.md` and `LFH Dashboard PC v4.dc.html`. The user approved HTTP with in-page alerts, missing-data handling outside the stored 20–200 Hz range, and keeping the dashboard accessible after recording stops.

## Global Constraints

- Replicate the supplied warm gray / dark inset-screen design, orange accent, Geist / Geist Mono / Doto typography and documented semantic animations.
- Page `#d9d7d0`, screen `#10100e`, accent `#ff5a1f`, missing-data warning `#ffaa00`, maximum content width 1560px.
- Canvas chart heights: live 132px, events 40px, spectrogram/profile 360px, levels 130px; timeline overview 30px when zoomed.
- Night windows are 21:00–09:00 in the phone’s timezone; include the current night plus 15 previous nights, with missing nights retained.
- Historical spectrogram: 64 stored bins, 20–200 Hz, approximately 30-second averages, quantization −110…−20 dBFS. No invented frequency or temporal resolution.
- Audio dBFS and vibration dB relative to 1 g remain distinct. Event durations must not double-count overlapping channels.
- HTTP with in-page alerts; no HTTPS setup, cloud service or desktop notification dependency.
- Respect `prefers-reduced-motion: reduce`; disable tweens and motion, retain functional feedback.
- Keep existing database contents, settings, token access, updater package and signing certificate compatible.
- Preserve existing PC marker, JSON/sample exports and advanced settings through secondary controls where the main prototype does not display them.
- Use existing local fonts; no font request to Google during dashboard use.
- Deliver verified changes on `main` and a signed installable GitHub release, following the project’s established release workflow.

## Review Focus

1. Different band settings within one night: use each session’s saved frequency/threshold; do not compare archived readings against today’s settings.
2. Midnight, daylight-saving changes and PC/phone timezone differences: use epoch timestamps and the phone’s timezone; preserve the actual elapsed night duration.
3. Connected HTTP server with stalled microphone: connection and data freshness are separate states; never report silence from a stale spectrum.
4. Gap, missing coverage and future time: represent them distinctly and exclude unavailable values from contrast, profiles and comparisons.
5. Out-of-order network responses and hot session restart: never replace the selected night with a stale response or duplicate samples/events.

---

### Task 1: Real night data and aggregate API

**Files:**
- Create `app/src/main/java/io/github/adrianss31/lowfreqhunter/server/NightData.kt`.
- Modify `data/Db.kt`, `server/LanServer.kt` in the same package root.
- Create `app/src/test/java/io/github/adrianss31/lowfreqhunter/NightDataTest.kt` and `LanServerTest.kt`.

**Interfaces:**
- `NightWindow.forDate(date: LocalDate, zone: ZoneId): NightWindow`, with epoch-second `from` and `to`.
- `NightData.load(date: LocalDate): NightPayload`, backed by range-limited DAO queries. Payload contains window/timezone, session IDs/config snapshots, events/gaps/notes/markers, compact stored slices with encoding metadata, summary and coverage.
- `NightData.summaries(anchor: LocalDate, count: Int): List<NightSummary>`, count capped at 16; include empty nights.
- `NightData.levels(date: LocalDate, from: Long, to: Long, cols: Int): LevelPayload`, resolution capped at 2000 columns, retaining per-channel mean and extrema and null coverage.
- GET `/api/nights?anchor=YYYY-MM-DD&count=16`, `/api/night?date=YYYY-MM-DD`, `/api/night/levels?date=YYYY-MM-DD&from=<epoch-s>&to=<epoch-s>&cols=<n>`, all require the existing token.

- [ ] Write failing tests for two overlapping events yielding their union duration; two sessions with changed thresholds; a missing night; a spring/autumn clock transition; slices overlapping a gap; hourly maxima containing a one-second peak.
- [ ] Run the focused tests and confirm the expected missing-interface/assertion failures before implementation.
- [ ] Implement range queries, night aggregation and hourly cache reuse. Keep config segments associated with their original session; distinguish audio from vibration in summary calculations.
- [ ] Add API validation tests for missing/wrong token, invalid date, reversed range, oversized column request and unknown night; implement bounded parsing and responses.
- [ ] Extend `/api/state` with `lastDataAt`, `timezone`, `charging`, `freeBytes`, optional measured storage estimate and optional next scheduled start. Read freshness from the actual spectrum timestamp, not HTTP response time.
- [ ] Run focused tests, inspect the diff and commit the verified unit.

### Task 2: Dashboard lifetime independent of recording

**Files:**
- Create `service/LanDashboardService.kt` and `app/src/test/java/io/github/adrianss31/lowfreqhunter/LanDashboardServiceTest.kt`.
- Modify `service/MonitorService.kt`, `MainActivity.kt`, `ui/SetupScreen.kt`, `service/MonitorBus.kt`, `data/Settings.kt`, `app/src/main/AndroidManifest.xml`.

**Interfaces:**
- `LanDashboardService.sync(context: Context)` starts the optional server only when LAN is enabled, from a permitted foreground entry point.
- `LanDashboardService.stop(context: Context)` stops the server and releases its resources when LAN is disabled.
- The service observes LAN/engine/calibration settings; it owns NanoHTTPD, Wi-Fi lock and its separate ongoing notification. `MonitorBus.state.lanUrl` reflects the service’s real availability.
- Use foreground-service type `connectedDevice` with `FOREGROUND_SERVICE_CONNECTED_DEVICE` and its network prerequisite `CHANGE_NETWORK_STATE` on API 34+, as documented for interactions with external devices over a network. It never opens the microphone. Reference: [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device).

- [ ] Write failing tests that a LAN-enabled stopped recorder remains readable, a disabled LAN service closes its socket, and restarting recording does not create a second server or reset the LAN URL.
- [ ] Verify the failures, then move server ownership out of `MonitorService`; preserve recorder draining and all recording locks/lifecycle behavior.
- [ ] Connect app foreground entry and Setup toggle to the dedicated service; stop it when disabled. Keep the service notification clear about PC access and recording status.
- [ ] Update Setup copy to explain that PC access also works after REC stops; do not display an active URL when server startup fails.
- [ ] Test destruction/restart/resource release, Android API 29 and 35 service configuration, and stale-spectrum handling after stop.
- [ ] Run focused tests and commit the verified unit.

### Task 3: Dashboard data model and HTTP integration

**Files:**
- Create `app/src/main/assets/dashboard-model.js` and `dashboard.js`.
- Create `tests/dashboard-model.test.cjs` and fixture data under `tests/fixtures/dashboard/`.

**Interfaces:**
- `LFHModel.decodeSlices(payload)`, preserving unavailable bins and interval metadata.
- `LFHModel.visibleContrast(slices, view): { min, max }`, visible-data percentiles 3 and 99.7, minimum span 18 dB.
- `LFHModel.clampView(target, bounds)`, minimum time zoom 120 seconds, frequency limits 0–250 Hz.
- `LFHModel.eventUnion(events, window)` and `LFHModel.comparison(nights, selected)`, excluding unrecorded nights; baseline from previous completed, comparable nights.
- `LFHModel.profile(slices, view, gaps)` computes a power-domain mean with valid coverage only.
- `dashboard.js` owns selected date, request cancellation/versioning, live polling, draft settings and alert preferences.

- [ ] Write failing model tests covering the five Review Focus cases, no-data auto contrast, cursor in an unavailable frequency, empty recorded night versus unrecorded night, and per-band hidden channels.
- [ ] Verify failures with `node --test tests/dashboard-model.test.cjs`.
- [ ] Implement the model and cancellable serialized polling; ignore outdated responses, keep archived selection during live session rollover, preserve last known data during an outage and show its age.
- [ ] Implement start/end/30-minute/60-second-outage/recording/battery alerts inside the page. First observation establishes a baseline; alerts are deduplicated, mute persists locally and unmuting does not replay past alerts.
- [ ] Make settings drafts independent of the latest live config. On save, refresh the latest full settings and merge only edited bands; show validation/network failures without discarding the draft.
- [ ] Run tests and commit the verified unit.

### Task 4: Faithful page and canvas interactions

**Files:**
- Replace `app/src/main/assets/dashboard.html`.
- Create `app/src/main/assets/dashboard.css`, `dashboard-render.js`, and local font assets.
- Extend `server/LanServer.kt` with a fixed allowlist for dashboard assets, guarded by the existing token.

**Interfaces:**
- `LFHRenderer.draw(frame)` draws live spectrum, aligned event lanes, spectrogram, frequency axis, profile, levels, overview and night heatmap.
- `LFHRenderer.setView(target, duration)` handles the shared time/frequency tween; durations 440/170/260/90ms for default/wheel/keyboard/overview drag.
- `LFHRenderer.setNight(payload, direction)` handles directional 660ms wipe and statistic transition; palette/contrast dissolve 240ms.
- Renderer consumes the Task 3 model and controller state; it does not fetch or alter stored measurements.

- [ ] Build the page from the approved design tokens and layout. Keep all prototype numeric examples out of the production page.
- [ ] Implement 0–250 Hz live spectrum with temporal smoothing, peak hold, band labels, threshold lines and collision avoidance.
- [ ] Draw stored spectrogram intervals on the true shared time axis; mark gaps, absent coverage and future separately. Display the historical resolution in a concise chart detail.
- [ ] Implement presets, palettes, auto/manual contrast, profile, hover/locked cursor, event selection, drag zoom, wheel zoom/pan, overview pan, keyboard navigation and reset.
- [ ] Implement the 16-row night diary, comparisons, reliability card, animated selection indicator, drawers, draft steppers, mute controls and toasts.
- [ ] Use transformed cached bitmaps during view motion and avoid expensive recomputation on every animation frame. Disable nonessential motion under reduced-motion preference.
- [ ] Verify in the browser against the supplied prototype at desktop width and a narrow browser window; verify drawer focus, keyboard shortcuts while editing fields, empty/error states and reduced motion.
- [ ] Commit the verified unit.

### Task 5: Exports and end-to-end regression checks

**Files:**
- Modify `server/LanServer.kt`, `server/NightData.kt`, `dashboard.js` and `README.md`.
- Extend `LanServerTest.kt` and dashboard model tests.
- Use an uncommitted local scenario server under the task’s `work/` directory for browser checks.

**Interfaces:**
- GET `/api/session/<id>/report.png` reuses `Exporter.reportPng`.
- Night export preserves constituent session identities; a composite night report must use the same clipped window, configs and summary as the dashboard.
- `PNG VISTA` exports the currently rendered spectrogram with frequency/time axes, displayed range and explicit dBFS units.
- `CSV` covers the selected night’s events, including session identity and preserved gap semantics; keep existing session exports accessible.

- [ ] Add failing tests for PNG content type/signature, selected-night clipping, CSV rows from multiple sessions, and empty-night export behavior.
- [ ] Verify failures, implement exports, and check that success toasts follow successful responses/blob creation.
- [ ] Exercise live, silence, offline, reconnect, stopped recording, unrecorded night, multi-session night and band-save failure in the browser. Verify that stale/out-of-order responses and polling failures do not break navigation.
- [ ] Run `node --test tests/*.test.cjs`, focused Android server/lifecycle tests and the full project-required Android tests/build/lint. Record unavailable device checks accurately.
- [ ] Document LAN behavior, in-page alert scope and historical frequency/resolution limits. Commit the verified unit.

### Task 6: Signed GitHub release

**Files:** `.github/workflows/build.yml` only if required to include missing release verification; otherwise retain existing tooling.

- [ ] Recheck GitHub `main`, latest release and remote changes; incorporate concurrent changes before final checks.
- [ ] Verify the final diff and requirement coverage. Use an independent review only if the user explicitly selects that execution method.
- [ ] Push the verified update directly to `main`; run the project’s required CI on that exact commit and wait for all required checks.
- [ ] Reuse the established signing identity and increasing version code. Verify package, version, signature, certificate compatibility and checksum of the installable APK.
- [ ] Publish a stable release with `lowfreqhunter.apk` through the existing updater channel; download the public asset and compare it with the verified signed APK.
- [ ] Deliver release/APK links, version and concise validation results. State that physical microphone, OEM background behavior and actual phone installation remain unverified unless tested on an available device.

## Handoff

This is a proposed implementation plan. Product implementation has not begun. Recommended execution is native in this chat because the API, data model and canvas interactions share interfaces and benefit from continuous integration checks.
