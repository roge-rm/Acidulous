// A machine patch's inserts and sends: the bank format, resolving them
// against the effects' own tables, which slots they take
// (engine/core/PatchChain.h) and EngineHost::applyPatchChain, which a host
// that drives the engine directly calls. The app's twin is PatchChainTest.kt.
#include "patchbank.h"

#include <EngineHost.h>
#include <engine/machine/MachineRegistry.h>

#include <cmath>
#include <cstdlib>
#include <cstdio>
#include <sstream>
#include <string>

using namespace acidulous;
using namespace acidulous::audition;

namespace {

int failures = 0;

void check(bool ok, const std::string &what) {
    if (!ok) {
        std::printf("  FAIL %s\n", what.c_str());
        ++failures;
    }
}

bool parse(const std::string &text, Bank &bank, std::string &error) {
    std::istringstream in(text);
    return readBankFrom(in, "test.bank", bank, error);
}

void planRules() {
    {
        const bool occupied[kEffectSlots] = {false, true, false, false};
        const InsertPlan p = planPatchInserts(occupied, 0b10u, 2);
        check(p.slotOf[0] == 0 && p.slotOf[1] == 1, "the last patch's slot and a free one, in slot order");
        check(p.clear == 0b10u && p.owned == 0b11u && p.skipped == 0, "plan bits");
    }
    {
        const bool occupied[kEffectSlots] = {true, true, false, false};
        const InsertPlan p = planPatchInserts(occupied, 0u, 1);
        check(p.slotOf[0] == -1 && p.skipped == 1 && p.owned == 0u, "no free slot skips the insert");
    }
    {
        const bool occupied[kEffectSlots] = {true, true, false, false};
        const InsertPlan p = planPatchInserts(occupied, 0b11u, 1);
        check(p.slotOf[0] == 0 && p.clear == 0b11u && p.owned == 0b01u, "one insert after a patch of two");
    }
    {
        const bool occupied[kEffectSlots] = {false, false, false, false};
        const InsertPlan p = planPatchInserts(occupied, 0u, 3);
        check(p.owned == 0b11u && p.skipped == 1, "more inserts than slots");
    }
    {
        // Slots 3 and 4 are the player's, even when they're free.
        const bool occupied[kEffectSlots] = {true, false, false, false};
        const InsertPlan p = planPatchInserts(occupied, 0u, 2);
        check(p.slotOf[0] == 1 && p.slotOf[1] == -1 && p.owned == 0b10u && p.skipped == 1,
              "a patch never takes the slots after its own");
    }
    {
        // Owned bits past the patch's slots are ignored.
        const bool occupied[kEffectSlots] = {false, false, true, true};
        const InsertPlan p = planPatchInserts(occupied, 0b1100u, 0);
        check(p.clear == 0u && p.owned == 0u, "the player's slots are never cleared");
    }
}

void format() {
    Bank bank;
    std::string error;
    const std::string text =
        "machine Trinity\n"
        "patch Init\n"
        "patch \"Old\" family=keys\n"
        "  volume 0.5\n"
        "patch \"New\" family=keys\n"
        "  volume 0.6\n"
        "  insert Chorus   # the first\n"
        "    mix 0.4\n"
        "  send reverb 0.25\n"
        "  insert Distortion\n"
        "    mix 0.3\n";
    check(parse(text, bank, error), "a bank with inserts parses: " + error);
    if (bank.patches.size() != 3) {
        check(false, "three patches");
        return;
    }
    const BankPatch &old = bank.patches[1], &nu = bank.patches[2];
    check(old.inserts.empty() && old.sendReverb < 0.0f && old.sendDelay < 0.0f, "an old patch has no chain");
    check(nu.values.size() == 1 && nu.values[0].name == "volume", "the machine's values stay the machine's");
    check(nu.inserts.size() == 2 && nu.inserts[0].type == "Chorus" && nu.inserts[1].type == "Distortion", "two inserts");
    check(nu.inserts[0].values.size() == 1 && nu.inserts[0].values[0].name == "mix", "values after insert are its");
    check(std::fabs(nu.sendReverb - 0.25f) < 1e-6f && nu.sendDelay < 0.0f, "send reverb");

    std::vector<std::string> problems;
    const PatchChain chain = resolveChain(nu, problems);
    check(problems.empty(), "the chain resolves");
    check(chain.hasSends && std::fabs(chain.sendReverb - 0.25f) < 1e-6f && chain.sendDelay == 0.0f,
          "naming one send sets the other to zero");
    int32_t count = 0;
    const ParamDef *defs = EffectRegistry::paramDefs("Chorus", count);
    check(chain.inserts.size() == 2 && static_cast<int32_t>(chain.inserts[0].params.size()) == count,
          "every Chorus parameter is there");
    for (int32_t i = 0; i < count && !chain.inserts.empty(); ++i) {
        const float want = std::string(defs[i].name) == "mix" ? defs[i].unmap(0.4f) : defs[i].unmap(defs[i].def);
        check(std::fabs(chain.inserts[0].params[static_cast<size_t>(i)].second - want) < 1e-6f,
              std::string("Chorus ") + defs[i].name);
    }
    const PatchChain none = resolveChain(old, problems);
    check(none.inserts.empty() && !none.hasSends, "no chain from an old patch");

    // What should not parse.
    check(!parse("machine Trinity\npatch Init\npatch A\n insert Chorus\n insert Delay\n insert Phaser\n", bank, error),
          "three inserts");
    check(!parse("machine Trinity\npatch Init\npatch A\n send reverb 2\n", bank, error), "a send over 1");
    check(!parse("machine Trinity\npatch Init\npatch A\n send chorus 0.2\n", bank, error), "a send that isn't one");
    check(!parse("effect Delay\npatch Init\npatch A\n insert Chorus\n", bank, error), "an effect with an insert");
    check(parse("machine Trinity\npatch Init\npatch A\n insert Nonesuch\n wibble 3\n", bank, error),
          "names are checked when resolved");
    problems.clear();
    resolveChain(bank.patches[1], problems);
    check(problems.size() == 1, "an unknown effect is one problem");
    check(parse("machine Trinity\npatch Init\npatch A\n insert Chorus\n wibble 3\n", bank, error), "parses");
    problems.clear();
    resolveChain(bank.patches[1], problems);
    check(problems.size() == 1, "an unknown insert parameter is a problem");
}

void sendRules() {
    PatchChain with;
    with.hasSends = true;
    with.sendReverb = 0.3f;
    PatchChain other = with;
    other.sendReverb = 0.1f;
    other.sendDelay = 0.2f;
    const PatchChain none;
    PatchTrackState st;

    SendPlan p = planPatchSends(none, 0.4f, 0.5f, st);
    check(!p.set && !st.sendsHeld, "no sends, nothing held: leave them");
    p = planPatchSends(with, 0.4f, 0.5f, st);
    check(p.set && p.reverb == 0.3f && p.delay == 0.0f, "the patch's sends");
    check(st.sendsHeld && st.heldReverb == 0.4f && st.heldDelay == 0.5f, "the player's are kept");
    p = planPatchSends(other, 0.3f, 0.0f, st);
    check(p.set && p.reverb == 0.1f && st.heldReverb == 0.4f, "a second patch keeps the first saved levels");
    p = planPatchSends(none, 0.1f, 0.2f, st);
    check(p.set && p.reverb == 0.4f && p.delay == 0.5f && !st.sendsHeld, "no sends puts the player's back");

    // Moved by hand: the sends are the player's, nothing is put back.
    planPatchSends(with, 0.4f, 0.5f, st);
    p = planPatchSends(none, 0.6f, 0.0f, st);
    check(!p.set && !st.sendsHeld, "a send moved by hand is the player's");
    p = planPatchSends(with, 0.6f, 0.0f, st);
    check(st.heldReverb == 0.6f, "and is what the next patch keeps");
}

void host() {
    EngineHost &h = EngineHost::instance();
    const int rack = 3;
    PatchChain one;
    one.inserts.push_back({"Chorus", {{"mix", 0.4f}}, {}});
    one.hasSends = true;
    one.sendReverb = 0.3f;
    PatchTrackState st;

    // The player's own insert in slot 2.
    h.mountEffect(rack, 1, "Delay");
    int32_t skipped = -1;
    SendPlan sends = h.applyPatchChain(rack, one, st, 0.2f, 0.1f, &skipped);
    check(st.owned == 0b01u && skipped == 0, "the patch takes the free slot");
    check(sends.set && sends.reverb == 0.3f && st.sendsHeld && st.heldReverb == 0.2f, "sends set and the player's held");
    check(std::string(h.mountedEffect(rack, 0)) == "Chorus", "Chorus mounted");
    check(std::string(h.mountedEffect(rack, 1)) == "Delay", "the player's Delay stays");

    PatchChain two;
    two.inserts.push_back({"Distortion", {}, {}});
    two.inserts.push_back({"Phaser", {}, {}});
    sends = h.applyPatchChain(rack, two, st, 0.3f, 0.0f, &skipped);
    check(st.owned == 0b01u && skipped == 1, "the second patch replaces the first and skips one");
    check(sends.set && sends.reverb == 0.2f && sends.delay == 0.1f && !st.sendsHeld, "and puts the sends back");
    check(std::string(h.mountedEffect(rack, 0)) == "Distortion", "Distortion replaces Chorus");
    check(std::string(h.mountedEffect(rack, 1)) == "Delay", "the Delay still stays");

    PatchChain future;
    future.inserts.push_back({"FutureVerb", {}, {}});
    future.inserts.push_back({"Phaser", {}, {}});
    int32_t unknown = 0;
    h.applyPatchChain(rack, future, st, 0.2f, 0.1f, &skipped, &unknown);
    check(st.owned == 0b01u && skipped == 0 && unknown == 1, "an unknown effect is left out and takes no slot");
    check(std::string(h.mountedEffect(rack, 0)) == "Phaser", "the known one goes in");

    h.applyPatchChain(rack, PatchChain(), st, 0.2f, 0.1f, &skipped);
    check(st.owned == 0u && std::string(h.mountedEffect(rack, 0)).empty(), "a patch without inserts clears the patch's");
    check(std::string(h.mountedEffect(rack, 1)) == "Delay", "and leaves the player's");
}

void banks(const std::string &dir) {
    // Every bank still reads and every chain in it resolves; the examples are there.
    int chains = 0;
    for (int32_t i = 0; i < MachineRegistry::count(); ++i) {
        Bank bank;
        std::string error;
        const std::string unit = MachineRegistry::name(i);
        if (!readBank(dir + "/" + unit + ".bank", bank, error)) continue;
        for (const BankPatch &p : bank.patches) {
            std::vector<std::string> problems;
            resolveChain(p, problems);
            for (const std::string &why : problems) check(false, unit + " / " + p.name + ": " + why);
            if (!p.inserts.empty()) ++chains;
        }
    }
    check(chains >= 2, "the factory banks carry the example chains");
}

} // namespace

int main() {
    planRules();
    format();
    sendRules();
    host();
    const char *root = std::getenv("ACIDULOUS_ROOT");
    banks(std::string(root != nullptr ? root : ".") + "/tools/banks");
    std::printf("patch chain: %s (%d failures)\n", failures == 0 ? "ok" : "FAILED", failures);
    return failures == 0 ? 0 : 1;
}
