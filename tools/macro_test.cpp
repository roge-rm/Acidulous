// Checks the macro routes of Trinity, Ratio, Reflux, Cumulus, Formulate, Hexbeat, Genesis, Resonance, Filament and Timber move the destinations they name.
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
#include <engine/machine/filament/Filament.h>
#include <engine/machine/formulate/Formulate.h>
#include <engine/machine/genesis/Genesis.h>
#include <engine/machine/hexbeat/Hexbeat.h>
#include <engine/machine/ratio/Ratio.h>
#include <engine/machine/reflux/Reflux.h>
#include <engine/machine/resonance/Resonance.h>
#include <engine/machine/timber/Timber.h>
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
// The note to play: 57 for the pitched machines, the kick for Hexbeat.
int gNote = 57;

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
    m->noteOn(gNote, 100);
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
    } else if (std::strcmp(type, "Genesis") == 0) {
        // The kick starts at 40 Hz, so the octave up stays inside its 90 Hz top.
        gUnits = {{"kicktune", 40.0f}};
        const auto routed = play(&up);
        gUnits = {{"kicktune", 80.0f}};
        const auto tuned = play(nullptr);
        gUnits.clear();
        double worst = 0, peak = 0;
        for (size_t i = 0; i < tuned.size(); ++i) {
            worst = std::max(worst, double(std::fabs(tuned[i] - routed[i])));
            peak = std::max(peak, double(std::fabs(tuned[i])));
        }
        std::snprintf(buf, sizeof(buf), "worst difference %.2g of peak %.2g", worst, peak);
        ok(peak > 1e-3 && worst < peak * 1e-3 && routed != base, who + "a pitch route matches the kick's tune knob", buf);
    } else if (std::strcmp(type, "Resonance") == 0) {
        // Every pad's tune goes up an octave: the pads are all at 120 Hz, so 240 Hz is the same.
        const auto routed = play(&up);
        for (int pad = 0; pad < 8; ++pad) gUnits.push_back({"p0" + std::to_string(pad) + "_tune", 240.0f});
        const auto tuned = play(nullptr);
        gUnits.clear();
        double worst = 0, peak = 0;
        for (size_t i = 0; i < tuned.size(); ++i) {
            worst = std::max(worst, double(std::fabs(tuned[i] - routed[i])));
            peak = std::max(peak, double(std::fabs(tuned[i])));
        }
        std::snprintf(buf, sizeof(buf), "worst difference %.2g of peak %.2g", worst, peak);
        ok(peak > 1e-3 && worst < peak * 1e-3 && routed != base, who + "a pitch route matches the pads' tune", buf);
    } else if (std::strcmp(type, "Filament") == 0 || std::strcmp(type, "Timber") == 0) {
        // A plucked string's (or a reed's onset's) zero crossings wander, so the route must match the transpose knob.
        gUnits = {{"transpose", 12.0f}};
        const auto tuned = play(nullptr);
        gUnits.clear();
        const auto routed = play(&up);
        double worst = 0, peak = 0;
        for (size_t i = 0; i < tuned.size(); ++i) {
            worst = std::max(worst, double(std::fabs(tuned[i] - routed[i])));
            peak = std::max(peak, double(std::fabs(tuned[i])));
        }
        std::snprintf(buf, sizeof(buf), "worst difference %.2g of peak %.2g", worst, peak);
        ok(peak > 1e-3 && worst < peak * 1e-3 && routed != base, who + "a pitch route matches the transpose knob", buf);
    } else if (std::strcmp(type, "Hexbeat") == 0) {
        // A drum has no steady pitch to count, so the route must match the kick's tune knob.
        gUnits = {{"kick_tune", 110.0f}};
        const auto tuned = play(nullptr);
        gUnits.clear();
        const auto routed = play(&up);
        double worst = 0, peak = 0;
        for (size_t i = 0; i < tuned.size(); ++i) {
            worst = std::max(worst, double(std::fabs(tuned[i] - routed[i])));
            peak = std::max(peak, double(std::fabs(tuned[i])));
        }
        std::snprintf(buf, sizeof(buf), "worst difference %.2g of peak %.2g", worst, peak);
        ok(peak > 1e-3 && worst < peak * 1e-3 && routed != base, who + "a pitch route matches the kick's tune knob", buf);
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

// Hexbeat's drive and its other routes: the drive knob's default is the old
// fixed 0.375, a route to it matches the knob, and a route to a decay changes
// the next hit the way the knob does.
void hexbeatRoutes() {
    using H = acidulous::machine::Hexbeat;
    gType = "Hexbeat";
    gDests = H::DestCount;
    gNote = 36;
    char buf[96];
    Machine *m = MachineRegistry::create("Hexbeat");
    m->prepare(kRate);
    const ParamDef &d = m->params().def(m->params().indexOf("drive"));
    ok(d.def == 0.375f && m->params().get(m->params().indexOf("drive")) == 0.375f, "Hexbeat: drive starts at the old fixed 0.375", "");
    delete m;

    // Drive 1.0 in the knob's own units: (1.0 - 0.125) / 2.
    gUnits = {{"drive", 1.0f}};
    const auto knob = play(nullptr);
    gUnits.clear();
    Route r{4, 2, H::DstDrive, (1.0f - 0.375f) / 2.0f, 1.0f};
    const auto routed = play(&r);
    double worst = 0;
    for (size_t i = 0; i < knob.size(); ++i) worst = std::max(worst, double(std::fabs(knob[i] - routed[i])));
    std::snprintf(buf, sizeof(buf), "worst difference %.2g", worst);
    ok(worst < 1e-5 && routed != play(nullptr), "Hexbeat: a route to drive matches the knob", buf);

    // A longer kick decay rings longer on the very next hit.
    const auto base = play(nullptr);
    Route longer{1, 1, H::DstKickDecay, 0.3f, 1.0f};
    const auto ring = play(&longer);
    const size_t tail = base.size() / 2;
    std::snprintf(buf, sizeof(buf), "tail rms %.4f -> %.4f", rms(base, tail), rms(ring, tail));
    ok(rms(ring, tail) > rms(base, tail) * 1.5, "Hexbeat: a route to kick decay lengthens the next hit", buf);
    gNote = 57;
}

// Genesis's other routes: a route to drive matches the knob, a route to a
// decay changes the next hit.
void genesisRoutes() {
    using G = acidulous::machine::Genesis;
    gType = "Genesis";
    gDests = G::DestCount;
    gNote = 36;
    char buf[96];
    // Drive 0.5 against a default of 0.15.
    gUnits = {{"drive", 0.5f}};
    const auto knob = play(nullptr);
    gUnits.clear();
    Route r{4, 2, G::DstDrive, 0.35f, 1.0f};
    const auto routed = play(&r);
    double worst = 0;
    for (size_t i = 0; i < knob.size(); ++i) worst = std::max(worst, double(std::fabs(knob[i] - routed[i])));
    std::snprintf(buf, sizeof(buf), "worst difference %.2g", worst);
    ok(worst < 1e-5 && routed != play(nullptr), "Genesis: a route to drive matches the knob", buf);

    const auto base = play(nullptr);
    Route longer{1, 1, G::DstKickDecay, 0.3f, 1.0f};
    const auto ring = play(&longer);
    const size_t tail = base.size() / 2;
    std::snprintf(buf, sizeof(buf), "tail rms %.4f -> %.4f", rms(base, tail), rms(ring, tail));
    ok(rms(ring, tail) > rms(base, tail) * 1.3, "Genesis: a route to kick decay lengthens the next hit", buf);

    gNote = 57;
}

// Resonance's other routes: all-pads decay lengthens the hit, a pad's level
// route moves that pad and no other.
void resonanceRoutes() {
    using R = acidulous::machine::Resonance;
    gType = "Resonance";
    gDests = R::DestCount;
    gNote = 36;
    char buf[96];
    const auto base = play(nullptr);
    Route longer{1, 1, R::DstDecay, 0.3f, 1.0f};
    const auto ring = play(&longer);
    const size_t tail = base.size() / 2;
    // The longer pad is trimmed quieter, so compare how much of the hit is left at the end.
    auto left = [&](const std::vector<float> &v) { return rms(v, tail) / rms(v, 0); };
    std::snprintf(buf, sizeof(buf), "left at the end %.3f -> %.3f", left(base), left(ring));
    ok(left(ring) > left(base) * 1.1, "Resonance: a route to every pad's decay lengthens the next hit", buf);

    // Pad 1 struck, coupling off so no other pad rings. Its level default is 0.8 of 0..1.5.
    gNote = 37;
    gUnits = {{"coupling", 0.0f}};
    const auto one = play(nullptr);
    Route other{2, 1, R::DstLevel + 0, 0.35f, 1.0f};
    ok(play(&other) == one, "Resonance: pad 0's level route leaves pad 1 alone", "");
    Route mine{2, 1, R::DstLevel + 1, 0.35f, 1.0f};
    const auto routed = play(&mine);
    gUnits = {{"coupling", 0.0f}, {"p01_level", 0.8f + 0.35f * 1.5f}};
    const auto knob = play(nullptr);
    gUnits.clear();
    double worst = 0;
    for (size_t i = 0; i < knob.size(); ++i) worst = std::max(worst, double(std::fabs(knob[i] - routed[i])));
    std::snprintf(buf, sizeof(buf), "worst difference %.2g", worst);
    ok(worst < 1e-5 && routed != one, "Resonance: pad 1's level route matches its level knob", buf);
    gNote = 57;
}

// The difference between two renders, as an rms.
double diffRms(const std::vector<float> &a, const std::vector<float> &b, size_t upTo = 0) {
    const size_t n = upTo == 0 ? a.size() : upTo;
    double s = 0;
    for (size_t i = 0; i < n; ++i) s += double(a[i] - b[i]) * (a[i] - b[i]);
    return std::sqrt(s / double(n));
}

// Filament's other routes: routes to sustain and to the vibrato match their
// knobs, the vibrato moves the note, and its delay holds it back.
void filamentRoutes() {
    using F = acidulous::machine::Filament;
    gType = "Filament";
    gDests = F::MacroDestCount;
    gNote = 57;
    char buf[96];
    auto same = [&](const char *what, const std::vector<float> &a, const std::vector<float> &b) {
        double worst = 0, peak = 0;
        for (size_t i = 0; i < a.size(); ++i) {
            worst = std::max(worst, double(std::fabs(a[i] - b[i])));
            peak = std::max(peak, double(std::fabs(a[i])));
        }
        std::snprintf(buf, sizeof(buf), "worst difference %.2g of peak %.2g", worst, peak);
        ok(peak > 1e-3 && worst < peak * 1e-4, what, buf);
    };
    const auto base = play(nullptr);

    // Sustain 0.8 by default, so +0.2 is the top.
    gUnits = {{"sustain", 1.0f}};
    const auto knob = play(nullptr);
    gUnits.clear();
    Route r{5, 3, F::McSustain, 0.2f, 1.0f};
    const auto routed = play(&r);
    same("Filament: a route to sustain matches the knob", knob, routed);

    // 50 cents of vibrato from the knob and from a route.
    gUnits = {{"vibdepth", 50.0f}};
    const auto vib = play(nullptr);
    gUnits.clear();
    Route v{2, 4, F::McVibDepth, 0.5f, 1.0f};
    same("Filament: a route to vibrato depth matches the knob", vib, play(&v));
    std::snprintf(buf, sizeof(buf), "rms difference %.4f of %.4f", diffRms(vib, base), rms(base, 0));
    ok(diffRms(vib, base) > rms(base, 0) * 0.05, "Filament: the vibrato moves the note", buf);

    // Two seconds of fade-in: over the first 60 ms the note has barely moved.
    // A dark string picked off the comb's integer steps, so the difference
    // is the pitch rather than the pick or the top partials.
    gUnits = {{"position", 0.31f}, {"tone", 0.1f}};
    const auto darkBase = play(nullptr);
    gUnits.push_back({"vibdepth", 50.0f});
    const auto darkVib = play(nullptr);
    gUnits.push_back({"vibdelay", 2.0f});
    const auto late = play(nullptr);
    gUnits.clear();
    const size_t front = 2880;
    std::snprintf(buf, sizeof(buf), "rms difference %.5f against %.5f", diffRms(late, darkBase, front),
                  diffRms(darkVib, darkBase, front));
    ok(diffRms(late, darkBase, front) < diffRms(darkVib, darkBase, front) * 0.2,
       "Filament: the vibrato delay holds it back", buf);

    // A route to the rate matches the knob.
    gUnits = {{"vibdepth", 50.0f}, {"vibrate", 10.0f}};
    const auto fast = play(nullptr);
    gUnits.clear();
    Route rate{7, 1, F::McVibRate, 1.0f, 1.0f};
    gUnits = {{"vibdepth", 50.0f}};
    const auto fastRouted = play(&rate);
    gUnits.clear();
    same("Filament: a route to vibrato rate matches the knob", fast, fastRouted);
}

// A note at [first], held, and a second at 60 slurred onto it at [slurAt]
// samples, from a fresh Timber with [units] set.
// A negative [slurAt] holds [first] alone.
std::vector<float> timberSlur(const std::vector<std::pair<std::string, float>> &units, int32_t slurAt,
                              uint8_t first = 57) {
    Machine *m = MachineRegistry::create("Timber");
    m->prepare(kRate);
    ParamSet &p = m->params();
    for (const auto &[name, value] : units) {
        const int32_t i = p.indexOf(name.c_str());
        p.set(i, p.def(i).unmap(value));
    }
    p.jumpAll();
    m->reset();
    m->noteOn(first, 100);
    std::vector<float> out;
    float L[kBlock], R[kBlock];
    for (int32_t b = 0; b < kBlocks * (slurAt < 0 ? 4 : 2); ++b) {
        if (b * kBlock == slurAt) m->noteOn(60, 100);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) out.push_back(L[i]);
    }
    delete m;
    return out;
}

