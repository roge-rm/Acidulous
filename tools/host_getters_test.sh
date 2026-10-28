#!/bin/bash
# EngineHost's read-only getters after replaying engine lines
# (host_getters_test.cpp). Built from patch_chain_test.sh's objects in
# build/engine-replay, as hostbeat_test.sh is.
set -u
set -o pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
OUT="$ROOT/build/engine-replay"
# Builds the host objects (and runs the patch chain checks, quietly).
"$ROOT/tools/patch_chain_test.sh" > /dev/null 2>&1 || { echo "  FAIL patch_chain_test.sh did not build"; exit 1; }
INCLUDES="-I $CPP/platform/lines -I $CPP/platform/desktop -I $CPP/platform/web -I $CPP -I $CPP/platform -I $CPP/third_party/lame/include"
objs=$(ls "$OUT"/*.o | grep -v -e tools_ -e engine_replay)
g++ -O1 -fPIC -std=c++17 -pthread $INCLUDES "$ROOT/tools/host_getters_test.cpp" $objs \
    "$ROOT/build/lame-host/libmp3lame.a" -lm -o "$OUT/host_getters_test" || exit 1
"$OUT/host_getters_test" 2>&1 | grep -v "^[IE]/"
