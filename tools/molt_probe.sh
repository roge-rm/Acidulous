#!/bin/bash
# What a take is, and what Molt makes of it. See tools/molt_probe.cpp.
#
# Sources local.env the way audition.sh does, so it measures the real
# recording where there is one and says so when there is not.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

# `${VAR+set}` rather than `-z`, so an explicitly empty ACIDULOUS_INPUT_FILE
# means "the synthetic phrase, please" rather than "go and look in local.env".
# That is the only way to see the calibration run on a machine that has a
# recording, and the calibration run is what says what these numbers mean.
if [ -z "${ACIDULOUS_INPUT_FILE+set}" ] && [ -f "$ROOT/tools/local.env" ]; then
    # shellcheck disable=SC1091
    . "$ROOT/tools/local.env"
fi
export ACIDULOUS_INPUT_FILE="${ACIDULOUS_INPUT_FILE:-}"

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/molt_probe.cpp" "$LIB" -o "$DIR/molt_probe" || exit 1
"$DIR/molt_probe" "$@"
