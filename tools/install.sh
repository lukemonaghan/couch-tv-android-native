#!/bin/sh
# Puts tvhome on a TV and makes it the home. Changes only what is listed below; switches nothing off.
#
#   TV_IP=<tv-ip> PY=/path/to/python sh tools/install.sh [path/to/app.apk]
#
# PY is a Python 3.11+ with the `adb-shell` and `cryptography` packages (couch-tv's .venv has them).
# ADB_PORT is 5555 unless set (see tools/tvadb.py). The TV must have USB debugging on and have allowed this computer.
#
# What it does, in order:
#   1. installs the app
#   2. grants it: Watch Next (Keep watching), secure settings (so the Home button watch can switch itself on),
#      and "modify system settings" (the sleep timer)
#   3. writes down the TV's current home in tools/.previous-home.<TV_ID>, so undo.sh can put it back
#   4. makes tvhome the home
#   5. on a Google TV or Android TV with its own home, switches the Home button watch on (an accessibility
#      service that only looks at which window came to the front, and puts tvhome back on top)
# Undo: sh tools/undo.sh
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(dirname "$HERE")
PY=${PY:-python3}
T="$HERE/tvadb.py"
: "${TV_IP:?set TV_IP to the TV address}"
export TV_IP
TV_ID=${TV_ID:-$(echo "$TV_IP" | tr '.:' '--')}
APK=${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}
APP=com.lukemonaghan.tvhome
HOME_ACT=$APP/.MainActivity
WATCH=$APP/$APP.FireWatch
PREV="$HERE/.previous-home.$TV_ID"

[ -f "$APK" ] || { echo "no app at $APK: build it first (./gradlew assembleDebug)"; exit 1; }
"$PY" "$T" ping || { echo "cannot reach the TV at $TV_IP"; exit 1; }

echo "1/5  Installing tvhome"
"$PY" "$T" install "$APK"

echo "2/5  Granting Watch Next, secure settings, system settings and music access"
"$PY" "$T" shell "pm grant $APP android.permission.READ_TV_LISTINGS" > /dev/null 2>&1 || true
"$PY" "$T" shell "pm grant $APP android.permission.WRITE_SECURE_SETTINGS" > /dev/null 2>&1 || true
"$PY" "$T" shell "appops set $APP WRITE_SETTINGS allow" > /dev/null 2>&1 || true
# Now playing (media sessions) needs notification access; the service reads no notifications (see MediaListener.kt)
"$PY" "$T" shell "cmd notification allow_listener $APP/$APP.MediaListener" > /dev/null 2>&1 || true
# the doorbell card (smart home) is shown over other apps: only when asked for with DOORBELL=1
if [ "${DOORBELL:-0}" = 1 ]; then "$PY" "$T" shell "appops set $APP SYSTEM_ALERT_WINDOW allow" > /dev/null 2>&1 || true; fi

echo "3/5  Writing down the current home"
NOW=$("$PY" "$T" shell "cmd shortcut get-default-launcher" | tr -d '\r' | grep -m1 Launcher || true)
case "$NOW" in
  *$APP/*) ;;   # already ours: keep the earlier record
  *) echo "$NOW" | sed 's/.*Launcher: ComponentInfo{\(.*\)}.*/\1/' > "$PREV"; echo "     was: $(cat "$PREV")" ;;
esac

echo "4/5  Making tvhome the home"
"$PY" "$T" shell "cmd package set-home-activity $HOME_ACT" > /dev/null
NOW=$("$PY" "$T" shell "cmd shortcut get-default-launcher" | tr -d '\r' | grep -m1 Launcher || true)
case "$NOW" in
  *$APP/*) echo "     the Home key opens tvhome" ;;
  *) echo "     the TV kept its own home as the one the Home key opens ($NOW)."
     echo "     That is normal on some Google TVs: the Home button watch brings tvhome back instead." ;;
esac

echo "5/5  Home button watch"
present=$("$PY" "$T" shell "pm list packages" | sed 's/package://' | tr -d '\r')
if echo "$present" | grep -qx -E "com.google.android.apps.tv.launcherx|com.google.android.tvlauncher|com.amazon.tv.launcher"; then
  "$PY" "$T" shell "am force-stop $APP; am start -n $HOME_ACT" > /dev/null
  CUR=$("$PY" "$T" shell "settings get secure enabled_accessibility_services" | tr -d '\r')
  REST=$(echo "$CUR" | tr ':' '\n' | grep -v -e "$APP" -e '^null$' -e '^$' | paste -sd: - || true)
  # take tvhome's entries out and put the watch back, so Android binds it afresh after the force-stop
  if [ -n "$REST" ]; then "$PY" "$T" shell "settings put secure enabled_accessibility_services $REST" > /dev/null
  else "$PY" "$T" shell "settings delete secure enabled_accessibility_services" > /dev/null; fi
  sleep 2
  if [ -n "$REST" ]; then NEW="$REST:$WATCH"; else NEW=$WATCH; fi
  "$PY" "$T" shell "settings put secure enabled_accessibility_services $NEW"
  "$PY" "$T" shell "settings put secure accessibility_enabled 1"
  echo "     on (the TV's own home may flash up for a moment, then tvhome comes back)"
else
  echo "     not needed on this TV"
  "$PY" "$T" shell "am start -n $HOME_ACT" > /dev/null
fi
"$PY" "$T" keys HOME || true
echo "done. To undo: TV_IP=$TV_IP PY=$PY sh tools/undo.sh"
