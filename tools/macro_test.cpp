// Checks the macro routes of Trinity, Ratio, Reflux, Cumulus, Formulate, Hexbeat, Genesis, Resonance, Filament, Timber, Brazen, Manual, Hammer, Draw, Fret, Tine, Palm, Fathom, Aviary, Tongue, Sympath, Chanter and Pollen move the destinations they name.
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
#include <engine/machine/brazen/Brazen.h>
#include <engine/machine/draw/Draw.h>
#include <engine/machine/fret/Fret.h>
#include <engine/machine/hammer/Hammer.h>
#include <engine/machine/filament/Filament.h>
#include <engine/machine/formulate/Formulate.h>
#include <engine/machine/genesis/Genesis.h>
#include <engine/machine/hexbeat/Hexbeat.h>
#include <engine/machine/manual/Manual.h>
#include <engine/machine/palm/Palm.h>
#include <engine/machine/fathom/Fathom.h>
#include <engine/machine/aviary/Aviary.h>
#include <engine/machine/tongue/Tongue.h>
#include <engine/machine/sympath/Sympath.h>
#include <engine/machine/chanter/Chanter.h>
#include <engine/machine/pollen/Pollen.h>
#include "audition_material.h"
#include <engine/machine/tine/Tine.h>
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

void ok(bool pass, const std::string &what, const std::string &detail);
// [ok] for a [pass] that writes its own [detail]: the detail is read after it.
void ok2(bool pass, const std::string &what, const char *detail) { ok(pass, what, detail); }
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
// The mod wheel sent after the reset, 0..127, or -1 for none.
int32_t gModWheel = -1;
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
    // Pollen plays nothing without a buffer.
    if (std::strcmp(gType, "Pollen") == 0) m->swapObject(0, acidulous::audition::breakLoop().release());
    m->allNotesOff();
    m->reset();
    if (gModWheel >= 0) m->controlChange(1, static_cast<uint8_t>(gModWheel));
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
    } else if (std::strcmp(type, "Filament") == 0 || std::strcmp(type, "Timber") == 0 ||
               std::strcmp(type, "Brazen") == 0 || std::strcmp(type, "Manual") == 0 ||
               std::strcmp(type, "Hammer") == 0 || std::strcmp(type, "Draw") == 0 || std::strcmp(type, "Fret") == 0 ||
               std::strcmp(type, "Tine") == 0 || std::strcmp(type, "Palm") == 0 || std::strcmp(type, "Fathom") == 0 ||
               std::strcmp(type, "Aviary") == 0 || std::strcmp(type, "Tongue") == 0 || std::strcmp(type, "Sympath") == 0 ||
               std::strcmp(type, "Chanter") == 0) {
        // A plucked string's (or a reed's or lips' onset's) zero crossings wander, so the route must match the transpose knob.
        // Draw, Fret, Tine, Palm, Fathom, Aviary, Tongue, Sympath and Chanter have no transpose, so an octave up.
        const bool byOctave = std::strcmp(type, "Draw") == 0 || std::strcmp(type, "Fret") == 0 ||
                              std::strcmp(type, "Tine") == 0 || std::strcmp(type, "Palm") == 0 ||
                              std::strcmp(type, "Fathom") == 0 || std::strcmp(type, "Aviary") == 0 ||
                              std::strcmp(type, "Tongue") == 0 || std::strcmp(type, "Sympath") == 0 ||
                              std::strcmp(type, "Chanter") == 0;
        const auto kept = gUnits;
        gUnits.push_back({byOctave ? "octave" : "transpose", byOctave ? 1.0f : 12.0f});
        const auto tuned = play(nullptr);
        gUnits = kept;
        const auto routed = play(&up);
        double worst = 0, peak = 0;
        for (size_t i = 0; i < tuned.size(); ++i) {
            worst = std::max(worst, double(std::fabs(tuned[i] - routed[i])));
            peak = std::max(peak, double(std::fabs(tuned[i])));
        }
        std::snprintf(buf, sizeof(buf), "worst difference %.2g of peak %.2g", worst, peak);
        ok(peak > 1e-3 && worst < peak * 1e-3 && routed != base, who + "a pitch route matches the transpose knob", buf);
    } else if (std::strcmp(type, "Pollen") == 0) {
        // A cloud's zero crossings say nothing, so the route must match the transpose knob.
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

double largestStep(const std::vector<float> &v);
double blockEdge(const std::vector<float> &v, size_t from, size_t to);

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

    // Flutter on a reed: the tongue taps the reed shut at the flutter rate,
    // read every sample like the jet's.
    const std::vector<std::pair<std::string, float>> reed = {
        {"family", 0.0f}, {"bore", 0.0f}, {"body", 146.8f}, {"pressure", 0.8f},
        {"vibrato", 0.0f}, {"flutterrate", 25.0f}};
    auto reedFlutter = reed;
    reedFlutter.push_back({"flutter", 0.6f});
    const auto reedStill = timberSlur(reed, -1, 50);
    const auto reedFluttered = timberSlur(reedFlutter, -1, 50);
    const double rStill = wobble(reedStill), rFlutter = wobble(reedFluttered);
    std::snprintf(buf, sizeof(buf), "25 Hz movement %.3f against %.3f", rFlutter, rStill);
    ok(rFlutter > 0.08 && rFlutter > rStill * 4, "Timber: flutter moves a reed", buf);
    std::snprintf(buf, sizeof(buf), "largest step %.4f against %.4f, block edge %.3f against %.3f",
                  largestStep(reedFluttered), largestStep(reedStill), blockEdge(reedFluttered, 2400, 30000),
                  blockEdge(reedStill, 2400, 30000));
    ok(largestStep(reedFluttered) <= largestStep(reedStill) * 1.05 &&
           blockEdge(reedFluttered, 2400, 30000) <= blockEdge(reedStill, 2400, 30000) * 1.1,
       "Timber: a reed's flutter has no steps in it", buf);
}

// One note event for timberEvents: at block [block], note [note] on (or off
// when [on] is false).
struct NoteEvent {
    int32_t block;
    uint8_t note;
    bool on;
};

// A fresh Timber with [units] set, played [events] over [blocks] blocks.
std::vector<float> timberEvents(const std::vector<std::pair<std::string, float>> &units,
                                const std::vector<NoteEvent> &events, int32_t blocks) {
    Machine *m = MachineRegistry::create("Timber");
    m->prepare(kRate);
    ParamSet &p = m->params();
    for (const auto &[name, value] : units) {
        const int32_t i = p.indexOf(name.c_str());
        p.set(i, p.def(i).unmap(value));
    }
    p.jumpAll();
    m->reset();
    std::vector<float> out;
    float L[kBlock], R[kBlock];
    for (int32_t b = 0; b < blocks; ++b) {
        for (const auto &e : events) {
            if (e.block != b) continue;
            if (e.on) m->noteOn(e.note, 100);
            else m->noteOff(e.note);
        }
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) out.push_back(L[i]);
    }
    delete m;
    return out;
}

// The largest second difference in [from, to): a corner, which is what a
// click is.
double largestCorner(const std::vector<float> &v, size_t from, size_t to) {
    double c = 0;
    for (size_t i = std::max<size_t>(from, 2); i < std::min(to, v.size()); ++i)
        c = std::max(c, std::fabs(double(v[i]) - 2.0 * v[i - 1] + v[i - 2]));
    return c;
}

