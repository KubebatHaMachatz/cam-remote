#!/usr/bin/env bash
# Walks through the three assignment features, in the order it lists them.
#
# Takes the agent's address -- the one its notification shows -- as its only argument.
# Photos land in ./shots.
#
#     ./scripts/demo.sh 10.0.0.4
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "usage: $(basename "$0") <agent-address>    e.g. $(basename "$0") 10.0.0.4:8099" >&2
    exit 2
fi

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
camremote=("$here/scripts/camremote" --host "$1")

echo "== the device =="
"${camremote[@]}" status

echo
echo "== 3. fetch device properties =="
"${camremote[@]}" getprop ro.product.model ro.product.manufacturer ro.build.version.release

echo
echo "== 1. open the camera app =="
"${camremote[@]}" open-camera
sleep 4

echo
echo "== 2. capture with the rear camera =="
# The camera app opened above is holding the sensor; give it back first.
echo "   (close the camera app on the device, then press Enter)"
read -r _
"${camremote[@]}" take-picture --out "$here/shots"

echo
echo "== the catalog this agent supports =="
"${camremote[@]}" commands
