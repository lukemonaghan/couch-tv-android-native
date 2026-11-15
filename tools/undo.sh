#!/bin/sh
# Takes tvhome off a TV: switches the Home button watch off, takes the grants back and puts the old home back.
#
#   TV_IP=<tv-ip> PY=/path/to/python sh tools/undo.sh        # keep the app installed
#   TV_IP=... UNINSTALL=1 sh tools/undo.sh                        # and remove the app
#
# Nothing else was changed by install.sh, so nothing else needs undoing.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
PY=${PY:-python3}
T="$HERE/tvadb.py"
: "${TV_IP:?set TV_IP to the TV address}"
export TV_IP
TV_ID=${TV_ID:-$(echo "$TV_IP" | tr '.:' '--')}
APP=com.lukemonaghan.tvhome
PREV="$HERE/.previous-home.$TV_ID"
"$PY" "$T" ping || { echo "cannot reach the TV at $TV_IP"; exit 1; }

echo "1/3  Home button watch off"
CUR=$("$PY" "$T" shell "settings get secure enabled_accessibility_services" | tr -d '\r')
REST=$(echo "$CUR" | tr ':' '\n' | grep -v -e "$APP" -e '^null$' -e '^$' | paste -sd: - || true)
if [ -n "$REST" ]; then "$PY" "$T" shell "settings put secure enabled_accessibility_services $REST" > /dev/null
else "$PY" "$T" shell "settings delete secure enabled_accessibility_services" > /dev/null
     "$PY" "$T" shell "settings put secure accessibility_enabled 0" > /dev/null; fi

echo "2/3  Taking the grants back"
"$PY" "$T" shell "appops set $APP WRITE_SETTINGS default" > /dev/null 2>&1 || true
"$PY" "$T" shell "appops set $APP SYSTEM_ALERT_WINDOW default" > /dev/null 2>&1 || true
"$PY" "$T" shell "cmd notification disallow_listener $APP/$APP.MediaListener" > /dev/null 2>&1 || true
"$PY" "$T" shell "pm revoke $APP android.permission.WRITE_SECURE_SETTINGS" > /dev/null 2>&1 || true
"$PY" "$T" shell "pm revoke $APP android.permission.READ_TV_LISTINGS" > /dev/null 2>&1 || true

echo "3/3  Putting the old home back"
if [ -s "$PREV" ] && [ "$(cat "$PREV")" != "$APP/.MainActivity" ]; then
  "$PY" "$T" shell "cmd package set-home-activity $(cat "$PREV")" > /dev/null && echo "     home is $(cat "$PREV") again"
else
  echo "     no record of an earlier home: pick one in the TV's Settings, Apps, Default apps (or press Home and choose)."
  "$PY" "$T" shell "cmd package set-home-activity com.google.android.tvlauncher/.MainActivity" > /dev/null 2>&1 || true
fi
if [ "${UNINSTALL:-0}" = 1 ]; then "$PY" "$T" shell "pm uninstall $APP" && echo "removed the app"; fi
"$PY" "$T" keys HOME || true
echo "done"
