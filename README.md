# Carens Dashboard

Phone-only driving dashboard for a Kia Carens — the P0 scope of the spec. GPS speed and trip
statistics, no OBD-II or extra hardware, and nothing about the engine is ever guessed.

## Build and run

1. Open the `CarensDashboard` folder in Android Studio (Narwhal / 2025.1 or newer).
2. Let Gradle sync (it downloads AGP 8.12, Kotlin 2.2 and the AndroidX libraries).
3. Enable USB debugging on the phone, plug it in, press **Run**.

Command line: `./gradlew installDebug`, and `./gradlew testDebugUnitTest` for the unit tests.

Requires Google Play services on the phone (fused location provider). Android 8.0+ (API 26).

## What's in this build

| Spec item (P0) | Where |
|---|---|
| GPS speedometer, km/h ↔ mph | `ui/DashboardScreen.kt` (`SpeedHero`), `settings/Settings.kt` |
| Accuracy + stale-data handling | `service/DrivingService.kt` → `publish()`; speed is hidden after 4 s without a fix |
| Trip distance / duration / moving time | `telemetry/TripTracker.kt` |
| Average (moving) and max speed | `telemetry/TripTracker.kt` |
| Landscape + portrait | `DashboardScreen` switches layout on aspect ratio |
| Dark driving theme, auto at night | `ui/Theme.kt` |
| Automatic trip start/stop | `telemetry/AutoTripDetector.kt` (start: ~5 s above 10 km/h; stop: 5 min idle) |
| Manual trip controls | Start / End trip on the dashboard and in the notification |
| Google Maps handoff | "Maps" button opens Google Maps at your position |
| Persistent settings | DataStore (`settings/Settings.kt`) |
| Privacy-conscious location | Foreground-only location, local Room DB, delete one/all trips, no backup |

Also included from P1 because they were cheap: the semicircular gauge arc, the overspeed
threshold (number turns red — no sound, no flashing), GPS quality badge, and trip history.

### Data flow

```
FusedLocationProvider ─▶ DrivingService (foreground, type=location)
                           ├─ SpeedFilter        → smoothed display speed
                           ├─ TripTracker        → distance, moving time, max/avg
                           ├─ AutoTripDetector   → start/stop decisions
                           ├─ Room (trips)       → saved every 30 s and at trip end
                           └─ TelemetryHub (StateFlow<DashboardTelemetry>)
                                                   └─ Compose UI (and later, Android Auto)
```

`TripTracker` and `AutoTripDetector` are plain Kotlin with no Android types, so a future
Android Auto screen can consume the same `TelemetryHub` flow.

### How the "never fabricate" rule is enforced

- Speed is `null` (shown as `--`) when there's no fix or the last fix is older than 4 s.
- Distance only counts fixes with ≤ 25 m accuracy; parked GPS wander is ignored; implausible
  jumps (> 250 km/h implied) are dropped.
- GPS outages over 2 minutes are **not** bridged — no distance is invented across them.
  Shorter ones (tunnels) are bridged with the straight-line distance, which can only under-count.
  Trips with ≥ 1 min of GPS loss are flagged in history.
- Elevation uses mean-sea-level altitude from Android 14's `AltitudeConverter` (the X300 Pro
  qualifies). Raw GNSS altitude is height above the ellipsoid — roughly 90 m off around Kerala —
  so on older Android versions the tile shows "n/a" instead.
- ECU slots (RPM, coolant, fuel) are off by default; when turned on they say "Not available".
  `VehicleReading` carries a source and timestamp so a future data source can't slip in
  unlabelled values.

### Service lifetime

The foreground service runs while the dashboard is on screen. If a trip is recording it keeps
going with the screen off or with Maps in front, until the trip ends. If you leave the app with
no trip running, it waits 10 minutes for a drive to start, then stops. The notification always
shows when GPS is in use and has **End trip** / **Exit** actions. No background-location
permission is needed.

## Android Auto

Android Auto 17.7+ no longer shows **sideloaded template apps**, even with "Unknown sources" on.
It still shows sideloaded **media apps**, so the car view is a media app
(`car/DashMediaService.kt`):

- **Now playing** shows a drawn speedometer as the artwork. The title is speed, the subtitle is
  trip distance · duration, and the third line is avg/max speed or GPS status.
- **Start trip / End trip** appear as custom buttons. Play opens the dashboard; Pause hands
  Android Auto's now-playing widget back to your music. No audio is played and audio focus is
  never taken.
- **Browse** has "Live dashboard" and "Recent trips" (last 20).
- Opening it on the car starts GPS/trip recording. If Android refuses to start GPS from the
  background, it shows "Open app on phone". Open the phone app once and it continues.

Setup on the phone (once): Android Auto settings → tap **Version** ~10× → ⋮ → Developer settings
→ **Unknown sources** on. Then reconnect. *Carens Dashboard* appears with the media apps. Check
**Customize launcher** if it doesn't.

The template screen (`car/DashCarScreen.kt`) is kept. It only appears if the app is ever
installed from the Play Store.

## All-in-one dashboard (build 6+)

**Phone** (full screen by default; swipe from the edge to show the system bars):
speed gauge, trip stats, live OpenStreetMap that follows the car with a heading arrow, area name,
weather (Open-Meteo, no key) and what's playing in any music app with ⏮ ⏯ ⏭ controls.
Music needs **notification access**: tap *Allow notification access* on the music card. That's
how Android lets an app read other apps' media sessions; notifications themselves are ignored.

**Car** (media app): the now-playing picture packs the speed gauge, a dark mini-map with heading
arrow, the clock, weather, trip, area name and current song. Play/pause/skip control your music
app. The first *play* only opens the dashboard. The flag button starts a trip, the square ends it.
The browse view is a full-width info panel (speed/GPS, trip, weather, place, music) plus recent
trips.

A sideloaded media app can't be truly full screen on Android Auto or show an interactive map.
That needs a navigation-category car app, which Android Auto only shows when installed from
the Play Store.

## Updates

Settings → **App updates** → *Check for updates* reads the latest GitHub release of
`vishnuszipstack/carens-dashboard` (tag `build-N`), compares N with the installed build, and
downloads and installs the APK. Android asks once to allow installs from this app.

Every push to `main` builds a new release. All builds are signed with `signing/carens.jks`
(committed on purpose so every CI build has the same key and installs as an update). Anyone with
the repo could sign an APK with it. That only matters if someone gets you to install their APK,
since the updater only downloads from this repo. For a Play Store release, move to a private key
held in GitHub secrets.

## Not in this build

- **Next turn / ETA / remaining distance.** Google Maps doesn't expose its route to other apps.
  The fields exist in `DashboardTelemetry` (always null) for a future in-app route source.
- **Embedded mini-map.** Needs a Google Maps SDK API key and billing setup; the Maps handoff
  covers P0.
- P1/P2 items: customisable cards, CSV/JSON export, fuel log, widgets, multiple vehicles.

## Things to test on the phone

- Parked: speed should read 0 and trip distance shouldn't creep.
- Rotate mid-trip: stats survive (they live in the service, not the screen).
- Open Maps from the dashboard and drive: the trip should auto-start in the background.
- Tunnel or multi-level parking: speed shows `--` with "GPS signal lost", then recovers.
- Vivo's battery manager may kill background apps aggressively. If trips stop recording with
  the screen off, set the app's battery usage to *Unrestricted* in system settings.
