// Every mod source in every matrix, and whether it reaches the sound.
//
// Written because Filament's two envelopes did not. They were `set` from
// their eight parameters every block and read by `sourceValue`, and nothing
// anywhere called `trigger` or `next` - so `eg1` and `eg2` returned nought
// for ever, and eight knobs on the panel did nothing at all. No factory patch
// routed them, so no harness here could see it; it was found by eye, while
// taking a `pow` out of the loop beside it.
//
// That is the same shape as the arp that had never been connected, and the
// same lesson as the render harness next door: **a fault nothing can fail on
// is a fault that waits.** So this asks the only question that matters of a
// modulation source - route it to something and does the audio change? - of
// every source of every machine that has a matrix, whether or not a patch
// happens to use it.
//
// What it cannot see: a source that is *wired* but wrong, and a source that
// only moves when something outside the machine does. The second is why the
// machine is driven below with a mod wheel, pressure, a bend and two notes
// before anything is judged.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <engine/core/InputBus.h>
#include <engine/machine/MachineRegistry.h>

using namespace acidulous;

namespace {
int checks = 0, failures = 0;
constexpr int32_t kSr = 48000;
constexpr int32_t kBlock = 64;
constexpr int32_t kBlocks = 240; // five seconds, so a slow envelope arrives

/**
 * Something for a machine that listens to listen to.
 *
 * Cipher's loudness, brightness and pitch sources are all measurements of the
 * *modulator*, which is the audio coming in - so on a silent bus all three
 * read nought and none of them can be told from a source that was never
 * wired. Three formants over a moving fundamental, loud then quiet: not a
 * voice, but enough of one that every measurement has something to measure.
 * The tree's own rule about synthetic material is about faults a clean signal
 * hides; nothing here is looking at the signal's quality, only at whether a
 * number moves.
 */
struct Modulator {
    std::vector<float> buf;
    double phase = 0.0;
    void fill(int32_t block, int32_t frames) {
        buf.assign(static_cast<size_t>(frames) * 2, 0.0f);
        const double f0 = 90.0 + 40.0 * std::sin(block * 0.05);
        const float loud = block % 90 < 45 ? 0.5f : 0.08f; // loudness has to move
        for (int32_t i = 0; i < frames; ++i) {
            phase += f0 / kSr;
            if (phase >= 1.0) phase -= 1.0;
            const auto p = static_cast<float>(phase);
            // A buzz, then three formants on top of it - enough shape for the
            // brightness measure to have an opinion.
            float x = 2.0f * p - 1.0f;
            x += 0.5f * std::sin(6.2831853f * p * 7.0f);
            x += 0.3f * std::sin(6.2831853f * p * 13.0f);
            x += 0.2f * std::sin(6.2831853f * p * 26.0f);
            buf[static_cast<size_t>(i) * 2] = buf[static_cast<size_t>(i) * 2 + 1] = x * loud * 0.4f;
        }
        InputBus::get().publish(buf.data(), frames);
    }
    static void silence() { InputBus::get().publish(nullptr, 0); }
};

/**
 * What a machine has to be doing before its sources can be judged.
 *
 * A source that reads nought is indistinguishable from a source that is not
 * wired, and some of them read nought until the machine is *doing* something:
 * the organ's rotor sources are the horn's and drum's angles, and the rotor
 * ships braked, so `horn` is exactly zero on a default patch and nothing it
 * is pointed at moves. That is the machine behaving correctly and the harness
 * being asked the wrong question.
 *
 * So a machine may name the knobs that have to be up first, as normalised
 * values. Keep this short: a long list here is the harness excusing itself
 * rather than testing.
 */
struct Setup {
    const char *machine;
    const char *name;
    float value;
};
constexpr Setup kSetup[] = {
    {"Manual", "rotspeed", 1.0f}, // off the brake, or `horn` and `drum` stand still
    // A vocoder with nothing singing into it is silent however loud its
    // carrier is, because the carrier is what the *modulator* shapes. The dry
    // path is the carrier itself, so turning it up gives this something to
    // listen to without inventing a voice to feed it.
    {"Cipher", "dry", 1.0f},
    // And its pitch source is the tracker's answer, which is not computed at
    // all until the tracker is switched on.
    {"Cipher", "track", 1.0f},
};

/** The two spellings a matrix slot goes by in this tree. */
struct Slot {
    int32_t src = -1, dest = -1, depth = -1;
    int32_t srcSteps = 0, destSteps = 0;
    bool found() const { return src >= 0 && dest >= 0 && depth >= 0; }
};

Slot firstSlot(const ParamSet &p) {
    static const char *spellings[][3] = {
        {"m1_src", "m1_dst", "m1_amt"},      // Cipher, Filament, Manual
        {"m01_src", "m01_dest", "m01_depth"} // Trinity, Ratio
    };
    Slot s;
    for (const auto &sp : spellings) {
        const int32_t a = p.indexOf(sp[0]), b = p.indexOf(sp[1]), c = p.indexOf(sp[2]);
        if (a >= 0 && b >= 0 && c >= 0) {
            s.src = a; s.dest = b; s.depth = c;
            s.srcSteps = p.def(a).steps;
            s.destSteps = p.def(b).steps;
            return s;
        }
    }
    return s;
}

/**
 * Five seconds of the machine being played, with everything a source might
 * be listening to actually moving.
 *
 * A source that needs a mod wheel reads nought on a machine nobody touched,
 * which would make this harness fail honest wiring. So the wheel is up, the
 * key is under pressure, the bend is off centre, and two notes are played at
 * different velocities in different octaves.
 */
std::vector<float> play(const std::string &name, int32_t srcStep, int32_t destStep, int32_t slotSteps,
                        const Slot &slot) {
    Machine *m = MachineRegistry::create(name.c_str());
    if (m == nullptr) return {};
    m->prepare(kSr);
    m->reset();
    for (const auto &su : kSetup) {
        if (name == su.machine) {
            const int32_t i = m->params().indexOf(su.name);
            if (i >= 0) m->params().set(i, su.value);
        }
    }
    if (srcStep > 0) {
        m->params().set(slot.src, static_cast<float>(srcStep) / static_cast<float>(slot.srcSteps - 1));
        m->params().set(slot.dest, static_cast<float>(destStep) / static_cast<float>(slot.destSteps - 1));
        m->params().set(slot.depth, 1.0f); // the top of a -1..1 knob
    }
    (void)slotSteps;
    m->params().jumpAll();
    m->onBlock(0, kBlock, 120.0f);
    m->controlChange(1, 100); // the wheel
    m->channelPressure(90);
    m->pitchBend(10000);

    std::vector<float> out;
    out.reserve(static_cast<size_t>(kBlocks) * kBlock);
    float L[kBlock], R[kBlock];
    Modulator mod;
    for (int32_t b = 0; b < kBlocks; ++b) {
        mod.fill(b, kBlock);
        if (b == 0) m->noteOn(48, 100);
        if (b == 80) m->noteOn(60, 40);
        if (b == 160) { m->noteOff(48); m->noteOff(60); }
        for (int32_t i = 0; i < kBlock; ++i) L[i] = R[i] = 0.0f;
        m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) out.push_back(L[i]);
    }
    Modulator::silence();
    delete m;
    return out;
}