// Timber played mono and legato: a slur onto a held note keeps the level and
// has no corner at the join, a note after the last one let go is tongued
// afresh without a click, and fast notes, even two in a row inside the 2 ms
// fade, never step.
void timberPlaying() {
    char buf[128];
    const std::vector<std::pair<std::string, float>> mono = {
        {"mono", 1.0f}, {"glide", 0.05f}, {"attack", 0.05f}, {"release", 0.3f}, {"cutoff", 1800.0f}};
    const int32_t at = 180, blocks = 300;
    const size_t join = size_t(at) * kBlock;

    // A slur onto the held note.
    {
        const auto v = timberEvents(mono, {{0, 57, true}, {at, 60, true}}, blocks);
        const double held = rmsRange(v, join - 4800, join);
        double low = 10.0;
        for (int32_t w = 0; w < 10; ++w) {
            const size_t from = join + w * 480;
            low = std::min(low, rmsRange(v, from, from + 480) / held);
        }
        const double corner = largestCorner(v, join - 4800, join - 480);
        const double atJoin = largestCorner(v, join - 480, join + 480);
        std::snprintf(buf, sizeof(buf), "lowest 10 ms %.2f of the held note, join corner %.2fx", low,
                      atJoin / std::max(corner, 1e-12));
        ok(low > 0.85 && atJoin < corner * 2.0, "Timber: a slur does not dip or click", buf);
    }
    // A note played after the last one was let go, while its tail sounds:
    // tongued again on a voice of its own rather than slurred, the old tail
    // ringing out under it, and no click.
    {
        const auto v = timberEvents(mono, {{0, 57, true}, {at - 6, 57, false}, {at, 60, true}}, blocks);
        const auto alone = timberEvents(mono, {{0, 60, true}}, blocks - at);
        const double tail = rmsRange(v, join - 480, join);
        const double corner = largestCorner(v, join + 4800, v.size());
        const double atJoin = largestCorner(v, join - 480, join + 480);
        // Tongued afresh, it sounds as the same note played alone.
        const double a = rmsRange(v, join + 2400, join + 7200), b = rmsRange(alone, 2400, 7200);
        // The 10 ms after the new note against the 10 ms before: the tail
        // goes on falling at its own rate rather than being cut.
        const double rings = rmsRange(v, join, join + 480) / std::max(tail, 1e-12);
        std::snprintf(buf, sizeof(buf), "tail %.4f, then %.2f of it, join corner %.2fx, level %.4f against alone %.4f",
                      tail, rings, atJoin / std::max(corner, 1e-12), a, b);
        ok(tail > 1e-3 && rings > 0.4 && atJoin < corner * 2.0 && std::fabs(a - b) < b * 0.1,
           "Timber: a note after a release is tongued, the tail rings out", buf);
    }
    // Fast notes: eighths of 60 ms, and two notes a block apart, inside the
    // fade. Nothing in it steps further than a held note does.
    {
        std::vector<NoteEvent> ev;
        for (int32_t k = 0; k < 8; ++k) {
            ev.push_back({20 + k * 45, uint8_t(k % 2 ? 62 : 60), true});
            ev.push_back({20 + k * 45 + 30, uint8_t(k % 2 ? 62 : 60), false});
        }
        const auto v = timberEvents(mono, ev, 420);
        const auto held = timberEvents(mono, {{0, 62, true}}, 420);
        const double c = largestCorner(v, 0, v.size()), h = largestCorner(held, 2400, held.size());
        std::snprintf(buf, sizeof(buf), "largest corner %.4f against a held note's %.4f", c, h);
        ok(c < h * 2.0, "Timber: fast repeated notes do not click", buf);

        const auto twice = timberEvents(mono, {{0, 57, true}, {at, 57, false}, {at + 3, 60, true}, {at + 4, 62, true}}, blocks);
        const double c2 = largestCorner(twice, join, twice.size());
        const double h2 = largestCorner(twice, 4800, join);
        std::snprintf(buf, sizeof(buf), "largest corner %.4f against the held note's %.4f", c2, h2);
        ok(c2 < h2 * 2.0, "Timber: a second note inside the fade does not click", buf);
    }
}

// A note at [first], held, and a second at 60 slurred onto it at [slurAt]
// samples, from a fresh Brazen with [units] set. A negative [slurAt] holds
// [first] alone. [releaseAt], if not negative, lets go of [first] there.
std::vector<float> brazenSlur(const std::vector<std::pair<std::string, float>> &units, int32_t slurAt,
                              uint8_t first = 57, int32_t releaseAt = -1) {
    Machine *m = MachineRegistry::create("Brazen");
    m->prepare(kRate);
    ParamSet &p = m->params();
    for (const auto &[name, value] : units) {
        const int32_t i = p.indexOf(name.c_str());
        if (i < 0) { std::printf("  FAIL no parameter %s\n", name.c_str()); ++failures; continue; }
        p.set(i, p.def(i).unmap(value));
    }
    p.jumpAll();
    m->reset();
    m->noteOn(first, 100);
    std::vector<float> out;
    float L[kBlock], R[kBlock];
    for (int32_t b = 0; b < kBlocks * (slurAt < 0 ? 4 : 2); ++b) {
        if (b * kBlock == releaseAt) m->noteOff(first);
        if (b * kBlock == slurAt) m->noteOn(60, 100);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) out.push_back(L[i]);
    }
    delete m;
    return out;
}

double largestStep(const std::vector<float> &v) {
    double j = 0;
    for (size_t i = 1; i < v.size(); ++i) j = std::max(j, double(std::fabs(v[i] - v[i - 1])));
    return j;
}

// How much a block's edge stands out: the mean size of the second
// difference at each of the 64 positions in a block, the largest against
// their average. A value set once a block and applied to every sample puts a
// corner at one position, wherever the tube's delay carries it; a smooth
// signal reads close to 1.
double blockEdge(const std::vector<float> &v, size_t from, size_t to) {
    double m[kBlock] = {};
    int n[kBlock] = {};
    for (size_t i = std::max<size_t>(from, 2); i < to; ++i) {
        m[i % kBlock] += std::fabs(double(v[i]) - 2.0 * v[i - 1] + v[i - 2]);
        ++n[i % kBlock];
    }
    double top = 0, sum = 0;
    for (int k = 0; k < kBlock; ++k) {
        const double a = n[k] > 0 ? m[k] / n[k] : 0.0;
        top = std::max(top, a);
        sum += a;
    }
    return sum > 0 ? top / (sum / kBlock) : 0.0;
}

