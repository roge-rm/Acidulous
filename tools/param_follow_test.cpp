// Every machine's parameters follow a live change: a knob turned, or a lane
// played, while it renders. A machine that never calls params_.tick() keeps
// the value it was last jumped to, and its knobs do nothing until a patch
// loads or a panic (Diction and Molt did, 2026-10-04).
#include <engine/core/Constants.h>
#include <engine/machine/MachineRegistry.h>

#include <cmath>
#include <cstdio>
#include <memory>

using namespace acidulous;

int main() {
    int failures = 0, machines = 0;
    for (int32_t k = 0; k < MachineRegistry::count(); ++k) {
        const char *name = MachineRegistry::name(k);
        std::unique_ptr<Machine> m(MachineRegistry::create(name));
        m->prepare(kSampleRate);
        m->reset();
        ParamSet &p = m->params();
        p.jumpAll();
        int32_t count = 0;
        const ParamDef *defs = m->paramDefs(count);
        // Each smoothed parameter moved a little at once, as a lane does.
        for (int32_t i = 0; i < count; ++i) {
            if (defs[i].curve == Curve::Stepped) continue;
            p.set(i, p.normalized(i) < 0.5f ? 0.75f : 0.25f);
        }
        float L[kBlockFrames], R[kBlockFrames];
        for (int b = 0; b < 400; ++b) m->render(L, R, kBlockFrames);
        int stuck = 0;
        for (int32_t i = 0; i < count; ++i) {
            if (defs[i].curve == Curve::Stepped) continue;
            const float want = p.target(i), got = p.get(i);
            if (std::fabs(want - got) > 1e-3f * (1.0f + std::fabs(want))) {
                if (stuck++ == 0) std::printf("  FAIL %s: %s is %g, set to %g\n", name, defs[i].name, got, want);
            }
        }
        if (stuck > 0) {
            std::printf("  FAIL %s: %d parameters didn't follow\n", name, stuck);
            ++failures;
        }
        ++machines;
    }
    std::printf("%d machines, %d failures\n", machines, failures);
    return failures == 0 ? 0 : 1;
}