double difference(const std::vector<float> &a, const std::vector<float> &b) {
    double d = 0.0;
    const size_t n = a.size() < b.size() ? a.size() : b.size();
    for (size_t i = 0; i < n; ++i) d += std::fabs(a[i] - b[i]);
    return d;
}

double energyOf(const std::vector<float> &a) {
    double e = 0.0;
    for (float s : a) e += std::fabs(s);
    return e;
}

void aMachine(const std::string &name) {
    Machine *probe = MachineRegistry::create(name.c_str());
    if (probe == nullptr) return;
    const Slot slot = firstSlot(probe->params());
    delete probe;
    if (!slot.found()) return; // no matrix; nothing to ask

    const auto silent = play(name, 0, 0, 0, slot);
    const double base = energyOf(silent);
    if (base <= 0.0) {
        // Mosaic is the one this lands on: a sample player with nothing
        // mounted has nothing to play, and mounting a file here would make
        // this harness depend on material it does not own. `bank_test`
        // reports the same nine machines the same way.
        printf("  ..   %-10s makes no sound unrouted; nothing to compare\n", name.c_str());
        return;
    }

    std::vector<int32_t> dead;
    for (int32_t s = 1; s < slot.srcSteps; ++s) {
        bool moved = false;
        // Every destination, until one of them moves. A source is wired if
        // *anything* it can be pointed at responds; which destinations suit
        // it is a question for the ear, not for this.
        for (int32_t d = 1; d < slot.destSteps && !moved; ++d) {
            const auto routed = play(name, s, d, 0, slot);
            if (difference(silent, routed) > base * 0.001) moved = true;
        }
        if (!moved) dead.push_back(s);
    }

    ++checks;
    if (dead.empty()) {
        printf("  ok   %-10s all %d sources reach the sound\n", name.c_str(), slot.srcSteps - 1);
        return;
    }
    ++failures;
    std::string list;
    for (size_t i = 0; i < dead.size(); ++i) {
        if (i != 0) list += ", ";
        list += std::to_string(dead[i]);
    }
    printf("  FAIL %-10s source%s %s move nothing, whatever they are pointed at\n", name.c_str(),
           dead.size() == 1 ? "" : "s", list.c_str());
}

} // namespace

int main(int argc, char **argv) {
    const std::string only = argc > 1 ? argv[1] : "";
    printf("\nmodulation sources, and whether they arrive\n\n");
    for (int32_t i = 0; i < MachineRegistry::count(); ++i) {
        const std::string n = MachineRegistry::name(i);
        if (!only.empty() && only != n) continue;
        aMachine(n);
    }
    printf("\n%d machines with a matrix, %d with a source that never arrives\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