// Brazen's breath swell and flutter: a route to the swell matches the knob,
// a rising swell starts quiet and arrives, a falling one starts loud, neither
// steps, a slurred note keeps the swell it joined, and flutter moves the
// level at its rate without steps.
void brazenRoutes() {
    using Bz = acidulous::machine::Brazen;
    gType = "Brazen";
    gDests = Bz::MacroDestCount;
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
    // A quick-speaking horn, so the swell is the only slow thing.
    const std::vector<std::pair<std::string, float>> horn = {
        {"size", 0.95f}, {"pressure", 0.9f}, {"attack", 0.005f}, {"vibrato", 0.0f}, {"swelltime", 0.1f}};
    auto with = [&](std::initializer_list<std::pair<std::string, float>> more) {
        auto u = horn;
        for (const auto &x : more) u.push_back(x);
        return u;
    };
    gUnits = horn;
    const auto base = play(nullptr);
    gUnits = with({{"swell", 0.8f}});
    const auto rise = play(nullptr);
    // Swell is -1..1 from 0, so +0.4 of its range is 0.8.
    gUnits = horn;
    Route r{4, 2, Bz::McSwell, 0.4f, 1.0f};
    same("Brazen: a route to swell matches the knob", rise, play(&r));

    const size_t front = 2400, tail = 7000;
    std::snprintf(buf, sizeof(buf), "front %.4f against %.4f", rmsRange(rise, 0, front), rmsRange(base, 0, front));
    ok(rmsRange(rise, 0, front) < rmsRange(base, 0, front) * 0.5, "Brazen: a rising swell starts quiet", buf);
    std::snprintf(buf, sizeof(buf), "tail %.4f against %.4f", rmsRange(rise, tail, rise.size()),
                  rmsRange(base, tail, base.size()));
    ok(rmsRange(rise, tail, rise.size()) > rmsRange(base, tail, base.size()) * 0.7,
       "Brazen: a rising swell arrives", buf);

    gUnits = with({{"swell", -0.8f}});
    const auto fall = play(nullptr);
    gUnits.clear();
    std::snprintf(buf, sizeof(buf), "front %.4f against %.4f", rmsRange(fall, 0, 4800), rmsRange(base, 0, 4800));
    ok(rmsRange(fall, 0, 4800) > rmsRange(base, 0, 4800) * 1.1, "Brazen: a falling swell starts loud", buf);

    // Every sample moves the breath, so nothing in the swell steps: the
    // largest jump between samples is no bigger than the loudest note the
    // swell passes through, held. A harder-blown horn has a steeper wave,
    // so a falling swell is held against its own starting pressure: 0.8
    // falling by 0.2 starts at 0.96.
    gUnits = with({{"pressure", 0.8f}, {"swell", -0.2f}});
    const auto fallSoft = play(nullptr);
    gUnits = with({{"pressure", 0.96f}});
    const auto hard = play(nullptr);
    gUnits.clear();
    std::snprintf(buf, sizeof(buf), "largest step %.4f against %.4f, %.4f against %.4f", largestStep(rise),
                  largestStep(base), largestStep(fallSoft), largestStep(hard));
    ok(largestStep(rise) <= largestStep(base) * 1.05 && largestStep(fallSoft) <= largestStep(hard) * 1.05,
       "Brazen: the swell jumps no further than a held note", buf);
    // And no corner at a block's edge while it rises from quiet, where one
    // would show. A falling swell starts loud, and a loud horn's own edges
    // (the lift at note-on) hide one there.
    const auto riseSlow = brazenSlur(with({{"swelltime", 0.1f}, {"swell", 1.0f}}), -1);
    const auto baseSlow = brazenSlur(with({{"swelltime", 0.1f}}), -1);
    std::snprintf(buf, sizeof(buf), "block edge %.3f against %.3f", blockEdge(riseSlow, 0, 4800),
                  blockEdge(baseSlow, 0, 4800));
    ok(blockEdge(riseSlow, 0, 4800) <= std::max(1.4, blockEdge(baseSlow, 0, 4800)),
       "Brazen: the swell has no steps in it", buf);

    // Mono with a glide, slurred at 240 ms once the swell has arrived: the
    // second note does not start another one, so it sounds as it does with
    // no swell at all.
    const int32_t slurAt = 180 * kBlock;
    const auto slurBase = brazenSlur(with({{"mono", 1.0f}, {"glide", 0.05f}, {"swelltime", 0.05f}}), slurAt);
    const auto slurSwell =
        brazenSlur(with({{"mono", 1.0f}, {"glide", 0.05f}, {"swelltime", 0.05f}, {"swell", 1.0f}}), slurAt);
    // Window by window, 5 ms each, so a swell that restarts part way can't
    // hide in the average.
    const double a = rmsRange(slurSwell, slurAt, slurAt + 2400), b = rmsRange(slurBase, slurAt, slurAt + 2400);
    double worstWin = 10.0;
    for (int32_t w = 0; w < 10; ++w) {
        const size_t from = slurAt + w * 240;
        const double bw = rmsRange(slurBase, from, from + 240);
        if (bw > 1e-4) worstWin = std::min(worstWin, rmsRange(slurSwell, from, from + 240) / bw);
    }
    std::snprintf(buf, sizeof(buf), "after the slur %.4f against %.4f, worst 5 ms %.3f", a, b, worstWin);
    // The two tubes are in different states when the slur lands, so the
    // re-tongued onset differs a little; a swell started again dips to a
    // third.
    ok(b > 1e-3 && std::fabs(a - b) < b * 0.05 && worstWin > 0.75, "Brazen: a slurred note keeps the swell", buf);

    // A slur carries the breath and the filter on: the level stays near the
    // held note's in every 10 ms of the first 100, and nothing steps at the join.
    {
        const auto slur = brazenSlur(with({{"mono", 1.0f}, {"glide", 0.05f}, {"cutoff", 1800.0f}, {"attack", 0.05f}}), slurAt);
        const double held = rmsRange(slur, slurAt - 4800, slurAt);
        double low = 10.0;
        for (int32_t w = 0; w < 10; ++w) {
            const size_t from = slurAt + w * 480;
            low = std::min(low, rmsRange(slur, from, from + 480) / held);
        }
        double stepJoin = 0, stepHeld = 0;
        for (size_t i = slurAt - 4800; i < size_t(slurAt + 480); ++i) {
            const double d = std::fabs(double(slur[i]) - 2.0 * slur[i - 1] + slur[i - 2]);
            (i < size_t(slurAt - 480) ? stepHeld : stepJoin) = std::max(i < size_t(slurAt - 480) ? stepHeld : stepJoin, d);
        }
        std::snprintf(buf, sizeof(buf), "lowest 10 ms %.2f of the held note, join corner %.2fx", low,
                      stepJoin / std::max(stepHeld, 1e-12));
        ok(low > 0.85 && stepJoin < stepHeld * 2.0, "Brazen: a slur does not dip or click", buf);
    }

    // Mono without legato at glide 0 tongues a note over a held one, and
    // choke tongues one onto a tail just let go. The old note fades out over
    // 2 ms first, so the join has no corner bigger than the held note's or
    // a fresh note's own onset.
    {
        auto corner = [](const std::vector<float> &v, size_t from, size_t to) {
            double c = 0;
            for (size_t i = std::max<size_t>(from, 2); i < to && i < v.size(); ++i)
                c = std::max(c, std::fabs(double(v[i]) - 2.0 * v[i - 1] + v[i - 2]));
            return c;
        };
        const auto alone = brazenSlur(with({{"cutoff", 1800.0f}, {"attack", 0.05f}}), -1, 60);
        const double onset = corner(alone, 0, 4800);
        for (int choke = 0; choke < 2; ++choke) {
            auto u = with({{"mono", 1.0f}, {"legato", 0.0f}, {"glide", 0.0f}, {"cutoff", 1800.0f}, {"attack", 0.05f}});
            if (choke) u.push_back({"choke", 1.0f});
            const auto v = brazenSlur(u, slurAt, 57, choke ? slurAt - 6 * kBlock : -1);
            const double held = corner(v, slurAt - 9600, slurAt - 6 * kBlock - 480);
            const double join = corner(v, slurAt - 480, slurAt + 4800);
            const double worst = std::max(held, onset);
            std::snprintf(buf, sizeof(buf), "join corner %.2fx of the held note or a fresh onset", join / std::max(worst, 1e-12));
            ok(join < worst * 1.5, choke ? "Brazen: a choke onto a tail does not click" : "Brazen: a tongued mono note does not click", buf);
        }
    }

    // Flutter: the air chopped at its rate, so the level moves at that rate,
    // and smoothly.
    const auto steady = with({{"flutterrate", 25.0f}});
    auto withFlutter = steady;
    withFlutter.push_back({"flutter", 0.6f});
    const auto still = brazenSlur(steady, -1, 62);
    const auto fluttered = brazenSlur(withFlutter, -1, 62);
    // The level's movement at 25 Hz over the held note, against its mean,
    // from 5 ms block levels.
    auto wobble = [](const std::vector<float> &v) {
        const size_t hop = 240, from = 9600;
        double re = 0, im = 0, mean = 0;
        for (size_t s = from; s + hop <= v.size(); s += hop) {
            const double lv = rmsRange(v, s, s + hop), t = double(s) / kRate;
            re += lv * std::cos(2 * M_PI * 25.0 * t);
            im += lv * std::sin(2 * M_PI * 25.0 * t);
            mean += lv;
        }
        return mean > 0 ? 2.0 * std::sqrt(re * re + im * im) / mean : 0.0;
    };
    const double wStill = wobble(still), wFlutter = wobble(fluttered);
    std::snprintf(buf, sizeof(buf), "25 Hz movement %.3f against %.3f", wFlutter, wStill);
    ok(wFlutter > 0.15 && wFlutter > wStill * 4, "Brazen: flutter moves the sound", buf);
    std::snprintf(buf, sizeof(buf), "largest step %.4f against %.4f, block edge %.3f against %.3f",
                  largestStep(fluttered), largestStep(still), blockEdge(fluttered, 2400, 30000),
                  blockEdge(still, 2400, 30000));
    ok(largestStep(fluttered) <= largestStep(still) * 1.05 &&
           blockEdge(fluttered, 2400, 30000) <= std::max(1.4, blockEdge(still, 2400, 30000) * 1.1),
       "Brazen: flutter has no steps in it", buf);
}

// Manual, played for [blocks] blocks with [units] set, and then [moves]
// applied at their blocks: a param by name in its own units, or the mod
// wheel when the name is "cc1" and the expression pedal when it is "cc11" (0..127).
struct Move { int32_t block; std::string name; float value; };

std::vector<float> manualPlay(const std::vector<std::pair<std::string, float>> &units,
                              const std::vector<Move> &moves, int32_t blocks, uint8_t note = 60) {
    Machine *m = MachineRegistry::create("Manual");
    m->prepare(kRate);
    ParamSet &p = m->params();
    auto put = [&](const std::string &name, float value) {
        if (name == "cc1" || name == "cc11") {
            m->controlChange(name == "cc1" ? 1 : 11, static_cast<uint8_t>(value));
            return;
        }
        const int32_t i = p.indexOf(name.c_str());
        if (i < 0) { std::printf("  FAIL no parameter %s\n", name.c_str()); ++failures; return; }
        p.set(i, p.def(i).unmap(value));
    };
    auto isCc = [](const std::string &name) { return name == "cc1" || name == "cc11"; };
    for (const auto &[name, value] : units) if (!isCc(name)) put(name, value);
    p.jumpAll();
    m->allNotesOff();
    m->reset();
    for (const auto &[name, value] : units) if (isCc(name)) put(name, value);
    m->noteOn(note, 100);
    std::vector<float> out;
    float L[kBlock], R[kBlock];
    for (int32_t b = 0; b < blocks; ++b) {
        for (const auto &mv : moves) if (mv.block == b) put(mv.name, mv.value);
        m->onBlock(b * 4, b * 4 + 4, 120.0f);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) out.push_back(L[i]);
    }
    delete m;
    return out;
}

/** Brightness: the first difference's rms against the signal's, over [from, to). */
double brightness(const std::vector<float> &v, size_t from, size_t to) {
    double d = 0, a = 0;
    for (size_t i = std::max<size_t>(from, 1); i < to; ++i) {
        const double x = double(v[i]) - v[i - 1];
        d += x * x;
        a += double(v[i]) * v[i];
    }
    return a > 0 ? std::sqrt(d / a) : 0.0;
}

