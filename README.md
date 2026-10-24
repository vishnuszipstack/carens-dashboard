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

`car/DashCarAppService.kt` adds a template-based car screen: live GPS speed, trip distance and
duration, moving time, average/max speed, heading and elevation, plus a Start/End trip button. It
uses the same `TelemetryHub` data as the phone, and opening it on the car starts GPS recording
even if the phone app wasn't opened.

Android Auto only lets apps use fixed templates, so it's a text read-out, not a gauge. It
refreshes about every 2 seconds. Row titles stay fixed so refreshes don't use up Android Auto's
template quota.

Because the app isn't from the Play Store, Android Auto hides it until you allow unknown sources:

1. On the phone: Settings → Connected devices → Android Auto (on Vivo, search "Android Auto").
2. Scroll to the bottom and tap **Version** about 10 times, then accept to enable developer mode.
3. Top-right ⋮ menu → **Developer settings** → turn on **Unknown sources**.
4. Reconnect to the car. "Carens Dashboard" appears in the app launcher on the car screen.

It's declared as a POI-category app. That's fine for sideloading but wouldn't pass Play Store review.

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
