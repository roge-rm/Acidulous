#!/bin/bash
# Renders a song zip with this tree's engine and with another commit's
# (master by default) through tools/engine_replay.sh, and checks the two
# WAVs are the same to the bit. For changes that must leave a song alone,
# like an insert's mod with every mod off.
#
#   tools/match_master.sh <song.zip> [ref] [seconds]
#
# Make a zip with `./gradlew :desktop:engineLines --args="squelch"`. The
# ref's engine is built in a temporary folder and removed afterwards.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
if [ $# -lt 1 ]; then
    echo "usage: $0 <song.zip> [ref] [seconds]" >&2
    exit 2
fi
ZIP=$(realpath "$1")
REF=${2:-master}
SECONDS_=${3:-0}
DIR=$(mktemp -d)
trap 'rm -rf "${DIR:?}"' EXIT

mkdir -p "$DIR/ref"
git -C "$ROOT" archive "$REF" app/src/main/cpp tools | tar -x -C "$DIR/ref" || exit 1
echo "  building $REF's engine" >&2
"$DIR/ref/tools/engine_replay.sh" "$ZIP" "$DIR/ref.wav" "$SECONDS_" >/dev/null || exit 1
echo "  building this tree's engine" >&2
"$ROOT/tools/engine_replay.sh" "$ZIP" "$DIR/here.wav" "$SECONDS_" >/dev/null || exit 1
if cmp -s "$DIR/ref.wav" "$DIR/here.wav"; then
    echo "  identical to $REF: $(stat -c %s "$DIR/here.wav") bytes of $(basename "$ZIP")"
    exit 0
fi
echo "  FAIL: differs from $REF at byte $(cmp "$DIR/ref.wav" "$DIR/here.wav" | awk '{print $5}' | tr -d ,)"
exit 1
