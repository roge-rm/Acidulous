#!/bin/bash
# Measures a take and what Molt makes of it. See tools/molt_probe.cpp.
#
# Sources local.env like audition.sh does, so it uses the real recording if
# there is one.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

# `${VAR+set}` rather than `-z`, so an explicitly empty ACIDULOUS_INPUT_FILE
# uses the synthetic phrase instead of reading local.env. That's how you run
# the calibration on a machine that has a recording.
if [ -z "${ACIDULOUS_INPUT_FILE+set}" ] && [ -f "$ROOT/tools/local.env" ]; then
    # shellcheck disable=SC1091
    . "$ROOT/tools/local.env"
fi
export ACIDULOUS_INPUT_FILE="${ACIDULOUS_INPUT_FILE:-}"

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/molt_probe.cpp" "$LIB" -o "$DIR/molt_probe" || exit 1
"$DIR/molt_probe" "$@"
