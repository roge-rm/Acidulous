#!/bin/bash
# Re-vendor Ableton Link and asio from upstream, at the pinned versions.
#
# Run this to update them; it is also the record of exactly what was taken,
# so third_party/link and third_party/asio can be checked against upstream by
# anybody who wants to. Nothing here is edited on the way in - the only files
# of ours in those trees are CMakeLists.txt and PROVENANCE.md.
set -eu
LINK_TAG=Link-4.0
ASIO_TAG=asio-1-36-0   # the submodule Link pins at that tag
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/app/src/main/cpp/third_party"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "cloning $LINK_TAG and its asio submodule ..."
git clone -q --depth 1 --branch "$LINK_TAG" --recurse-submodules --shallow-submodules \
    https://github.com/Ableton/link.git "$WORK/link"

# --- Link: the headers, and only the platforms this app builds for ----------
rm -rf "$OUT/link/include" "$OUT/link/LICENSE.md" "$OUT/link/GNU-GPL-v2.0.md" "$OUT/link/README.md"
mkdir -p "$OUT/link/include"
cp -r "$WORK/link/include/ableton" "$OUT/link/include/"
# Left behind: the tests, the Link Audio extension (a different feature), and
# the platforms nothing here builds for.
rm -rf "$OUT/link/include/ableton/test" \
       "$OUT/link/include/ableton/link_audio" \
       "$OUT/link/include/ableton/LinkAudio.hpp" "$OUT/link/include/ableton/LinkAudio.ipp" \
       "$OUT/link/include/ableton/platforms/darwin" \
       "$OUT/link/include/ableton/platforms/esp32" \
       "$OUT/link/include/ableton/platforms/windows"
cp "$WORK/link/LICENSE.md" "$WORK/link/GNU-GPL-v2.0.md" "$WORK/link/README.md" "$OUT/link/"

# --- asio: the include tree whole ------------------------------------------
# Not the subset Link happens to reach today: asio's headers include each
# other freely, the subset differs by platform, and a header missing from a
# future build is a worse problem than four megabytes of text.
rm -rf "$OUT/asio/include"
mkdir -p "$OUT/asio"
cp -r "$WORK/link/modules/asio-standalone/asio/include" "$OUT/asio/"
cp "$WORK/link/modules/asio-standalone/asio/LICENSE_1_0.txt" "$OUT/asio/"

echo "link:  $(find "$OUT/link/include" -type f | wc -l) files"
echo "asio:  $(find "$OUT/asio/include" -type f | wc -l) files"
echo "done. $ASIO_TAG is what $LINK_TAG pins; check third_party/*/PROVENANCE.md still says so."
