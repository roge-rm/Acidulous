#include "Forage.h"
#include <cmath>
#include <engine/dsp/Math.h>

namespace acidulous::machine {

namespace {
// Built once: 13 pads x 14 parameters, then the globals. Names are stable
// strings the table points into.
struct Table {
    std::vector<std::string> names;
    std::vector<ParamDef> defs;
    Table() {
        const ParamDef per[Forage::PadParamCount] = {
            {"start", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
            {"end", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
            {"pitch", -24.0f, 24.0f, 0.0f, Curve::Linear, 0, "st"},
            {"decay", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""}, // 1 = play through
            // Up to four, not one.
            //
            // A pad level that can only attenuate cannot match anything: put
            // thirteen files from thirteen sources on the pads and the loud
            // ones come down while the quiet ones are already at the top of
            // the knob. Measured across a ragged kit, matching against a
            // ceiling of one closed eighteen decibels of spread to thirteen;
            // twelve decibels of headroom closes it to one. The default is
            // unchanged at 0.8, so nothing sounds different until somebody
            // turns it up.
            {"level", 0.0f, 4.0f, 0.8f, Curve::Linear, 0, ""},
            {"pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
            {"reverse", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
            {"choke", 0.0f, 4.0f, 0.0f, Curve::Stepped, 5, ""}, // 0 none, 1..4 groups
            {"cutoff", 40.0f, 18000.0f, 18000.0f, Curve::Exponential, 0, "Hz"},
            {"reso", 0.0f, 1.0f, 0.1f, Curve::Linear, 0, ""},
            {"mode", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""}, // lp, bp
            {"crush", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
            {"penv", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},   // octaves of sweep, signed
            {"pdecay", 5.0f, 500.0f, 60.0f, Curve::Exponential, 0, "ms"},
        };
        const ParamDef globals[Forage::GlobalCount] = {
            {"accent", 0.0f, 1.0f, 0.6f, Curve::Linear, 0, ""},
            // The machine had no level of its own at all, so a bank of kits
            // could not be levelled against anything - see kDrive below.
            {"volume", 0.0f, 1.5f, 0.9f, Curve::Linear, 0, ""},
        };
        // Every name is placed before any def points at one: a ParamDef holds
        // a bare char*, and a vector that reallocates would leave every one of
        // them dangling. The reserve is doing real work.
        names.reserve(Forage::kPads * Forage::PadParamCount + Forage::GlobalCount);
        for (int32_t p = 0; p < Forage::kPads; ++p) {
            for (int32_t i = 0; i < Forage::PadParamCount; ++i) {
                char buf[32];
                snprintf(buf, sizeof(buf), "p%02d_%s", p, per[i].name);
                names.emplace_back(buf);
            }
        }
        for (const ParamDef &g : globals) names.emplace_back(g.name);
        for (int32_t p = 0; p < Forage::kPads; ++p) {
            for (int32_t i = 0; i < Forage::PadParamCount; ++i) {
                ParamDef d = per[i];
                d.name = names[static_cast<size_t>(p * Forage::PadParamCount + i)].c_str();
                defs.push_back(d);
            }
        }
        for (int32_t g = 0; g < Forage::GlobalCount; ++g) {
            ParamDef d = globals[g];
            d.name = names[static_cast<size_t>(Forage::kPads * Forage::PadParamCount + g)].c_str();
            defs.push_back(d);
        }
    }
};
const Table &table() { static const Table t; return t; }

// How hard the pads hit the clipper, and where a levelled bank sits in the
// volume knob's travel. Two constants because they answer two questions:
// kDrive decides how dirty the box is, kHouse decides where the fader lands.
constexpr float kDrive = 0.375f; // -8.5 dB into the clipper

// The ramps at a sample's edges, in frames at 48 kHz.
//
// A pad does not necessarily start at a zero crossing: `start` above zero
// drops the read head into the middle of a waveform, and what that produces
// is a step. Measured, a kit with `start` at a tenth read a discontinuity
// twenty-one thousand times the size of the sound's own - which is not a
// transient, it is a click on every hit. The same applies at `end`, which
// otherwise stops dead mid-waveform.
//
// Half a millisecond in, so a kick still arrives as a kick; two out, because
// nothing is waiting on the tail and a longer ramp is cheaper to hide.
constexpr int32_t kFadeIn = 24;
constexpr int32_t kFadeOut = 96;
constexpr float kHouse = 0.83f;
} // namespace

Forage::Forage() { initParams(); }

const ParamDef *Forage::paramDefs(int32_t &count) const {
    count = static_cast<int32_t>(table().defs.size());
    return table().defs.data();
}

void Forage::prepare(int32_t sampleRate) {
    sr = static_cast<float>(sampleRate);
    for (auto &p : pads) p.filter.setSampleRate(sr);
    params_.jumpAll();
    reset();
}

void Forage::reset() {
    for (auto &p : pads) { p.playing = false; p.amp = 0.0f; p.filter.reset(); }
}

void Forage::allNotesOff() {}

void *Forage::swapObject(int32_t slot, void *object) {
    if (slot == kSharedSlot) {
        void *old = const_cast<SampleData *>(shared);
        shared = static_cast<const SampleData *>(object);
        // Anything reading the shared sample has to stop before it goes.
        for (auto &p : pads) if (p.sample == nullptr) p.playing = false;
        return old;
    }
    if (slot < 0 || slot >= kPads) return object;
    void *old = const_cast<SampleData *>(pads[slot].sample);
    pads[slot].sample = static_cast<const SampleData *>(object);
    pads[slot].playing = false;
    return old;
}

void Forage::noteOn(uint8_t note, uint8_t velocity) {
    const int32_t pad = static_cast<int32_t>(note) - kBaseNote;
    if (pad < 0 || pad >= kPads) return;
    trigger(pad, velocity / 127.0f, velocity >= 100);
}

void Forage::trigger(int32_t i, float vel, bool accent) {
    Pad &p = pads[i];
    const SampleData *src = p.sample != nullptr ? p.sample : shared;
    if (src == nullptr || src->frames == 0) return;
    const float accentAmt = params_.get(globalIndex(Accent));
    // Choke: pads in the same non-zero group cut each other.
    const float group = params_.get(index(i, Choke));
    if (group >= 0.5f) {
        for (int32_t j = 0; j < kPads; ++j) {
            if (j != i && pads[j].playing && std::fabs(params_.get(index(j, Choke)) - group) < 0.5f) {
                pads[j].ampCoeff = dsp::onePoleCoeff(0.006f, sr); // a fast release, not a click
                pads[j].penv = 0.0f;
            }
        }
    }
    const bool reverse = params_.get(index(i, Reverse)) >= 0.5f;
    const double frames = src->frames;
    const double start = params_.get(index(i, Start)) * frames;
    const double end = params_.get(index(i, End)) * frames;
    p.pos = reverse ? std::max(start, end) - 1.0 : std::min(start, end);
    p.playing = true;
    p.gain = (0.3f + 0.7f * vel) * (1.0f + (accent ? accentAmt * 0.8f : 0.0f));
    const float decay = params_.get(index(i, Decay));
    // decay 1.0 plays through; below it an exponential release, 20 ms .. 4 s
    p.amp = 1.0f;
    p.ampCoeff = decay >= 0.999f ? 0.0f : dsp::onePoleCoeff(0.02f * std::pow(200.0f, decay), sr);
    p.penv = 1.0f;
    p.penvCoeff = dsp::onePoleCoeff(params_.get(index(i, PitchDecay)) * 0.001f, sr);
    p.filter.reset();
    p.holdPhase = 0.0f;
    p.age = 0;
}

bool Forage::render(float *L, float *R, int32_t frames) {
    params_.tick();
    for (int32_t n = 0; n < frames; ++n) { L[n] = 0.0f; R[n] = 0.0f; }
    for (int32_t i = 0; i < kPads; ++i) {
        Pad &p = pads[i];
        const SampleData *src = p.sample != nullptr ? p.sample : shared;
        if (!p.playing || src == nullptr) continue;
        const SampleData &s = *src;
        const bool reverse = params_.get(index(i, Reverse)) >= 0.5f;
        const double lo = std::min(params_.get(index(i, Start)), params_.get(index(i, End))) * s.frames;
        const double hi = std::max(params_.get(index(i, Start)), params_.get(index(i, End))) * s.frames;
        const float pitch = params_.get(index(i, Pitch));
        const float penvAmt = params_.get(index(i, PitchEnv));
        const float level = params_.get(index(i, Level)) * p.gain;
        const float pan = params_.get(index(i, Pan));
        const float gl = std::cos((pan + 1.0f) * 0.25f * dsp::kPi) * 1.4142f;
        const float gr = std::sin((pan + 1.0f) * 0.25f * dsp::kPi) * 1.4142f;
        const float crush = params_.get(index(i, Crush));
        const bool bandpass = params_.get(index(i, Mode)) >= 0.5f;
        p.filter.set(params_.get(index(i, Cutoff)), params_.get(index(i, Reso)));
        const float levels = crush > 0.0f ? std::exp2(16.0f - crush * 13.0f) : 0.0f; // 16 -> 3 bits
        const float holdStep = 1.0f / (1.0f + crush * 11.0f);                          // 48 kHz -> 4 kHz

        // The read rate is a power of two, and an exp2 per sample per pad is
        // thirteen of them per frame for a knob almost every pad leaves alone.
        // Only a pitch envelope actually moves it; without one it is fixed for
        // the whole block.
        const bool sweeping = penvAmt != 0.0f;
        const double fixedRate = std::exp2(pitch / 12.0f);
        for (int32_t n = 0; n < frames; ++n) {
            const double rate = sweeping ? std::exp2((pitch + penvAmt * 12.0f * p.penv) / 12.0f) : fixedRate;
            p.penv = dsp::undenormal(p.penv - p.penv * p.penvCoeff);
            if (p.ampCoeff > 0.0f) { p.amp -= p.amp * p.ampCoeff; if (p.amp < 1e-4f) { p.playing = false; break; } }
            // `> hi - 1` and not `>=`: a reversed pad starts at exactly the
            // last readable frame, and with the old test it was outside its
            // own range on its first sample and stopped before it made a
            // sound. Every reversed patch in the bank measured -200 dB.
            if (p.pos < lo || p.pos > hi - 1.0) { p.playing = false; break; }
            const auto i0 = static_cast<size_t>(p.pos);
            const float frac = static_cast<float>(p.pos - static_cast<double>(i0));
            const size_t i1 = i0 + 1 < static_cast<size_t>(s.frames) ? i0 + 1 : i0;
            float l = s.left[i0] * (1.0f - frac) + s.left[i1] * frac;
            float r = s.stereo ? s.right[i0] * (1.0f - frac) + s.right[i1] * frac : l;
            p.pos += reverse ? -rate : rate;

            if (crush > 0.0f) {
                p.holdPhase += holdStep;
                if (p.holdPhase >= 1.0f) {
                    p.holdPhase -= 1.0f;
                    p.holdL = std::round(l * levels) / levels;
                    p.holdR = std::round(r * levels) / levels;
                }
                l = p.holdL;
                r = p.holdR;
            }
            const float m = (l + r) * 0.5f;
            // A band-pass comes back with the filter's own Q as a gain, so
            // without this the `reso` knob is a volume control and band-pass
            // mode is several decibels under low-pass for no reason anybody
            // asked for. The narrowness is the point and stays; the scaling
            // was never the point. Same correction as Cipher and the Nexus
            // band block needed.
            const float f = bandpass ? p.filter.bandpass(m) * p.filter.bandNorm() : p.filter.lowpass(m);
            // keep the stereo image: apply the filter's change as a mono correction
            const float corr = f - m;
            l += corr;
            r += corr;
            // How far from whichever edge playback is running towards.
            const double toEdge = reverse ? p.pos - lo : (hi - 1.0) - p.pos;
            const float rampIn = p.age < kFadeIn ? static_cast<float>(p.age) / static_cast<float>(kFadeIn) : 1.0f;
            const float rampOut = toEdge < kFadeOut
                                      ? static_cast<float>(toEdge) / static_cast<float>(kFadeOut)
                                      : 1.0f;
            ++p.age;
            const float a = p.amp * level * rampIn * (rampOut > 0.0f ? rampOut : 0.0f);
            L[n] += l * a * gl;
            R[n] += r * a * gr;
        }
    }
    // Thirteen pads summed straight into a tanh is not a mix bus, it is a
    // ceiling: every pad arrived at it near full scale, so the whole kit came
    // out flattened to within a decibel of itself and of every other kit. It
    // read as "balanced" and was nothing of the kind - the clipper was doing
    // the balancing. Hexbeat had exactly this and was fixed the same way.
    //
    // The tanh stays, because a sample box that bends when the whole kit lands
    // on one beat is the sound. What changes is that it is something the loud
    // moments reach rather than something every hit lives inside: at kDrive a
    // single accented pad comes out near a third of full scale and stays
    // straight, and it takes several at once to bend.
    const float out = params_.get(globalIndex(Volume)) * kHouse;
    for (int32_t n = 0; n < frames; ++n) {
        L[n] = dsp::fastTanh(L[n] * kDrive) * out;
        R[n] = dsp::fastTanh(R[n] * kDrive) * out;
    }
    return true;
}

} // namespace acidulous::machine
