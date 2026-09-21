#include "Resonance.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <engine/dsp/Math.h>

namespace acidulous::machine {

namespace {
constexpr float kTwoPi = 6.28318530718f;

/**
 * What a shape rings at, as ratios of its lowest mode. These are the real
 * numbers: a drumhead's are Bessel zeros, a free bar's come from its fourth
 * order equation, a bell's are what founders have measured for centuries.
 * That is why the machine sounds like objects rather than like filters.
 */
const float kRatios[Resonance::ShapeCount][Resonance::kMaxModes] = {
    // Membrane: a circular drumhead, which is why a tom is not a pitch.
    {1.000f, 1.594f, 2.136f, 2.296f, 2.653f, 2.918f, 3.156f, 3.501f, 3.600f, 3.652f, 4.060f, 4.154f,
     4.230f, 4.378f, 4.832f, 5.000f, 5.412f, 5.550f, 5.892f, 6.155f, 6.500f, 6.780f, 7.100f, 7.500f},
    // Bar, free at both ends: the marimba and the glockenspiel.
    {1.000f, 2.756f, 5.404f, 8.933f, 13.34f, 18.64f, 24.82f, 31.87f, 39.80f, 48.60f, 58.28f, 68.83f,
     80.25f, 92.55f, 105.7f, 119.7f, 134.6f, 150.3f, 166.9f, 184.3f, 202.6f, 221.7f, 241.7f, 262.5f},
    // Plate: dense and close-packed, which is why a plate is a crash.
    {1.000f, 2.040f, 3.010f, 3.660f, 4.580f, 5.540f, 6.120f, 7.020f, 7.940f, 8.480f, 9.320f, 10.15f,
     10.90f, 11.72f, 12.48f, 13.31f, 14.09f, 14.92f, 15.70f, 16.53f, 17.31f, 18.14f, 18.92f, 19.75f},
    // Tube, open: the harmonic series, and so the only shape with a pitch.
    {1.000f, 2.000f, 3.000f, 4.000f, 5.000f, 6.000f, 7.000f, 8.000f, 9.000f, 10.00f, 11.00f, 12.00f,
     13.00f, 14.00f, 15.00f, 16.00f, 17.00f, 18.00f, 19.00f, 20.00f, 21.00f, 22.00f, 23.00f, 24.00f},
    // Bowl: a bell, with the minor third founders spent centuries tuning out.
    {1.000f, 2.000f, 3.010f, 4.170f, 5.430f, 6.790f, 8.210f, 9.700f, 11.24f, 12.83f, 14.47f, 16.15f,
     17.87f, 19.63f, 21.42f, 23.25f, 25.11f, 27.00f, 28.92f, 30.87f, 32.85f, 34.86f, 36.89f, 38.95f},
    // Metal: irrational on purpose - the square roots, which never agree.
    {1.000f, 1.414f, 1.732f, 2.236f, 2.646f, 3.162f, 3.606f, 4.123f, 4.583f, 5.099f, 5.568f, 6.083f,
     6.557f, 7.071f, 7.550f, 8.062f, 8.544f, 9.055f, 9.539f, 10.05f, 10.54f, 11.05f, 11.53f, 12.04f},
};
} // namespace

Resonance::Resonance() { initParams(); }

const ParamDef *Resonance::paramDefs(int32_t &count) const {
    static ParamDef defs[Count];
    static bool built = false;
    static char names[kPads * PadParamCount][16];
    if (!built) {
        // Eight identical objects, named the way Forage names its pads, so
        // one panel describes them all and a lane can point at any of them.
        struct Spec { const char *name; float min, max, def; Curve curve; int32_t steps; const char *unit; };
        static const Spec kPad[PadParamCount] = {
            {"kind", 0.0f, 5.0f, 0.0f, Curve::Stepped, 6, ""},
            {"tune", 30.0f, 2000.0f, 120.0f, Curve::Exponential, 0, "Hz"},
            {"decay", 0.02f, 8.0f, 0.6f, Curve::Exponential, 0, "s"},
            {"damp", 0.0f, 1.0f, 0.4f, Curve::Linear, 0, ""},
            {"inharm", -0.15f, 0.35f, 0.0f, Curve::Linear, 0, ""},
            {"hit", 0.0f, 1.0f, 0.35f, Curve::Linear, 0, ""},
            {"hard", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
            {"noise", 0.0f, 1.0f, 0.2f, Curve::Linear, 0, ""},
            {"bend", 0.0f, 24.0f, 0.0f, Curve::Linear, 0, ""},
            {"bendtime", 0.002f, 0.4f, 0.03f, Curve::Exponential, 0, "s"},
            {"drive", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
            {"level", 0.0f, 1.5f, 0.8f, Curve::Linear, 0, ""},
            {"pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
            {"couple", 0.0f, 1.0f, 0.3f, Curve::Linear, 0, ""},
        };
        for (int32_t pad = 0; pad < kPads; ++pad) {
            for (int32_t i = 0; i < PadParamCount; ++i) {
                const int32_t at = pad * PadParamCount + i;
                std::snprintf(names[at], sizeof(names[at]), "p%02d_%s", pad, kPad[i].name);
                defs[at] = {names[at], kPad[i].min, kPad[i].max, kPad[i].def, kPad[i].curve, kPad[i].steps, kPad[i].unit};
            }
        }
        defs[Modes] = {"modes", 4.0f, 24.0f, 12.0f, Curve::Stepped, 6, ""};
        defs[Coupling] = {"coupling", 0.0f, 1.0f, 0.25f, Curve::Linear, 0, ""};
        defs[Humanise] = {"humanise", 0.0f, 1.0f, 0.15f, Curve::Linear, 0, ""};
        defs[Accent] = {"accent", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""};
        defs[Volume] = {"volume", 0.0f, 1.5f, 0.5f, Curve::Linear, 0, ""}; // Init on the house line
        defs[MasterPan] = {"pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""};
        built = true;
    }
    count = Count;
    return defs;
}

void Resonance::prepare(int32_t sampleRate) {
    sr = static_cast<float>(sampleRate);
    reset();
}

void Resonance::reset() {
    // A whole pad back to new. It carried the excitation step and gain, the
    // bend coefficients and the velocity of the last hit, none of which were
    // being cleared - and a default Pad has builtTune -1, which is what
    // forces the rebuild the old code asked for by hand.
    for (auto &p : pads) p = Pad();
    knockBus = ringBus = 0.0f;
    rng = kRngSeed;
}

void Resonance::allNotesOff() {
    // A struck object does not take its ring back, but a panic must.
    for (auto &p : pads) {
        for (auto &m : p.modes) m.clear();
        p.exciteLeft = 0.0f;
        p.ringing = false;
        p.last = 0.0f;
        p.coupleLp = 0.0f;
    }
    knockBus = ringBus = 0.0f;
}

/**
 * Build one object: where its modes are, how loud each is given where it
 * was hit, and how long each takes to die.
 */
void Resonance::buildPad(int32_t pad) {
    Pad &p = pads[pad];
    const int32_t kind = std::clamp(padStep(pad, Kind), 0, ShapeCount - 1);
    const float tune = padParam(pad, Tune);
    const float decay = padParam(pad, Decay);
    const float damp = padParam(pad, Damp);
    const float inharm = padParam(pad, Inharm);
    const float hit = hitOf(pad);
    const int32_t want = std::clamp(static_cast<int32_t>(params_.get(Modes) + 0.5f), 1, kMaxModes);

    p.modeCount = want;
    p.builtKind = kind;
    p.builtTune = tune;
    p.builtDecay = decay;
    p.builtDamp = damp;
    p.builtInharm = inharm;
    p.builtHit = hit;
    p.builtModes = want;

    // How loud this object is overall must not depend on how long it rings:
    // a two-pole resonator driven for longer than a few of its own periods
    // builds towards b0/(1-r), and 1-r runs forty to one between a practice
    // pad at fifty milliseconds and a bell at two seconds, so the dry kits
    // came out thirty decibels under the ringing ones. The square root of it,
    // because a strike is short against a low mode's period and long against
    // a high one's - full correction put the long-decay kits down instead.
    //
    // **Once per object, from its own decay - not per mode.** The high modes
    // are damped far shorter than the fundamental on purpose, and correcting
    // each one by its own decay handed them up to seventeen decibels of boost
    // apiece: every object in the bank grew a hiss made of its own overtones.
    // What is being levelled here is objects against each other, not the
    // inside of one.
    static const float kRefDecay = 0.5f;
    const float rRef = std::exp(-6.9078f / (kRefDecay * sr));
    const float r0 = std::exp(-6.9078f / (std::max(0.01f, decay) * sr));
    const float decayTrim = std::sqrt((1.0f - r0) / (1.0f - rRef));
    // The same number keeps the coupling loop stable at every decay: a
    // resonator's gain at resonance runs as 1/sqrt(1-r), so scaling what it
    // accepts from the frame by sqrt(1-r) makes the round trip flat. Without
    // it, Cathedral - eight-second bells at coupling 0.8 - sat at a steady
    // -15.9 dB for ever while the short kits were perfectly stable.
    p.couplingTrim = std::min(1.0f, decayTrim);

    for (int32_t k = 0; k < want; ++k) {
        // Stretch: a real bar is stiff, and stiffness runs its partials
        // sharp. Past a little of it nothing on earth sounds like this.
        const float ratio = std::pow(kRatios[kind][k], 1.0f + inharm);
        const float hz = std::clamp(tune * ratio, 20.0f, sr * 0.47f);
        // Where it was hit decides which modes are there at all: a mode with
        // a node under the stick does not get struck. That is why a rim shot
        // and a centre hit are different sounds and not a filter apart.
        const float node = std::fabs(std::sin(kTwoPi * 0.5f * (k + 1) * std::clamp(hit, 0.02f, 0.98f)));
        // Higher modes die sooner, and the more so the more damping there
        // is - the difference between a bell and a practice pad.
        const float t60 = std::max(0.01f, decay / (1.0f + damp * 6.0f * (ratio - 1.0f)));
        const float r = std::exp(-6.9078f / (t60 * sr));
        const float w = kTwoPi * hz / sr;
        Mode &m = p.modes[k];
        m.a1 = 2.0f * r * std::cos(w);
        m.a2 = -r * r;
        // Normalised so a mode's peak does not depend much on its frequency,
        // then rolled off up the series so an object has a spectrum rather
        // than a comb.
        //
        // The *square root* of sin(w), not the whole of it. A resonator
        // driven continuously peaks at b0/((1-r).sin w), so sin(w) is the
        // right correction for a sustained input - but a strike is impulsive
        // against a low mode, where the peak is simply b0, and sustained only
        // against a high one whose period is shorter than the contact. With
        // the full correction b0 *rose* threefold up the series: a
        // glockenspiel's fundamental was the quietest thing in it and the kit
        // measured as 99.7% treble.
        m.b0Base = std::sqrt(std::sin(w)) / std::pow(static_cast<float>(k + 1), 0.7f) * decayTrim;
        m.b0 = m.b0Base * node;
    }
    for (int32_t k = want; k < kMaxModes; ++k) p.modes[k].clear();
}

/**
 * The same pad, struck somewhere else.
 *
 * Everything an object is - its partial ratios, its decays, its pole pair -
 * is unchanged by where the stick lands; only which modes are excited is.
 * `buildPad` above computes both and this computes the second alone, which is
 * what a note-on needs and is one sine a mode instead of six libm calls.
 */
void Resonance::restrike(int32_t pad) {
    Pad &p = pads[pad];
    const float hit = hitOf(pad);
    p.builtHit = hit;
    for (int32_t k = 0; k < p.modeCount; ++k) {
        const float node =
            std::fabs(std::sin(kTwoPi * 0.5f * (k + 1) * std::clamp(hit, 0.02f, 0.98f)));
        p.modes[k].b0 = p.modes[k].b0Base * node;
    }
}

void Resonance::noteOn(uint8_t note, uint8_t velocity) {
    const int32_t pad = note - kBaseNote;
    if (pad < 0 || pad >= kPads) return;
    Pad &p = pads[pad];

    const float accent = params_.get(Accent);
    const float vel = static_cast<float>(velocity) / 127.0f;
    p.velocity = 1.0f - accent + accent * vel;

    // Humanise: a hand never hits the same place twice, so the strike moves
    // a little and the object answers differently. It is the reason two hits
    // in a row do not sound like a copy.
    const float humanise = params_.get(Humanise);
    const float wobble = humanise > 0.001f ? noise() * humanise * 0.12f : 0.0f;
    p.hit = std::clamp(padParam(pad, Hit) + wobble, 0.0f, 1.0f);

    const float hard = padParam(pad, Hard);
    // A hard stick is a short, bright hit; a soft one is longer and duller.
    const float length = (0.4f + (1.0f - hard) * 6.0f) * 0.001f * sr;
    p.exciteLeft = length;
    p.exciteStep = 1.0f / length;
    p.exciteGain = p.velocity * (0.6f + hard * 0.8f);
    p.bendDepth = padParam(pad, Bend);
    p.bendLeft = p.bendDepth > 0.01f ? 1.0f : 0.0f;
    p.bendCoeff = 1.0f - std::exp(-1.0f / (std::max(0.002f, padParam(pad, BendTime)) * sr));
    p.ringing = true;
}

namespace {
/**
 * Where the modal bank actually lands.
 *
 * A mode's `b0` is normalised for its frequency but not for its gain, and
 * eight objects of up to twenty-four resonators each, panned twice at 1.4142,
 * came out at **+34.5 dBFS** with nothing but defaults - fifty times full
 * scale, against -24 to 0 for every other machine in the app. `reset_test`
 * had been printing `peak 51.5` in its own output since this machine was
 * written; nobody reads that column.
 *
 * Measured rather than derived, because the peak is set by the strike
 * transient and not by the resonators' steady state: it sits between +33.2
 * and +34.9 dBFS across every mode count from 4 to 24 and every decay from
 * 50 ms to 8 seconds. One constant is therefore the whole of it, and it
 * leaves the balance between objects and the shape of every sound exactly
 * where they were.
 *
 * It is applied after the coupling bus rather than to the modal sum, because
 * the bus goes through a tanh: scaling before it would change how hard the
 * objects drive each other, which is a sound and not a level.
 */
// The house level. 1/96 was set against mode gains that had no decay term
// in them; normalising those moved the whole machine down, and thirteen of
// twenty-six kits ended up pinned at full volume and still short. This does
// not decide how loud Resonance is - the bank is levelled either way - it
// decides where in the volume knob's travel the kits sit.
constexpr float kOutputTrim = 1.0f / 26.0f; // -28.3 dB
// One pole at about a kilohertz at 48 kHz: what a shared frame passes.
constexpr float kBusPole = 0.125f;
// How much of an object's *ring* reaches the frame. A knock is broadband and
// travels; a ring is a handful of lines and travels much less well. It is
// also what keeps the two-step path - A into B into A - under unity.
constexpr float kRingToBus = 0.06f;
// And how much of a *knock* does. Nearly all of it: a strike is broadband and
// is what sets a neighbour going. It is also a one-shot - made by a key, not
// by a resonator - so no amount of it can close a loop.
constexpr float kKnockToBus = 0.9f;
} // namespace

bool Resonance::render(float *L, float *R, int32_t frames) {
    params_.tick();
    for (int32_t i = 0; i < frames; ++i) L[i] = R[i] = 0.0f;

    const float coupling = params_.get(Coupling);
    const float volume = params_.get(Volume);
    const float masterPan = params_.get(MasterPan);
    const int32_t wantModes = std::clamp(static_cast<int32_t>(params_.get(Modes) + 0.5f), 1, kMaxModes);

    for (int32_t pad = 0; pad < kPads; ++pad) {
        Pad &p = pads[pad];
        // Rebuilt only when the object itself changed: two dozen cosines is
        // not something to do per block for eight pads.
        // The object, and then the strike. Split because they change at very
        // different rates: the object when somebody turns a knob, the strike
        // on every single note.
        if (p.builtKind != padStep(pad, Kind) || p.builtTune != padParam(pad, Tune) ||
            p.builtDecay != padParam(pad, Decay) || p.builtDamp != padParam(pad, Damp) ||
            p.builtInharm != padParam(pad, Inharm) || p.builtModes != wantModes) {
            buildPad(pad);
        } else if (p.builtHit != hitOf(pad)) {
            restrike(pad);
        }
    }

    for (int32_t i = 0; i < frames; ++i) {
        float mixL = 0.0f, mixR = 0.0f;
        const float knockIn = knockBus, ringIn = ringBus;
        float knockOut = 0.0f, ringOut = 0.0f;

        for (int32_t pad = 0; pad < kPads; ++pad) {
            Pad &p = pads[pad];
            const float couple = padParam(pad, Couple) * coupling;
            if (!p.ringing && couple <= 0.0001f) continue;

            // The strike: a burst somewhere between a click and a puff of
            // noise, shaped by how hard and how soft the beater is.
            float x = 0.0f;
            if (p.exciteLeft > 0.0f) {
                const float shape = p.exciteLeft * p.exciteStep; // 1 -> 0
                const float noiseAmount = padParam(pad, Noise);
                const float click = shape * shape;
                x = (click * (1.0f - noiseAmount) + noise() * shape * noiseAmount) * p.exciteGain;
                p.exciteLeft -= 1.0f;
            }
            const float strike = x;
            // And what the rest of the kit is doing, which is the whole
            // point: eight objects in one room, not eight recordings.
            // **The difference, not the sum - a pad must not hear itself.**
            //
            // Feeding a resonator its own output back into its own input is
            // regeneration: it raises the Q, and with eight high-Q banks in
            // one loop the round trip passes unity and the kit howls. The
            // `fastTanh` on the bus bounds the amplitude of that howl but
            // does nothing to its gain, so it settled at a steady tone
            // instead of growing. A frame *shares*: each object takes its
            // portion of what the room is doing less what it is doing itself,
            // so what one gains another loses. Same fault and same fix as
            // Filament's sympathetic strings.
            //
            // **And the losses are per pad, not on the bus.** Filtering the
            // shared bus looks equivalent and is not: the subtraction then
            // takes an *unfiltered* `p.last` off a *filtered* sum, so the
            // cancellation is exact only at DC and a filtered residue of the
            // pad's own output returns everywhere else - positive feedback,
            // strongest exactly where a lowpass passes best. Kit and Foundry
            // sat at a dead-steady -34 dB at 95 and 176 Hz for that reason.
            // Subtract first, then filter.
            // Subtract what this pad actually put *into* the bus, which is
            // `kRingToBus` of its output and not the whole of it. Taking the
            // whole leaves a net negative whenever an object rings alone -
            // the frame damping it instead of sharing it - and the bell kits
            // died in a second and a half.
            // The knock arrives whole - it is the thing that actually sets a
            // neighbour going - and the ring arrives as a trimmed share of
            // what the others are doing, less this object's own contribution
            // so it never regenerates itself.
            const float ringShare = (ringIn - p.last * kRingToBus) *
                                    (1.0f / static_cast<float>(kPads - 1)) * couple * 0.5f *
                                    p.couplingTrim;
            const float knockShare = (knockIn - strike * kKnockToBus) *
                                     (1.0f / static_cast<float>(kPads - 1)) * couple * 2.2f;
            p.coupleLp += ((ringShare + knockShare) - p.coupleLp) * kBusPole;
            x += p.coupleLp;

            // **Nothing resonates at DC, and this did.** A mode here is all
            // poles and no zeros, so its gain at nought hertz is not nought,
            // and the strike makes it worse - `shape * shape` is always
            // positive, so a hit is a lump of DC with a click on top. Feeding
            // every mode the *difference* of the input puts a zero at DC and
            // at Nyquist, which is what a struck object actually has. One
            // pair of history values per pad, not per mode, because all
            // twenty-four modes see the same excitation.
            const float xin = x - p.x2;
            p.x2 = p.x1;
            p.x1 = x;

            float sum = 0.0f;
            for (int32_t k = 0; k < p.modeCount; ++k) sum += p.modes[k].step(xin);

            // Pitch bend on the strike, the way a tom's head tightens.
            if (p.bendLeft > 0.0001f) {
                p.bendLeft -= p.bendLeft * p.bendCoeff;
                if (p.bendLeft < 0.0005f) p.bendLeft = 0.0f;
            }

            const float drive = padParam(pad, Drive);
            if (drive > 0.0001f) {
                // Normalised so a nominal signal passes at its own size, not
                // by 1/sqrt(k) - which hands *small* signals a gain of
                // sqrt(k), nine decibels at the top of the knob. That is gain
                // inside the coupling loop, and it is what kept Junkyard
                // sitting at a flat -26 dB for ever: a drive that can amplify
                // can sustain. This one can only ever reduce.
                const float k = 1.0f + drive * 8.0f;
                const float norm = 0.35f / dsp::fastTanh(0.35f * k);
                sum = dsp::fastTanh(sum * k) * norm;
            }
            sum *= padParam(pad, Level);
            p.last = sum;
            // What travels between objects is not only what they are
            // sounding - it is the knock itself, through the frame they
            // share. A strike is broadband; a ring is a handful of lines,
            // and a handful of lines cannot excite anything that is not
            // already in tune with it.
            ringOut += sum * kRingToBus;
            knockOut += strike * kKnockToBus;

            if (p.ringing && p.exciteLeft <= 0.0f && std::fabs(sum) < 1e-5f) {
                // It has stopped; let it out of the loop until it is hit again.
                bool quiet = true;
                for (int32_t k = 0; k < p.modeCount && quiet; ++k) {
                    if (std::fabs(p.modes[k].y1) > 1e-5f) quiet = false;
                }
                if (quiet) p.ringing = false;
            }

            const float pan = std::clamp(padParam(pad, Pan), -1.0f, 1.0f);
            const float angle = (pan + 1.0f) * 0.25f * 3.14159265f;
            mixL += sum * std::cos(angle) * 1.4142f;
            mixR += sum * std::sin(angle) * 1.4142f;
        }

        // The room, one sample old, kept in bounds: coupling is a loop and a
        // loop with gain is a howl.
        // The average of what the objects are doing, not the sum: a frame
        // carries one room's worth of movement however many things are
        // bolted to it, and summing made the loop gain scale with the pad
        // count. The tanh stays as a last resort, not as the design.
        // The sum of what the objects are doing; each pad takes its share of
        // the rest of it above and loses what the frame loses. The tanh is a
        // last resort, not the design - it bounds a howl and does not stop
        // one. And a ring contributes far less to the frame than a knock
        // does, which is both true of a real kit and what takes the two-step
        // path - A into B into A - safely under unity.
        ringBus = dsp::fastTanh(ringOut);
        knockBus = knockOut;
        if (!std::isfinite(ringBus)) ringBus = 0.0f;
        if (!std::isfinite(knockBus)) knockBus = 0.0f;

        const float angle = (masterPan + 1.0f) * 0.25f * 3.14159265f;
        L[i] = mixL * volume * std::cos(angle) * 1.4142f * kOutputTrim;
        R[i] = mixR * volume * std::sin(angle) * 1.4142f * kOutputTrim;
    }
    return true;
}

} // namespace acidulous::machine
