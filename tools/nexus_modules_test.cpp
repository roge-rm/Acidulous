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
#include <engine/machine/nexus/Modules.h>

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

} // namespace

int main() {
    printf("nexus modules\n");
    everyModuleBehaves();
    effectKnobsAreRealParameters();
    effectModulesAreTheEffects();
    theSecondInputMovesItsParameter();
    swellIsTheEffectsCompressor();
    blownInstrumentsSpeakAtTheirNote();
    theJawHarpRingsWhenPlucked();
    thePianoPlaysFromAGate();
    theThroatMakesVowels();
    formulasRunFromTheirText();
    theFollowerHearsThePitch();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
