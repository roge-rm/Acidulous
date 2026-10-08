// Plays every machine on its defaults at the top of the keyboard and lets
// go: whatever it is, it must fall silent. A model can come apart far above
// the instrument it models (a string too short to lose its energy) and ring
// for ever, and strum keys or a wide keyboard reach up there.
#include <cmath>
#include <cstdio>
#include <memory>
#include <string>
#include <engine/machine/Machine.h>
#include <engine/machine/MachineRegistry.h>

using namespace acidulous;

namespace {
constexpr int kRate = 48000, kBlock = 64;
int checks = 0, failures = 0;

/** The rms of the second after [settle] seconds of silence, dB, once the note is let go. */
double tailAfter(const char *type, int note, double settle) {
    std::unique_ptr<Machine> m(MachineRegistry::create(type));
    if (!m) return -200.0;
    m->prepare(kRate);
    m->reset();
    m->params().jumpAll();
    float L[kBlock], R[kBlock];
    auto play = [&](double seconds, double *sum) {
        const int blocks = static_cast<int>(seconds * kRate / kBlock);
        for (int b = 0; b < blocks; ++b) {
            m->render(L, R, kBlock);
            if (sum) for (int i = 0; i < kBlock; ++i) *sum += double(L[i]) * L[i] + double(R[i]) * R[i];
        }
    };
    m->noteOn(static_cast<uint8_t>(note), 127);
    play(0.3, nullptr);
    m->noteOff(static_cast<uint8_t>(note));
    play(settle, nullptr);
    double sum = 0.0;
    play(1.0, &sum);
    return 10.0 * std::log10(sum / (2.0 * kRate) + 1e-20);
}
} // namespace

int main() {
    std::printf("high notes\n");
    // Machines that hold a sound on purpose after the key is up: a looper, a
    // four-track and the like are played from clips, not keys.
    const std::string skip = " Bias Dice Forage Mosaic Pollen Molt Cipher Diction Nexus ";
    for (int32_t i = 0; i < MachineRegistry::count(); ++i) {
        const char *type = MachineRegistry::name(i);
        if (skip.find(std::string(" ") + type + " ") != std::string::npos) continue;
        int bad = 0, worstNote = -1;
        double worst = -200.0;
        for (int note = 96; note <= 127; note += 3) {
            const double tail = tailAfter(type, note, 12.0);
            if (tail > -70.0) ++bad;
            if (tail > worst) { worst = tail; worstNote = note; }
        }
        ++checks;
        const bool ok = bad == 0;
        if (!ok) ++failures;
        std::printf("  %s %-12s worst %.1f dB at note %d, %d notes still sounding\n",
                    ok ? "ok  " : "FAIL", type, worst, worstNote, bad);
    }
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
