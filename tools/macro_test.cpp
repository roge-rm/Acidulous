// Checks the macro routes of Trinity, Ratio, Reflux, Cumulus and Formulate move the destinations they name.
//
// A macro at zero must leave the sound alone, bit for bit. At full, a route
// to amplitude with a known depth must scale the level by a known amount, for
// every macro and every one of its four routes. Another route to pitch must
// move the note by the semitones its depth says.
//
// The LFO step destinations: a square LFO on the level shows where its
// cycles start. A synced one stepped by a macro must start each cycle on its
// new note value's grid from the note-on, also when the knob moves mid-note,
// and a free one must double or halve its rate.

#include <engine/machine/MachineRegistry.h>
#include <engine/machine/cumulus/Cloud.h>
#include <engine/machine/cumulus/Cumulus.h>
#include <engine/machine/formulate/Formulate.h>
#include <engine/machine/ratio/Ratio.h>
#include <engine/machine/reflux/Reflux.h>
#include <engine/machine/trinity/Trinity.h>
#include <engine/dsp/Lfo.h>
#include <engine/dsp/LfoGen.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using namespace acidulous;
using T = acidulous::machine::Trinity;

namespace {

constexpr int32_t kRate = 48000;
constexpr int32_t kBlock = 64;
constexpr int32_t kBlocks = 128;

int checks = 0, failures = 0;

void ok(bool pass, const std::string &what, const std::string &detail) {
    ++checks;
    if (!pass) ++failures;
    std::printf("  %-4s %-58s %s\n", pass ? "ok" : "FAIL", what.c_str(), detail.c_str());
}

// The machine under test; main() runs the basic checks once for each.
const char *gType = "Trinity";
int gDests = T::DestCount;
// One more param to set in play(), for comparing a route against the param it stands in for.
const char *gExtra = nullptr;
float gExtraNorm = 0.0f;
// More params to set in play(), by name and in their own units.
std::vector<std::pair<std::string, float>> gUnits;

struct Route { int macro, route, dest; float depth; float knob; };

void setNorm(ParamSet &p, const std::string &name, float v) {
    const int32_t i = p.indexOf(name.c_str());
    if (i < 0) { std::printf("  FAIL no parameter %s\n", name.c_str()); ++failures; return; }
    p.set(i, v);
}

std::vector<float> play(const Route *r) {
    Machine *m = MachineRegistry::create(gType);
    m->prepare(kRate);
    ParamSet &p = m->params();
    if (r != nullptr) {
        const std::string k = "mac" + std::to_string(r->macro);
        setNorm(p, k, r->knob);
        setNorm(p, k + "_d" + std::to_string(r->route), r->dest / (gDests - 1.0f));
        setNorm(p, k + "_a" + std::to_string(r->route), (r->depth + 1.0f) * 0.5f);
    }
    if (gExtra != nullptr) setNorm(p, gExtra, gExtraNorm);
    for (const auto &[name, value] : gUnits) {
        const int32_t i = p.indexOf(name.c_str());
        if (i < 0) { std::printf("  FAIL no parameter %s\n", name.c_str()); ++failures; continue; }
        p.set(i, p.def(i).unmap(value));
    }
    p.jumpAll();
    // Cumulus plays nothing without a table.
    if (std::strcmp(gType, "Cumulus") == 0)
        m->swapObject(0, acidulous::machine::cumulus::buildCloud(static_cast<acidulous::machine::Cumulus *>(m)->spec(), kRate).release());
    m->allNotesOff();
    m->reset();
    m->noteOn(57, 100);
    std::vector<float> out;
    float L[kBlock], R[kBlock];
    for (int32_t b = 0; b < kBlocks; ++b) {
        m->onBlock(b * 4, b * 4 + 4, 120.0f);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) out.push_back(L[i]);
    }
    delete m;
    return out;
}

double rms(const std::vector<float> &v, size_t from) {
    double s = 0;
    for (size_t i = from; i < v.size(); ++i) s += double(v[i]) * v[i];
    return std::sqrt(s / double(v.size() - from));
}

