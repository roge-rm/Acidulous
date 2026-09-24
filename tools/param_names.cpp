// Every name the engine can put on a screen, one per line, for
// tools/panel_words.py to check against the strings the app translates.
//
//   machine|<type>|<param>     effect|<type>|<param>     mod|<type>|<param>
//   nexus|<module>|knob|<label>     nexus|<module>|in|<jack>     nexus|<module>|out|<jack>
//
// A parameter's name is a key as well as a label - a patch, a lane and a
// mapping all address it - so it cannot change to suit a language. What the
// app shows for it can, and this is the list of what there is to show.
#include <cstdio>
#include <engine/effect/EffectRegistry.h>
#include <engine/inputmod/InputModRegistry.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/nexus/Modules.h>

using namespace acidulous;

template <typename Registry>
static void list(const char *kind) {
    for (int32_t i = 0; i < Registry::count(); ++i) {
        const char *type = Registry::name(i);
        int32_t n = 0;
        const ParamDef *defs = Registry::paramDefs(type, n);
        for (int32_t p = 0; p < n; ++p) std::printf("%s|%s|%s\n", kind, type, defs[p].name);
    }
}

int main() {
    list<MachineRegistry>("machine");
    list<EffectRegistry>("effect");
    list<InputModRegistry>("mod");
    using namespace acidulous::machine::nexus;
    for (int32_t t = 0; t < TypeCount; ++t) {
        const ModuleInfo &m = infoFor(t);
        for (int k = 0; k < kKnobs; ++k)
            if (m.knob[k]) std::printf("nexus|%s|knob|%s\n", m.name, m.knob[k]);
        for (int k = 0; k < kPorts; ++k) {
            if (m.in[k]) std::printf("nexus|%s|in|%s\n", m.name, m.in[k]);
            if (m.out[k]) std::printf("nexus|%s|out|%s\n", m.name, m.out[k]);
        }
    }
    return 0;
}