// Manual's swell box and bar glide. A route to either matches its knob; a
// closed expression with the box up is darker and quieter than without it,
// an open one sounds the same; opening the box moves without steps. The
// bar glide moves a drawbar, a tab and a rank over its time instead of
// jumping, and a macro route to a drawbar glides as the knob does.
void manualRoutes() {
    using Mn = acidulous::machine::Manual;
    gType = "Manual";
    gDests = Mn::MacroDestCount;
    gNote = 60;
    char buf[128];
    auto same = [&](const char *what, const std::vector<float> &a, const std::vector<float> &b) {
        double worst = 0, peak = 0;
        for (size_t i = 0; i < a.size(); ++i) {
            worst = std::max(worst, double(std::fabs(a[i] - b[i])));
            peak = std::max(peak, double(std::fabs(a[i])));
        }
        std::snprintf(buf, sizeof(buf), "worst difference %.2g of peak %.2g", worst, peak);
        ok(peak > 1e-3 && worst < peak * 1e-4, what, buf);
    };
    // Bright and still: every upper bar out, no cabinet, no vibrato, no click.
    std::vector<std::pair<std::string, float>> organ = {
        {"rotary", 0.0f}, {"vibdepth", 0.0f}, {"click", 0.0f}, {"perc", 0.0f}, {"spray", 0.0f},
        {"ua_2", 1.0f}, {"ua_1", 1.0f}, {"ua_223", 1.0f}};
    auto with = [&](std::initializer_list<std::pair<std::string, float>> more) {
        auto u = organ;
        for (const auto &x : more) u.push_back(x);
        return u;
    };

    // Swell box: the knob and a route land on the same sound, with the box
    // closed (the mod wheel up).
    gModWheel = 127;
    gUnits = with({{"swellbox", 0.6f}});
    const auto boxKnob = play(nullptr);
    gUnits = organ;
    Route r{5, 3, Mn::McSwellBox, 0.6f, 1.0f};
    same("Manual: a route to the swell box matches the knob", boxKnob, play(&r));
    gUnits.clear();
    gModWheel = -1;

    // Closed (the mod wheel up): darker and quieter with the box up.
    const int32_t n = 400;
    const auto closedDry = manualPlay(with({{"cc1", 127.0f}}), {}, n);
    const auto closedBox = manualPlay(with({{"cc1", 127.0f}, {"swellbox", 1.0f}}), {}, n);
    const size_t from = 4800, to = n * kBlock;
    const double bDry = brightness(closedDry, from, to), bBox = brightness(closedBox, from, to);
    const double lDry = rmsRange(closedDry, from, to), lBox = rmsRange(closedBox, from, to);
    std::snprintf(buf, sizeof(buf), "brightness %.3f against %.3f, level %.1f dB", bBox, bDry,
                  20 * std::log10(lBox / lDry));
    ok(bBox < bDry * 0.5 && lBox < lDry, "Manual: a closed swell box is darker and quieter", buf);
    // Half the knob is between.
    const auto halfBox = manualPlay(with({{"cc1", 127.0f}, {"swellbox", 0.5f}}), {}, n);
    const double bHalf = brightness(halfBox, from, to);
    std::snprintf(buf, sizeof(buf), "brightness %.3f between %.3f and %.3f", bHalf, bBox, bDry);
    ok(bHalf < bDry && bHalf > bBox, "Manual: half the swell box is between", buf);

    // Open (the wheel down): the box makes no difference, and it's as loud
    // as expression off.
    const auto openDry = manualPlay(organ, {}, n);
    const auto openBox = manualPlay(with({{"swellbox", 1.0f}}), {}, n);
    ok(openDry == openBox, "Manual: an open swell box sounds as the knob at 0", "");
    // Expression off: the box makes no difference either.
    const auto offDry = manualPlay(with({{"express", 0.0f}}), {}, n);
    const auto offBox = manualPlay(with({{"express", 0.0f}, {"swellbox", 1.0f}}), {}, n);
    ok(offDry == offBox, "Manual: with expression off the swell box does nothing", "");
    ok(openDry == offDry, "Manual: the mod wheel down is open", "");
    // The pedal (CC11) starts open: with none sent it sounds as expression off.
    const auto pedalOpen = manualPlay(with({{"express", 3.0f}, {"swellbox", 1.0f}}), {}, n);
    ok(pedalOpen == offDry, "Manual: the pedal with no CC11 is open", "");
    // CC11 at 0 closes it, as the wheel up does.
    const auto pedalShut = manualPlay(with({{"express", 3.0f}, {"swellbox", 1.0f}, {"cc11", 0.0f}}), {}, n);
    ok(pedalShut == closedBox, "Manual: the pedal at 0 closes as the wheel up", "");
    std::snprintf(buf, sizeof(buf), "level %.1f dB under open", 20 * std::log10(rmsRange(offDry, from, to) /
                                                                                 rmsRange(pedalShut, from, to)));
    ok(rmsRange(pedalShut, from, to) < rmsRange(offDry, from, to) * 0.3, "Manual: the pedal at 0 is quieter", buf);

    // Opening the box mid-note, the wheel coming down. The expression gain
    // itself steps a block at a time, so the box is held against the same
    // move without it.
    std::vector<Move> open;
    for (int32_t k = 0; k <= 32; ++k) open.push_back({150 + k * 4, "cc1", float(k * 4 > 127 ? 0 : 127 - k * 4)});
    const auto movingDry = manualPlay(with({{"cc1", 127.0f}}), open, n);
    const auto movingBox = manualPlay(with({{"cc1", 127.0f}, {"swellbox", 1.0f}}), open, n);
    const double eDry = blockEdge(movingDry, 150 * kBlock, 290 * kBlock);
    const double eBox = blockEdge(movingBox, 150 * kBlock, 290 * kBlock);
    std::snprintf(buf, sizeof(buf), "block edge %.3f against %.3f", eBox, eDry);
    ok(eBox <= std::max(1.4, eDry * 1.1), "Manual: the swell box opens without steps", buf);
    std::snprintf(buf, sizeof(buf), "brightness open %.3f, closed %.3f",
                  brightness(movingBox, 300 * kBlock, to), brightness(movingBox, from, 140 * kBlock));
    ok(brightness(movingBox, 300 * kBlock, to) > brightness(movingBox, from, 140 * kBlock) * 1.5,
       "Manual: opening the swell box brightens", buf);

    // Bar glide: the knob and a route land on the same sound.
    gUnits = with({{"barglide", 1.2f}});
    const auto glideKnob = play(nullptr);
    gUnits = organ;
    Route g{6, 4, Mn::McBarGlide, 0.3f, 1.0f};
    same("Manual: a route to the bar glide matches the knob", glideKnob, play(&g));
    gUnits.clear();

    // A stop drawn at block 100 from silence, on each model's own: the
    // tonewheel's 8' drawbar, the combo's 8' tab and the pipe's flute rank.
    struct Case { const char *what; std::vector<std::pair<std::string, float>> base; const char *knob; };
    const std::vector<Case> cases = {
        {"drawbar", {{"rotary", 0.0f}, {"spray", 0.0f}, {"vibdepth", 0.0f}, {"click", 0.0f}, {"perc", 0.0f}, {"ua_16", 0.0f},
                     {"ua_8", 0.0f}, {"ua_4", 0.0f}}, "ua_8"},
        {"tab", {{"model", 1.0f}, {"spray", 0.0f}, {"rotary", 0.0f}, {"vibdepth", 0.0f}, {"click", 0.0f}, {"perc", 0.0f},
                 {"tab16", 0.0f}, {"tab8", 0.0f}, {"tab4", 0.0f}}, "tab8"},
        {"rank", {{"model", 2.0f}, {"spray", 0.0f}, {"rotary", 0.0f}, {"vibdepth", 0.0f}, {"chiff", 0.0f}, {"tracker", 0.0f},
                  {"perc", 0.0f}, {"ua_16", 0.0f}, {"ua_4", 0.0f}, {"principal", 0.0f}, {"string", 0.0f},
                  {"reed", 0.0f}, {"mixture", 0.0f}, {"flute", 0.0f}}, "flute"},
    };
    const int32_t gn = 1200, at = 100;
    for (const auto &c : cases) {
        auto glide = c.base;
        glide.push_back({"barglide", 1.0f});
        const auto jump = manualPlay(c.base, {{at, c.knob, 1.0f}}, gn);
        const auto slow = manualPlay(glide, {{at, c.knob, 1.0f}}, gn);
        const auto before = manualPlay(c.base, {}, gn);
        auto lv = [&](const std::vector<float> &v, double sec) {
            const size_t s0 = at * kBlock + size_t(sec * kRate);
            return rmsRange(v, s0, s0 + 960);
        };
        // The glide gets 95% of the way in its time, a one-pole, so a third
        // of the way through it has gone about two thirds.
        const double end = lv(jump, 1.05), early = lv(slow, 0.1), mid = lv(slow, 0.33), late = lv(slow, 1.05);
        const double was = lv(before, 0.1);
        std::snprintf(buf, sizeof(buf), "%.4f -> %.4f -> %.4f, from %.4f to %.4f", early, mid, late, was, end);
        const std::string what = std::string("Manual: the bar glide moves a ") + c.what + " over its time";
        const double part = (mid - was) / (end - was);
        ok(early < was + (end - was) * 0.4 && part > 0.5 && part < 0.8 && late > was + (end - was) * 0.9 &&
               late < end * 1.05,
           what, buf);
        const double eSlow = blockEdge(slow, at * kBlock, (at + 600) * kBlock);
        const double eStill = blockEdge(before, at * kBlock, (at + 600) * kBlock);
        std::snprintf(buf, sizeof(buf), "block edge %.3f against %.3f, largest step %.4f against %.4f", eSlow,
                      eStill, largestStep(slow), largestStep(jump));
        ok(eSlow <= std::max(1.4, eStill * 1.1) && largestStep(slow) <= largestStep(jump) * 1.05,
           std::string("Manual: a gliding ") + c.what + " has no steps in it", buf);
    }

    // A macro route to the 8' glides as the knob does.
    {
        auto base = cases[0].base;
        base.push_back({"barglide", 1.0f});
        const auto knob = manualPlay(base, {{at, "ua_8", 1.0f}}, gn);
        auto routed = base;
        routed.push_back({"mac2_d1", float(Mn::McUpperA + 2)});
        routed.push_back({"mac2_a1", 1.0f});
        const auto mac = manualPlay(routed, {{at, "mac2", 1.0f}}, gn);
        same("Manual: a macro on a drawbar glides as the knob does", knob, mac);
    }
}

} // namespace

