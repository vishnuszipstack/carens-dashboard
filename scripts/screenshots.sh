#!/usr/bin/env bash
# Captures screenshots of the app on an emulator while simulating a drive
# through Thrissur along a real OSRM route. Run from the repo root with the
# debug APK already built and an emulator connected.
set -u
PKG=dev.carens.dashboard
ACT="$PKG/.MainActivity"
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=shots
mkdir -p "$OUT"

# Route: Swaraj Round (east) -> Thrissur railway station.
START_LAT=10.52770; START_LON=76.21690
DEST_LAT=10.51460;  DEST_LON=76.20990
DEST_NAME="Thrissur Railway Station"

adb install -r -g "$APK"
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell cmd notification allow_listener $PKG/$PKG.info.MusicListenerService || true
adb shell settings put secure location_mode 3 || true
# Pre-dismiss Android's one-time "Viewing full screen" hint so it isn't in the shots.
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1   # landscape, like a car mount

# Build a drive along the route at ~40 km/h, one point per second.
curl -s "https://router.project-osrm.org/route/v1/driving/$START_LON,$START_LAT;$DEST_LON,$DEST_LAT?overview=full&geometries=geojson" > route.json
python3 - <<'PY'
import json, math
r = json.load(open("route.json"))["routes"][0]["geometry"]["coordinates"]
def dist(a, b):
    k = math.cos(math.radians(a[1]))
    return math.hypot((b[0]-a[0])*k*111320, (b[1]-a[1])*111320)
speed = 11.0  # m/s
pts = [r[0]]
carry = 0.0
for a, b in zip(r, r[1:]):
    d = dist(a, b)
    pos = speed - carry
    while pos <= d:
        t = pos / d
        pts.append([a[0] + (b[0]-a[0])*t, a[1] + (b[1]-a[1])*t])
        pos += speed
    carry = d - (pos - speed)
# Sit still for 5 s first, then drive; loop the drive so it lasts the whole run.
with open("drive.txt", "w") as f:
    for _ in range(5):
        f.write(f"{r[0][0]} {r[0][1]} 0\n")
    for _ in range(4):
        for p in pts[1:]:
            f.write(f"{p[0]} {p[1]} 21.4\n")   # knots ~= 40 km/h
        for p in reversed(pts[:-1]):
            f.write(f"{p[0]} {p[1]} 21.4\n")
print(len(pts), "points per leg")
PY

( while read -r lon lat knots; do
    adb emu geo fix "$lon" "$lat" 20 8 "$knots" >/dev/null 2>&1
    sleep 1
  done < drive.txt ) &
DRIVE_PID=$!

shot() {
  sleep "$2"
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "captured $1"
}
open() {
  adb shell am start -n "$ACT" "$@" >/dev/null
}

open --es screen HOME
shot 01-home 40            # GPS fix, auto trip start, weather, map tiles
open --es screen MAP
shot 02-map 10
open --es screen MAP --es dest_lat "$DEST_LAT" --es dest_lon "$DEST_LON" --es dest_name "$DEST_NAME"
shot 03-route-preview 15
open --es screen MAP --es dest_lat "$DEST_LAT" --es dest_lon "$DEST_LON" --es dest_name "$DEST_NAME" --es autostart true
shot 04-navigating 20
open --es screen HOME
shot 05-home-navigating 8
open --es screen CAR
shot 06-android-auto-preview 10
open --es screen MUSIC
shot 07-music 5
open --es screen APPS
shot 08-apps 6
open --es screen MORE
shot 09-more 4
open --es screen TRIPS
shot 10-trips-stats 4
open --es screen FUEL
shot 11-fuel-log 4
open --es screen SETTINGS
shot 12-settings 4

adb shell settings put system user_rotation 0   # portrait
open --es screen HOME
shot 13-home-portrait 8
open --es screen MAP
shot 14-map-portrait 6

kill $DRIVE_PID 2>/dev/null || true
adb logcat -d -s CarensLaunch CarensNav AndroidRuntime > "$OUT/logcat.txt" 2>&1 || true
ls -la "$OUT"
