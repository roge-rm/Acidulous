#!/bin/bash
# Checks a macOS .dmg from `./gradlew :desktop:macosDmg`, on a Mac:
#
#   desktop/macos/check.sh build/macos/acidulous-<version>-macos-<arch>.dmg <arm64|x86_64> [folder for logs]
#
# - the app's signature is whole (ad hoc) and what Gatekeeper says about it
# - every library and program in it is for <arch> and runs on macOS 11
# - the bundled Java runs the engine: the demo song rendered offline
# - the app itself opens a window, with a screenshot in the log folder
# - its menu bar has the Acidulous, Edit and Window menus (if the runner allows UI scripting)
# - a .acpreset opened with `open -a` goes into the running copy
# - Quit, as from the Dock, saves the session's last edit
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
support="$HOME/Library/Application Support/Acidulous/data"
user="$support/user"
here=$(cd "$(dirname "$0")" && pwd)
# Quits an app the way the Dock's Quit does, through NSRunningApplication.
cat > "$out/quit.swift" <<'SWIFT'
import AppKit
let apps = NSRunningApplication.runningApplications(withBundleIdentifier: "com.rm.acidulous")
for app in apps { print("quit pid \(app.processIdentifier): \(app.terminate())") }
if apps.isEmpty { print("not running"); exit(1) }
SWIFT
xcrun swiftc -O -o "$out/quit" "$out/quit.swift"

"$app/Contents/MacOS/Acidulous" > "$logs/app.log" 2>&1 &
pid=$!
sleep 40
screencapture -x "$logs/screen.png" || true
if ! kill -0 "$pid" 2>/dev/null; then
    echo "the app quit by itself:"
    tail -40 "$logs/app.log"
    exit 1
fi
echo "ran 40 seconds; screenshot in $logs/screen.png"

echo "== menu bar"
# Needs the runner to allow UI scripting; says so if it doesn't.
if menus=$(perl -e 'alarm 30; exec @ARGV' osascript -e \
    "tell application \"System Events\" to get name of every menu bar item of menu bar 1 of (first process whose unix id is $pid)" 2>&1); then
    echo "menus: $menus"
    for m in Acidulous Edit Window; do
        case ", $menus," in *", $m,"*) ;; *) echo "no $m menu"; exit 1 ;; esac
    done
    items=$(perl -e 'alarm 30; exec @ARGV' osascript -e \
        "tell application \"System Events\" to get name of every menu item of menu 1 of menu bar item \"Edit\" of menu bar 1 of (first process whose unix id is $pid)" 2>&1 || true)
    echo "Edit: $items"
else
    echo "could not read the menu bar: $menus"
fi

echo "== open a preset"
rm -rf "$user/patches/Trinity"
open -a "$app" "$here/../../shared/src/desktopTest/resources/acpreset/app-bank.acpreset"
for _ in $(seq 60); do
    [ "$(ls "$user/patches/Trinity" 2>/dev/null | grep -c '\.json$')" -ge 2 ] && break
    sleep 0.5
done
ls "$user/patches/Trinity"
[ "$(ls "$user/patches/Trinity" | grep -c '\.json$')" -ge 2 ] || { echo "the preset wasn't imported"; tail -30 "$logs/app.log"; exit 1; }
kill -0 "$pid" || { echo "a second copy took over"; exit 1; }
echo "imported into the running copy"

echo "== quit saves the session"
# A song bundle of the session under a new name. Opening it changes the
# song, and quitting a second later, well inside the autosave's 5 s, must
# still leave it in the session.
python3 - "$user/session.json" "$out/Check quit.zip" <<'PY'
import json, sys, zipfile
song = json.load(open(sys.argv[1]))
song["name"] = "Check quit"
with zipfile.ZipFile(sys.argv[2], "w") as z:
    z.writestr("song.json", json.dumps(song))
PY
rm -f "$user/songs/Check quit.json"
open -a "$app" "$out/Check quit.zip"
for _ in $(seq 60); do
    [ -f "$user/songs/Check quit.json" ] && break
    sleep 0.25
done
[ -f "$user/songs/Check quit.json" ] || { echo "the song bundle wasn't opened"; tail -30 "$logs/app.log"; exit 1; }
sleep 1
"$out/quit"
for _ in $(seq 60); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
done
if kill -0 "$pid" 2>/dev/null; then
    echo "the app didn't quit"
    kill -9 "$pid" || true
    exit 1
fi
tail -15 "$logs/app.log"
name=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["name"])' "$user/session.json")
echo "session after quitting: $name"
[ "$name" = "Check quit" ] || { echo "the last edit wasn't saved on quitting"; exit 1; }

crashes="$support/crashes"
if ls "$crashes"/hs_err_*.log >/dev/null 2>&1; then
    echo "the JVM crashed:"
    head -40 "$crashes"/hs_err_*.log
    exit 1
fi
echo "all checks passed"