// [type] played from fresh with [units] set: [notes] struck at the start
// and held for [blocks] blocks.
std::vector<float> playHeld(const char *type, const std::vector<std::pair<std::string, float>> &units,
                            const std::vector<uint8_t> &notes, int32_t blocks, const Route *r = nullptr,
                            int dests = 0) {
    Machine *m = MachineRegistry::create(type);
    m->prepare(kRate);
    ParamSet &p = m->params();
    for (const auto &[name, value] : units) {
        const int32_t i = p.indexOf(name.c_str());
        if (i < 0) { std::printf("  FAIL no parameter %s\n", name.c_str()); ++failures; continue; }
        p.set(i, p.def(i).unmap(value));
    }
    if (r != nullptr) {
        const std::string k = "mac" + std::to_string(r->macro);
        setNorm(p, k, r->knob);
        setNorm(p, k + "_d" + std::to_string(r->route), r->dest / (dests - 1.0f));
        setNorm(p, k + "_a" + std::to_string(r->route), (r->depth + 1.0f) * 0.5f);
    }
    p.jumpAll();
    if (std::strcmp(type, "Pollen") == 0) m->swapObject(0, acidulous::audition::breakLoop().release());
    m->reset();
    for (uint8_t n : notes) m->noteOn(n, 100);
    std::vector<float> out;
    float L[kBlock], R[kBlock];
    for (int32_t b = 0; b < blocks; ++b) {
        m->onBlock(b * 4, b * 4 + 4, 120.0f);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) out.push_back(L[i]);
    }
    delete m;
    return out;
}

/** The worst difference between [a] and [b] against [a]'s peak, and whether it is under [limit] of it. */
bool sameAs(const std::vector<float> &a, const std::vector<float> &b, char *buf, size_t size, double limit = 1e-4) {
    double worst = 0, peak = 0;
    for (size_t i = 0; i < a.size() && i < b.size(); ++i) {
        worst = std::max(worst, double(std::fabs(a[i] - b[i])));
        peak = std::max(peak, double(std::fabs(a[i])));
    }
    std::snprintf(buf, size, "worst difference %.2g of peak %.2g", worst, peak);
    return peak > 1e-4 && worst <= peak * limit;
}

// Fret's swell: a route to it matches the knob, a note starts quiet and
// arrives, and it rises without steps.
void fretRoutes() {
    using Ft = acidulous::machine::Fret;
    char buf[128];
    const int32_t blocks = 750; // a second
    const auto base = playHeld("Fret", {{"sustain", 1.0f}}, {57}, blocks);
    const auto swelled = playHeld("Fret", {{"sustain", 1.0f}, {"swell", 0.4f}}, {57}, blocks);
    // Swell is 0..4 s, so a route of 0.1 is 0.4 s.
    Route r{2, 3, Ft::McSwell, 0.1f, 1.0f};
    ok2(sameAs(swelled, playHeld("Fret", {{"sustain", 1.0f}}, {57}, blocks, &r, Ft::MacroDestCount), buf, sizeof(buf)),
       "Fret: a route to swell matches the knob", buf);
    std::snprintf(buf, sizeof(buf), "front %.4f against %.4f", rmsRange(swelled, 0, 2400), rmsRange(base, 0, 2400));
    ok(rmsRange(swelled, 0, 2400) < rmsRange(base, 0, 2400) * 0.1, "Fret: a swelled note starts quiet", buf);
    const size_t late = 24000;
    std::snprintf(buf, sizeof(buf), "after %.4f against %.4f", rmsRange(swelled, late, late + 4800),
                  rmsRange(base, late, late + 4800));
    ok(std::fabs(rmsRange(swelled, late, late + 4800) / rmsRange(base, late, late + 4800) - 1.0) < 0.01,
       "Fret: a swelled note arrives at its level", buf);
    std::snprintf(buf, sizeof(buf), "largest step %.4f against %.4f", largestStep(swelled), largestStep(base));
    ok(largestStep(swelled) <= largestStep(base) * 1.05, "Fret: the swell has no steps in it", buf);
}

// Hammer's roll and duplex: a route to either matches its knob, a roll
// strikes a held note again (it keeps its level where one strike dies
// away), and the duplex rings faintly with a treble note.
void hammerRoutes() {
    using Hm = acidulous::machine::Hammer;
    char buf[128];
    const int32_t blocks = 1500; // two seconds
    const auto once = playHeld("Hammer", {{"model", 10.0f}}, {69}, blocks);
    const auto rolled = playHeld("Hammer", {{"model", 10.0f}, {"roll", 12.0f}}, {69}, blocks);
    // Roll is 0..24 Hz, so a route of 0.5 is 12.
    Route r{5, 1, Hm::McRoll, 0.5f, 1.0f};
    ok2(sameAs(rolled, playHeld("Hammer", {{"model", 10.0f}}, {69}, blocks, &r, Hm::MacroDestCount), buf, sizeof(buf)),
       "Hammer: a route to roll matches the knob", buf);
    const size_t end = once.size();
    std::snprintf(buf, sizeof(buf), "last half second %.4f against %.4f", rmsRange(rolled, end - 24000, end),
                  rmsRange(once, end - 24000, end));
    ok(rmsRange(rolled, end - 24000, end) > rmsRange(once, end - 24000, end) * 2.0, "Hammer: a roll strikes a held note again", buf);
    // Struck about 12 times a second: the level's rises, block by block.
    int strikes = 0;
    double was = 0;
    for (size_t at = 4800; at + 480 <= end; at += 480) {
        const double now = rmsRange(rolled, at, at + 480);
        if (was > 0 && now > was * 1.15) ++strikes;
        was = now;
    }
    std::snprintf(buf, sizeof(buf), "%d rises in 1.9 s", strikes);
    ok(strikes >= 15 && strikes <= 30, "Hammer: a roll strikes about as often as it says", buf);

    // Tension and clang, routed: the same as their knobs, and not the same as without.
    const auto tense = playHeld("Hammer", {{"tension", 1.0f}}, {72}, 400);
    Route t{7, 2, Hm::McTension, 1.0f, 1.0f};
    ok2(sameAs(tense, playHeld("Hammer", {}, {72}, 400, &t, Hm::MacroDestCount), buf, sizeof(buf)),
       "Hammer: a route to tension matches the knob", buf);
    ok2(!sameAs(tense, playHeld("Hammer", {}, {72}, 400), buf, sizeof(buf)), "Hammer: tension changes the note", buf);
    const auto rung = playHeld("Hammer", {{"model", 1.0f}, {"clang", 1.0f}}, {60}, 400);
    Route c{8, 3, Hm::McClang, 1.0f, 1.0f};
    ok2(sameAs(rung, playHeld("Hammer", {{"model", 1.0f}}, {60}, 400, &c, Hm::MacroDestCount), buf, sizeof(buf)),
       "Hammer: a route to clang matches the knob", buf);
    ok2(!sameAs(rung, playHeld("Hammer", {{"model", 1.0f}}, {60}, 400), buf, sizeof(buf)), "Hammer: clang changes the note", buf);

    const auto dry = playHeld("Hammer", {}, {84}, blocks);
    const auto duplexed = playHeld("Hammer", {{"duplex", 1.0f}}, {84}, blocks);
    Route d{6, 4, Hm::McDuplex, 1.0f, 1.0f};
    ok2(sameAs(duplexed, playHeld("Hammer", {}, {84}, blocks, &d, Hm::MacroDestCount), buf, sizeof(buf)),
       "Hammer: a route to duplex matches the knob", buf);
    std::vector<float> diff(end);
    for (size_t i = 0; i < end; ++i) diff[i] = duplexed[i] - dry[i];
    const double under = 20.0 * std::log10(rmsRange(diff, 0, end) / rmsRange(dry, 0, end));
    std::snprintf(buf, sizeof(buf), "%.1f dB under the note", under);
    ok(under > -40.0 && under < -6.0, "Hammer: the duplex rings faintly with a note", buf);
    // A moving duplex knob ramps: half way through, from 0 to 1 at once.
    Machine *m = MachineRegistry::create("Hammer");
    m->prepare(kRate);
    m->reset();
    m->noteOn(84, 100);
    std::vector<float> moved;
    float L[kBlock], R[kBlock];
    for (int32_t b = 0; b < blocks; ++b) {
        if (b == blocks / 2) m->params().set(m->params().indexOf("duplex"), 1.0f);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) moved.push_back(L[i]);
    }
    delete m;
    std::snprintf(buf, sizeof(buf), "largest step %.5f against %.5f", largestStep(moved), largestStep(dry));
    ok(largestStep(moved) <= largestStep(dry) * 1.05, "Hammer: the duplex knob moves without steps", buf);
}

