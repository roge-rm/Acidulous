#!/bin/bash
# Put the debug build where Dan can fetch it, and leave nothing else behind.
#
# **One file: `acidulous-debug.apk`, overwritten.** Not a sha-named copy
# beside it. Dan asked for that on 2026-09-11, asked again on 2026-09-14 when
# the habit came back - "please just replace the acidulous-debug.apk without
# the extra commit-specific apk" - and on 2026-09-18 had to clear out the
# twenty-eight that had piled up since: "delete all the older debug files ...
# and every time you make a debug build now just delete the older ones".
#
# So this deletes any sha-named build it finds. What it does not touch is
# `audition/` next door: two gigabytes of rendered demos, which are the point
# of that folder and nothing to do with builds. The find below is maxdepth 1
# for exactly that reason.
#
#   tools/drop_debug.sh
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEST="${ACIDULOUS_DEBUG_DROP:-/srv/downloads/temp/debug}"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

[ -f "$APK" ] || { echo "drop: no debug apk at $APK - build one first" >&2; exit 1; }
mkdir -p "$DEST" || exit 1

find "$DEST" -maxdepth 1 -type f -name 'acidulous-debug-*.apk' -delete
cp "$APK" "$DEST/acidulous-debug.apk" || exit 1
echo "drop: $DEST/acidulous-debug.apk ($(cd "$ROOT" && git rev-parse --short HEAD))"
ls -la "$DEST"/*.apk