/** Rising zero crossings per second over the second half, for pitch. */
double hz(const std::vector<float> &v) {
    int n = 0;
    size_t first = 0, last = 0;
    for (size_t i = v.size() / 2 + 1; i < v.size(); ++i)
        if (v[i - 1] < 0 && v[i] >= 0) { if (n == 0) first = i; last = i; ++n; }
    return n < 2 ? 0 : (n - 1) * double(kRate) / double(last - first);
}

// A square LFO 1 on the level, so each cycle starts with a rise.
struct LfoCase {
    int sync = 3;             // the LFO's sync step: 3 is 1/16
    int dest = T::DstLfo1Step;
    float depth = 0.25f;
    float knob = 0.0f;        // from the note-on
    int moveAt = -1;          // the block the knob moves at, or -1
    float knobAfter = 0.0f;
    float lfoDepth = 0.35f;
    bool route = true;
};

constexpr int32_t kLfoBlocks = 1500; // two seconds

std::vector<float> playLfo(const LfoCase &c) {
    Machine *m = MachineRegistry::create("Trinity");
    m->prepare(kRate);
    ParamSet &p = m->params();
    setNorm(p, "l1_wave", 4.0f / (dsp::LfoGen::WaveCount - 1.0f));
    setNorm(p, "l1_sync", c.sync / (1.0f + dsp::Lfo::kRates - 1.0f));
    setNorm(p, "m01_src", T::SrcLfo1 / (T::SourceCount - 1.0f));
    setNorm(p, "m01_dest", T::DstAmp / (T::DestCount - 1.0f));
    setNorm(p, "m01_depth", (c.lfoDepth + 1.0f) * 0.5f);
    setNorm(p, "mac1", c.knob);
    if (c.route) {
        setNorm(p, "mac1_d1", c.dest / (T::DestCount - 1.0f));
        setNorm(p, "mac1_a1", (c.depth + 1.0f) * 0.5f);
    }
    p.jumpAll();
    m->allNotesOff();
    m->reset();
    m->noteOn(81, 100);
    std::vector<float> out;
    float L[kBlock], R[kBlock];
    for (int32_t b = 0; b < kLfoBlocks; ++b) {
        if (b == c.moveAt) setNorm(p, "mac1", c.knobAfter);
        m->onBlock(b * 4, b * 4 + 4, 120.0f);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) out.push_back(L[i]);
    }
    delete m;
    return out;
}

/** Where the LFO's cycles start, in samples: the blocks where the level rises against [flat]. */
std::vector<int> rises(const std::vector<float> &v, const std::vector<float> &flat) {
    std::vector<int> at;
    bool high = true;
    for (int b = 0; b < kLfoBlocks; ++b) {
        double a = 0, f = 0;
        for (int i = b * kBlock; i < (b + 1) * kBlock; ++i) { a += double(v[i]) * v[i]; f += double(flat[i]) * flat[i]; }
        if (f <= 0) continue;
        const bool now = std::sqrt(a / f) > 1.0;
        if (now && !high) at.push_back(b * kBlock);
        high = now;
    }
    return at;
}

/** The furthest [at] is from the grid of [period] samples from the note-on. */
int offGrid(const std::vector<int> &at, double period) {
    int worst = 0;
    for (int t : at) {
        const double k = std::round(t / period);
        worst = std::max(worst, int(std::fabs(t - k * period)));
    }
    return worst;
}

/** The shortest time between two cycle starts. */
int shortest(const std::vector<int> &at) {
    int s = 1 << 30;
    for (size_t i = 1; i < at.size(); ++i) s = std::min(s, at[i] - at[i - 1]);
    return s;
}

/** The mean time between cycle starts from [from] samples on. */
double meanPeriod(const std::vector<int> &at, int from) {
    std::vector<int> late;
    for (int t : at) if (t >= from) late.push_back(t);
    return late.size() < 2 ? 0 : double(late.back() - late.front()) / double(late.size() - 1);
}

std::vector<int> after(const std::vector<int> &at, int from) {
    std::vector<int> out;
    for (int t : at) if (t >= from) out.push_back(t);
    return out;
}

