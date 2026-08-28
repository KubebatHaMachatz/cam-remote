#!/usr/bin/env bash
# Walks through every capability, in the order the assignment lists them.
#
# Assumes `camremote pair` has already been run. Photos land in ./shots.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
camremote="$here/scripts/camremote"

echo "== the device =="
"$camremote" status

echo
echo "== 3. fetch device properties =="
"$camremote" getprop ro.product.model ro.product.manufacturer ro.build.version.release

echo
echo "== 1. open the camera app =="
"$camremote" open-camera
sleep 4

echo
echo "== 2. capture with the rear camera =="
# The camera app opened above is holding the sensor; give it back first.
echo "   (close the camera app on the device, then press Enter)"
read -r _
"$camremote" take-picture --out "$here/shots"

echo
echo "== the catalog this agent supports =="
"$camremote" commands
