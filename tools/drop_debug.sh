#!/bin/bash
# Copies the debug build to the downloads folder as `acidulous-debug.apk`,
# overwriting the old one, and deletes any older commit-named copies.
#
# The find is maxdepth 1 so it doesn't touch the `audition/` folder next to it.
#
#   tools/drop_debug.sh
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEST="${ACIDULOUS_DEBUG_DROP:-/srv/downloads/temp/debug}"
# The build output is under acidulous.buildRoot if it's set (see the root
# build.gradle.kts), otherwise app/build.
BUILD_ROOT=$(sed -n 's/^acidulous\.buildRoot=//p' "$HOME/.gradle/gradle.properties" 2>/dev/null | tail -1)
APP_BUILD="${BUILD_ROOT:+$BUILD_ROOT/$(basename "$ROOT")/app}"
APK="${APP_BUILD:-$ROOT/app/build}/outputs/apk/debug/app-debug.apk"

[ -f "$APK" ] || { echo "drop: no debug apk at $APK - build one first" >&2; exit 1; }
mkdir -p "$DEST" || exit 1

find "$DEST" -maxdepth 1 -type f -name 'acidulous-debug-*.apk' -delete
cp "$APK" "$DEST/acidulous-debug.apk" || exit 1
echo "drop: $DEST/acidulous-debug.apk ($(cd "$ROOT" && git rev-parse --short HEAD))"
ls -la "$DEST"/*.apk