std::vector<int> before(const std::vector<int> &at, int to) {
    std::vector<int> out;
    for (int t : at) if (t < to) out.push_back(t);
    return out;
}

void stepChecks() {
    // 120 bpm: a sixteenth is 6000 samples.
    constexpr double k16 = 6000.0, k32 = 3000.0, k8 = 12000.0;
    constexpr int kTol = 2 * kBlock;
    LfoCase flatCase;
    flatCase.lfoDepth = 0.0f;
    flatCase.route = false;
    const auto flat = playLfo(flatCase);
    char buf[96];

    LfoCase none;
    none.route = false;
    const auto plain = playLfo(none);
    LfoCase zero;
    ok(playLfo(zero) == plain, "a step route at zero leaves the sound alone", "");

    auto grid = [&](const LfoCase &c, double period, const std::string &what) {
        const auto at = rises(playLfo(c), flat);
        const int off = offGrid(at, period);
        const double mean = meanPeriod(at, 0);
        std::snprintf(buf, sizeof(buf), "%zu cycles, every %.0f, %d off the grid", at.size(), mean, off);
        ok(at.size() > 4 && off <= kTol && std::fabs(mean - period) < period * 0.005, what, buf);
    };
    grid(none, k16, "an unstepped 1/16 LFO starts every 6000 samples");
    LfoCase below = zero;
    below.knob = 0.4f;
    grid(below, k16, "a step route under half a step stays at 1/16");
    LfoCase up = zero;
    up.knob = 0.6f;
    grid(up, k32, "a step route past half a step runs at 1/32");
    up.knob = 1.0f;
    grid(up, k32, "a step route at full runs at 1/32");
    LfoCase down = zero;
    down.knob = 1.0f;
    down.depth = -0.25f;
    grid(down, k8, "a negative step route runs at 1/8");
    LfoCase two = zero;
    two.knob = 1.0f;
    two.depth = 0.5f;
    grid(two, 1500.0, "two steps up run at 1/64");

    // The smooth rate destination leaves the grid between its ends: what the
    // step one is for, and proof that the check can fail.
    LfoCase smooth = zero;
    smooth.dest = T::DstLfo1Rate;
    smooth.knob = 0.6f;
    {
        const auto at = rises(playLfo(smooth), flat);
        const int off = std::min(offGrid(at, k16), offGrid(at, k32));
        std::snprintf(buf, sizeof(buf), "%d off the grid", off);
        ok(off > kTol, "the smooth rate destination runs off the grid", buf);
    }

    // The knob moves mid-note, off the grid. Faster: the next 1/32 start is
    // a 1/16 one, and every start after it is on the 1/32 grid.
    const int moveBlock = 337; // 21568 samples, not on either grid
    const int moveAt = moveBlock * kBlock;
    LfoCase faster = zero;
    faster.moveAt = moveBlock;
    faster.knobAfter = 1.0f;
    {
        const auto at = rises(playLfo(faster), flat);
        const auto late = after(at, moveAt);
        std::snprintf(buf, sizeof(buf), "%d off before, %d off after, shortest %d, after every %.0f", offGrid(before(at, moveAt), k16),
                      offGrid(late, k32), shortest(at), meanPeriod(late, moveAt + int(k16)));
        ok(offGrid(before(at, moveAt), k16) <= kTol && offGrid(late, k32) <= kTol && shortest(at) >= k32 - kTol &&
               std::fabs(meanPeriod(late, moveAt + int(k16)) - k32) < k32 * 0.005,
           "a knob moved up mid-note steps to 1/32 on the grid", buf);
    }
    // Slower: it waits for a start both grids share, and no cycle is cut short.
    LfoCase slower = zero;
    slower.knob = 1.0f;
    slower.moveAt = moveBlock;
    slower.knobAfter = 0.0f;
    {
        const auto at = rises(playLfo(slower), flat);
        const auto late = after(at, moveAt + int(k16));
        std::snprintf(buf, sizeof(buf), "%d off after, shortest %d, after every %.0f", offGrid(late, k16), shortest(at),
                      meanPeriod(late, 0));
        ok(offGrid(late, k16) <= kTol && shortest(at) >= k32 - kTol && std::fabs(meanPeriod(late, 0) - k16) < k16 * 0.005,
           "a knob moved down mid-note steps to 1/16 on the grid", buf);
    }

    // A free LFO doubles its rate in Hz.
    LfoCase freeBase = none;
    freeBase.sync = 0;
    LfoCase freeUp = zero;
    freeUp.sync = 0;
    freeUp.knob = 1.0f;
    {
        const double p0 = meanPeriod(rises(playLfo(freeBase), flat), 0);
        const double p1 = meanPeriod(rises(playLfo(freeUp), flat), 0);
        std::snprintf(buf, sizeof(buf), "every %.0f -> every %.0f", p0, p1);
        ok(p0 > 0 && std::fabs(p1 / p0 - 0.5) < 0.02, "a step route doubles a free LFO's rate", buf);
    }
}