// Draw's left hand: below the split a key plays its bass and chord, every
// key of a name the same; above it nothing changes; a route to its level
// matches the knob.
void drawRoutes() {
    using Dr = acidulous::machine::Draw;
    char buf[128];
    const int32_t blocks = 375;
    const auto right = playHeld("Draw", {}, {48}, blocks);
    const auto left = playHeld("Draw", {{"bass", 60.0f}}, {48}, blocks);
    std::snprintf(buf, sizeof(buf), "rms %.4f against %.4f", rmsRange(left, 0, left.size()), rmsRange(right, 0, right.size()));
    ok(left != right && rmsRange(left, 0, left.size()) > 1e-3, "Draw: a key below the split plays the left hand", buf);
    ok(playHeld("Draw", {{"bass", 60.0f}}, {36}, blocks) == left, "Draw: every key of a name plays the same button", "");
    ok(playHeld("Draw", {{"bass", 60.0f}}, {72}, blocks) == playHeld("Draw", {}, {72}, blocks),
       "Draw: a key above the split plays as before", "");
    ok(playHeld("Draw", {{"bass", 60.0f}, {"bass chord", 0.0f}}, {48}, blocks) !=
           playHeld("Draw", {{"bass", 60.0f}, {"bass chord", 1.0f}}, {48}, blocks),
       "Draw: a minor chord is not the major", "");
    const auto quiet = playHeld("Draw", {{"bass", 60.0f}, {"bass level", 0.5f}}, {48}, blocks);
    // Bass level is 0..1.5 from 1, so a route of -1/3 is 0.5.
    Route r{7, 2, Dr::McBassLevel, -1.0f / 3.0f, 1.0f};
    ok2(sameAs(quiet, playHeld("Draw", {{"bass", 60.0f}}, {48}, blocks, &r, Dr::MacroDestCount), buf, sizeof(buf), 1e-3),
       "Draw: a route to the bass level matches the knob", buf);
}

// Ten-millisecond rms windows, for beats and onsets.
std::vector<double> windows(const std::vector<float> &v, size_t width) {
    std::vector<double> w;
    for (size_t at = 0; at + width <= v.size(); at += width) w.push_back(rmsRange(v, at, at + width));
    return w;
}

// Plays [note] on one machine twice, with a reset between, mid-ring the first
// time: the second must be the first, so reset() clears the new state.
bool sameAfterReset(const char *type, const std::vector<std::pair<std::string, float>> &units, uint8_t note) {
    Machine *m = MachineRegistry::create(type);
    m->prepare(kRate);
    ParamSet &p = m->params();
    for (const auto &[name, value] : units) p.set(p.indexOf(name.c_str()), p.def(p.indexOf(name.c_str())).unmap(value));
    p.jumpAll();
    std::vector<float> takes[2];
    float L[kBlock], R[kBlock];
    for (auto &out : takes) {
        m->reset();
        m->noteOn(note, 100);
        for (int32_t b = 0; b < 200; ++b) {
            // A second hit 10 ms before the end, so a flam or a pair is still going at the reset.
            if (b == 192) m->noteOn(note, 90);
            m->render(L, R, kBlock);
            out.insert(out.end(), L, L + kBlock);
        }
    }
    delete m;
    return takes[0] == takes[1];
}

// Tine's pair: a route to it matches the knob, a paired note starts at the
// note's level and beats, and a ringing note struck again with pair on goes
// on without a step.
void tineRoutes() {
    using Tn = acidulous::machine::Tine;
    char buf[128];
    const int32_t blocks = 1500; // two seconds
    // Vibes, no tube, so the bar alone rings.
    const std::vector<std::pair<std::string, float>> vibes = {{"model", 1.0f}, {"tube", 0.0f}};
    auto with = [&](std::vector<std::pair<std::string, float>> u, const char *name, float value) {
        u.push_back({name, value});
        return u;
    };
    const auto plain = playHeld("Tine", vibes, {69}, blocks);
    const auto paired = playHeld("Tine", with(vibes, "pair", 20.0f), {69}, blocks);
    // Pair is 0..50 cents, so a route of 0.4 is 20.
    Route r{4, 2, Tn::McPair, 0.4f, 1.0f};
    ok2(sameAs(paired, playHeld("Tine", vibes, {69}, blocks, &r, Tn::MacroDestCount), buf, sizeof(buf)),
        "Tine: a route to pair matches the knob", buf);
    std::snprintf(buf, sizeof(buf), "first 10 ms %.4f against %.4f", rmsRange(paired, 0, 480), rmsRange(plain, 0, 480));
    ok(std::fabs(rmsRange(paired, 0, 480) / rmsRange(plain, 0, 480) - 1.0) < 0.05, "Tine: a paired note starts at the note's level", buf);
    // 20 cents at 440 Hz beats about 5 times a second: the level rises again
    // between beats, where a single bar only dies away.
    auto rises = [](const std::vector<double> &w) {
        int n = 0;
        for (size_t i = 10; i + 1 < w.size(); ++i) n += w[i + 1] > w[i] * 1.1 ? 1 : 0;
        return n;
    };
    const auto pw = windows(paired, 480), sw = windows(plain, 480);
    std::snprintf(buf, sizeof(buf), "%d rising windows against %d", rises(pw), rises(sw));
    ok(rises(pw) >= 8 && rises(sw) == 0, "Tine: a paired note beats", buf);

    // Struck again half way, with pair turned on just before.
    auto restruck = [&](bool pair) {
        Machine *m = MachineRegistry::create("Tine");
        m->prepare(kRate);
        ParamSet &p = m->params();
        for (const auto &[name, value] : vibes) p.set(p.indexOf(name.c_str()), p.def(p.indexOf(name.c_str())).unmap(value));
        p.jumpAll();
        m->reset();
        m->noteOn(69, 100);
        std::vector<float> out;
        float L[kBlock], R[kBlock];
        for (int32_t b = 0; b < 200; ++b) {
            if (b == 100) {
                if (pair) p.set(p.indexOf("pair"), 0.4f);
                m->noteOn(69, 100);
            }
            std::memset(L, 0, sizeof(L));
            std::memset(R, 0, sizeof(R));
            m->render(L, R, kBlock);
            for (int32_t i = 0; i < kBlock; ++i) out.push_back(L[i]);
        }
        delete m;
        return out;
    };
    const auto again = restruck(false), againPaired = restruck(true);
    std::snprintf(buf, sizeof(buf), "largest step %.5f against %.5f", largestStep(againPaired), largestStep(again));
    ok(againPaired != again && largestStep(againPaired) <= largestStep(again) * 1.05,
       "Tine: struck again with pair on, a note goes on without a step", buf);
    ok(sameAfterReset("Tine", with(vibes, "pair", 20.0f), 69), "Tine: a paired note plays the same after a reset", "");
}

// Palm's flam: a route to it matches the knob, the grace stroke is softer,
// the stroke lands when it says, and in a kit a route to a shared drum param
// reaches every pad.
void palmRoutes() {
    using Pm = acidulous::machine::Palm;
    char buf[128];
    const int32_t blocks = 375; // half a second
    const auto plain = playHeld("Palm", {}, {57}, blocks);
    const auto flammed = playHeld("Palm", {{"flam", 30.0f}}, {57}, blocks);
    // Flam is 0..60 ms, so a route of 0.5 is 30.
    Route r{8, 4, Pm::McFlam, 0.5f, 1.0f};
    ok2(sameAs(flammed, playHeld("Palm", {}, {57}, blocks, &r, Pm::MacroDestCount), buf, sizeof(buf)),
        "Palm: a route to flam matches the knob", buf);
    std::snprintf(buf, sizeof(buf), "grace %.4f against stroke %.4f", rmsRange(flammed, 0, 1440), rmsRange(plain, 0, 1440));
    ok(rmsRange(flammed, 0, 1440) < rmsRange(plain, 0, 1440) * 0.8, "Palm: a flam's grace stroke is softer", buf);
    // The biggest jump in 1 ms windows is the stroke, 30 ms in, and the 3 ms
    // a re-struck head takes to settle before the stroke lands.
    const auto w = windows(flammed, 48);
    size_t at = 1;
    for (size_t i = 1; i < w.size(); ++i)
        if (w[i] - w[i - 1] > w[at] - w[at - 1]) at = i;
    std::snprintf(buf, sizeof(buf), "at %zu ms", at);
    ok(at >= 30 && at <= 34, "Palm: the stroke lands the flam's time after the grace", buf);
    std::snprintf(buf, sizeof(buf), "after %.4f against %.4f", rmsRange(flammed, 4800, 9600), rmsRange(plain, 4800, 9600));
    ok(rmsRange(flammed, 4800, 9600) > rmsRange(plain, 4800, 9600) * 0.5, "Palm: the flammed stroke is played whole", buf);

    ok(sameAfterReset("Palm", {{"flam", 40.0f}}, 57), "Palm: a flammed hit plays the same after a reset", "");

    // A roll under a flam: nothing strikes between the grace and the flam's
    // stroke, and the stroke takes a roll stroke's place.
    {
        const auto rolled = playHeld("Palm", {{"flam", 60.0f}, {"roll", 20.0f}}, {57}, blocks);
        const auto lone = playHeld("Palm", {{"flam", 60.0f}}, {57}, blocks);
        const double a = rmsRange(rolled, 44 * 48, 61 * 48), b = rmsRange(lone, 44 * 48, 61 * 48);
        std::snprintf(buf, sizeof(buf), "%.5f against %.5f", a, b);
        ok(std::fabs(a - b) <= b * 0.05, "Palm: a roll waits for the flam's stroke", buf);
    }
    // A flam shorter than the grace's pulse: the stroke waits for it, so the
    // head isn't stepped.
    {
        // Both are shorter than the pulse, so both are held to its length.
        ok2(sameAs(playHeld("Palm", {{"flam", 0.1f}}, {21}, blocks), playHeld("Palm", {{"flam", 0.2f}}, {21}, blocks), buf, sizeof(buf)),
            "Palm: a flam shorter than the grace's pulse waits for it", buf);
    }

    // Kit mode: a route to decay reaches every pad, as the knob does.
    for (uint8_t pad : {uint8_t(36), uint8_t(40), uint8_t(43)}) {
        const auto knob = playHeld("Palm", {{"kit", 1.0f}, {"decay", 0.8f}}, {pad}, blocks);
        Route d{1, 1, Pm::McDecay, 0.3f, 1.0f};
        const auto routed = playHeld("Palm", {{"kit", 1.0f}}, {pad}, blocks, &d, Pm::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)) && routed != playHeld("Palm", {{"kit", 1.0f}}, {pad}, blocks),
            "Palm: in a kit a route to decay reaches pad " + std::to_string(pad - 35), buf);
    }
}

