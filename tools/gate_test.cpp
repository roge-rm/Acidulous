// The gate, which is the thing a loud amp asks for next.
//
// A gate is judged on four numbers and two habits. The numbers are easy and
// are here: how far down it shuts, how fast it opens, how long it holds, how
// fast it falls. The habits are what make a bad gate: it chatters on anything
// sitting near the threshold, and it opens for a room rather than for a note.
// Both get a check of their own, and neither can be seen in a frequency
// response, so all of this is rendered rather than evaluated.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include <engine/effect/Effects.h>

using namespace acidulous;
using namespace acidulous::effect;

namespace {
int checks = 0, failures = 0;

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-50s %s\n", cond ? "ok  " : "FAIL", what, detail.c_str());
    if (!cond) ++failures;
}

constexpr float kRate = 48000.0f;
constexpr int32_t kBlock = 64;

double dB(double v) { return 20.0 * std::log10(v + 1e-20); }

/** Set a parameter in its own units, the way a patch does. */
void setUnit(Gate &fx, const char *name, float value) {
    const int32_t i = fx.params().indexOf(name);
    if (i < 0) { printf("  FAIL no parameter named %s\n", name); ++failures; return; }
    fx.params().set(i, fx.params().def(i).unmap(value));
}

/** A gate with everything named, so no test depends on a default. */
Gate make(float threshold, float hyst, float attack, float hold, float release,
          float duck = -90.0f, float key = 20.0f) {
    Gate fx;
    fx.prepare(static_cast<int32_t>(kRate));
    setUnit(fx, "threshold", threshold);
    setUnit(fx, "hyst", hyst);
    setUnit(fx, "attack", attack);
    setUnit(fx, "hold", hold);
    setUnit(fx, "release", release);
    setUnit(fx, "duck", duck);
    setUnit(fx, "key", key);
    fx.params().jumpAll();
    return fx;
}

/** Run a mono signal through, in blocks, and give back what came out. */
std::vector<float> run(Gate &fx, const std::vector<float> &in) {
    std::vector<float> l = in, r = in;
    // **Every sample, including the remainder.** A signal whose length is not
    // a multiple of the block left its last few dozen samples untouched, and
    // a peak measurement over the tail then read the input back and called it
    // the gate passing a signal it had in fact shut out.
    for (size_t at = 0; at < in.size(); at += kBlock) {
        const int32_t n = static_cast<int32_t>(std::min<size_t>(kBlock, in.size() - at));
        fx.run(l.data() + at, r.data() + at, n, true);
    }
    return l;
}

std::vector<float> tone(float hz, float amp, float seconds) {
    std::vector<float> x(static_cast<size_t>(seconds * kRate));
    for (size_t i = 0; i < x.size(); ++i) {
        x[i] = amp * std::sin(2.0 * M_PI * hz * static_cast<double>(i) / kRate);
    }
    return x;
}

float peakOver(const std::vector<float> &x, size_t from, size_t to) {
    float p = 0.0f;
    for (size_t i = from; i < to && i < x.size(); ++i) p = std::fmax(p, std::fabs(x[i]));
    return p;
}

// --- the numbers ------------------------------------------------------------------

void itShutsAndItOpens() {
    printf("- shut below, open above\n");
    // A tone 15 dB under the threshold, held for a quarter of a second.
    {
        Gate fx = make(-45.0f, 4.0f, 1.0f, 40.0f, 150.0f);
        const auto out = run(fx, tone(440.0f, 0.0018f, 0.25f)); // about -55 dBFS
        ok("a tone under the threshold does not come through",
           dB(peakOver(out, kRate / 10, out.size())) < -80.0,
           std::string("out ") + std::to_string(dB(peakOver(out, kRate / 10, out.size()))) + " dB");
    }
    // The same tone 25 dB over it.
    {
        Gate fx = make(-45.0f, 4.0f, 1.0f, 40.0f, 150.0f);
        const auto in = tone(440.0f, 0.3f, 0.25f);
        const auto out = run(fx, in);
        const float settled = peakOver(out, kRate / 10, out.size());
        ok("a tone over it comes through at full level",
           std::fabs(dB(settled) - dB(0.3)) < 0.1,
           std::string("out ") + std::to_string(dB(settled)) + " dB, in " + std::to_string(dB(0.3)));
    }
}

