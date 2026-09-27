#!/bin/bash
# What each machine and effect costs in a decaying tail: without flush-to-zero,
# with the web build's guards, and with flush-to-zero. See denormal_probe.cpp.
# Not pass/fail, since timings on a busy machine are noisy, but a unit marked
# << is one a browser still pays for. Run it after adding a filter, an
# envelope follower or a feedback path.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/denormal_probe.cpp" "$LIB" -o "$DIR/plain" -lpthread || exit 1

# The engine again with the web build's guards compiled in.
GUARDED=$(HOST_ENGINE_OUT="$ROOT/build/host-engine-guarded" HOST_ENGINE_FLAGS=-DACID_SOFT_DENORMALS \
    "$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -DACID_SOFT_DENORMALS -I "$CPP" "$ROOT/tools/denormal_probe.cpp" "$GUARDED" \
    -o "$DIR/guarded" -lpthread || exit 1

"$DIR/plain" > "$DIR/plain.txt" & "$DIR/guarded" > "$DIR/guarded.txt" & "$DIR/plain" ftz > "$DIR/ftz.txt" & wait
python3 - "$DIR" <<'PY'
import sys
d = sys.argv[1]
def load(f):
    return {tuple(l.split('|')[:2]): float(l.split('|')[4]) for l in open(f"{d}/{f}") if '|' in l}
plain, guarded, ftz = load('plain.txt'), load('guarded.txt'), load('ftz.txt')
print(f"{'tail, us a block':30} {'no flush':>9} {'guarded':>9} {'flush':>9}")
for k in plain:
    mark = '  <<' if guarded[k] > ftz[k] * 1.5 and guarded[k] - ftz[k] > 5 else ''
    print(f"{k[0] + ' ' + (k[1] if k[1] != '-' else ''):30} {plain[k]:9.1f} {guarded[k]:9.1f} {ftz[k]:9.1f}{mark}")
PY