// Fathom: in a kit the routes reach every pad, as the shared knobs do, and
// a route to density moves each pad's own density.
void fathomRoutes() {
    using Fa = acidulous::machine::Fathom;
    const int32_t blocks = 375; // half a second
    char buf[96];
    // The default kit: fire on C2, wind on D2, rain on E2.
    for (uint8_t pad : {uint8_t(36), uint8_t(38), uint8_t(40)}) {
        const auto plain = playHeld("Fathom", {{"kit", 1.0f}}, {pad}, blocks);
        const auto knob = playHeld("Fathom", {{"kit", 1.0f}, {"tone", 0.75f}, {"gust", 0.75f}}, {pad}, blocks);
        Route t{1, 1, Fa::McTone, 0.25f, 1.0f};
        Route g{2, 1, Fa::McGust, 0.25f, 1.0f};
        const auto routed = [&] {
            // Two routes at once: set the second by hand after the first.
            Machine *m = MachineRegistry::create("Fathom");
            m->prepare(kRate);
            ParamSet &p = m->params();
            setNorm(p, "kit", 1.0f);
            for (const Route *r : {&t, &g}) {
                const std::string k = "mac" + std::to_string(r->macro);
                setNorm(p, k, r->knob);
                setNorm(p, k + "_d" + std::to_string(r->route), r->dest / (Fa::MacroDestCount - 1.0f));
                setNorm(p, k + "_a" + std::to_string(r->route), (r->depth + 1.0f) * 0.5f);
            }
            p.jumpAll();
            m->reset();
            m->noteOn(pad, 100);
            std::vector<float> out;
            float L[kBlock], R[kBlock];
            for (int32_t b = 0; b < blocks; ++b) {
                m->onBlock(b * 4, b * 4 + 4, 120.0f);
                std::memset(L, 0, sizeof(L));
                std::memset(R, 0, sizeof(R));
                m->render(L, R, kBlock);
                for (int32_t i = 0; i < kBlock; ++i) out.push_back(L[i]);
            }
            delete m;
            return out;
        }();
        ok2(sameAs(knob, routed, buf, sizeof(buf)) && routed != plain,
            "Fathom: in a kit routes to tone and gust reach pad " + std::to_string(pad - 35), buf);
    }
    // Pad 1 is fire at density 0.5: a route of 0.25 is the pad's knob at 0.75.
    {
        const std::string pd = "p01_density";
        const auto knob = playHeld("Fathom", {{"kit", 1.0f}, {pd, 0.75f}}, {36}, blocks);
        Route d{1, 1, Fa::McDensity, 0.25f, 1.0f};
        const auto routed = playHeld("Fathom", {{"kit", 1.0f}}, {36}, blocks, &d, Fa::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)) && routed != playHeld("Fathom", {{"kit", 1.0f}}, {36}, blocks),
            "Fathom: in a kit a route to density moves the pad's density", buf);
    }
    // Out of a kit: a route to density matches the density knob, on rain.
    {
        const auto knob = playHeld("Fathom", {{"model", 2.0f}, {"density", 0.75f}}, {60}, blocks);
        Route d{4, 2, Fa::McDensity, 0.25f, 1.0f};
        const auto routed = playHeld("Fathom", {{"model", 2.0f}}, {60}, blocks, &d, Fa::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)), "Fathom: a route to density matches the knob", buf);
    }
    // Waves: a full route takes surf's swell to the top of its range.
    {
        const auto knob = playHeld("Fathom", {{"model", 4.0f}, {"swell", 16.0f}}, {60}, blocks);
        Route w{5, 3, Fa::McSwell, 1.0f, 1.0f};
        const auto routed = playHeld("Fathom", {{"model", 4.0f}}, {60}, blocks, &w, Fa::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf), 1e-3), "Fathom: a route to waves matches the knob", buf);
    }
}

// Aviary: in a kit the routes reach every pad, as the shared knobs do; a
// route to sweep is read at each syllable, and one to spread at the note.
void aviaryRoutes() {
    using Av = acidulous::machine::Aviary;
    const int32_t blocks = 750; // a second
    char buf[96];
    // The default kit: a whistle on C2, a chirp on D2, a trill on E2.
    for (uint8_t pad : {uint8_t(36), uint8_t(38), uint8_t(40)}) {
        const auto plain = playHeld("Aviary", {{"kit", 1.0f}}, {pad}, blocks);
        const auto knob = playHeld("Aviary", {{"kit", 1.0f}, {"throat", 0.75f}}, {pad}, blocks);
        Route t{1, 1, Av::McThroat, 0.25f, 1.0f};
        const auto routed = playHeld("Aviary", {{"kit", 1.0f}}, {pad}, blocks, &t, Av::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)) && routed != plain,
            "Aviary: in a kit a route to throat reaches pad " + std::to_string(pad - 35), buf);
    }
    // Sweep 7 by default; a route of 0.25 is the knob at 19 (of -24..24).
    {
        const auto knob = playHeld("Aviary", {{"sweep", 19.0f}}, {60}, blocks);
        Route w{2, 3, Av::McSweep, 0.25f, 1.0f};
        const auto routed = playHeld("Aviary", {}, {60}, blocks, &w, Av::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)), "Aviary: a route to sweep matches the knob", buf);
    }
    // Two birds on a call, spread 0.5 by default: a route of 0.5 is spread 1.
    {
        const auto knob = playHeld("Aviary", {{"pattern", 4.0f}, {"flock", 2.0f}, {"spread", 1.0f}}, {60}, blocks);
        Route d{5, 4, Av::McSpread, 0.5f, 1.0f};
        const auto routed = playHeld("Aviary", {{"pattern", 4.0f}, {"flock", 2.0f}}, {60}, blocks, &d, Av::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)), "Aviary: a route to spread matches the knob", buf);
    }
    // Caw: a route to rasp still moves it, from where the caw forces it.
    {
        const auto knob = playHeld("Aviary", {{"pattern", 7.0f}, {"rasp", 0.6f}}, {60}, blocks);
        Route r{8, 1, Av::McRasp, 0.5f, 1.0f};
        const auto routed = playHeld("Aviary", {{"pattern", 7.0f}}, {60}, blocks, &r, Av::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)), "Aviary: a route to rasp moves a caw's rasp", buf);
    }
}

// Tongue: routes to the mouth, the wah and its rate match their knobs.
void tongueRoutes() {
    using To = acidulous::machine::Tongue;
    const int32_t blocks = 750; // a second
    char buf[96];
    // Mouth 0.5 by default: a route of 0.25 is the knob at 0.75.
    {
        const auto knob = playHeld("Tongue", {{"mouth", 0.75f}}, {55}, blocks);
        Route m{1, 2, To::McMouth, 0.25f, 1.0f};
        const auto routed = playHeld("Tongue", {}, {55}, blocks, &m, To::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)), "Tongue: a route to mouth matches the knob", buf);
    }
    // Wah 0 by default: a route of 0.5 is the knob at 0.5.
    {
        const auto plain = playHeld("Tongue", {}, {55}, blocks);
        const auto knob = playHeld("Tongue", {{"wah", 0.5f}}, {55}, blocks);
        Route w{3, 1, To::McWah, 0.5f, 1.0f};
        const auto routed = playHeld("Tongue", {}, {55}, blocks, &w, To::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)) && routed != plain, "Tongue: a route to wah matches the knob", buf);
    }
    // Smooth wah: a route to wah still matches the knob.
    {
        const auto knob = playHeld("Tongue", {{"wah", 0.5f}, {"wahshape", 1.0f}}, {55}, blocks);
        Route w{4, 2, To::McWah, 0.5f, 1.0f};
        const auto routed = playHeld("Tongue", {{"wahshape", 1.0f}}, {55}, blocks, &w, To::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)), "Tongue: a route to a smooth wah matches the knob", buf);
    }
    // Wah rate 1/8 (3 of 0..5) by default: a route of 0.4 is 1/16.
    {
        const auto knob = playHeld("Tongue", {{"wah", 0.5f}, {"wahrate", 5.0f}}, {55}, blocks);
        Route r{6, 4, To::McWahRate, 0.4f, 1.0f};
        const auto routed = playHeld("Tongue", {{"wah", 0.5f}}, {55}, blocks, &r, To::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)), "Tongue: a route to wah rate matches the knob", buf);
    }
}

