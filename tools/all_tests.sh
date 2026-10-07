#!/bin/bash
# Runs every host harness in one go. Run it after touching the engine.
set -u
# Needed: every line below is `harness | tail -2 || fail=1`, and without
# pipefail the pipeline's status is tail's, which is always 0, so failures
# would be missed.
set -o pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# Use ccache if the machine has it, since the tests rebuild the same engine
# files over and over.
[ -d /usr/lib/ccache ] && export PATH="/usr/lib/ccache:$PATH"
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
fail=0

# The sequencer is header-only, so these build from one file.
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

# The ones with their own runner script.
echo "--- reset"; "$ROOT/tools/reset_test.sh" | tail -3 || fail=1
echo "--- mpe";   "$ROOT/tools/mpe_test.sh"   | tail -2 || fail=1
echo "--- sched"; "$ROOT/tools/scheduler_test.sh" | tail -2 || fail=1
echo "--- queue"; "$ROOT/tools/queue_test.sh" | tail -2 || fail=1
echo "--- retire"; "$ROOT/tools/retire_test.sh" | tail -2 || fail=1
echo "--- sweep"; "$ROOT/tools/param_sweep.sh" | tail -1 || fail=1
echo "--- render"; "$ROOT/tools/render_test.sh" | tail -2 || fail=1
echo "--- loudness"; "$ROOT/tools/loudness_test.sh" | tail -2 || fail=1
echo "--- perform"; "$ROOT/tools/perform_test.sh" | tail -2 || fail=1
echo "--- modsource"; "$ROOT/tools/modsource_test.sh" | tail -2 || fail=1
echo "--- bias";  "$ROOT/tools/bias_test.sh" | tail -2 || fail=1
echo "--- marks"; "$ROOT/tools/marks_test.sh" | tail -2 || fail=1
echo "--- stretch"; "$ROOT/tools/stretch_test.sh" | tail -2 || fail=1
echo "--- dice follow"; "$ROOT/tools/dice_follow_test.sh" | tail -2 || fail=1
echo "--- oversample"; "$ROOT/tools/oversample_test.sh" | tail -2 || fail=1
echo "--- amp";  "$ROOT/tools/amp_test.sh" | tail -2 || fail=1
echo "--- inputfx"; "$ROOT/tools/inputfx_test.sh" | tail -2 || fail=1
echo "--- gate";  "$ROOT/tools/gate_test.sh" | tail -2 || fail=1
echo "--- swell"; "$ROOT/tools/swell_test.sh" | tail -2 || fail=1
echo "--- inserts"; "$ROOT/tools/inserts_test.sh" | tail -2 || fail=1
echo "--- effects"; "$ROOT/tools/effects_test.sh" | tail -2 || fail=1
echo "--- nexus modules"; "$ROOT/tools/nexus_modules_test.sh" | tail -2 || fail=1
echo "--- tuner"; "$ROOT/tools/tuner_test.sh" | tail -2 || fail=1
echo "--- swing"; "$ROOT/tools/swing_test.sh" | tail -2 || fail=1
echo "--- record"; "$ROOT/tools/record_test.sh" | tail -2 || fail=1
echo "--- params follow"; "$ROOT/tools/param_follow_test.sh" | tail -2 || fail=1
echo "--- inputmod"; "$ROOT/tools/inputmod_test.sh" | tail -2 || fail=1
# Not pass/fail. The cost table is for reading, since a threshold would only
# hold on the machine that set it. Check it when touching DSP.
echo "--- cost"; "$ROOT/tools/cpu_test.sh" | tail -3 || fail=1
# Every factory patch names real parameters, makes a sound, doesn't clip
# badly, and plays the same twice.
echo "--- bank";  "$ROOT/tools/bank_test.sh"  | tail -2 || fail=1
echo "--- sink";  "$ROOT/tools/sink_test.sh"  | tail -2 || fail=1
echo "--- molt";  "$ROOT/tools/molt_test.sh"  | tail -2 || fail=1
echo "--- diction"; "$ROOT/tools/diction_test.sh" | tail -2 || fail=1
echo "--- hammer"; "$ROOT/tools/hammer_test.sh" | tail -2 || fail=1
echo "--- tongue"; "$ROOT/tools/tongue_test.sh" | tail -2 || fail=1
echo "--- draw"; "$ROOT/tools/draw_test.sh" | tail -2 || fail=1
echo "--- fret"; "$ROOT/tools/fret_test.sh" | tail -2 || fail=1
echo "--- overwrite"; "$ROOT/tools/overwrite_test.sh" | tail -2 || fail=1
# Link takes about half a minute, mostly two sessions finding each other over
# the local network.
echo "--- link";  "$ROOT/tools/link_test.sh"  | tail -2 || fail=1
echo "--- delay"; "$ROOT/tools/delay_test.sh" | tail -2 || fail=1
echo "--- slice"; "$ROOT/tools/slice_test.sh" | tail -2 || fail=1
echo "--- forage"; "$ROOT/tools/forage_test.sh" | tail -2 || fail=1
echo "--- format"; "$ROOT/tools/format_test.sh" | tail -2 || fail=1
echo "--- edit";  "$ROOT/tools/sampleedit_test.sh" | tail -2 || fail=1
# These two check the source tree rather than run the engine.
#
# noteon: does anything start a note from a smoothed parameter? reset_test
# can't catch this case (see the file).
echo "--- noteon"; python3 "$ROOT/tools/noteon_check.py" | tail -2 || fail=1
# plan: does the milestone table still match the tree? Only where docs/PLAN.md
# and the check are on disk; neither is in the repo.
if [ -f "$ROOT/docs/PLAN.md" ] && [ -f "$ROOT/tools/plan_check.py" ]; then
    echo "--- plan";  python3 "$ROOT/tools/plan_check.py" | tail -2 || fail=1
fi
# The manual and the app's Help window must have the same text.
echo "--- manual"; (cd "$ROOT" && python3 tools/gen_manual.py --check) | tail -2 || fail=1
# Each platform's engine bridge is generated from one list of calls. This
# fails if the list changed and the script wasn't run again.
echo "--- engine bridge"; (cd "$ROOT" && python3 tools/gen_engine_bridge.py --check) | tail -2 || fail=1
# Every word a panel shows needs a translatable string. This fails if a panel
# changed and the generator wasn't run again.
echo "--- words"; (cd "$ROOT" && python3 tools/panel_words.py --check) | tail -2 || fail=1

exit $fail
