#!/bin/bash
# Builds the load test for the browser (out/) and natively (out/loadtest-native).
#
# Needs Emscripten: source ~/.local/share/emsdk/emsdk_env.sh first, or set EMSDK.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CPP="$ROOT/app/src/main/cpp"
OUT="$HERE/out"
mkdir -p "$OUT/obj"
if ! command -v em++ >/dev/null; then source "${EMSDK:-$HOME/.local/share/emsdk}/emsdk_env.sh" >/dev/null 2>&1; fi

# The bank texts, compiled in: the browser has no files to open.
{
    echo "// Written by web/loadtest/build.sh from tools/banks; do not edit."
    echo "#pragma once"
    echo "struct EmbeddedBank { const char *unit; const char *text; };"
    echo "static const EmbeddedBank kBanks[] = {"
    for f in "$ROOT"/tools/banks/*.bank; do
        unit=$(basename "$f" .bank)
        printf '    {"%s", R"BANK(' "$unit"
        cat "$f"
        printf ')BANK"},\n'
    done
    echo "};"
} > "$OUT/banks.gen.h"

FLAGS="-O3 -ffast-math -fno-finite-math-only -std=c++17 -I $CPP -I $ROOT/tools -I $OUT"
# Everything under engine/ but the two files that want LAME, which nothing here uses.
SRC=$(find "$CPP/engine" -name '*.cpp' ! -name 'Mp3Reader.cpp' ! -name 'Mp3Writer.cpp' ! -name 'AudioSink.cpp' ! -name 'AudioDecoder.cpp')

# Native, for the comparison.
g++ $FLAGS -march=native -o "$OUT/loadtest-native" "$HERE/LoadTest.cpp" $SRC -lpthread

# WebAssembly: one standalone module, no Emscripten JS - it is loaded by hand,
# in a worker for the benchmark and in the AudioWorklet to play, neither of
# which can run Emscripten's usual loader.
objs=""
for src in $SRC "$HERE/LoadTest.cpp"; do
    obj="$OUT/obj/$(echo "${src#$ROOT/}" | tr '/' '_').o"
    objs="$objs $obj"
    em++ $FLAGS -msimd128 -c "$src" -o "$obj" &
    while [ "$(jobs -r | wc -l)" -ge 8 ]; do sleep 0.2; done
done
wait
em++ -O3 -msimd128 $objs -o "$OUT/loadtest.wasm" \
    -sSTANDALONE_WASM --no-entry \
    -sEXPORTED_FUNCTIONS=_lt_build,_lt_play,_lt_render128,_lt_tracks \
    -sINITIAL_MEMORY=64MB -sALLOW_MEMORY_GROWTH=1 -sSTACK_SIZE=1MB
cp "$HERE"/index.html "$HERE"/*.js "$OUT/"
ls -la "$OUT/loadtest.wasm"
