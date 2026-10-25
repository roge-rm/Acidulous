#!/bin/bash
# Checks the macro routes of Trinity, Ratio, Reflux, Cumulus, Formulate, Hexbeat, Genesis, Resonance, Filament, Timber, Brazen, Manual, Hammer, Draw, Fret, Tine, Palm, Fathom, Aviary, Tongue, Sympath, Chanter, Pollen, Mosaic and Diction move what they name.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/macro_test.cpp" "$LIB" -o "$DIR/macro_test" || exit 1
"$DIR/macro_test"
