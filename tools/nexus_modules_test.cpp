// Tests Nexus's modules one at a time, outside a patch: every module stays
// finite and bounded on a plain signal, the module table's names are unique
// and the effect modules' knobs name real parameters of their effect. Then
// the modules built from other parts of the engine are checked against
// those parts: a module that hosts an effect sounds exactly like the effect,
// a block late, and the swell module is the Swell effect's own compressor.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <set>
#include <string>
#include <vector>

#include <engine/dsp/Swell.h>
#include <engine/effect/EffectRegistry.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/nexus/Modules.h>

#include "audition_settings.h"

using namespace acidulous;
using namespace acidulous::machine::nexus;

namespace {
int checks = 0, failures = 0;

void ok(const std::string &what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-56s %s\n", cond ? "ok  " : "FAIL", what.c_str(), detail.c_str());
    if (!cond) ++failures;
}

constexpr float kRate = 48000.0f;

std::unique_ptr<Module> make(int32_t type) {
    std::unique_ptr<Module> m(makeModule(type));
    m->prepare(kRate, 16);
    m->setKnobs(infoFor(type).def);
    return m;
}

Context context() {
    Context c;
    c.sampleRate = kRate;
    c.bpm = 120.0f;
    c.tickInc = 96.0 * 2.0 / kRate; // 96 ticks a beat at 120 bpm, in case a module counts them
    return c;
}

/** A module's first output, for a mono input in port 0 and an optional second input. */
std::vector<float> run(Module &m, const std::vector<float> &in, const std::vector<float> *second = nullptr) {
    Context c = context();
    std::vector<float> out(in.size());
    float ins[kPorts]{}, outs[kPorts]{};
    for (size_t i = 0; i < in.size(); ++i) {
        std::fill(std::begin(ins), std::end(ins), 0.0f);
        ins[0] = in[i];
        if (second != nullptr) ins[1] = (*second)[i];
        std::fill(std::begin(outs), std::end(outs), 0.0f);
        m.step(ins, outs, c);
        out[i] = outs[0];
        c.tick += c.tickInc;
    }
    return out;
}

std::vector<float> tone(float hz, float amp, size_t n) {
    std::vector<float> x(n);
    for (size_t i = 0; i < n; ++i) x[i] = amp * static_cast<float>(std::sin(2.0 * M_PI * hz * i / kRate));
    return x;
}

double worstDifference(const std::vector<float> &a, const std::vector<float> &b, size_t delay) {
    double worst = 0.0;
    for (size_t i = delay; i < a.size(); ++i) worst = std::max(worst, std::fabs(double(a[i]) - b[i - delay]));
    return worst;
}

// --- every module -------------------------------------------------------------

void everyModuleBehaves() {
    printf("- every module on a plain signal\n");
    const auto in = tone(220.0f, 0.5f, static_cast<size_t>(kRate));
    int bad = 0;
    std::string which;
    for (int32_t t = 0; t < TypeCount; ++t) {
        auto m = make(t);
        Context c = context();
        float ins[kPorts]{}, outs[kPorts]{};
        bool fine = true;
        for (size_t i = 0; i < in.size() && fine; ++i) {
            // A note held for half the second, so the voice-driven modules do something.
            for (int p = 0; p < kPorts; ++p) ins[p] = p == 0 ? in[i] : 0.0f;
            std::fill(std::begin(outs), std::end(outs), 0.0f);
            m->step(ins, outs, c);
            for (float v : outs) if (!std::isfinite(v) || std::fabs(v) > 64.0f) fine = false;
            c.tick += c.tickInc;
        }
        if (!fine) { ++bad; which += std::string(" ") + infoFor(t).name; }
    }
    ok("every module stays finite and bounded", bad == 0, which);

    std::set<std::string> names;
    for (int32_t t = 0; t < TypeCount; ++t) names.insert(infoFor(t).name);
    ok("every module has its own name", static_cast<int32_t>(names.size()) == TypeCount);
    for (int32_t t = 0; t < TypeCount; ++t) {
        ok(std::string("the table finds ") + infoFor(t).name + " by name", typeFromName(infoFor(t).name) == t);
    }
}

// --- the effect modules ----------------------------------------------------------

struct FxCase { int32_t type; const char *effect; };
const FxCase kFx[] = {
    {TReverb, "Reverb"}, {TChorus, "Chorus"}, {TPhaser, "Phaser"},
    {TCrush, "Bitcrusher"}, {TShift, "Shifter"}, {TDrive, "Distortion"},
};

void effectKnobsAreRealParameters() {
    printf("- the effect modules' knobs\n");
    for (const auto &f : kFx) {
        const ModuleInfo &info = infoFor(f.type);
        int32_t n = 0;
        const ParamDef *defs = EffectRegistry::paramDefs(f.effect, n);
        auto has = [&](const char *name) {
            for (int32_t i = 0; i < n; ++i) if (std::strcmp(defs[i].name, name) == 0) return true;
            return false;
        };
        bool all = true;
        std::string missing;
        for (int k = 0; k < kKnobs; ++k) {
            if (info.knob[k] != nullptr && !has(info.knob[k])) { all = false; missing += std::string(" ") + info.knob[k]; }
        }
        if (info.in[1] != nullptr && !has(info.in[1])) { all = false; missing += std::string(" cv:") + info.in[1]; }
        ok(std::string(info.name) + " names real " + f.effect + " parameters", all, missing);
        // The module's defaults are the effect's.
        bool same = true;
        for (int k = 0; k < kKnobs; ++k) {
            if (info.knob[k] == nullptr) continue;
            for (int32_t i = 0; i < n; ++i) {
                if (std::strcmp(defs[i].name, info.knob[k]) != 0) continue;
                if (std::fabs(defs[i].unmap(defs[i].def) - info.def[k]) > 0.002f) same = false;
            }
        }
        ok(std::string(info.name) + " starts where the effect does", same);
    }
}

/** The effect itself, run mono in the module's block size with the module's knobs. */
std::vector<float> asEffect(const FxCase &f, const std::vector<float> &in) {
    std::unique_ptr<Effect> fx(EffectRegistry::create(f.effect));
    fx->prepare(static_cast<int32_t>(kRate));
    const ModuleInfo &info = infoFor(f.type);
    for (int k = 0; k < kKnobs; ++k) {
        if (info.knob[k] == nullptr) continue;
        fx->params().set(fx->params().indexOf(info.knob[k]), info.def[k]);
    }
    std::vector<float> l = in, r = in;
    constexpr int32_t kBlock = FxMod::kBlock;
    Context c = context();
    for (size_t at = 0; at + kBlock <= in.size(); at += kBlock) {
        fx->onBlock(static_cast<int64_t>(c.tick), static_cast<int64_t>(c.tick + c.tickInc * kBlock), c.bpm);
        fx->run(l.data() + at, r.data() + at, kBlock, false);
        // Counted a sample at a time, as the module sees it, so the two
        // round the transport position the same way.
        for (int32_t i = 0; i < kBlock; ++i) c.tick += c.tickInc;
    }
    return l;
}

void effectModulesAreTheEffects() {
    printf("- an effect module is the effect, a block late\n");
    const auto in = tone(330.0f, 0.4f, 24000);
    for (const auto &f : kFx) {
        auto m = make(f.type);
        const auto viaModule = run(*m, in);
        const auto viaEffect = asEffect(f, in);
        const double d = worstDifference(viaModule, viaEffect, FxMod::kBlock);
        ok(std::string(infoFor(f.type).name) + " matches " + f.effect, d < 1e-5, "worst " + std::to_string(d));
    }
}

void theSecondInputMovesItsParameter() {
    printf("- the second input\n");
    const auto in = tone(330.0f, 0.4f, 24000);
    for (const auto &f : kFx) {
        auto still = make(f.type), moved = make(f.type);
        const std::vector<float> cv(in.size(), 0.3f);
        const auto a = run(*still, in), b = run(*moved, in, &cv);
        ok(std::string(infoFor(f.type).name) + "'s " + infoFor(f.type).in[1] + " input changes the sound",
           worstDifference(a, b, 0) > 1e-3);
    }
}

// --- swell -------------------------------------------------------------------------

void swellIsTheEffectsCompressor() {
    printf("- swell\n");
    auto in = tone(220.0f, 0.05f, 48000);
    auto m = make(TSwell);
    const auto viaModule = run(*m, in);
    dsp::Swell s;
    s.prepare(kRate);
    const float *k = infoFor(TSwell).def;
    dsp::Swell::Settings set;
    set.floorDb = lin(k[0], -80.0f, 0.0f);
    set.ceilingDb = lin(k[1], -30.0f, 0.0f);
    set.amount = k[2];
    set.split = k[3];
    set.releaseSec = expo(k[4], 0.005f, 2.0f);
    set.mix = k[5];
    s.set(set);
    std::vector<float> direct(in.size());
    for (size_t i = 0; i < in.size(); ++i) direct[i] = s.process(in[i]);
    ok("the module is the Swell compressor", worstDifference(viaModule, direct, 0) < 1e-6);
    double peakIn = 0, peakOut = 0;
    for (size_t i = 24000; i < in.size(); ++i) { peakIn = std::max(peakIn, double(std::fabs(in[i]))); peakOut = std::max(peakOut, double(std::fabs(viaModule[i]))); }
    ok("at its defaults it brings a quiet tone up", peakOut > peakIn * 2.0,
       std::to_string(20 * std::log10(peakIn)) + " -> " + std::to_string(20 * std::log10(peakOut)) + " dB");
    auto up = make(TSwell);
    const std::vector<float> full(in.size(), 0.5f);
    const auto pushed = run(*up, in, &full);
    double peakPushed = 0;
    for (size_t i = 24000; i < in.size(); ++i) peakPushed = std::max(peakPushed, double(std::fabs(pushed[i])));
    ok("its amount input pushes it further", peakPushed > peakOut * 1.2);
}

// --- the machines' instruments -------------------------------------------------

/** Runs a module with every input given per sample by [feed], and gives back output port [port]. */
template <typename Feed>
std::vector<float> drive(Module &m, size_t n, Feed feed, int port = 0, Context c = context()) {
    std::vector<float> out(n);
    float ins[kPorts]{}, outs[kPorts]{};
    for (size_t i = 0; i < n; ++i) {
        std::fill(std::begin(ins), std::end(ins), 0.0f);
        feed(i, ins);
        std::fill(std::begin(outs), std::end(outs), 0.0f);
        m.step(ins, outs, c);
        out[i] = outs[port];
        c.tick += c.tickInc;
    }
    return out;
}

double rms(const std::vector<float> &x, size_t from) {
    double s = 0.0;
    for (size_t i = from; i < x.size(); ++i) s += double(x[i]) * x[i];
    return std::sqrt(s / double(x.size() - from));
}

double peak(const std::vector<float> &x) {
    double p = 0.0;
    for (float v : x) p = std::max(p, double(std::fabs(v)));
    return p;
}

/**
 * The pitch of [x] from [from] on, Hz, by the strongest repeat between
 * [lo] and [hi]: the lag where it's most like itself.
 */
double pitchOf(const std::vector<float> &x, size_t from, double lo, double hi) {
    const size_t n = std::min<size_t>(x.size() - from, 9600);
    const int minLag = static_cast<int>(kRate / hi), maxLag = static_cast<int>(kRate / lo);
    std::vector<double> r(maxLag + 1, -1.0);
    double best = -1.0;
    for (int lag = minLag; lag <= maxLag; ++lag) {
        double num = 0.0, a = 0.0, b = 0.0;
        for (size_t i = from; i + lag < from + n; ++i) {
            num += double(x[i]) * x[i + lag];
            a += double(x[i]) * x[i];
            b += double(x[i + lag]) * x[i + lag];
        }
        r[lag] = num / std::sqrt(a * b + 1e-30);
        best = std::max(best, r[lag]);
    }
    // The shortest lag that repeats nearly as well as the best: two periods
    // repeat as well as one, and that's an octave too low.
    for (int lag = minLag + 1; lag < maxLag; ++lag) {
        if (r[lag] >= best * 0.97 && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) return kRate / lag;
    }
    return 0.0;
}

std::string hz(double v) { char b[32]; snprintf(b, sizeof b, "%.1f Hz", v); return b; }
double cents(double a, double b) { return 1200.0 * std::log2(a / b); }

/** Sets one knob of a module, keeping the rest at their defaults. */
void knob(Module &m, int32_t type, int index, float value) {
    float k[kKnobs];
    std::copy(infoFor(type).def, infoFor(type).def + kKnobs, k);
    k[index] = value;
    m.setKnobs(k);
}

// Quant picks the nearest scale degree, and the root an octave up counts.
void quantSnapsToTheNearestDegree() {
    auto q = make(TQuant);
    const auto snap = [&](float note) {
        Context c = context();
        float ins[kPorts]{}, outs[kPorts]{};
        ins[0] = note / 127.0f;
        q->step(ins, outs, c);
        return outs[0] * 127.0f;
    };
    ok("59.9999 in C major snaps up to C", std::fabs(snap(59.9999f) - 60.0f) < 0.01f, std::to_string(snap(59.9999f)));
    ok("59.6 in C major snaps up to C", std::fabs(snap(59.6f) - 60.0f) < 0.01f, std::to_string(snap(59.6f)));
    ok("58.4 in C major stays on B", std::fabs(snap(58.4f) - 59.0f) < 0.01f, std::to_string(snap(58.4f)));
    ok("60.2 in C major stays on C", std::fabs(snap(60.2f) - 60.0f) < 0.01f, std::to_string(snap(60.2f)));
}

void blownInstrumentsSpeakAtTheirNote() {
    printf("- blown, at the note\n");
    const float a3 = 57.0f / 127.0f; // 220 Hz
    struct Case { int32_t type; int kind; const char *what; };
    const Case cases[] = {
        {TBore, -1, "bore"}, {TPipe, 0, "pipe, a reed on a cylinder"}, {TPipe, 1, "pipe, a double reed"},
        {TPipe, 2, "pipe, a jet"}, {TReed, 0, "reed, a harmonica's"}, {TReed, 1, "reed, an accordion's"},
        {TReed, 2, "reed, a melodica's"}, {TReed, 3, "reed, a harmonium's"}, {TReed, 4, "reed, a concertina's"},
    };
    for (const auto &k : cases) {
        auto m = make(k.type);
        if (k.kind >= 0) knob(*m, k.type, 0, (k.kind + 0.5f) / (k.type == TReed ? 5.0f : 3.0f));
        m->setConnected(1u); // a cable in the breath
        const auto out = drive(*m, 48000, [&](size_t, float *in) { in[0] = 0.8f; in[1] = a3; });
        const double level = rms(out, 24000), at = pitchOf(out, 24000, 110.0, 440.0);
        ok(std::string(k.what) + " sounds", level > 0.01 && peak(out) < 8.0, "rms " + std::to_string(level) + " peak " + std::to_string(peak(out)));
        ok(std::string(k.what) + " plays 220 Hz", std::fabs(cents(at, 220.0)) < 60.0, hz(at));
    }
    // With no cable in the breath, the voice's own gate blows it.
    auto m = make(TBore);
    m->setConnected(0u);
    float gate[16] = {1.0f}, vel[16] = {1.0f}, pitch[16] = {57.0f};
    Context c = context();
    c.voice = 0;
    c.gateOf = gate;
    c.velocityOf = vel;
    c.pitchOf = pitch;
    const auto out = drive(*m, 48000, [&](size_t, float *) {}, 0, c);
    ok("with nothing in the breath, the key blows it", rms(out, 24000) > 0.01);
    const auto quiet = drive(*make(TBore), 24000, [&](size_t, float *) {}, 0, context());
    ok("...and with no key down it's silent", peak(quiet) < 1e-6);
}

void theJawHarpRingsWhenPlucked() {
    printf("- jaw\n");
    auto m = make(TJaw);
    const auto out = drive(*m, 48000, [&](size_t i, float *in) { in[0] = i < 100 ? 1.0f : 0.0f; in[1] = 57.0f / 127.0f; });
    const double early = rms(std::vector<float>(out.begin(), out.begin() + 9600), 0);
    const double late = rms(out, 38400);
    ok("a pluck rings", early > 0.005, std::to_string(early));
    ok("...and dies away", late < early * 0.7, std::to_string(late));
    ok("...at the note", std::fabs(cents(pitchOf(out, 2000, 110.0, 440.0), 220.0)) < 60.0, hz(pitchOf(out, 2000, 110.0, 440.0)));
    auto held = make(TJaw);
    knob(*held, TJaw, 6, 1.0f); // drive: the air keeps it going
    const auto kept = drive(*held, 96000, [&](size_t i, float *in) { in[0] = i < 100 ? 1.0f : 0.0f; in[1] = 57.0f / 127.0f; });
    ok("with drive it keeps sounding", rms(kept, 86400) > late, std::to_string(rms(kept, 86400)));
    ok("...and stays bounded", peak(kept) < 8.0, std::to_string(peak(kept)));
}

void thePianoPlaysFromAGate() {
    printf("- piano\n");
    auto m = make(TPiano);
    const auto l = drive(*m, 72000, [&](size_t i, float *in) { in[0] = 60.0f / 127.0f; in[1] = i < 36000 ? 1.0f : 0.0f; in[2] = 0.8f; });
    const double held = rms(std::vector<float>(l.begin() + 4800, l.begin() + 24000), 0);
    const double after = rms(l, 66000);
    ok("a gate plays a note", held > 0.003, std::to_string(held));
    ok("...at middle C", std::fabs(cents(pitchOf(l, 9600, 130.0, 520.0), 261.63)) < 60.0, hz(pitchOf(l, 9600, 130.0, 520.0)));
    ok("letting go damps it", after < held * 0.3, std::to_string(after));
    auto r = make(TPiano);
    const auto right = drive(*r, 24000, [&](size_t, float *in) { in[0] = 60.0f / 127.0f; in[1] = 1.0f; }, 1);
    ok("it's stereo", rms(right, 4800) > 0.001);
}

void wholeMachinesPlayFromAGate() {
    printf("- whole machines\n");
    struct Case { int32_t type; const char *what; float note; double want; bool pitched; };
    const Case cases[] = {
        {TGuitar, "guitar", 52.0f, 164.81, true}, {TMallets, "mallets", 60.0f, 261.63, true},
        {TSitar, "sitar", 57.0f, 220.0, true}, {TDrum, "drum", 55.0f, 196.0, false},
        {TPipes, "pipes", 69.0f, 440.0, false}, {TBird, "bird", 48.0f, 523.25, false},
        {TWater, "water", 72.0f, 523.25, false},
    };
    for (const Case &k : cases) {
        auto m = make(k.type);
        const auto l = drive(*m, 96000, [&](size_t i, float *in) { in[0] = k.note / 127.0f; in[1] = i < 48000 ? 1.0f : 0.0f; in[2] = 0.8f; });
        const double held = rms(std::vector<float>(l.begin() + 4800, l.begin() + 48000), 0);
        ok(std::string(k.what) + ": a gate plays it", held > 0.001, std::to_string(held));
        if (k.pitched) {
            const double got = pitchOf(l, 9600, k.want * 0.6, k.want * 1.6);
            ok(std::string(k.what) + ": at the note", std::fabs(cents(got, k.want)) < 60.0, hz(got));
        }
        bool finite = true;
        for (float v : l) finite = finite && std::isfinite(v);
        ok(std::string(k.what) + ": and stays bounded", finite && peak(l) < 8.0, std::to_string(peak(l)));
    }
    // The bird sings two octaves up while the gate's held, and stops after.
    auto bird = make(TBird);
    knob(*bird, TBird, 0, 0.0f); // a plain whistle
    knob(*bird, TBird, 3, 0.5f); // no sweep
    const auto b = drive(*bird, 72000, [&](size_t i, float *in) { in[0] = 48.0f / 127.0f; in[1] = i < 36000 ? 1.0f : 0.0f; in[2] = 0.8f; });
    ok("bird: a whistle two octaves up", std::fabs(cents(pitchOf(b, 14400, 400.0, 700.0), 523.25)) < 30.0, hz(pitchOf(b, 14400, 400.0, 700.0)));
    ok("bird: quiet once the gate's let go", rms(b, 60000) < rms(std::vector<float>(b.begin() + 9600, b.begin() + 33600), 0) * 0.01, std::to_string(rms(b, 60000)));
}

void theThroatMakesVowels() {
    printf("- throat\n");
    // A saw at 110 Hz, the voice's own kind of source.
    std::vector<float> saw(48000);
    for (size_t i = 0; i < saw.size(); ++i) saw[i] = 2.0f * std::fmod(110.0f * i / kRate, 1.0f) - 1.0f;
    auto oo = make(TThroat), ee = make(TThroat);
    knob(*oo, TThroat, 0, 0.0f);
    knob(*ee, TThroat, 0, 1.0f);
    const auto a = run(*oo, saw), b = run(*ee, saw);
    const double in = rms(saw, 0), outA = rms(a, 4800), outB = rms(b, 4800);
    ok("it passes a saw at about its level", outA > in * 0.1 && outA < in * 4.0 && outB > in * 0.1 && outB < in * 4.0,
       std::to_string(outA / in) + " and " + std::to_string(outB / in));
    // oo is dark and ee bright: compare the saw's harmonics from 2.2 to
    // 3.3 kHz, where ee's second and third formants sit and oo has none,
    // against each one's whole level.
    auto band = [](const std::vector<float> &x, double whole) {
        double sum = 0.0;
        for (int h = 20; h <= 30; ++h) {
            double re = 0.0, im = 0.0;
            for (size_t i = 4800; i < x.size(); ++i) {
                const double ph = 2.0 * M_PI * 110.0 * h * i / kRate;
                re += x[i] * std::cos(ph);
                im += x[i] * std::sin(ph);
            }
            const double amp = 2.0 * std::hypot(re, im) / double(x.size() - 4800);
            sum += amp * amp;
        }
        return 10.0 * std::log10(sum / (whole * whole) + 1e-30);
    };
    const double darkOo = band(a, outA), brightEe = band(b, outB);
    ok("ee is brighter than oo", brightEe > darkOo + 10.0, std::to_string(darkOo) + " -> " + std::to_string(brightEe) + " dB");
}

void formulasRunFromTheirText() {
    printf("- formula\n");
    auto m = make(TFormula);
    std::string why;
    ok("a formula parses", m->setText("t * (t >> 5 | t >> 8)", why), why);
    const auto out = run(*m, std::vector<float>(24000, 0.0f));
    ok("...and plays", rms(out, 0) > 0.05);
    auto bad = make(TFormula);
    ok("a broken one says why", !bad->setText("t * (", why) && !why.empty(), why);
    auto none = make(TFormula);
    none->setText("", why);
    ok("with no formula it's silent", peak(run(*none, std::vector<float>(4800, 0.0f))) == 0.0);
    // x is the input: a formula of x alone passes it through, in 8 bits.
    auto pass = make(TFormula);
    pass->setText("x", why);
    knob(*pass, TFormula, 6, 1.0f);
    const auto tone440 = tone(440.0f, 0.5f, 4800);
    const auto through = run(*pass, tone440);
    ok("x is the input", worstDifference(through, tone440, 0) < 0.02);
}

void theFollowerHearsThePitch() {
    printf("- follow\n");
    auto m = make(TFollow);
    const auto in = tone(220.0f, 0.4f, 48000);
    const auto pitch = drive(*m, in.size(), [&](size_t i, float *x) { x[0] = in[i]; }, 0);
    auto g = make(TFollow);
    const auto gate = drive(*g, in.size(), [&](size_t i, float *x) { x[0] = in[i]; }, 1);
    const double note = pitch.back() * 127.0;
    ok("it finds the note of a tone", std::fabs(note - 57.0) < 0.3, std::to_string(note));
    ok("...and opens its gate", gate.back() > 0.5f);
    auto q = make(TFollow);
    const auto shut = drive(*q, 24000, [&](size_t, float *) {}, 1);
    ok("silence keeps it shut", peak(shut) == 0.0);
}

// --- poly voices into a mono module -----------------------------------------------

/** A Nexus playing [patch], with every knob at its module's default. */
std::unique_ptr<Machine> nexusWith(const std::string &patch) {
    std::unique_ptr<Machine> m(MachineRegistry::create("Nexus"));
    m->prepare(static_cast<int32_t>(kRate));
    std::unique_ptr<Graph> unused;
    audition::mountNexusGraph(m.get(), patch, kRate, {}, unused);
    m->onBlock(0, 0, 120.0f);
    return m;
}

/** [seconds] of the machine's left channel. */
std::vector<float> play(Machine &m, float seconds) {
    const size_t n = static_cast<size_t>(seconds * kRate);
    std::vector<float> out;
    float L[256], R[256];
    while (out.size() < n) {
        m.render(L, R, 256);
        out.insert(out.end(), L, L + 256);
    }
    out.resize(n);
    return out;
}

double rms(const std::vector<float> &x) {
    double sum = 0.0;
    for (float v : x) sum += double(v) * v;
    return x.empty() ? 0.0 : std::sqrt(sum / x.size());
}

/**
 * How far the sound is from [note], in cents, from the best autocorrelation
 * lag within half an octave down and up of the note's period, so the answer
 * can't land on an octave. 9999 if it's silent.
 */
double centsFrom(const std::vector<float> &x, float note) {
    if (rms(x) < 1e-5) return 9999.0;
    const double period = kRate / hzOf(note);
    const int lo = static_cast<int>(period / 1.414), hi = static_cast<int>(period * 1.414) + 1;
    const size_t span = x.size() - static_cast<size_t>(hi) - 2;
    std::vector<double> r(static_cast<size_t>(hi) + 2, 0.0);
    for (int lag = lo - 1; lag <= hi + 1; ++lag) {
        double ab = 0.0, aa = 0.0, bb = 0.0;
        for (size_t i = 0; i < span; ++i) {
            ab += double(x[i]) * x[i + lag];
            aa += double(x[i]) * x[i];
            bb += double(x[i + lag]) * x[i + lag];
        }
        r[static_cast<size_t>(lag)] = ab / std::sqrt(aa * bb + 1e-30);
    }
    int best = lo;
    for (int lag = lo; lag <= hi; ++lag) if (r[static_cast<size_t>(lag)] > r[static_cast<size_t>(best)]) best = lag;
    // Parabolic interpolation for the fraction of a sample.
    const double a = r[static_cast<size_t>(best - 1)], b = r[static_cast<size_t>(best)];
    const double c = r[static_cast<size_t>(best + 1)];
    const double den = a - 2.0 * b + c;
    const double lag = best + (den != 0.0 ? 0.5 * (a - c) / den : 0.0);
    return 1200.0 * std::log2(period / lag);
}

std::string centsText(double c) {
    char buf[32];
    std::snprintf(buf, sizeof buf, "%+.1f cents", c);
    return buf;
}

void monoModulesFollowTheLeadVoice() {
    printf("- poly voices into a mono module\n");
    // The keys into a mono saw: its pitch is one note's, at any velocity.
    const std::string saw = "v|2\nm|0|voice\nm|1|osc|mono\nm|2|out|mono\nc|0.0|1.0|1.0\nc|1.0|2.0|1.0";
    for (int vel : {30, 127}) {
        auto m = nexusWith(saw);
        m->noteOn(60, static_cast<uint8_t>(vel));
        play(*m, 0.1f);
        const double c = centsFrom(play(*m, 0.2f), 60.0f);
        ok("a mono saw plays the note at velocity " + std::to_string(vel), std::fabs(c) < 5.0, centsText(c));
    }
    {
        auto m = nexusWith(saw);
        m->noteOn(60, 40);
        play(*m, 0.1f);
        m->noteOn(67, 110);
        play(*m, 0.1f);
        const double up = centsFrom(play(*m, 0.2f), 67.0f);
        ok("an overlapping note takes the mono saw to its pitch", std::fabs(up) < 5.0, centsText(up));
        m->noteOff(67);
        play(*m, 0.1f);
        const double back = centsFrom(play(*m, 0.2f), 60.0f);
        ok("letting it go returns to the note still held", std::fabs(back) < 5.0, centsText(back));
    }

    // A whole machine played from the keys: it speaks at a low velocity and
    // overlapping notes play the newest.
    const std::string guitar = "v|2\nm|0|voice\nm|1|guitar|mono\nm|2|out|mono\n"
                               "c|0.0|1.0|1.0\nc|0.1|1.1|1.0\nc|0.2|1.2|1.0\nc|1.0|2.0|1.0";
    for (int vel : {30, 127}) {
        auto m = nexusWith(guitar);
        m->noteOn(57, static_cast<uint8_t>(vel));
        play(*m, 0.1f);
        const double c = centsFrom(play(*m, 0.2f), 57.0f);
        ok("the mono guitar plays the note at velocity " + std::to_string(vel), std::fabs(c) < 15.0, centsText(c));
    }
    {
        auto m = nexusWith(guitar);
        m->noteOn(57, 50);
        play(*m, 0.15f);
        m->noteOn(64, 100);
        play(*m, 0.05f);
        const double c = centsFrom(play(*m, 0.2f), 64.0f);
        ok("an overlapping note plays the guitar at its pitch", std::fabs(c) < 15.0, centsText(c));
    }

    // A pitch through poly control blocks first is still one note's pitch.
    // D and A, as they sit well inside a scale step; a slewed C settles a hair
    // under the root, which quant now rounds up to C.
    const std::string quant = "v|2\nm|0|voice\nm|3|quant|poly\nm|1|osc|mono\nm|2|out|mono\n"
                              "c|0.0|3.0|1.0\nc|3.0|1.0|1.0\nc|1.0|2.0|1.0";
    const std::string chain = "v|2\nm|0|voice\nm|4|slew|poly\nm|3|quant|poly\nm|1|osc|mono\nm|2|out|mono\n"
                              "c|0.0|4.0|1.0\nc|4.0|3.0|1.0\nc|3.0|1.0|1.0\nc|1.0|2.0|1.0";
    for (const auto &[name, patch] : {std::pair<std::string, std::string>{"quant", quant}, {"slew and quant", chain}}) {
        auto m = nexusWith(patch);
        m->noteOn(62, 40);
        play(*m, 0.3f);
        m->noteOn(69, 110);
        play(*m, 0.3f);
        const double up = centsFrom(play(*m, 0.2f), 69.0f);
        ok("two held notes through a poly " + name + " play the newest", std::fabs(up) < 5.0, centsText(up));
        m->noteOff(69);
        play(*m, 0.3f);
        const double back = centsFrom(play(*m, 0.2f), 62.0f);
        ok("letting it go through the " + name + " returns to the held note", std::fabs(back) < 5.0, centsText(back));
    }

    // Audio from poly oscillators into a mono module is still a sum.
    {
        const std::string poly = "v|2\nm|0|voice\nm|1|osc|poly\nm|2|out|mono\nc|0.0|1.0|1.0\nc|1.0|2.0|1.0";
        auto one = nexusWith(poly);
        one->noteOn(60, 100);
        play(*one, 0.1f);
        const double r1 = rms(play(*one, 0.2f));
        auto two = nexusWith(poly);
        two->noteOn(60, 100);
        two->noteOn(67, 100);
        play(*two, 0.1f);
        const double r2 = rms(play(*two, 0.2f));
        ok("two poly saws into a mono out are both heard", r2 > r1 * 1.2, std::to_string(r2 / r1));
    }
}

} // namespace