// Sympath: routes to the sympathetic strings, the pluck and the drone strings match their knobs.
void sympathRoutes() {
    using Sy = acidulous::machine::Sympath;
    const int32_t blocks = 750; // a second
    char buf[96];
    // Tarbs 0.5 by default: a route of 0.25 is the knob at 0.75.
    {
        const auto knob = playHeld("Sympath", {{"tarbs", 0.75f}}, {61}, blocks);
        Route t{1, 2, Sy::McTarbs, 0.25f, 1.0f};
        const auto routed = playHeld("Sympath", {}, {61}, blocks, &t, Sy::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)), "Sympath: a route to tarbs matches the knob", buf);
    }
    // Pluck 0.5 by default, read at the pluck: a route of 0.3 is the knob at 0.8.
    {
        const auto knob = playHeld("Sympath", {{"pluck", 0.8f}}, {61}, blocks);
        Route p{4, 3, Sy::McPluck, 0.3f, 1.0f};
        const auto routed = playHeld("Sympath", {}, {61}, blocks, &p, Sy::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)), "Sympath: a route to pluck matches the knob", buf);
    }
    // Chikari 0 by default: a route of 0.6 strikes the drone strings as the knob does.
    {
        const auto plain = playHeld("Sympath", {}, {61}, blocks);
        const auto knob = playHeld("Sympath", {{"chikari", 0.6f}}, {61}, blocks);
        Route c{7, 4, Sy::McChikari, 0.6f, 1.0f};
        const auto routed = playHeld("Sympath", {}, {61}, blocks, &c, Sy::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)) && routed != plain, "Sympath: a route to chikari matches the knob", buf);
    }
}

// Chanter: routes to the drones, the grace notes, the crank and the dog match their knobs.
void chanterRoutes() {
    using Ch = acidulous::machine::Chanter;
    const int32_t blocks = 750; // a second
    char buf[96];
    // Drones 0.7 by default: a route of 0.2 is the knob at 0.9.
    {
        const auto knob = playHeld("Chanter", {{"drones", 0.9f}}, {69}, blocks);
        Route d{1, 2, Ch::McDrones, 0.2f, 1.0f};
        const auto routed = playHeld("Chanter", {}, {69}, blocks, &d, Ch::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)), "Chanter: a route to drones matches the knob", buf);
    }
    // Grace 0 by default, read at the note: a route of 0.5 is the knob at 30 ms.
    {
        const auto plain = playHeld("Chanter", {}, {69}, blocks);
        const auto knob = playHeld("Chanter", {{"grace", 30.0f}}, {69}, blocks);
        Route g{4, 3, Ch::McGrace, 0.5f, 1.0f};
        const auto routed = playHeld("Chanter", {}, {69}, blocks, &g, Ch::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)) && routed != plain, "Chanter: a route to grace matches the knob", buf);
    }
    // On the hurdy-gurdy, crank 0.5 by default: a route of 0.3 is the knob at 0.8, and the dog's 0.5 plus 0.4 is 0.9.
    {
        const auto knob = playHeld("Chanter", {{"model", 3.0f}, {"wheel", 0.8f}}, {67}, blocks);
        Route w{6, 1, Ch::McWheel, 0.3f, 1.0f};
        const auto routed = playHeld("Chanter", {{"model", 3.0f}}, {67}, blocks, &w, Ch::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)), "Chanter: a route to the crank matches the knob", buf);
        const auto dogKnob = playHeld("Chanter", {{"model", 3.0f}, {"wheel", 0.9f}, {"dog", 0.9f}}, {67}, blocks);
        Route g{8, 4, Ch::McDog, 0.4f, 1.0f};
        const auto dogRouted = playHeld("Chanter", {{"model", 3.0f}, {"wheel", 0.9f}}, {67}, blocks, &g, Ch::MacroDestCount);
        ok2(sameAs(dogKnob, dogRouted, buf, sizeof(buf)), "Chanter: a route to the dog matches the knob", buf);
    }
}

// Pollen: routes to density, size, cutoff and bits match their knobs.
void pollenRoutes() {
    using Po = acidulous::machine::Pollen;
    const int32_t blocks = 750; // a second
    char buf[96];
    // Density 45 a second by default: a route of 0.25 is the knob a quarter of its range up.
    {
        const ParamDef &d = MachineRegistry::create("Pollen")->params().def(Po::Density);
        const float to = d.map(d.unmap(45.0f) + 0.25f);
        const auto knob = playHeld("Pollen", {{"density", to}}, {60}, blocks);
        Route r{1, 2, Po::McDensity, 0.25f, 1.0f};
        const auto routed = playHeld("Pollen", {}, {60}, blocks, &r, Po::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)), "Pollen: a route to density matches the knob", buf);
    }
    // Cutoff 18 kHz by default: a route of -0.5 is the knob halfway down its range.
    {
        const ParamDef &d = MachineRegistry::create("Pollen")->params().def(Po::Cutoff);
        const float to = d.map(d.unmap(18000.0f) - 0.5f);
        const auto knob = playHeld("Pollen", {{"cutoff", to}}, {60}, blocks);
        Route r{5, 4, Po::McCutoff, -0.5f, 1.0f};
        const auto routed = playHeld("Pollen", {}, {60}, blocks, &r, Po::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)), "Pollen: a route to cutoff matches the knob", buf);
    }
    // Bits 16 by default: a route of -0.6 is 7 bits.
    {
        const auto plain = playHeld("Pollen", {}, {60}, blocks);
        const auto knob = playHeld("Pollen", {{"bits", 7.0f}}, {60}, blocks);
        Route r{8, 1, Po::McBits, -0.6f, 1.0f};
        const auto routed = playHeld("Pollen", {}, {60}, blocks, &r, Po::MacroDestCount);
        ok2(sameAs(knob, routed, buf, sizeof(buf)) && routed != plain, "Pollen: a route to bits matches the knob", buf);
    }
}

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
    timberPlaying();
    gNote = 57;
    using Bz = acidulous::machine::Brazen;
    // Brazen's volume is Timber's, 0 to 1.5 from 0.8.
    basics("Brazen", Bz::MacroDestCount, Bz::McVolume, Bz::McPitch, Bz::McOff, Bz::kMacros, 0.05 / 0.8, 0.425 / 0.8);
    brazenRoutes();
    gNote = 57;
    using Mn = acidulous::machine::Manual;
    // Manual's volume is 0 to 2, set to 0.75 here, so -0.5 (a whole 1.0)
    // leaves nothing and -0.25 leaves 0.25.
    gUnits = {{"volume", 0.75f}};
    basics("Manual", Mn::MacroDestCount, Mn::McVolume, Mn::McPitch, Mn::McOff, Mn::kMacros, 0.0, 0.25 / 0.75);
    gUnits.clear();
    manualRoutes();
    gNote = 57;
    using Hm = acidulous::machine::Hammer;
    // Hammer's volume is 0 to 1.5 from 1, so -0.5 leaves 0.25 and -0.25 leaves 0.625.
    basics("Hammer", Hm::MacroDestCount, Hm::McVolume, Hm::McPitch, Hm::McOff, Hm::kMacros, 0.25, 0.625);
    hammerRoutes();
    using Dr = acidulous::machine::Draw;
    basics("Draw", Dr::MacroDestCount, Dr::McVolume, Dr::McPitch, Dr::McOff, Dr::kMacros);
    drawRoutes();
    using Ft = acidulous::machine::Fret;
    basics("Fret", Ft::MacroDestCount, Ft::McVolume, Ft::McPitch, Ft::McOff, Ft::kMacros);
    fretRoutes();
    using Tn = acidulous::machine::Tine;
    basics("Tine", Tn::MacroDestCount, Tn::McVolume, Tn::McPitch, Tn::McOff, Tn::kMacros);
    tineRoutes();
    using Pm = acidulous::machine::Palm;
    basics("Palm", Pm::MacroDestCount, Pm::McVolume, Pm::McPitch, Pm::McOff, Pm::kMacros);
    palmRoutes();
    using Fa = acidulous::machine::Fathom;
    basics("Fathom", Fa::MacroDestCount, Fa::McVolume, Fa::McPitch, Fa::McOff, Fa::kMacros);
    fathomRoutes();
    using Av = acidulous::machine::Aviary;
    basics("Aviary", Av::MacroDestCount, Av::McVolume, Av::McPitch, Av::McOff, Av::kMacros);
    aviaryRoutes();
    using To = acidulous::machine::Tongue;
    basics("Tongue", To::MacroDestCount, To::McVolume, To::McPitch, To::McOff, To::kMacros);
    tongueRoutes();
    using Sy = acidulous::machine::Sympath;
    basics("Sympath", Sy::MacroDestCount, Sy::McVolume, Sy::McPitch, Sy::McOff, Sy::kMacros);
    sympathRoutes();
    using Ch = acidulous::machine::Chanter;
    basics("Chanter", Ch::MacroDestCount, Ch::McVolume, Ch::McPitch, Ch::McOff, Ch::kMacros);
    chanterRoutes();
    using Po = acidulous::machine::Pollen;
    // Volume 0.8 of 1.5: a route of -0.5 leaves 0.05, and half of it 0.425.
    basics("Pollen", Po::MacroDestCount, Po::McVolume, Po::McPitch, Po::McOff, Po::kMacros, 0.05 / 0.8, 0.425 / 0.8);
    pollenRoutes();

    std::printf("%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
