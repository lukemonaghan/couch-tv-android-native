#!/bin/sh
# Sends one optional setting to tvhome over ADB, so nobody types a token or a link with the remote.
# Nothing is kept in this repository: the TV keeps what it is sent in the app's own private storage.
#
#   TV_IP=<tv-ip> PY=/path/to/python sh tools/send-config.sh <what> <value>
#
#   ha <file.json>      the smart home section (Home Assistant address, token, rooms, scenes, doorbell).
#                       See HomeAssistant.kt for its shape. The file is read here and sent; it is never printed.
#   ha none             turns smart home (and the doorbell) off and forgets the token
#   cards <https-link>  a JSON file of your own cards for the end of Keep watching ("" removes them)
#   repo <owner/name>   where updates come from on GitHub ("" turns updates off)
#   auto true|false     the weekly look for new versions
#
# The doorbell card also needs "display over other apps": DOORBELL=1 sh tools/install.sh grants it.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
PY=${PY:-python3}
T="$HERE/tvadb.py"
: "${TV_IP:?set TV_IP to the TV address}"
export TV_IP
WHAT=${1:?what to send: ha, cards, repo or auto}
VALUE=${2-}
APP=com.lukemonaghan.tvhome
START="am start -n $APP/.MainActivity"
case "$WHAT" in
  ha)
    if [ "$VALUE" = none ]; then EXTRA="--es ha_b64 none"
    else
      [ -f "$VALUE" ] || { echo "no such file: $VALUE"; exit 1; }
      EXTRA="--es ha_b64 $(base64 < "$VALUE" | tr -d '\n')"
    fi ;;
  cards) EXTRA="--es cards_url '$VALUE'" ;;
  repo) EXTRA="--es update_repo '$VALUE'" ;;
  auto) EXTRA="--es update_auto $VALUE" ;;
  *) echo "unknown setting: $WHAT"; exit 1 ;;
esac
"$PY" "$T" ping || { echo "cannot reach the TV at $TV_IP"; exit 1; }
"$PY" "$T" shell "$START $EXTRA" > /dev/null
echo "sent $WHAT"
