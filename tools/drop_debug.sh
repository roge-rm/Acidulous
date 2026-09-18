#!/bin/bash
# Put a debug build where Dan can fetch it, and take the old ones away.
#
# Two copies land: a stable `acidulous-debug.apk`, which is the one to grab
# when you just want "the current build", and a `-<sha>-<label>.apk` so a
# particular build can be named in conversation and found again afterwards.
#
# **Every drop deletes the sha-named builds that came before it.** Twenty-eight
# of them had accumulated at about 22 MB each, and a folder of near-identical
# APKs named after commits nobody remembers is not an archive, it is a pile.
# The stable copy is kept, and so is `audition/` next door - that is two
# gigabytes of rendered demos, which are the *point* of the folder and are
# nothing to do with builds.
#
#   tools/drop_debug.sh <label>
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEST="${ACIDULOUS_DEBUG_DROP:-/srv/downloads/temp/debug}"
LABEL="${1:-build}"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

[ -f "$APK" ] || { echo "drop: no debug apk at $APK - build one first" >&2; exit 1; }
mkdir -p "$DEST" || exit 1

SHA=$(cd "$ROOT" && git rev-parse --short HEAD)
NAME="acidulous-debug-$SHA-$LABEL.apk"

# Out with the old first, so a drop never leaves two generations behind even
# if the copy below fails. Only sha-named APKs directly in DEST: no recursion,
# so audition/ is not reachable from here however this is called.
find "$DEST" -maxdepth 1 -type f -name 'acidulous-debug-*.apk' ! -name "$NAME" -delete

cp "$APK" "$DEST/$NAME" && cp "$APK" "$DEST/acidulous-debug.apk" || exit 1
echo "drop: $DEST/$NAME"
ls -la "$DEST"/*.apk
