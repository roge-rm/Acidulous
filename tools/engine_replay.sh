#!/bin/bash
# Replays a song zip's engine.txt and renders it to a WAV, with no JVM: the
# engine, EngineHost and jni_bridge.cpp built for the host, the calls made
# through platform/web/jni.h (see platform/lines).
#
#   tools/engine_replay.sh [--lines FILE] [--rack N] [--slot N] <song.zip or folder> <out.wav> [seconds] [tail] [bits]
#
# seconds 0 (the default) renders to the end of the song; tail is 2 seconds
# and bits 32 (float) unless given.
#
# Built with the desktop engine's flags (desktop/native/CMakeLists.txt) so
# its render can be compared with the desktop app's sample for sample. The
# objects are kept in build/engine-replay and only what changed is rebuilt.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
LAME="$CPP/third_party/lame"
OUT="$ROOT/build/engine-replay"
BIN="$OUT/engine_replay"
FLAGS="-O3 -ffast-math -fno-finite-math-only -fPIC -std=c++17 -pthread"
[ -d /usr/lib/ccache ] && export PATH="/usr/lib/ccache:$PATH"
mkdir -p "$OUT"

REPLAY_ARGS=()
while [ $# -ge 2 ] && [ "${1#--}" != "$1" ]; do REPLAY_ARGS+=("$1" "$2"); shift 2; done

if [ $# -lt 2 ]; then
    echo "usage: $0 <song.zip or folder> <out.wav> [seconds] [tail] [bits]" >&2
    exit 2
fi

# LAME for the host, shared with sink_test.sh.
LAME_OUT="$ROOT/build/lame-host"
ARCHIVE="$LAME_OUT/libmp3lame.a"
mkdir -p "$LAME_OUT"
if [ ! -f "$ARCHIVE" ] || [ -n "$(find "$LAME" -name '*.[ch]' -newer "$ARCHIVE" -print -quit)" ]; then
    echo "  building LAME for the host (once)" >&2
    for c in "$LAME"/libmp3lame/*.c "$LAME"/mpglib/*.c; do
        gcc -O1 -w -c -DHAVE_CONFIG_H -I "$LAME" -I "$LAME/include" -I "$LAME/libmp3lame" \
            -I "$LAME/mpglib" "$c" -o "$LAME_OUT/$(basename "$c" .c).o" || exit 1
    done
    ar rcs "$ARCHIVE" "$LAME_OUT"/*.o || exit 1
fi

# platform/lines first for its AudioDriver, the desktop's log, then the web's
# stand-in jni.h.
INCLUDES="-I $CPP/platform/lines -I $CPP/platform/desktop -I $CPP/platform/web -I $CPP -I $CPP/platform -I $LAME/include"
SRC="$(find "$CPP/engine" -name '*.cpp') $CPP/EngineHost.cpp $CPP/platform/android/jni_bridge.cpp"
SRC="$SRC $CPP/platform/web/LinkOff.cpp $(find "$CPP/platform/lines" -name '*.cpp') $ROOT/tools/engine_replay.cpp"

# Any header newer than the program rebuilds everything, like host_engine.sh.
if [ -f "$BIN" ] && [ -n "$(find "$CPP" "$ROOT/tools/engine_replay.cpp" -name '*.h' -newer "$BIN" -print -quit)" ]; then
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
    echo "  engine_replay: compiling $(echo $stale | wc -w) files" >&2
    # Eight at a time, the same as a desktop engine build.
    echo $stale | tr ' ' '\n' | xargs -P 8 -I{} sh -c 'src="${1%%:*}"; obj="${1#*:}"; g++ '"$FLAGS $INCLUDES"' -c "$src" -o "$obj"' _ {} || exit 1
fi
if [ -n "$stale" ] || [ ! -f "$BIN" ]; then
    g++ $FLAGS $objs "$ARCHIVE" -lm -o "$BIN" || exit 1
fi

IN="$1"
shift
if [ -d "$IN" ]; then
    exec "$BIN" "${REPLAY_ARGS[@]}" "$IN" "$@"
fi
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
unzip -q "$IN" -d "$DIR" || exit 1
"$BIN" "${REPLAY_ARGS[@]}" "$DIR" "$@"
