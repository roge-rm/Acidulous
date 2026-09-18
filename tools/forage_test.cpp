// How a Forage pad plays: once, round and round, or for as long as it is held.
//
// `noteOff` was an empty function until "while held" existed, and a loop is
// the one mode that can fail by never stopping - so both are worth a test that
// does not need ears.
#include <cmath>
#include <cstdio>
#include <memory>
#include <engine/core/Sample.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/forage/Forage.h>

using namespace acidulous;

namespace {
int gChecks = 0, gFails = 0;
void check(bool ok, const char *what) {
    ++gChecks;
    if (!ok) { ++gFails; std::printf("  FAIL %s\n", what); } else std::printf("  ok   %s\n", what);
}
constexpr int32_t kSr = 48000;
constexpr int32_t kBlock = 128;

/** Half a second of steady tone, so "is it still playing" is unambiguous. */
SampleData *tone() {
    auto *s = new SampleData();
    s->frames = kSr / 2;
    s->left.resize(static_cast<size_t>(s->frames));
    for (int32_t i = 0; i < s->frames; ++i) {
        s->left[static_cast<size_t>(i)] = 0.5f * std::sin(2.0f * 3.14159265f * 220.0f * i / kSr);
    }
    s->measure();
    return s;
}

/** Render and throw away, to get past something. */
void skip(Machine *m, float seconds) {
    float L[kBlock], R[kBlock];
    const int32_t blocks = static_cast<int32_t>(kSr * seconds) / kBlock;
    for (int32_t b = 0; b < blocks; ++b) m->render(L, R, kBlock);
}

/** Peak over [seconds] of rendering, starting now. */
float peakOver(Machine *m, float seconds) {
    float L[kBlock], R[kBlock], peak = 0.0f;
    const int32_t blocks = static_cast<int32_t>(kSr * seconds) / kBlock;
    for (int32_t b = 0; b < blocks; ++b) {
        m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) peak = std::max(peak, std::fabs(L[i]));
    }
    return peak;
}

std::unique_ptr<Machine> padSetTo(int mode, SampleData *sample) {
    std::unique_ptr<Machine> m(MachineRegistry::create("Forage"));
    m->prepare(kSr);
    m->reset();
    // `play` is stepped over three values, so normalised is mode/2.
    m->params().set(machine::Forage::index(0, machine::Forage::Play), static_cast<float>(mode) / 2.0f);
    m->params().jumpAll();
    m->swapObject(0, sample);
    return m;
}
} // namespace

int main() {
    SampleData *s = tone();

    // One shot: half a second of sample, silent well before a second.
    {
        auto m = padSetTo(0, s);
        m->noteOn(36, 110);
        check(peakOver(m.get(), 0.2f) > 0.05f, "one shot speaks");
        skip(m.get(), 0.4f); // past the end of a half-second sample
        check(peakOver(m.get(), 0.3f) < 1e-4f, "one shot stops at the end of the sample");
    }
    // One shot ignores a note off, because a drum pad does.
    {
        auto m = padSetTo(0, s);
        m->noteOn(36, 110);
        peakOver(m.get(), 0.05f);
        m->noteOff(36);
        check(peakOver(m.get(), 0.2f) > 0.05f, "one shot ignores the note coming up");
    }
    // Loop: still going long after the sample would have run out.
    {
        auto m = padSetTo(1, s);
        m->noteOn(36, 110);
        check(peakOver(m.get(), 0.4f) > 0.05f, "a loop speaks");
        check(peakOver(m.get(), 2.0f) > 0.05f, "and is still going four sample-lengths later");
        // But only while it is held. A loop that outlives its note would be
        // set going by one step of a sequence and never stop.
        m->noteOff(36);
        skip(m.get(), 0.05f);
        check(peakOver(m.get(), 1.0f) < 1e-3f, "and stops when the note comes up");
        m->noteOn(36, 110);
        // And the seam is a ramp, not a step: nothing should exceed what the
        // sample itself does.
        float L[kBlock], R[kBlock], worst = 0.0f, prev = 0.0f;
        for (int b = 0; b < 400; ++b) {
            m->render(L, R, kBlock);
            for (int32_t i = 0; i < kBlock; ++i) { worst = std::max(worst, std::fabs(L[i] - prev)); prev = L[i]; }
        }
        // One sample of a 220 Hz tone at this level steps by about 0.005;
        // a raw splice of a half-second loop would be far larger.
        check(worst < 0.02f, "and its seam is a ramp rather than a step");
    }
    // While held: stops when the note comes up, and not before.
    {
        auto m = padSetTo(2, s);
        m->noteOn(36, 110);
        check(peakOver(m.get(), 0.1f) > 0.05f, "a held pad speaks");
        m->noteOff(36);
        skip(m.get(), 0.05f); // the release is eight milliseconds
        check(peakOver(m.get(), 0.2f) < 1e-3f, "and stops when the note comes up");
    }
    // While held, never released: it still ends with the sample rather than
    // hanging for ever.
    {
        auto m = padSetTo(2, s);
        m->noteOn(36, 110);
        skip(m.get(), 0.6f); // it is a one shot until the note comes up
        check(peakOver(m.get(), 0.3f) < 1e-4f, "a held pad nobody released still ends");
    }
    std::printf("\n%d checks, %d failures\n", gChecks, gFails);
    return gFails == 0 ? 0 : 1;
}
