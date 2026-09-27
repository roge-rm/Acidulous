#!/bin/bash
# Re-vendors Ableton Link and asio from upstream at the pinned versions.
#
# Nothing is edited on the way in. The only files of ours in those trees are
# CMakeLists.txt and PROVENANCE.md.
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

# --- Link: the headers, only for the platforms we build for -----------------
rm -rf "$OUT/link/include" "$OUT/link/LICENSE.md" "$OUT/link/GNU-GPL-v2.0.md" "$OUT/link/README.md"
mkdir -p "$OUT/link/include"
cp -r "$WORK/link/include/ableton" "$OUT/link/include/"
# Leave out the tests, the Link Audio extension and platforms we don't build
# for. Windows is kept for the desktop build.
rm -rf "$OUT/link/include/ableton/test" \
       "$OUT/link/include/ableton/link_audio" \
       "$OUT/link/include/ableton/LinkAudio.hpp" "$OUT/link/include/ableton/LinkAudio.ipp" \
       "$OUT/link/include/ableton/platforms/darwin" \
       "$OUT/link/include/ableton/platforms/esp32"
cp "$WORK/link/LICENSE.md" "$WORK/link/GNU-GPL-v2.0.md" "$WORK/link/README.md" "$OUT/link/"

# --- asio: the whole include tree ------------------------------------------
# asio's headers include each other freely and differ by platform, so copy
# them all instead of just what Link uses today.
rm -rf "$OUT/asio/include"
mkdir -p "$OUT/asio"
cp -r "$WORK/link/modules/asio-standalone/asio/include" "$OUT/asio/"
cp "$WORK/link/modules/asio-standalone/asio/LICENSE_1_0.txt" "$OUT/asio/"

echo "link:  $(find "$OUT/link/include" -type f | wc -l) files"
echo "asio:  $(find "$OUT/asio/include" -type f | wc -l) files"
echo "done. $ASIO_TAG is what $LINK_TAG pins; check third_party/*/PROVENANCE.md still says so."