double rmsRange(const std::vector<float> &v, size_t from, size_t to) {
    double s = 0;
    for (size_t i = from; i < to; ++i) s += double(v[i]) * v[i];
    return std::sqrt(s / double(to - from));
}

// Timber's breath swell: a route to it matches the knob, a rising swell
// starts quiet and arrives, a falling one starts loud, and a slurred note
// carries on the swell it joined rather than starting another.
void timberRoutes() {
    using Tb = acidulous::machine::Timber;
    gType = "Timber";
    gDests = Tb::MacroDestCount;
    gNote = 57;
    char buf[96];
    auto same = [&](const char *what, const std::vector<float> &a, const std::vector<float> &b) {
        double worst = 0, peak = 0;
        for (size_t i = 0; i < a.size(); ++i) {
            worst = std::max(worst, double(std::fabs(a[i] - b[i])));
            peak = std::max(peak, double(std::fabs(a[i])));
        }
        std::snprintf(buf, sizeof(buf), "worst difference %.2g of peak %.2g", worst, peak);
        ok(peak > 1e-3 && worst < peak * 1e-4, what, buf);
    };
    gUnits = {{"swelltime", 0.1f}};
    const auto base = play(nullptr);

    // Swell is -1..1 from 0, so +0.4 of its range is 0.8.
    gUnits = {{"swelltime", 0.1f}, {"swell", 0.8f}};
    const auto rise = play(nullptr);
    gUnits = {{"swelltime", 0.1f}};
    Route r{4, 2, Tb::McSwell, 0.4f, 1.0f};
    same("Timber: a route to swell matches the knob", rise, play(&r));

    const size_t front = 2400, tail = 7000;
    std::snprintf(buf, sizeof(buf), "front %.4f against %.4f", rmsRange(rise, 0, front), rmsRange(base, 0, front));
    ok(rmsRange(rise, 0, front) < rmsRange(base, 0, front) * 0.5, "Timber: a rising swell starts quiet", buf);
    std::snprintf(buf, sizeof(buf), "tail %.4f against %.4f", rmsRange(rise, tail, rise.size()),
                  rmsRange(base, tail, base.size()));
    ok(rmsRange(rise, tail, rise.size()) > rmsRange(base, tail, base.size()) * 0.7,
       "Timber: a rising swell arrives", buf);

    gUnits = {{"swelltime", 0.1f}, {"swell", -0.8f}};
    const auto fall = play(nullptr);
    gUnits.clear();
    std::snprintf(buf, sizeof(buf), "front %.4f against %.4f", rmsRange(fall, 0, 4800), rmsRange(base, 0, 4800));
    ok(rmsRange(fall, 0, 4800) > rmsRange(base, 0, 4800) * 1.1, "Timber: a falling swell starts loud", buf);

    // Every sample moves the breath, so nothing in the swell steps: the
    // largest jump between samples is no bigger than without it.
    double jumpRise = 0, jumpBase = 0;
    for (size_t i = 1; i < base.size(); ++i) {
        jumpRise = std::max(jumpRise, double(std::fabs(rise[i] - rise[i - 1])));
        jumpBase = std::max(jumpBase, double(std::fabs(base[i] - base[i - 1])));
    }
    std::snprintf(buf, sizeof(buf), "largest step %.4f against %.4f", jumpRise, jumpBase);
    ok(jumpRise <= jumpBase * 1.05, "Timber: the swell has no steps in it", buf);

    // Slurred at 120 ms, after the swell is done: the second note does not
    // start another one, so it sounds as it does with no swell at all.
    const int32_t slurAt = 90 * kBlock;
    const auto slurBase = timberSlur({{"swelltime", 0.05f}}, slurAt);
    const auto slurSwell = timberSlur({{"swelltime", 0.05f}, {"swell", 1.0f}}, slurAt);
    // Window by window, 5 ms each, so a swell that restarts part way (a dip
    // of a few milliseconds) can't hide in the average.
    const double a = rmsRange(slurSwell, slurAt, slurAt + 2400), b = rmsRange(slurBase, slurAt, slurAt + 2400);
    double worstWin = 10.0;
    for (int32_t w = 0; w < 10; ++w) {
        const size_t from = slurAt + w * 240;
        const double bw = rmsRange(slurBase, from, from + 240);
        if (bw > 1e-4) worstWin = std::min(worstWin, rmsRange(slurSwell, from, from + 240) / bw);
    }
    std::snprintf(buf, sizeof(buf), "after the slur %.4f against %.4f, worst 5 ms %.3f", a, b, worstWin);
    ok(b > 1e-3 && std::fabs(a - b) < b * 0.05 && worstWin > 0.9, "Timber: a slurred note carries on the swell", buf);

    // Flutter on an air jet: the tongue breaks the jet up at the flutter
    // rate, so the level moves at that rate, and smoothly.
    const std::vector<std::pair<std::string, float>> jet = {
        {"family", 2.0f}, {"bore", 1.0f}, {"body", 261.6f}, {"breath", 0.45f}, {"lattice", 1400.0f},
        {"pressure", 0.8f}, {"vibrato", 0.0f}, {"flutterrate", 25.0f}};
    auto withFlutter = jet;
    withFlutter.push_back({"flutter", 0.6f});
    const auto still = timberSlur(jet, -1, 72);
    const auto fluttered = timberSlur(withFlutter, -1, 72);
    // The level's movement at 25 Hz over the held note, against its mean,
    // from 5 ms block levels.
    auto wobble = [](const std::vector<float> &v) {
        const size_t hop = 240, from = 9600;
        double re = 0, im = 0, mean = 0;
        int n = 0;
        for (size_t s = from; s + hop <= v.size(); s += hop, ++n) {
            const double lv = rmsRange(v, s, s + hop), t = double(s) / kRate;
            re += lv * std::cos(2 * M_PI * 25.0 * t);
            im += lv * std::sin(2 * M_PI * 25.0 * t);
            mean += lv;
        }
        return mean > 0 ? 2.0 * std::sqrt(re * re + im * im) / mean : 0.0;
    };
    const double wStill = wobble(still), wFlutter = wobble(fluttered);
    std::snprintf(buf, sizeof(buf), "25 Hz movement %.3f against %.3f", wFlutter, wStill);
    ok(wFlutter > 0.15 && wFlutter > wStill * 4, "Timber: flutter moves an air jet", buf);
    double jumpStill = 0, jumpFlutter = 0;
    for (size_t i = 1; i < still.size(); ++i) {
        jumpStill = std::max(jumpStill, double(std::fabs(still[i] - still[i - 1])));
        jumpFlutter = std::max(jumpFlutter, double(std::fabs(fluttered[i] - fluttered[i - 1])));
    }
    std::snprintf(buf, sizeof(buf), "largest step %.4f against %.4f", jumpFlutter, jumpStill);
    ok(jumpFlutter <= jumpStill * 1.05, "Timber: a jet's flutter has no steps in it", buf);
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
    gNote = 36;
    using Hx = acidulous::machine::Hexbeat;
    // Hexbeat's volume is 0 to 1.5 from 0.9, so -0.5 leaves 0.9 - 0.75 = 0.15
    // and -0.25 leaves 0.525.
    basics("Hexbeat", Hx::DestCount, Hx::DstVolume, Hx::DstPitch, Hx::DstOff, Hx::kMacros, 0.15 / 0.9, 0.525 / 0.9);
    hexbeatRoutes();
    using Gn = acidulous::machine::Genesis;
    gNote = 36;
    // Genesis's volume has the same range and default as Hexbeat's.
    basics("Genesis", Gn::DestCount, Gn::DstVolume, Gn::DstPitch, Gn::DstOff, Gn::kMacros, 0.15 / 0.9, 0.525 / 0.9);
    genesisRoutes();
    gNote = 36;
    using Rs = acidulous::machine::Resonance;
    // Resonance's volume is 0 to 1.5 from 0.5, so -0.5 leaves nothing and -0.25 leaves 0.125.
    basics("Resonance", Rs::DestCount, Rs::DstVolume, Rs::DstPitch, Rs::DstOff, Rs::kMacros, 0.0, 0.125 / 0.5);
    resonanceRoutes();
    gNote = 57;
    using Fl = acidulous::machine::Filament;
    // Filament's volume is Reflux's, 0 to 1.5 from 0.8.
    basics("Filament", Fl::MacroDestCount, Fl::McVolume, Fl::McPitch, Fl::McOff, Fl::kMacros, 0.05 / 0.8, 0.425 / 0.8);
    filamentRoutes();
    gNote = 57;
    using Tm = acidulous::machine::Timber;
    // Timber's volume is Filament's, 0 to 1.5 from 0.8.
    basics("Timber", Tm::MacroDestCount, Tm::McVolume, Tm::McPitch, Tm::McOff, Tm::kMacros, 0.05 / 0.8, 0.425 / 0.8);
    timberRoutes();

    std::printf("%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
