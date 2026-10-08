#!/bin/bash
# Builds the lyric chord simulator: Diction and the chord decoder as WebAssembly,
# inlined into one page, fm1/sim/lyric-chords.html, that runs from the file.
#
#   fm1/sim/build.sh            needs Emscripten (emsdk) as web/engine/build.sh does
#
# The tables come from fm1/chord_tables.h; after changing fm1/chords.py, write
# them again with `python3 fm1/chords.py FREQ --c` first.
set -euo pipefail
cd "$(dirname "$0")"
. "${EMSDK:-$HOME/.local/share/emsdk}/emsdk_env.sh" >/dev/null 2>&1
CPP=../../app/src/main/cpp
OUT=${OUT:-build}
mkdir -p "$OUT"
# Diction, the dsp it uses and the analyser its recorded voices come from. A
# standalone module: no Emscripten runtime, the page calls the exports itself.
em++ -O2 -std=c++17 -I "$CPP" -DACID_SOFT_DENORMALS -fno-exceptions \
    -sSTANDALONE_WASM --no-entry -sALLOW_MEMORY_GROWTH \
    sim.cpp "$CPP"/engine/machine/diction/*.cpp "$CPP"/engine/dsp/*.cpp "$CPP"/engine/core/Utterance.cpp \
    -o "$OUT/sim.wasm"
python3 - "$OUT/sim.wasm" page.html "$OUT/page.html" lyric-chords.html <<'PY'
import base64, sys
wasm, page, fragment, full = sys.argv[1:]
body = open(page).read().replace("__WASM_BASE64__", base64.b64encode(open(wasm, "rb").read()).decode())
# The page as the Artifact tool publishes it (it adds the document around it),
# and as a file of its own.
open(fragment, "w").write(body)
open(full, "w").write('<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n'
                      '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
                      '<style>body{margin:0}</style>\n</head>\n<body>\n' + body + '\n</body>\n</html>\n')
print(f"{full}: {len(open(full).read()) // 1024} KB")
PY