/**
 * The checks every machine with macros shares. [dstAmp], [dstPitch] and [dstOff] are its own destination numbers.
 * [full] and [halfWant] are the level ratios an amp route of -0.5 gives at full and half knob.
 */
void basics(const char *type, int dests, int dstAmp, int dstPitch, int dstOff, int macros,
            double full = 2.0 / 7.0, double halfWant = 0.45 / 0.7) {
    gType = type;
    gDests = dests;
    const std::string who = std::string(type) + ": ";
    const auto base = play(nullptr);
    ok(rms(base, 0) > 1e-4, who + "makes sound", "");
    const double baseRms = rms(base, 0);

    for (int macro = 1; macro <= macros; ++macro) {
        // At zero the route does nothing, bit for bit.
        Route off{macro, 1, dstAmp, -0.5f, 0.0f};
        ok(play(&off) == base, who + "macro " + std::to_string(macro) + " at zero leaves the sound alone", "");
        for (int route = 1; route <= 4; ++route) {
            // On Trinity, volume 0.7 minus 0.5 leaves 0.2, so the level is 2/7 of the base.
            Route r{macro, route, dstAmp, -0.5f, 1.0f};
            const double ratio = rms(play(&r), 0) / baseRms;
            char buf[64];
            std::snprintf(buf, sizeof(buf), "ratio %.3f (want %.3f)", ratio, full);
            ok(std::fabs(ratio - full) < 0.02, who + "macro " + std::to_string(macro) + " route " + std::to_string(route) + " scales the level", buf);
        }
    }

    // Half the knob is half the depth.
    Route half{1, 1, dstAmp, -0.5f, 0.5f};
    const double h = rms(play(&half), 0) / baseRms;
    char buf[64];
    std::snprintf(buf, sizeof(buf), "ratio %.3f (want %.3f)", h, halfWant);
    ok(std::fabs(h - halfWant) < 0.02, who + "half the knob is half the depth", buf);

    // Pitch: the destination is in units of 24 semitones, so 1/24 of depth is one.
    Route up{3, 2, dstPitch, 12.0f / 24.0f, 1.0f};
    if (std::strcmp(type, "Cumulus") == 0) {
        // A cloud has no pitch to count, so the route must match the octave knob.
        gExtra = "octave";
        gExtraNorm = 4.0f / 6.0f;
        const auto octave = play(nullptr);
        gExtra = nullptr;
        const auto routed = play(&up);
        double worst = 0, peak = 0;
        for (size_t i = 0; i < octave.size(); ++i) {
            worst = std::max(worst, double(std::fabs(octave[i] - routed[i])));
            peak = std::max(peak, double(std::fabs(octave[i])));
        }
        std::snprintf(buf, sizeof(buf), "worst difference %.2g of peak %.2g", worst, peak);
        ok(peak > 1e-3 && worst < peak * 1e-3 && routed != base, who + "a pitch route matches the octave knob", buf);
    } else {
        const double f0 = hz(base);
        const double f1 = hz(play(&up));
        std::snprintf(buf, sizeof(buf), "%.1f Hz -> %.1f Hz", f0, f1);
        ok(f0 > 0 && std::fabs(f1 / f0 - 2.0) < 0.03, who + "a pitch route moves the note an octave", buf);
    }

    // A route to nothing does nothing.
    Route none{2, 3, dstOff, 1.0f, 1.0f};
    ok(play(&none) == base, who + "a route to off does nothing", "");
}