void cablesAreNumberedByTheirPlace() {
    printf("- cable numbers\n");
    // The second cable names a slot with no module and is skipped; the third
    // is still cable 2, as the editor lists it.
    const std::string patch = "v|2\nm|0|voice\nm|1|osc|mono\nm|2|out|mono\n"
                              "c|0.0|1.0|1.0\nc|7.0|2.0|1.0\nc|1.0|2.0|1.0";
    auto loudness = [&](const char *a, const char *b) {
        auto m = nexusWith(patch);
        ParamSet &p = m->params();
        for (const char *name : {a, b}) {
            const int i = p.indexOf(name);
            p.jump(i, p.def(i).unmap(0.0f));
        }
        m->noteOn(60, 100);
        return peak(play(*m, 0.2f));
    };
    const double two = loudness("c02_a", "c02_b"), one = loudness("c01_a", "c01_b");
    ok("cable 2 at depth 0 silences the third cable", two < 1e-6, std::to_string(two));
    ok("cable 1, the skipped one, changes nothing", one > 0.05, std::to_string(one));
}

int main() {
    printf("nexus modules\n");
    everyModuleBehaves();
    effectKnobsAreRealParameters();
    effectModulesAreTheEffects();
    theSecondInputMovesItsParameter();
    swellIsTheEffectsCompressor();
    quantSnapsToTheNearestDegree();
    blownInstrumentsSpeakAtTheirNote();
    theJawHarpRingsWhenPlucked();
    thePianoPlaysFromAGate();
    wholeMachinesPlayFromAGate();
    theThroatMakesVowels();
    formulasRunFromTheirText();
    theFollowerHearsThePitch();
    monoModulesFollowTheLeadVoice();
    cablesAreNumberedByTheirPlace();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
