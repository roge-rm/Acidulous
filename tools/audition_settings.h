#pragma once
#include <engine/machine/Machine.h>
#include <engine/machine/nexus/Graph.h>
#include <engine/machine/nexus/Modules.h>
#include <cstdio>
#include <memory>
#include <set>
#include <string>
#include <vector>

// Mounting a Nexus graph, in one place.
//
// `audition` and `bank_test` each had their own applySettings, and each only
// knew about Formulate - so when `audition` learned to mount a Nexus graph,
// `bank_test` did not, and reported every Nexus patch silent while `audition
// play` measured the same patch at -10.5 dB. Two copies of one rule is one
// copy too many.
namespace acidulous::audition {

/**
 * Build the graph a Nexus patch describes and hand it to the machine.
 *
 * Returns false and says why if the text will not parse. [named] is the set of
 * parameters the patch stated for itself: everything else takes the module's
 * own default rather than zero, because every slot knob defaults to zero and a
 * graph whose oscillator level is nought makes no sound at all.
 */
inline bool mountNexusGraph(Machine *m, const std::string &text, float sampleRate,
                            const std::set<std::string> &named,
                            std::unique_ptr<machine::nexus::Graph> &store) {
    namespace nx = machine::nexus;
    if (text.empty()) return true;
    std::string error;
    nx::Graph *g = nx::Graph::parse(text, sampleRate, error);
    if (g == nullptr) {
        std::fprintf(stderr, "  the graph did not parse: %s\n", error.c_str());
        return false;
    }
    std::vector<float> knobs(static_cast<size_t>(nx::kSlots) * nx::kKnobs, 0.0f);
    g->defaultKnobs(knobs.data());
    int32_t count = 0;
    const ParamDef *defs = m->paramDefs(count);
    for (int32_t i = 0; i < count; ++i) {
        const std::string name = defs[i].name;
        if (name.size() < 6 || name[0] != 's' || name[3] != '_' || name[4] != 'p') continue;
        if (named.count(name) != 0) continue; // the patch said so itself
        const int32_t slot = (name[1] - '0') * 10 + (name[2] - '0');
        const int32_t knob = name[5] - '1';
        if (slot < 0 || slot >= nx::kSlots || knob < 0 || knob >= nx::kKnobs) continue;
        m->params().set(i, defs[i].unmap(knobs[static_cast<size_t>(slot) * nx::kKnobs + knob]));
    }
    m->params().jumpAll();
    // Swap first, *then* release what was there. `swapObject` carries the
    // sounding instances over from the old graph - it dereferences it - so
    // `store.reset(g)` before the swap freed the very graph the machine was
    // still pointing at, and the second mount of a patch segfaulted inside
    // `adoptFrom`. The engine gets this right (Engine.cpp retires the pointer
    // the swap hands back, and not before); the harness did not.
    m->swapObject(0, g);
    store.reset(g);
    return true;
}

} // namespace acidulous::audition
