#!/bin/bash
# Every host harness, in one go.
#
# Four of these had no runner and were built by hand each time they were
# wanted, which meant they were rarely wanted. They take four seconds
# together; there is no reason not to run them after touching the engine.
set -u
# **`pipefail`, and it is not decoration.**
#
# Every line below is `harness | tail -2 || fail=1`, and without this the
# status of that pipeline is `tail`'s - which is nought whatever the harness
# did. So `|| fail=1` never fired, `fail` was never set, and this script
# printed "all ok" and exited 0 over the top of failing harnesses. It did
# exactly that while sink_test was red about an MP3 coming back 2304 frames
# short, which is how that went from a bug to "an unreproduced flake" in my
# own notes: the runner was saying it was fine.
set -o pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
fail=0

# The sequencer is header-only, so these need no other translation unit.
for t in clockin clockout launcher songpos expr trig; do
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
echo "--- sched"; "$ROOT/tools/scheduler_test.sh" | tail -2 || fail=1
echo "--- tape";  "$ROOT/tools/tape_test.sh" | tail -2 || fail=1
# The banks: every factory patch names real parameters, makes a sound, does
# not clip fifty times over, and plays the same twice.
echo "--- bank";  "$ROOT/tools/bank_test.sh"  | tail -2 || fail=1
echo "--- sink";  "$ROOT/tools/sink_test.sh"  | tail -2 || fail=1
echo "--- molt";  "$ROOT/tools/molt_test.sh"  | tail -2 || fail=1
# Link takes half a minute: most of it is two sessions finding each other
# over the machine's own network, which is the part worth waiting for.
echo "--- link";  "$ROOT/tools/link_test.sh"  | tail -2 || fail=1
echo "--- delay"; "$ROOT/tools/delay_test.sh" | tail -2 || fail=1
echo "--- slice"; "$ROOT/tools/slice_test.sh" | tail -2 || fail=1
echo "--- forage"; "$ROOT/tools/forage_test.sh" | tail -2 || fail=1
echo "--- format"; "$ROOT/tools/format_test.sh" | tail -2 || fail=1
echo "--- edit";  "$ROOT/tools/sampleedit_test.sh" | tail -2 || fail=1
# Neither of these is a harness; both read the tree and ask it a question.
#
# noteon: does anything seed a note from a smoothed parameter? reset_test is
# the harness for that class of bug and is structurally blind to this instance
# of it - see the file.
echo "--- noteon"; python3 "$ROOT/tools/noteon_check.py" | tail -2 || fail=1
# plan: does the milestone table still agree with the tree? Passes with
# nothing to say where there is no docs/PLAN.md, which is every checkout but
# Dan's.
echo "--- plan";  python3 "$ROOT/tools/plan_check.py" | tail -2 || fail=1
# The manual and the app's Help window are the same words, and the only thing
# keeping them that way is that this fails when they are not.
echo "--- manual"; (cd "$ROOT" && python3 tools/gen_manual.py --check) | tail -2 || fail=1

exit $fail
