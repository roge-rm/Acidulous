#!/bin/bash
# Renders Hammer at the anchor keys and compares it with a measured reference
# set, as tools/hammer_reference/compare.py describes.
#
#   tools/hammer_reference.sh <set>[:group] [hammer_render options and name=value ...]
#
# The renders are kept in $HAMMER_RENDERS (default: a folder under /tmp that
# the next run replaces), so they can be listened to.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
[ $# -ge 1 ] || { sed -n '2,9p' "$0"; exit 2; }
SET=$1
shift
OUT=${HAMMER_RENDERS:-/tmp/hammer-renders}
BIN=$(mktemp -d)
trap 'rm -rf "$BIN"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/hammer_render.cpp" "$LIB" -o "$BIN/hammer_render" || exit 1
rm -rf "${OUT:?}"
mkdir -p "$OUT"
"$BIN/hammer_render" "$OUT" "$@" > "$OUT/render.log" || exit 1
python3 "$ROOT/tools/hammer_reference/compare.py" "$OUT" "$SET"