void theAttackIsTheAttack() {
    printf("- the attack time is the one on the knob\n");
    for (float ms : {0.5f, 5.0f, 20.0f}) {
        Gate fx = make(-45.0f, 4.0f, ms, 200.0f, 500.0f);
        // A burst that starts at a zero crossing, so the first samples are
        // not small for a reason that has nothing to do with the gate.
        std::vector<float> in(static_cast<size_t>(0.2f * kRate), 0.0f);
        for (size_t i = 0; i < in.size(); ++i) {
            in[i] = 0.4f * std::sin(2.0 * M_PI * 1000.0 * static_cast<double>(i) / kRate);
        }
        const auto out = run(fx, in);
        // Where the envelope of the output first reaches 90% of the input's.
        // Measured on the peak of each cycle so a sine's own zeros do not
        // count as the gate being shut.
        int64_t at = -1;
        for (size_t i = 0; i + 48 < out.size(); i += 48) {
            if (peakOver(out, i, i + 48) >= 0.9f * peakOver(in, i, i + 48)) { at = static_cast<int64_t>(i); break; }
        }
        const float tookMs = at < 0 ? 1e9f : 1000.0f * static_cast<float>(at) / kRate;
        // A one-pole reaches 90% in 2.3 time constants, and the measurement
        // is quantised to a millisecond of cycles either way.
        char label[64];
        snprintf(label, sizeof(label), "%.1f ms attack opens in about that", ms);
        ok(label,
           tookMs > 0.0f && tookMs < 2.3f * ms + 2.0f,
           std::string("took ") + std::to_string(tookMs) + " ms");
    }
}

void holdCarriesAGap() {
    printf("- hold carries a gap, and without it the gap is a hole\n");
    // A tone with 60 ms of silence in the middle of it - the gap between two
    // phrases rather than between two cycles. Shorter than about 12 ms and
    // neither setting closes, because the detector itself takes ten of those
    // to fall from a loud note to under the threshold.
    auto withGap = [] {
        auto x = tone(1000.0f, 0.4f, 0.4f);
        const size_t from = static_cast<size_t>(0.2f * kRate), to = from + static_cast<size_t>(0.06f * kRate);
        for (size_t i = from; i < to; ++i) x[i] = 0.0f;
        return x;
    };
    // Measured in the five milliseconds *after* the gap, with an attack slow
    // enough that re-opening takes a moment: a gate that held through the gap
    // is already at full there, one that shut is on its way back up. With a
    // one-millisecond attack both are open again before this window ends and
    // the difference - which is perfectly audible on a held note - would be
    // invisible to the harness.
    const size_t after = static_cast<size_t>(0.2605f * kRate);
    const size_t window = static_cast<size_t>(0.005f * kRate);
    {
        Gate fx = make(-45.0f, 4.0f, 20.0f, 200.0f, 30.0f);
        const auto out = run(fx, withGap());
        ok("hold 200 ms: the note after a 60 ms gap is unbroken",
           dB(peakOver(out, after, after + window)) > dB(0.4) - 1.0,
           std::string("after ") + std::to_string(dB(peakOver(out, after, after + window))) + " dB");
    }
    {
        Gate fx = make(-45.0f, 4.0f, 20.0f, 0.0f, 30.0f);
        const auto out = run(fx, withGap());
        ok("hold 0 ms: the same gap closes it",
           dB(peakOver(out, after, after + window)) < dB(0.4) - 6.0,
           std::string("after ") + std::to_string(dB(peakOver(out, after, after + window))) + " dB");
    }
}

/** How many times the gain crosses halfway, which is what chatter sounds like. */
int crossings(const std::vector<float> &out, const std::vector<float> &in) {
    int n = 0;
    bool wasOpen = false;
    for (size_t i = 0; i + 48 < out.size(); i += 48) {
        const float a = peakOver(in, i, i + 48);
        if (a < 1e-6f) continue;
        const bool isOpen = peakOver(out, i, i + 48) > 0.5f * a;
        if (i > 0 && isOpen != wasOpen) ++n;
        wasOpen = isOpen;
    }
    return n;
}

