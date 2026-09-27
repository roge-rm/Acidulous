#!/bin/bash
# What the machines and effects cost per block, worst case. See tools/cpu_test.cpp.
#
#   cpu_test.sh                             every unit, at its defaults
#   cpu_test.sh paired Trinity              full against lean, in one process
#   cpu_test.sh paired Trinity --patch Halo  a named patch out of tools/banks/
#   cpu_test.sh paired Trinity o1_density=1  one knob, normalised
#   cpu_test.sh --rate 8.3                  sixteenths at 124 bpm, not the stress rate
#   cpu_test.sh rack | idle | stretch       a whole rack, silence, the stretcher
#
# The default note rate is a stress rate, about three times faster than
# music, so patches with long releases hold more voices than in a song. It's
# printed on every run and `--rate` changes it. A unit's defaults aren't a
# patch anyone plays, so use `--patch` to time a real sound.
#
# -O2 and no sanitisers, since a sanitised build reports several times the
# real cost.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/cpu_test.cpp" "$LIB" -o "$DIR/cpu_test" || exit 1
ACIDULOUS_ROOT="$ROOT" "$DIR/cpu_test" "$@"
