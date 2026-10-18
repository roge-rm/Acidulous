#!/bin/bash
# Following the host's beat (EngineHost::alignBeat). hostbeat_test.cpp runs the
# Engine block by block; hostbeat_host_test.cpp goes through EngineHost itself,
# in real time, built from patch_chain_test.sh's objects in build/engine-replay.
set -u
set -o pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/hostbeat_test.cpp" "$LIB" -o "$DIR/hostbeat_test" || exit 1
fail=0
"$DIR/hostbeat_test" || fail=1

# Builds the host objects (and runs the patch chain checks, quietly).
"$ROOT/tools/patch_chain_test.sh" > /dev/null 2>&1 || { echo "  FAIL patch_chain_test.sh did not build"; exit 1; }
OUT="$ROOT/build/engine-replay"
INCLUDES="-I $CPP/platform/lines -I $CPP/platform/desktop -I $CPP/platform/web -I $CPP -I $CPP/platform -I $CPP/third_party/lame/include"
objs=$(ls "$OUT"/*.o | grep -v -e tools_ -e engine_replay)
g++ -O1 -fPIC -std=c++17 -pthread $INCLUDES "$ROOT/tools/hostbeat_host_test.cpp" $objs \
    "$ROOT/build/lame-host/libmp3lame.a" -lm -o "$DIR/hostbeat_host_test" || exit 1
"$DIR/hostbeat_host_test" 2>&1 | grep -v "^[IE]/" || fail=1
# Both binaries' totals, for all_tests.sh's tail.
exit $fail