void hysteresisStopsTheChatter() {
    printf("- a signal sitting on the threshold\n");
    // A tone whose level drifts slowly across the threshold: the classic way
    // to make a gate stutter, and exactly what a held note fading out does.
    std::vector<float> in(static_cast<size_t>(1.0f * kRate));
    for (size_t i = 0; i < in.size(); ++i) {
        const double t = static_cast<double>(i) / kRate;
        const double lvl = 0.0056 * (1.0 + 0.10 * std::sin(2.0 * M_PI * 3.0 * t)); // -45 dB, +-0.9 dB
        in[i] = static_cast<float>(lvl * std::sin(2.0 * M_PI * 220.0 * t));
    }
    Gate none = make(-45.0f, 0.0f, 1.0f, 0.0f, 10.0f);
    Gate some = make(-45.0f, 6.0f, 1.0f, 0.0f, 10.0f);
    const int a = crossings(run(none, in), in);
    const int b = crossings(run(some, in), in);
    ok("hysteresis of 6 dB stops it flapping",
       b < a && b <= 1,
       std::string("without ") + std::to_string(a) + " transitions, with " + std::to_string(b));
}

void duckIsARangeNotAMute() {
    printf("- duck: how far down closed is\n");
    for (float duckDb : {-12.0f, -24.0f}) {
        Gate fx = make(-45.0f, 4.0f, 1.0f, 0.0f, 5.0f, duckDb);
        const auto in = tone(440.0f, 0.005f, 0.4f); // -46 dBFS: under the threshold
        const auto out = run(fx, in);
        const float got = dB(peakOver(out, kRate / 4, out.size())) - dB(0.005);
        ok((std::string("duck ") + std::to_string(static_cast<int>(duckDb)) + " dB attenuates by that much").c_str(),
           std::fabs(got - duckDb) < 0.5,
           std::string("measured ") + std::to_string(got) + " dB");
    }
}

void theKeyFilterListensAbove() {
    printf("- key: what the detector is allowed to hear\n");
    const auto rumble = tone(60.0f, 0.056f, 0.3f);  // -25 dBFS
    const auto note = tone(1000.0f, 0.056f, 0.3f);  // the same level
    {
        Gate open = make(-45.0f, 4.0f, 1.0f, 20.0f, 50.0f, -90.0f, 20.0f);
        const auto out = run(open, rumble);
        ok("key wide open: 60 Hz opens it",
           dB(peakOver(out, kRate / 5, out.size())) > dB(0.056) - 1.0);
    }
    {
        Gate keyed = make(-45.0f, 4.0f, 1.0f, 20.0f, 50.0f, -90.0f, 500.0f);
        const auto out = run(keyed, rumble);
        ok("key at 500 Hz: the same 60 Hz does not",
           dB(peakOver(out, kRate / 5, out.size())) < dB(0.056) - 20.0,
           std::string("out ") + std::to_string(dB(peakOver(out, kRate / 5, out.size()))) + " dB");
    }
    {
        Gate keyed = make(-45.0f, 4.0f, 1.0f, 20.0f, 50.0f, -90.0f, 500.0f);
        const auto out = run(keyed, note);
        ok("key at 500 Hz: a note at the same level does",
           dB(peakOver(out, kRate / 5, out.size())) > dB(0.056) - 1.0,
           std::string("out ") + std::to_string(dB(peakOver(out, kRate / 5, out.size()))) + " dB");
    }
}

void theDetectorIsShared() {
    printf("- one detector for the pair\n");
    // Loud in the left, quiet in the right. Two independent gates would shut
    // the right channel and the image would step sideways on every note.
    Gate fx = make(-45.0f, 4.0f, 1.0f, 20.0f, 50.0f);
    const auto loud = tone(440.0f, 0.4f, 0.3f);
    const auto quiet = tone(440.0f, 0.004f, 0.3f); // -48 dBFS, under the threshold
    std::vector<float> l = loud, r = quiet;
    for (size_t at = 0; at + kBlock <= l.size(); at += kBlock) fx.run(l.data() + at, r.data() + at, kBlock, true);
    const float got = dB(peakOver(r, kRate / 5, r.size())) - dB(0.004);
    ok("a burst in one channel opens both", std::fabs(got) < 0.2,
       std::string("right channel ") + std::to_string(got) + " dB");
}

