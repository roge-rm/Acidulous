#!/bin/bash
# Checks a macOS .dmg from `./gradlew :desktop:macosDmg`, on a Mac:
#
#   desktop/macos/check.sh build/macos/acidulous-<version>-macos-<arch>.dmg <arm64|x86_64> [folder for logs]
#
# - the app's signature is whole (ad hoc) and what Gatekeeper says about it
# - every library and program in it is for <arch> and runs on macOS 11
# - the bundled Java runs the engine: the demo song rendered offline
# - the app itself opens a window, with a screenshot in the log folder
set -euo pipefail
dmg=$1
arch=$2
logs=${3:-$(mktemp -d)}
mkdir -p "$logs"
mount=$(mktemp -d)
hdiutil attach -nobrowse -readonly -mountpoint "$mount" "$dmg" >/dev/null
trap 'hdiutil detach "$mount" -force >/dev/null 2>&1 || true' EXIT
app="$mount/Acidulous.app"

echo "== signature"
codesign -dv "$app" 2>&1 | grep -E "Identifier|Format|Signature|CodeDirectory"
codesign -dv "$app" 2>&1 | grep -q "Signature=adhoc"
codesign --verify --deep --strict --verbose=2 "$app"
# Ad hoc isn't notarised, so Gatekeeper is expected to say "rejected" here.
spctl --assess --type execute -vv "$app" 2>&1 || true

echo "== architecture and minimum macOS"
count=0
while IFS= read -r -d '' f; do
    file -b "$f" | grep -q "Mach-O" || continue
    count=$((count + 1))
    archs=$(lipo -archs "$f")
    if [ "$archs" != "$arch" ]; then echo "wrong architecture ($archs): $f"; exit 1; fi
    # LC_BUILD_VERSION's minos, or LC_VERSION_MIN_MACOSX's version on older tools.
    min=$(otool -l "$f" | awk '/LC_BUILD_VERSION/{b=1} b&&/minos/{print $2; exit} /LC_VERSION_MIN_MACOSX/{v=1} v&&/ version/{print $2; exit}')
    major=${min%%.*}
    if [ -z "$min" ] || [ "$major" -gt 11 ]; then echo "needs macOS $min: $f"; exit 1; fi
    codesign --verify --strict "$f"
done < <(find "$app" -type f -print0)
echo "$count binaries, all $arch, all macOS 11 or older, all signed"

echo "== engine"
out=$(mktemp -d)
"$app/Contents/runtime/Contents/Home/bin/java" -version 2>&1 | head -3
"$app/Contents/runtime/Contents/Home/bin/java" \
    "-Djava.library.path=$app/Contents/app" --enable-native-access=ALL-UNNAMED \
    "-DengineLines.out=$out" -cp "$app/Contents/app/lib/*" \
    com.rm.acidulous.desktop.EngineLinesToolKt squelch 8 > "$logs/engine.log" 2>&1 || { tail -30 "$logs/engine.log"; exit 1; }
tail -2 "$logs/engine.log"
wav="$out/squelch-app.wav"
size=$(stat -f %z "$wav")
echo "rendered $wav, $size bytes"
# 8 seconds plus a 2 second tail of 32-bit float stereo at 48 kHz is about 3.8 MB.
[ "$size" -gt 1000000 ]

echo "== app"
"$app/Contents/MacOS/Acidulous" > "$logs/app.log" 2>&1 &
pid=$!
sleep 40
screencapture -x "$logs/screen.png" || true
if ! kill -0 "$pid" 2>/dev/null; then
    echo "the app quit by itself:"
    tail -40 "$logs/app.log"
    exit 1
fi
kill "$pid" || true
sleep 2
kill -9 "$pid" 2>/dev/null || true
tail -15 "$logs/app.log"
crashes="$HOME/Library/Application Support/Acidulous/data/crashes"
if ls "$crashes"/hs_err_*.log >/dev/null 2>&1; then
    echo "the JVM crashed:"
    head -40 "$crashes"/hs_err_*.log
    exit 1
fi
echo "ran 40 seconds; screenshot in $logs/screen.png"
