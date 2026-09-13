#!/bin/bash
# Every host harness, in one go.
#
# Four of these had no runner and were built by hand each time they were
# wanted, which meant they were rarely wanted. They take four seconds
# together; there is no reason not to run them after touching the engine.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
fail=0

# The sequencer is header-only, so these need no other translation unit.
for t in clockin clockout launcher songpos expr; do
    echo "--- $t"
    if ! g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/${t}_test.cpp" -o "$DIR/$t" 2>&1; then
        echo "  FAIL did not build"; fail=1; continue
    fi
    "$DIR/$t" | tail -2 || fail=1
done

# The click lives on the master bus.
echo "--- click"
if g++ -O2 -std=c++17 -I "$CPP" "$ROOT/tools/click_test.cpp" \
       "$CPP/engine/rack/MasterBus.cpp" "$CPP"/engine/dsp/*.cpp -o "$DIR/click" 2>&1; then
    "$DIR/click" | tail -2 || fail=1
else
    echo "  FAIL did not build"; fail=1
fi

# And the two that bring their own runner.
echo "--- reset"; "$ROOT/tools/reset_test.sh" | tail -3 || fail=1
echo "--- mpe";   "$ROOT/tools/mpe_test.sh"   | tail -2 || fail=1
# The banks: every factory patch names real parameters, makes a sound, does
# not clip fifty times over, and plays the same twice.
echo "--- bank";  "$ROOT/tools/bank_test.sh"  | tail -2 || fail=1
echo "--- sink";  "$ROOT/tools/sink_test.sh"  | tail -2 || fail=1
echo "--- molt";  "$ROOT/tools/molt_test.sh"  | tail -2 || fail=1
# Link takes half a minute: most of it is two sessions finding each other
# over the machine's own network, which is the part worth waiting for.
echo "--- link";  "$ROOT/tools/link_test.sh"  | tail -2 || fail=1
echo "--- delay"; "$ROOT/tools/delay_test.sh" | tail -2 || fail=1

exit $fail
