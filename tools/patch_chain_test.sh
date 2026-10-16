#!/bin/bash
# A patch's inserts and sends: the bank format, the slot rule and
# EngineHost::applyPatchChain (patch_chain_test.cpp). Shares engine_replay's
# host objects in build/engine-replay, built with the same flags.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
OUT="$ROOT/build/engine-replay"
BIN="$OUT/patch_chain_test"
FLAGS="-O1 -fPIC -std=c++17 -pthread"
REPLAY_FLAGS="-O3 -ffast-math -fno-finite-math-only -fPIC -std=c++17 -pthread"
[ -d /usr/lib/ccache ] && export PATH="/usr/lib/ccache:$PATH"
mkdir -p "$OUT"
INCLUDES="-I $CPP/platform/lines -I $CPP/platform/desktop -I $CPP/platform/web -I $CPP -I $CPP/platform -I $CPP/third_party/lame/include"
SRC="$(find "$CPP/engine" -name '*.cpp') $CPP/EngineHost.cpp $CPP/platform/android/jni_bridge.cpp"
SRC="$SRC $CPP/platform/web/LinkOff.cpp $(find "$CPP/platform/lines" -name '*.cpp')"
# A header newer than the objects means they are stale; engine_replay.sh
# rebuilds the same way.
STAMP="$OUT/patch_chain_objs.stamp"
if [ -f "$STAMP" ] && [ -n "$(find "$CPP" -name '*.h' -newer "$STAMP" -print -quit)" ]; then
    rm -f "$OUT"/*.o
fi
objs=""
stale=""
for src in $SRC; do
    rel="${src#$ROOT/}"
    obj="$OUT/$(echo "$rel" | tr '/' '_' | sed 's/\.cpp$/.o/')"
    objs="$objs $obj"
    if [ ! -f "$obj" ] || [ "$src" -nt "$obj" ]; then stale="$stale $src:$obj"; fi
done
if [ -n "$stale" ]; then
    echo "  patch_chain_test: compiling $(echo $stale | wc -w) files" >&2
    echo $stale | tr ' ' '\n' | xargs -P 8 -I{} sh -c 'src="${1%%:*}"; obj="${1#*:}"; g++ '"$REPLAY_FLAGS $INCLUDES"' -c "$src" -o "$obj"' _ {} || exit 1
fi
touch "$STAMP"
# EngineHost links the MP3 writer, so LAME for the host, as engine_replay.sh builds it.
LAME_SRC="$CPP/third_party/lame"
LAME_OUT="$ROOT/build/lame-host"
LAME="$LAME_OUT/libmp3lame.a"
mkdir -p "$LAME_OUT"
if [ ! -f "$LAME" ] || [ -n "$(find "$LAME_SRC" -name '*.[ch]' -newer "$LAME" -print -quit)" ]; then
    echo "  building LAME for the host (once)" >&2
    for c in "$LAME_SRC"/libmp3lame/*.c "$LAME_SRC"/mpglib/*.c; do
        gcc -O1 -w -c -DHAVE_CONFIG_H -I "$LAME_SRC" -I "$LAME_SRC/include" -I "$LAME_SRC/libmp3lame" \
            -I "$LAME_SRC/mpglib" "$c" -o "$LAME_OUT/$(basename "$c" .c).o" || exit 1
    done
    ar rcs "$LAME" "$LAME_OUT"/*.o || exit 1
fi
g++ $FLAGS $INCLUDES -I "$ROOT/tools" "$ROOT/tools/patch_chain_test.cpp" $objs "$LAME" -lm -o "$BIN" || exit 1
ACIDULOUS_ROOT="$ROOT" "$BIN"