// Formulate's filter envelope: with the cutoff low, an amount opens the
// front of the note and leaves the tail where it was, and a macro route to
// the amount does the same as the knob.
void formulateFilterEnv() {
    using F = acidulous::machine::Formulate;
    gType = "Formulate";
    gDests = F::DestCount;
    const std::vector<std::pair<std::string, float>> low = {
        {"wave", 2.0f}, {"cutoff", 200.0f}, {"fdecay", 0.05f}, {"fsustain", 0.0f}};
    gUnits = low;
    const auto closed = play(nullptr);
    gUnits = low;
    gUnits.push_back({"fenv", 4.0f});
    const auto open = play(nullptr);
    gUnits = low;
    // 4 octaves is a third of the -6..6 range from the centre.
    Route r{2, 3, F::DstFiltEnv, 1.0f / 3.0f, 1.0f};
    const auto routed = play(&r);
    gUnits.clear();

    const size_t front = kRate / 50, tail = closed.size() * 3 / 4;
    auto seg = [](const std::vector<float> &v, size_t a, size_t b) {
        double s = 0;
        for (size_t i = a; i < b; ++i) s += double(v[i]) * v[i];
        return std::sqrt(s / double(b - a));
    };
    char buf[96];
    const double f0 = seg(closed, 0, front), f1 = seg(open, 0, front);
    std::snprintf(buf, sizeof(buf), "front rms %.4f -> %.4f", f0, f1);
    ok(f1 > f0 * 1.5, "Formulate: the filter envelope opens the front of the note", buf);
    const double t0 = seg(closed, tail, closed.size()), t1 = seg(open, tail, open.size());
    std::snprintf(buf, sizeof(buf), "tail rms %.4f -> %.4f", t0, t1);
    ok(std::fabs(t1 / t0 - 1.0) < 0.05, "Formulate: with no sustain the tail is the filter as set", buf);
    double worst = 0;
    for (size_t i = 0; i < open.size(); ++i) worst = std::max(worst, double(std::fabs(open[i] - routed[i])));
    std::snprintf(buf, sizeof(buf), "worst difference %.2g", worst);
    ok(worst < 1e-4, "Formulate: a macro route to the amount matches the knob", buf);
}

} // namespace

int main() {
    basics("Trinity", T::DestCount, T::DstAmp, T::DstPitch, T::DstOff, T::kMacros);
    stepChecks();
    basics("Ratio", acidulous::machine::Ratio::DestCount, acidulous::machine::Ratio::DstAmp,
           acidulous::machine::Ratio::DstPitch, acidulous::machine::Ratio::DstOff, acidulous::machine::Ratio::kMacros);
    // Reflux moves its volume knob in the knob's own range, 0 to 1.5: 0.8 is
    // 0.533 of it, so -0.5 leaves 0.05 (1/16 of the level) and -0.25 leaves 0.425.
    using X = acidulous::machine::Reflux;
    basics("Reflux", X::DestCount, X::DstAmp, X::DstPitch, X::DstOff, X::kMacros, 0.05 / 0.8, 0.425 / 0.8);
    // Cumulus's volume is 0 to 1.5 and starts at 1.07, so -0.5 leaves 1.07 - 0.75 = 0.32
    // and -0.25 leaves 0.695.
    using C = acidulous::machine::Cumulus;
    basics("Cumulus", C::DestCount, C::DstAmp, C::DstPitch, C::DstOff, C::kMacros, 0.32 / 1.07, 0.695 / 1.07);
    // Formulate's volume is Reflux's, 0 to 1.5 from 0.8.
    using Fo = acidulous::machine::Formulate;
    basics("Formulate", Fo::DestCount, Fo::DstAmp, Fo::DstPitch, Fo::DstOff, Fo::kMacros, 0.05 / 0.8, 0.425 / 0.8);
    formulateFilterEnv();

    std::printf("%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
