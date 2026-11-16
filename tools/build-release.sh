#!/bin/bash
# Builds every file of an Acidulous release from the committed tree.
# Usage: tools/build-release.sh VERSION. Output goes in ~/Projects/releases/acidulous-VERSION.
set -euo pipefail
V=${1:?version}
REPO=/home/dan/Projects/AndroidStudioProjects/Acidulous
OUT=/home/dan/Projects/releases/acidulous-$V
mkdir -p "$OUT"
cd "$REPO"
ROOT=$REPO
. tools/build_dir.sh
APP=$(build_dir app app) DESKTOP=$(build_dir desktop desktop) WEB=$(build_dir webApp web/app)
[ -z "$(git status --porcelain | grep -v -e '^?? core\.' -e '^?? tools/build-release.sh')" ] || { echo "tree not clean"; exit 1; }
grep -q "versionName = \"$V\"" app/build.gradle.kts || { echo "version is not $V"; exit 1; }
heavy build ./gradlew clean :app:assembleRelease
cp "$APP"/outputs/apk/release/app-release.apk "$OUT/acidulous-$V.apk"
heavy build ./gradlew :app:assembleRelease -Parm32
cp "$APP"/outputs/apk/release/app-release.apk "$OUT/acidulous-$V-32bit.apk"
heavy build ./gradlew :desktop:debAmd64 :desktop:debArm64 :desktop:appImageAmd64 :desktop:appImageArm64 :desktop:windowsX64
cp "$DESKTOP"/deb/acidulous_${V}_amd64.deb "$DESKTOP"/deb/acidulous_${V}_arm64.deb "$OUT/"
cp "$DESKTOP"/appimage/acidulous-$V-x86_64.AppImage "$DESKTOP"/appimage/acidulous-$V-aarch64.AppImage "$OUT/"
cp "$DESKTOP"/windows/acidulous-$V-setup.exe "$DESKTOP"/windows/acidulous-$V-windows-x64.zip "$OUT/"
heavy build ./gradlew :webApp:wasmJsBrowserDistribution
rm -rf "${OUT:?}/web"; cp -r "$WEB"/dist/wasmJs/productionExecutable "$OUT/web"
cd "$OUT"
sha256sum acidulous-$V.apk acidulous-$V-32bit.apk acidulous_${V}_amd64.deb acidulous_${V}_arm64.deb \
    acidulous-$V-aarch64.AppImage acidulous-$V-x86_64.AppImage acidulous-$V-setup.exe acidulous-$V-windows-x64.zip > SHA256SUMS
cat SHA256SUMS
echo "The macOS .dmg files aren't built here: the tag's run of .github/workflows/macos.yml attaches them to the release with SHA256SUMS-macos."
# Only the newest release folder is kept.
for d in /home/dan/Projects/releases/acidulous-*; do
    v=${d##*-}
    [ "$v" != "$V" ] && [ "$(printf '%s\n%s\n' "$v" "$V" | sort -V | head -1)" = "$v" ] && rm -rf "${d:?}"
done
echo DONE