void resetIsShut() {
    printf("- reset\n");
    Gate fx = make(-45.0f, 4.0f, 50.0f, 200.0f, 500.0f);
    const auto in = tone(440.0f, 0.5f, 0.2f);
    run(fx, in);            // leave it wide open
    fx.reset();
    const auto out = run(fx, in);
    // With a 50 ms attack, a gate that reset open would pass the first
    // millisecond at full. One that reset shut cannot.
    ok("a reset gate starts shut", peakOver(out, 0, 48) < 0.05f,
       std::string("first ms peaked at ") + std::to_string(peakOver(out, 0, 48)));
}

// --- the habit that only shows on real material -----------------------------------

/**
 * A plucked note over a hiss floor.
 *
 * The harness's own tones have no noise, no decay and no harmonics, and a
 * gate that passes every one of the checks above can still chop the tail off
 * a note or breathe on the hiss. So this one is built to be awkward: six
 * harmonics with a slightly stiff string's stretch, a decay that takes the
 * note down through the threshold rather than stopping at it, and a floor of
 * hiss underneath the whole thing.
 */
void aPluckedNoteOverHiss() {
    printf("- a plucked note over a hiss floor\n");
    const float f0 = 82.41f; // low E
    const double floorAmp = 0.0018;  // about -55 dBFS
    std::vector<float> in(static_cast<size_t>(2.0f * kRate), 0.0f);
    uint32_t rng = 0x12345678u;
    for (size_t i = 0; i < in.size(); ++i) {
        const double t = static_cast<double>(i) / kRate;
        double v = 0.0;
        if (t >= 0.25 && t < 1.5) {
            const double age = t - 0.25;
            for (int h = 1; h <= 6; ++h) {
                const double stretch = std::sqrt(1.0 + 0.0002 * h * h); // string stiffness
                const double decay = std::exp(-age * (1.2 + 0.55 * h));
                v += (0.55 / h) * decay * std::sin(2.0 * M_PI * f0 * h * stretch * age);
            }
        }
        rng = rng * 1664525u + 1013904223u;
        v += floorAmp * ((static_cast<double>(rng >> 8) / 8388608.0) - 1.0);
        in[i] = static_cast<float>(v);
    }

    Gate fx = make(-45.0f, 6.0f, 1.0f, 80.0f, 250.0f);
    const auto out = run(fx, in);

    const size_t beforeTo = static_cast<size_t>(0.20f * kRate);
    ok("the hiss before the note is gone",
       dB(peakOver(out, 0, beforeTo)) < dB(floorAmp) - 20.0,
       std::string("floor ") + std::to_string(dB(peakOver(out, 0, beforeTo))) + " dB, hiss " +
           std::to_string(dB(floorAmp)) + " dB");

    // The attack of the note itself, unchanged - measured over 25 ms, because
    // a one-millisecond attack is *by definition* not transparent over the two
    // milliseconds it spends opening, and this plucked note happens to peak
    // inside them. Anything still down after 25 ms is the gate's doing.
    const size_t onset = static_cast<size_t>(0.25f * kRate);
    const size_t span = static_cast<size_t>(0.025f * kRate);
    const double pick = dB(peakOver(out, onset, onset + span)) - dB(peakOver(in, onset, onset + span));
    ok("the pick is not softened", pick > -1.0,
       std::string("first 25 ms is ") + std::to_string(pick) + " dB off the input");

    // And the tail: the note is still audible while it is still above the
    // threshold. A gate that closes on its own hysteresis band would have
    // taken half a second off this.
    size_t lastAudible = 0;
    for (size_t i = 0; i + 480 < in.size(); i += 480) {
        if (dB(peakOver(in, i, i + 480)) > -45.0) lastAudible = i;
    }
    const float kept = dB(peakOver(out, lastAudible, lastAudible + 480)) -
                       dB(peakOver(in, lastAudible, lastAudible + 480));
    ok("the tail is not cut while it is still over the threshold",
       kept > -3.0,
       std::string("last audible 40 ms is ") + std::to_string(kept) + " dB off the input");
}

} // namespace

int main() {
    printf("the gate\n");
    itShutsAndItOpens();
    theAttackIsTheAttack();
    holdCarriesAGap();
    hysteresisStopsTheChatter();
    duckIsARangeNotAMute();
    theKeyFilterListensAbove();
    theDetectorIsShared();
    resetIsShut();
    aPluckedNoteOverHiss();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
