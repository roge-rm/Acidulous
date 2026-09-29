// A machine writes every sample of its block.
//
// The rack hands a machine the buffer it used last block without clearing
// it, so a machine that adds into it, or returns early while asleep, plays
// what was there before. Diction did both: once asleep, its last block came
// round every 64 samples, a 750 Hz tone that never stopped. Every harness
// before this one cleared its buffers, so none could hear it.
//
// Each machine renders twin blocks, one into a cleared buffer and one into a
// buffer full of junk. They must match, while a note plays and once it's over.
#include <engine/machine/MachineRegistry.h>

#include <cmath>
#include <cstdio>
#include <initializer_list>
#include <memory>
#include <string>

using namespace acidulous;

namespace {
constexpr int32_t kSr = 48000;
constexpr int32_t kBlock = 64;
}

int main() {
    int failures = 0;
    for (int32_t k = 0; k < MachineRegistry::count(); ++k) {
        const char *name = MachineRegistry::name(k);
        std::unique_ptr<Machine> clean(MachineRegistry::create(name)), dirty(MachineRegistry::create(name));
        for (Machine *m : {clean.get(), dirty.get()}) {
            m->prepare(kSr);
            m->params().jumpAll();
            m->reset();
        }
        float worst = 0.0f;
        int32_t at = -1;
        // A second of a note, a second after it, and a moment of idling.
        const int32_t blocks = 3 * kSr / kBlock;
        for (int32_t b = 0; b < blocks; ++b) {
            if (b == 10) { clean->noteOn(60, 100); dirty->noteOn(60, 100); }
            if (b == kSr / kBlock) { clean->noteOff(60); dirty->noteOff(60); }
            float cl[kBlock], cr[kBlock], dl[kBlock], dr[kBlock];
            for (int32_t i = 0; i < kBlock; ++i) {
                cl[i] = cr[i] = 0.0f;
                dl[i] = dr[i] = 0.37f * std::sin(0.3f * static_cast<float>(i)); // last block's leftovers
            }
            const bool cs = clean->render(cl, cr, kBlock);
            const bool ds = dirty->render(dl, dr, kBlock);
            for (int32_t i = 0; i < kBlock; ++i) {
                const float d = std::fabs(cl[i] - dl[i]) + (cs && ds ? std::fabs(cr[i] - dr[i]) : 0.0f);
                if (d > worst) { worst = d; if (at < 0) at = b; }
            }
        }
        const bool ok = worst == 0.0f;
        if (!ok) ++failures;
        std::printf("  %-4s %-12s %s\n", ok ? "ok" : "FAIL", name,
                    ok ? "" : ("leftovers come through, first in block " + std::to_string(at)).c_str());
    }
    std::printf("\n%d machines, %d failures\n", MachineRegistry::count(), failures);
    return failures == 0 ? 0 : 1;
}
