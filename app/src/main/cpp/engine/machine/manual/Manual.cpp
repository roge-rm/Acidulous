#include "Manual.h"
#include <cstdio>
#include <cstring>

namespace acidulous::machine {
using namespace dsp;

namespace {
constexpr float kPi = 3.14159265f;
// Footages, in semitones from the key: 16' 5⅓' 8' 4' 2⅔' 2' 1⅗' 1⅓' 1'
constexpr int kFootage[Manual::kBars] = {-12, 7, 0, 12, 19, 24, 28, 31, 36};
constexpr int kPedalFootage[Manual::kPedalBars] = {-12, 0};
const char *kModelNames[] = {"tonewheel", "transistor", "pipe", "reed"};
const char *kVibNames[] = {"V1", "V2", "V3", "C1", "C2", "C3"};

// The stop list, when the model is pipes: how much of each rank every
// footage draws.
//
// A drawbar organ has one timbre per footage, and a pipe organ is not built
// that way at all - its 8' is a Diapason *and* a Gamba *and* a Trumpet, three
// ranks of pipes at the same pitch that you draw separately and that sound
// together. One rank per footage meant the string and the reed had nowhere to
// be except the mutations, so a patch that wanted strings at 8' got silence
// and one that wanted a trumpet got a 1 1/3' squeal. These are stops:
//
//   16'    Bourdon (flute) with a Bombarde (reed) under it
//   5 1/3' Quint, a flute
//   8'     Diapason, Gamba and Trumpet - principal, string and reed together
//   4'     Octave and a Flute 4
//   2 2/3' Nazard, a flute
//   2'     Fifteenth, a principal
//   1 3/5' Tierce, a flute
//   1 1/3' Larigot, a principal
//   1'     the mixture
//
// Each rank still gets its own bus slot, because two ranks on one wheel are
// two pipes and do load the wind separately.
constexpr float kPipeStops[Manual::kBars][5] = {
    // principal, flute, string, reed, mixture
    {0.55f, 1.00f, 0.00f, 0.55f, 0.00f}, // 16'    Bourdon, Bombarde
    {0.00f, 1.00f, 0.00f, 0.00f, 0.00f}, // 5 1/3' Quint
    {1.00f, 1.00f, 1.00f, 1.00f, 0.00f}, // 8'     Diapason, Rohrflote, Gamba, Trumpet
    {1.00f, 1.00f, 0.45f, 0.35f, 0.00f}, // 4'     Octave, Flute, Salicet, Clarion
    {0.00f, 1.00f, 0.00f, 0.00f, 0.00f}, // 2 2/3' Nazard
    {1.00f, 0.55f, 0.30f, 0.00f, 0.00f}, // 2'     Fifteenth, Piccolo
    {0.00f, 1.00f, 0.00f, 0.00f, 0.00f}, // 1 3/5' Tierce
    {0.80f, 0.00f, 0.00f, 0.00f, 0.45f}, // 1 1/3' Larigot
    {0.00f, 0.00f, 0.00f, 0.00f, 1.00f}, // 1'     Mixture
};
} // namespace

Manual::Manual() { initParams(); }

const ParamDef *Manual::paramDefs(int32_t &count) const {
    static ParamDef defs[Count];
    static char names[Count][12];
    static bool built = false;
    if (!built) {
        auto put = [&](int32_t i, const char *name, float mn, float mx, float df, Curve c, int32_t steps,
                       const char *unit) {
            std::snprintf(names[i], sizeof(names[i]), "%s", name);
            defs[i] = ParamDef{names[i], mn, mx, df, c, steps, unit};
        };
        auto lin = [&](int32_t i, const char *n, float mn, float mx, float df, const char *u = "") {
            put(i, n, mn, mx, df, Curve::Linear, 0, u);
        };
        auto exp_ = [&](int32_t i, const char *n, float mn, float mx, float df, const char *u = "") {
            put(i, n, mn, mx, df, Curve::Exponential, 0, u);
        };
        auto step = [&](int32_t i, const char *n, int32_t count_, float df) {
            put(i, n, 0.0f, static_cast<float>(count_ - 1), df, Curve::Stepped, count_, "");
        };

        step(Model, "model", ModelCount, 0.0f);
        lin(Age, "age", 0.0f, 1.0f, 0.25f);
        // Quiet by default, and still there.
        //
        // The generator turns whether or not a key is down, so an idle organ
        // hums - that is true of the instrument and the model is right to have
        // it. What it cannot be is *noticeable*: a track makes this sound the
        // moment it is added, before a note has been played, and sixteen racks
        // of it sum. Dan: "there is an immediate sound playing, no matter
        // which patch I select, before any notes are played."
        //
        // At 0.18 the idle peak was -33.6 dBFS against a played peak of
        // -10.7: audible in a gap, inaudible under anything. Dropping it to
        // -45 was not enough either - a freshly added track should make no
        // sound whatever until it is played, and "very quiet" is still a
        // noise nobody asked for. Off by default; the knobs reach the old
        // values, and a patch that wants the room hum says so.
        lin(Leakage, "leakage", 0.0f, 1.0f, 0.0f);
        lin(Hum, "hum", 0.0f, 1.0f, 0.0f);
        lin(Click, "click", 0.0f, 1.0f, 0.35f);
        lin(ClickRelease, "clickoff", 0.0f, 1.0f, 0.2f);
        lin(ContactSpread, "contacts", 0.0f, 1.0f, 0.4f);

        lin(Split, "split", 0.0f, 127.0f, 60.0f);
        lin(PedalSplit, "pedsplit", 0.0f, 127.0f, 48.0f);
        lin(UpperLevel, "upper", 0.0f, 1.0f, 1.0f);
        lin(LowerLevel, "lower", 0.0f, 1.0f, 0.9f);
        lin(PedalLevel, "pedal", 0.0f, 1.0f, 0.9f);
        step(LowerOn, "loweron", 2, 0.0f);
        // On by default. One rack is a whole instrument, and a note below the
        // pedal split with the pedals switched off does not go silent - it
        // goes to a *manual*, where a 16' drawbar and an 8' drawbar fold onto
        // the same bottom wheel and double up at 43 Hz. The pedal division
        // has its own registration, its own level and its own foldback, which
        // is the reason it exists. The split between the two manuals stays
        // off by default, because that one really is a decision.
        step(PedalOn, "pedalon", 2, 1.0f);
        lin(PedalSustain, "pedsus", 0.0f, 1.0f, 0.15f);

        static const char *barName[Manual::kBars] = {"16", "513", "8", "4", "223", "2", "135", "113", "1"};
        for (int b = 0; b < kBars; ++b) {
            char n[12];
            std::snprintf(n, sizeof(n), "ua_%s", barName[b]);
            lin(UpperA + b, n, 0.0f, 1.0f, (b == 0 || b == 2) ? 1.0f : (b == 3 ? 0.75f : 0.0f));
            std::snprintf(n, sizeof(n), "ub_%s", barName[b]);
            lin(UpperB + b, n, 0.0f, 1.0f, b >= 4 ? 0.8f : 0.25f);
            std::snprintf(n, sizeof(n), "la_%s", barName[b]);
            lin(LowerA + b, n, 0.0f, 1.0f, (b == 2 || b == 3) ? 0.8f : 0.0f);
            std::snprintf(n, sizeof(n), "lb_%s", barName[b]);
            lin(LowerB + b, n, 0.0f, 1.0f, b == 2 ? 1.0f : 0.2f);
        }
        for (int b = 0; b < kPedalBars; ++b) {
            char n[12];
            std::snprintf(n, sizeof(n), "pa_%d", b + 1);
            lin(PedalA + b, n, 0.0f, 1.0f, b == 0 ? 1.0f : 0.5f);
            std::snprintf(n, sizeof(n), "pb_%d", b + 1);
            lin(PedalB + b, n, 0.0f, 1.0f, 0.6f);
        }

        lin(Morph, "morph", 0.0f, 1.0f, 0.0f);
        step(MorphSource, "morphsrc", SourceCount, 0.0f);
        lin(MorphAmount, "morphamt", 0.0f, 1.0f, 1.0f);

        lin(Spray, "spray", 0.0f, 1.0f, 0.06f);
        exp_(SprayRate, "sprayrate", 0.05f, 8.0f, 0.7f, "Hz");
        lin(SprayWidth, "spraywide", 0.0f, 1.0f, 0.5f);
        step(SprayPattern, "spraypat", 3, 0.0f);

        step(PercOn, "perc", 2, 1.0f);
        step(PercHarmonic, "percharm", 2, 1.0f);
        lin(PercLevel, "perclvl", 0.0f, 1.0f, 0.6f);
        step(PercFast, "percfast", 2, 1.0f);
        exp_(PercDecay, "percdec", 0.05f, 4.0f, 0.35f, "s");
        step(PercPoly, "percpoly", 2, 0.0f);
        lin(PercKey, "perckey", 0.0f, 1.0f, 0.3f);
        step(PercSteal, "percsteal", 2, 1.0f);

        step(VibType, "vibtype", 6, 4.0f);
        exp_(VibRate, "vibrate", 2.0f, 12.0f, 6.9f, "Hz");
        lin(VibDepth, "vibdepth", 0.0f, 1.0f, 0.6f);
        step(VibUpper, "vibup", 2, 1.0f);
        step(VibLower, "viblow", 2, 0.0f);
        lin(VibStereo, "vibwide", 0.0f, 1.0f, 0.35f);

        lin(WindSag, "windsag", 0.0f, 1.0f, 0.12f);
        exp_(WindResponse, "windresp", 0.01f, 1.5f, 0.12f, "s");
        lin(WindNoise, "windnoise", 0.0f, 1.0f, 0.0f); // see Leakage: continuous, so off
        exp_(TremRate, "tremrate", 0.5f, 10.0f, 4.2f, "Hz");
        lin(TremDepth, "tremdepth", 0.0f, 1.0f, 0.0f);

        lin(RankPrincipal, "principal", 0.0f, 1.0f, 1.0f);
        lin(RankFlute, "flute", 0.0f, 1.0f, 0.8f);
        lin(RankString, "string", 0.0f, 1.0f, 0.5f);
        lin(RankReed, "reed", 0.0f, 1.0f, 0.35f);
        lin(RankMixture, "mixture", 0.0f, 1.0f, 0.4f);
        lin(Chiff, "chiff", 0.0f, 1.0f, 0.3f);
        lin(Tracker, "tracker", 0.0f, 1.0f, 0.2f);

        step(ComboWave, "combowave", 3, 0.0f);
        lin(Tab16, "tab16", 0.0f, 1.0f, 0.8f);
        lin(Tab8, "tab8", 0.0f, 1.0f, 1.0f);
        lin(Tab4, "tab4", 0.0f, 1.0f, 0.7f);
        lin(Tab2, "tab2", 0.0f, 1.0f, 0.0f);
        lin(TabII, "tab2r", 0.0f, 1.0f, 0.0f);
        lin(TabIV, "tab4r", 0.0f, 1.0f, 0.0f);
        lin(ComboReedy, "reedy", 0.0f, 1.0f, 0.45f);
        lin(ComboAttack, "comboatk", 0.0f, 1.0f, 0.1f);

        lin(ReedPressure, "pressure", 0.0f, 1.0f, 0.7f);
        lin(ReedBuzz, "buzz", 0.0f, 1.0f, 0.35f);
        lin(ReedTremolo, "reedtrem", 0.0f, 1.0f, 0.2f);

        step(RotOn, "rotary", 2, 1.0f);
        step(RotSpeed, "rotspeed", 3, 0.0f); // brake, slow, fast
        exp_(RotHornSlow, "hornslow", 0.1f, 2.0f, 0.8f, "Hz");
        exp_(RotHornFast, "hornfast", 2.0f, 10.0f, 6.6f, "Hz");
        exp_(RotDrumSlow, "drumslow", 0.1f, 2.0f, 0.65f, "Hz");
        exp_(RotDrumFast, "drumfast", 1.0f, 8.0f, 5.3f, "Hz");
        exp_(RotRampUp, "rampup", 0.05f, 4.0f, 0.9f, "s");
        exp_(RotRampDown, "rampdown", 0.05f, 6.0f, 1.6f, "s");
        lin(RotMicDistance, "micdist", 0.0f, 1.0f, 0.35f);
        lin(RotMicAngle, "micangle", 0.0f, 1.0f, 0.8f);
        lin(RotSpread, "rotwide", 0.0f, 1.0f, 0.75f);
        step(RotSync, "rotsync", 6, 0.0f); // free, 1/1, 1/2, 1/4, 1/8, 1/8T

        // Off by default, because `drive` is the *amplifier* and only two of
        // the four models have one. It was 0.2, which is not a little: the
        // normalisation is tanh(x.g)/tanh(0.6g), so 0.2 is +8.5 dB of gain
        // with 0.30 squashed to 0.80 - a fuzz pedal, and every pipe and reed
        // patch was inheriting it. The tonewheel and combo patches all set
        // their own.
        lin(Drive, "drive", 0.0f, 1.0f, 0.0f);
        lin(Bias, "bias", -1.0f, 1.0f, 0.0f);
        lin(Bass, "bass", -12.0f, 12.0f, 0.0f, "dB");
        lin(Mid, "mid", -12.0f, 12.0f, 0.0f, "dB");
        lin(Treble, "treble", -12.0f, 12.0f, 0.0f, "dB");
        // Up to 2.0, because a stop list means what it says: one 8' flute is
        // *quiet*, full organ is loud, and the honest difference between them
        // is far more than a patch volume topping out at 1.0 can cover. Soft
        // Flute could not reach the bank's level at full travel; the only
        // thing making it seem to before was a drive the model has no
        // amplifier for. The default sits at 1.35 rather than mid travel so
        // that Init - which sets nothing, by rule - arrives at the same level
        // as the rest of the bank with no amplifier under it.
        lin(Volume, "volume", 0.0f, 2.0f, 0.75f);
        lin(Pan, "pan", -1.0f, 1.0f, 0.0f);

        exp_(AmpAttack, "attack", 0.0005f, 0.5f, 0.004f, "s");
        exp_(AmpRelease, "release", 0.002f, 2.0f, 0.05f, "s");
        exp_(Eg1A, "eg1atk", 0.001f, 8.0f, 0.01f, "s");
        exp_(Eg1D, "eg1dec", 0.005f, 12.0f, 0.6f, "s");
        lin(Eg1S, "eg1sus", 0.0f, 1.0f, 0.7f);
        exp_(Eg1R, "eg1rel", 0.005f, 12.0f, 0.4f, "s");
        exp_(Eg2A, "eg2atk", 0.001f, 8.0f, 1.2f, "s");
        exp_(Eg2D, "eg2dec", 0.005f, 12.0f, 2.0f, "s");
        lin(Eg2S, "eg2sus", 0.0f, 1.0f, 1.0f);
        exp_(Eg2R, "eg2rel", 0.005f, 12.0f, 1.0f, "s");

        step(Lfo1Wave, "lfo1wave", LfoGen::WaveCount, 0.0f);
        exp_(Lfo1Rate, "lfo1rate", 0.01f, 20.0f, 0.4f, "Hz");
        step(Lfo1Sync, "lfo1sync", 6, 0.0f);
        lin(Lfo1Depth, "lfo1depth", 0.0f, 1.0f, 1.0f);
        lin(Lfo1Phase, "lfo1phase", 0.0f, 1.0f, 0.0f);
        step(Lfo2Wave, "lfo2wave", LfoGen::WaveCount, 1.0f);
        exp_(Lfo2Rate, "lfo2rate", 0.01f, 20.0f, 3.0f, "Hz");
        step(Lfo2Sync, "lfo2sync", 6, 0.0f);
        lin(Lfo2Depth, "lfo2depth", 0.0f, 1.0f, 1.0f);
        lin(Lfo2Phase, "lfo2phase", 0.0f, 1.0f, 0.25f);

        for (int m = 0; m < kMatrixSlots; ++m) {
            char n[12];
            const int base = MatrixBase + m * kMatrixParams;
            std::snprintf(n, sizeof(n), "m%d_src", m + 1);
            step(base + XSrc, n, SourceCount, 0.0f);
            std::snprintf(n, sizeof(n), "m%d_dst", m + 1);
            step(base + XDest, n, DestCount, 0.0f);
            std::snprintf(n, sizeof(n), "m%d_amt", m + 1);
            lin(base + XDepth, n, -1.0f, 1.0f, 0.0f);
        }

        lin(BendRange, "bend", 0.0f, 12.0f, 2.0f);
        lin(Octave, "octave", -3.0f, 3.0f, 0.0f);
        lin(Transpose, "transpose", -12.0f, 12.0f, 0.0f);
        lin(Fine, "fine", -50.0f, 50.0f, 0.0f, "c");
        lin(VelocityAmount, "vel", 0.0f, 1.0f, 0.0f);
        step(Expression, "express", 3, 1.0f); // off, mod wheel, pressure
        built = true;
    }
    count = Count;
    return defs;
}

int32_t Manual::steppedOf(int32_t p) const { return static_cast<int32_t>(paramOf(p) + 0.5f); }

void Manual::prepare(int32_t sr) {
    sampleRate = static_cast<float>(sr);
    bank = &WheelBank::shared(sampleRate);
    rotary.prepare(sampleRate);
    scanner.prepare(static_cast<int32_t>(sampleRate * 0.01f));
    for (auto &v : voices) v.amp.setSampleRate(sampleRate);
    for (auto &e : eg) e.setSampleRate(sampleRate);
    for (auto &l : lfo) l.reset(0.0f);
    bassEq.lowShelf(180.0f, 0.0f, sampleRate);
    midEq.peak(900.0f, 0.0f, 0.8f, sampleRate);
    trebleEq.highShelf(2600.0f, 0.0f, sampleRate);
    reedyFilter.peak(1500.0f, 6.0f, 1.4f, sampleRate);
    chiffFilter.lowpass(5200.0f, 0.7f, sampleRate);
    modelAge = -1.0f;
    rebuildTuning();
    reset();
}

// Every wheel gets its own small tuning and level error, fixed for the life
// of the instrument. That is what a generator is: gears cut to a ratio that
// is close to equal temperament and not exactly it, wearing unevenly.
void Manual::rebuildTuning() {
    const float age = paramOf(Age);
    const float spray = paramOf(Spray);
    const int32_t pattern = steppedOf(SprayPattern);
    if (std::fabs(age - modelAge) < 0.0005f && std::fabs(spray - modelSpray) < 0.0005f) return;
    modelAge = age;
    modelSpray = spray;
    uint32_t s = 0x9e3779b9u;
    for (int w = 0; w < WheelBank::kWheels; ++w) {
        s = s * 1664525u + 1013904223u;
        const float r1 = (static_cast<float>((s >> 9) & 0xffff) / 32768.0f) - 1.0f;
        s = s * 1664525u + 1013904223u;
        const float r2 = (static_cast<float>((s >> 9) & 0xffff) / 32768.0f) - 1.0f;
        // Spray is a detune across the generator rather than across one key,
        // so an octave played on two drawbars beats with itself and a chord
        // spreads. Which way it runs is the pattern.
        const float t = static_cast<float>(w) / static_cast<float>(WheelBank::kWheels - 1);
        // How much this wheel's second rank is detuned, as a fraction of the
        // spray amount. It used to be a ramp *through zero*, which left the
        // middle of the keyboard barely detuned and only the ends beating -
        // a stretch tuning rather than a celeste. A celeste is a roughly even
        // number of cents all the way up, because every note has to beat at a
        // musical rate and not only the ones at the extremes.
        const float shape = pattern == 0 ? 1.0f
                          : pattern == 1 ? 0.45f + 0.55f * t
                                         : 0.7f + 0.3f * std::sin(t * 12.566f);
        const float cents = r1 * age * 7.0f;
        wheelStep[w] = bank->freq(w) * std::pow(2.0f, cents / 1200.0f) / sampleRate;
        wheelTrim[w] = 1.0f + r2 * age * 0.12f;
        sprayDetune[w] = shape;
        // Where in the field the second rank sits: low at one side, high at
        // the other, which is a pair of ranks in a room rather than a pair of
        // ranks in the same place.
        sprayPan[w] = t * 2.0f - 1.0f;
    }
}

void Manual::reset() {
    // Whole voices. Clearing the gate and killing the amp left the
    // percussion, the key click and the chiff still decaying, and every
    // contact still part way through its make time.
    // A fresh Voice, then the one thing about it that prepare() owns rather
    // than a patch: the envelope's sample rate. Without this line an organ
    // at 44.1 kHz would run its envelopes as though it were at 48.
    for (auto &v : voices) { v = Voice(); v.amp.setSampleRate(sampleRate); }

    // The generator. Ninety-one wheels free-run by design - that is what
    // gives an organ its beating - so a reset has to put every one of them
    // back or a render starts wherever the last note left it.
    for (auto &p : wheelPhase) p = 0.0f;
    for (auto &p : wheelOut) p = 0.0f;
    for (auto &p : sprayPhase) p = 0.0f;
    for (auto &slot : wheelPeak) for (auto &p : slot) p = 0.0f;
    // The stamp cache says which wheels a slot touched this frame. Left
    // alone, stale stamps from before the reset match the new frame count
    // and wheels are skipped that should have sounded.
    for (auto &slot : wheelStamp) for (auto &p : slot) p = 0;
    for (auto &slot : wheelGain) for (auto &p : slot) p = 0.0f;
    for (auto &slot : usedWheel) for (auto &p : slot) p = 0;
    for (auto &n : usedCount) n = 0;
    frameStamp = 0;

    // Derived from the model parameters, and rebuilt when these say so.
    // Zeroed as well as invalidated: a half-built generator that is never
    // asked to rebuild is worse than an empty one.
    for (auto &p : wheelStep) p = 0.0f;
    for (auto &p : wheelTrim) p = 0.0f;
    for (auto &p : sprayPan) p = 0.0f;
    for (auto &p : sprayStep) p = 0.0f;
    modelAge = -1.0f;
    modelSpray = -1.0f;
    sprayDrift = 0.0f;

    for (auto &l : lfo) l.reset(0.0f);
    for (auto &v : lfoValue) v = 0.0f;
    for (auto &e : eg) e.reset();
    scanPhase = scanValue = 0.0f;
    tremPhase = 0.0f;
    humPhase = 0.0f;

    rotary.reset();
    scanner.clear();

    // The tone stack. Its coefficients come from the patch every block, but
    // its *history* is four samples of whatever was playing before, and a
    // render that starts with those is not the render that starts without.
    bassEq.reset();
    midEq.reset();
    trebleEq.reset();
    reedyFilter.reset();
    chiffFilter.reset();

    windPressure = 1.0f;
    heldCount = 0;
    bendSemis = 0.0f;
    modWheel = pressure = 0.0f;
    expression = 1.0f;
    for (auto &m : blockMod) m = 0.0f;
    leakSum = 0.0f;
    rngState = kRngSeed;
}

int32_t Manual::wheelFor(int32_t note, int32_t bar) const {
    const int32_t foot = kFootage[bar];
    int32_t w = note + foot - WheelBank::kLowestNote;
    // Foldback, as the generator does when it runs out of wheels at either end.
    while (w >= WheelBank::kWheels) w -= 12;
    while (w < 0) w += 12;
    return w;
}

void Manual::noteOn(uint8_t note, uint8_t velocity) {
    const int32_t split = static_cast<int32_t>(paramOf(Split) + 0.5f);
    const int32_t pedSplit = static_cast<int32_t>(paramOf(PedalSplit) + 0.5f);
    const bool lowerOn = steppedOf(LowerOn) != 0;
    const bool pedalOn = steppedOf(PedalOn) != 0;
    int32_t manual = MUpper;
    if (pedalOn && note < pedSplit) manual = MPedal;
    else if (lowerOn && note < split) manual = MLower;

    Voice *v = nullptr;
    for (auto &cand : voices) if (!cand.used) { v = &cand; break; }
    if (v == nullptr) {
        v = &voices[0];
        for (auto &cand : voices) if (!cand.gate) { v = &cand; break; }
    }
    const bool wasSilent = heldCount == 0;
    v->used = true;
    v->gate = true;
    v->note = note;
    v->manual = static_cast<uint8_t>(manual);
    v->velocity = static_cast<float>(velocity) / 127.0f;
    v->key01 = clampf((static_cast<float>(note) - 24.0f) / 72.0f, 0.0f, 1.0f);
    rngState = rngState * 1664525u + 1013904223u;
    v->rnd = static_cast<float>((rngState >> 9) & 0xffff) / 65536.0f;
    // A combo organ's percussive/soft switch is an attack, and this was the
    // knob for it: `comboatk` had been read into a local that nothing used,
    // so every tab setting spoke in the same four milliseconds. A quarter of
    // a second at the top of it is the slow-attack tab, which is the one
    // thing on those organs that is not percussive.
    float attackSec = paramOf(AmpAttack);
    if (steppedOf(Model) == Transistor) attackSec += paramOf(ComboAttack) * 0.25f;
    v->amp.set(0.0f, attackSec, 0.0f, 1.0f, paramOf(AmpRelease), false);
    v->amp.retrigger();

    const int bars = manual == MPedal ? kPedalBars : kBars;
    for (int b = 0; b < kBars; ++b) {
        v->wheel[b] = manual == MPedal && b < kPedalBars
                          ? (note + kPedalFootage[b] - WheelBank::kLowestNote + 120) % WheelBank::kWheels
                          : wheelFor(note, b);
        // The nine contacts under a key do not close together. The spread is
        // small, a couple of milliseconds, and it is the whole of key click.
        v->contactPhase[b] = b < bars ? (0.2f + 2.4f * paramOf(ContactSpread)) * 0.001f * sampleRate *
                                            (0.15f + 0.85f * std::fmod(v->rnd * (b + 3) * 7.13f, 1.0f))
                                      : 0.0f;
    }
    // Click and chiff are fixed at the moment the key goes down, so they
    // take the matrix's block value rather than a per-voice one: the voice
    // does not exist yet. Both were listed as destinations and neither was
    // read, so a routing to either did nothing at all.
    v->click = clampf(paramOf(Click) + blockMod[DstClick], 0.0f, 1.5f);
    v->clickCoeff = std::exp(-1.0f / (0.0018f * sampleRate));
    v->chiff = steppedOf(Model) == Pipe ? clampf(paramOf(Chiff) + blockMod[DstChiff], 0.0f, 1.5f) : 0.0f;
    v->chiffCoeff = std::exp(-1.0f / (0.035f * sampleRate));

    // Harmonic percussion fires on the first key of a phrase, not on every
    // key, unless it is asked to be polyphonic.
    if (steppedOf(PercOn) != 0 && manual == MUpper && (steppedOf(PercPoly) != 0 || wasSilent)) {
        const float keyScale = 1.0f - paramOf(PercKey) * v->key01;
        const float decay = paramOf(PercDecay) * (steppedOf(PercFast) != 0 ? 0.35f : 1.0f) * keyScale;
        v->perc = 1.0f;
        v->percCoeff = std::exp(-1.0f / (std::fmax(0.01f, decay) * sampleRate));
    } else {
        v->perc = 0.0f;
    }
    ++heldCount;
}

void Manual::noteOff(uint8_t note) {
    for (auto &v : voices) {
        if (v.used && v.gate && v.note == note) {
            v.gate = false;
            const float sus = v.manual == MPedal ? paramOf(PedalSustain) : 0.0f;
            v.amp.set(0.0f, paramOf(AmpAttack), 0.0f, 1.0f, paramOf(AmpRelease) + sus * 1.5f, false);
            v.amp.release();
            v.click = paramOf(Click) * paramOf(ClickRelease);
            if (heldCount > 0) --heldCount;
        }
    }
}

void Manual::allNotesOff() {
    for (auto &v : voices) { v.gate = false; v.amp.release(); }
    heldCount = 0;
}

void Manual::controlChange(uint8_t cc, uint8_t value) {
    if (cc == 1) modWheel = static_cast<float>(value) / 127.0f;
    if (cc == 11) expression = static_cast<float>(value) / 127.0f;
}
void Manual::channelPressure(uint8_t value) { pressure = static_cast<float>(value) / 127.0f; }
void Manual::pitchBend(int16_t value14) {
    bendSemis = (static_cast<float>(value14) / 8192.0f) * paramOf(BendRange);
}


void Manual::onBlock(int64_t, int64_t, float tempo) { bpm = tempo > 1.0f ? tempo : 120.0f; }

float Manual::sourceValue(int32_t src, const Voice &v) const {
    switch (src) {
    case SrcOn: return 1.0f;
    case SrcModWheel: return modWheel;
    case SrcPressure: return pressure;
    case SrcVelocity: return v.velocity;
    case SrcKeyTrack: return v.key01;
    case SrcRandom: return v.rnd;
    case SrcEg1: return eg[0].value();
    case SrcEg2: return eg[1].value();
    case SrcLfo1: return lfoValue[0];
    case SrcLfo2: return lfoValue[1];
    // The cabinet drives the patch: horn and drum phase as bipolar sources.
    case SrcHorn: return std::sin(6.2831853f * rotary.horn01());
    case SrcDrum: return std::sin(6.2831853f * rotary.drum01());
    case SrcScanner: return scanValue;
    case SrcWind: return windPressure - 1.0f;
    default: return 0.0f;
    }
}

void Manual::applyMatrix(const Voice &v, float *dest) const {
    for (int32_t d = 0; d < DestCount; ++d) dest[d] = 0.0f;
    for (int m = 0; m < kMatrixSlots; ++m) {
        const int base = MatrixBase + m * kMatrixParams;
        const int32_t src = static_cast<int32_t>(paramOf(base + XSrc) + 0.5f);
        const int32_t dst = static_cast<int32_t>(paramOf(base + XDest) + 0.5f);
        if (src == SrcOff || dst == DstOff) continue;
        dest[dst] += sourceValue(src, v) * paramOf(base + XDepth);
    }
}

int32_t Manual::timbreForRank(int32_t rank) const {
    static const int32_t rankTimbre[5] = {WheelBank::Principal, WheelBank::Flute, WheelBank::String,
                                          WheelBank::Reed, WheelBank::Principal};
    return rankTimbre[rank < 0 ? 0 : (rank > 4 ? 4 : rank)];
}

int32_t Manual::timbreFor(int32_t bar) const {
    (void)bar;
    switch (steppedOf(Model)) {
    case Transistor: {
        const int32_t w = steppedOf(ComboWave);
        return w == 0 ? WheelBank::Square : (w == 1 ? WheelBank::Pulse : WheelBank::Saw);
    }
    // Pipes have no single answer: a footage is however many ranks are drawn
    // at it, and the render loop walks them slot by slot.
    case Pipe: return WheelBank::Principal;
    case ReedOrgan: return WheelBank::Reed;
    default: return WheelBank::Wheel;
    }
}

// One drawbar's level, after the morph between the two registrations and
// whatever the matrix is doing to it.
float Manual::drawbarLevel(int32_t manual, int32_t bar, const float *mod) const {
    const int32_t model = steppedOf(Model);
    float a = 0.0f, b = 0.0f;
    if (manual == MPedal) {
        if (bar >= kPedalBars) return 0.0f;
        a = paramOf(PedalA + bar);
        b = paramOf(PedalB + bar);
    } else if (manual == MLower) {
        a = paramOf(LowerA + bar);
        b = paramOf(LowerB + bar);
    } else {
        a = paramOf(UpperA + bar);
        b = paramOf(UpperB + bar);
    }
    if (model == Transistor && manual != MPedal) {
        // Tabs, not drawbars: a combo organ has footage switches and two
        // mixture tabs above them.
        //
        // **The II tab used to sound the tierce**, the 1 3/5' - a major
        // third, two octaves up. One note at a time that is a colour. In a
        // chord it is a wrong note: every key grows a major third above
        // itself, so a major triad sprouts an augmented triad on top of it
        // and three notes arrive as six. A mixture is built of octaves and
        // fifths for exactly this reason, and these tabs are now: II is the
        // 2 2/3' and the 1 1/3' quints, IV adds the 2', the 1 1/3' and the
        // 1' on top of whatever else is drawn, which is what four ranks
        // means. The tierce stays a drawbar on the tonewheel model, where a
        // player chooses it a note at a time.
        static const int tabFor[kBars] = {Tab16, -1, Tab8, Tab4, TabII, Tab2, -1, TabII, -1};
        const int p = tabFor[bar];
        a = p < 0 ? 0.0f : paramOf(p) * (bar == 7 ? 0.6f : 1.0f);
        if (bar == 5) a += paramOf(TabIV) * 0.45f;
        else if (bar == 7) a += paramOf(TabIV) * 0.55f;
        else if (bar == 8) a += paramOf(TabIV);
        b = a;
    }
    // Pipes deliberately do not scale here: a footage's level is the drawbar,
    // and which ranks it draws is settled in render(), one slot each.
    float morph = clampf(paramOf(Morph) + mod[DstMorph] * paramOf(MorphAmount), 0.0f, 1.0f);
    const int32_t msrc = static_cast<int32_t>(paramOf(MorphSource) + 0.5f);
    if (msrc != SrcOff) morph = clampf(morph, 0.0f, 1.0f);
    float level = a + (b - a) * morph;
    level += mod[DstBar1 + bar];
    level += manual == MUpper ? mod[DstUpperAll] : mod[DstLowerAll];
    return clampf(level, 0.0f, 1.5f);
}

bool Manual::render(float *L, float *R, int32_t frames) {
    params_.tick();
    rebuildTuning();
    const float blockSeconds = static_cast<float>(frames) / sampleRate;
    const int32_t model = steppedOf(Model);

    // Block-rate modulation: LFOs, envelopes, the rotor's orders.
    static const float kSyncBeats[6] = {0.0f, 4.0f, 2.0f, 1.0f, 0.5f, 1.0f / 3.0f};
    for (int i = 0; i < 2; ++i) {
        const int32_t sync = steppedOf(i == 0 ? Lfo1Sync : Lfo2Sync);
        const float free = paramOf(i == 0 ? Lfo1Rate : Lfo2Rate);
        const float hz = sync == 0 ? free : (bpm / 60.0f) / kSyncBeats[sync];
        lfoValue[i] = lfo[i].advance(steppedOf(i == 0 ? Lfo1Wave : Lfo2Wave), hz, blockSeconds, 0.0f, false) *
                      paramOf(i == 0 ? Lfo1Depth : Lfo2Depth);
    }
    eg[0].set(0.0f, paramOf(Eg1A), paramOf(Eg1D), paramOf(Eg1S), paramOf(Eg1R), false);
    eg[1].set(0.0f, paramOf(Eg2A), paramOf(Eg2D), paramOf(Eg2S), paramOf(Eg2R), false);

    Voice global;
    global.velocity = 1.0f;
    applyMatrix(global, blockMod);
    // Matrix and drawbar levels are resolved once a block. Their sources are
    // block-rate anyway, and doing it per sample costs more than the organ.
    for (auto &v : voices) {
        if (!v.used) continue;
        applyMatrix(v, v.mod);
        const int bars = v.manual == MPedal ? kPedalBars : kBars;
        for (int b = 0; b < kBars; ++b) v.barLevel[b] = b < bars ? drawbarLevel(v.manual, b, v.mod) : 0.0f;
    }

    const int32_t rotSync = steppedOf(RotSync);
    const int32_t speed = steppedOf(RotSpeed);
    float hornHz = speed == 0 ? 0.0f : (speed == 1 ? paramOf(RotHornSlow) : paramOf(RotHornFast));
    float drumHz = speed == 0 ? 0.0f : (speed == 1 ? paramOf(RotDrumSlow) : paramOf(RotDrumFast));
    if (rotSync != 0) {
        hornHz = (bpm / 60.0f) / kSyncBeats[rotSync];
        drumHz = hornHz * 0.78f;
    }
    hornHz *= 1.0f + blockMod[DstRotorRate];
    drumHz *= 1.0f + blockMod[DstRotorRate];
    rotary.setTargets(hornHz, drumHz, paramOf(RotRampUp), paramOf(RotRampDown));
    rotary.setMic(paramOf(RotMicDistance), paramOf(RotMicAngle), paramOf(RotSpread));

    bassEq.lowShelf(180.0f, paramOf(Bass), sampleRate);
    midEq.peak(900.0f, paramOf(Mid), 0.8f, sampleRate);
    trebleEq.highShelf(2600.0f, clampf(paramOf(Treble) + blockMod[DstTreble] * 12.0f, -18.0f, 18.0f), sampleRate);

    const float spray = clampf(paramOf(Spray) + blockMod[DstSpray], 0.0f, 1.0f);
    // Spray is a detune measured in cents, not in hertz, or the bottom of
    // the generator would be a semitone out while the top was still in tune.
    // The slow term keeps it from being a static mistuning: it breathes.
    if (spray > 0.0005f) {
        sprayDrift += paramOf(SprayRate) * 0.08f * static_cast<float>(frames) / sampleRate;
        if (sprayDrift >= 1.0f) sprayDrift -= 1.0f;
        for (int w = 0; w < WheelBank::kWheels; ++w) {
            const float move = 1.0f + 0.35f * std::sin(6.2831853f * (sprayDrift + 0.11f * static_cast<float>(w)));
            const float cents = spray * 26.0f * sprayDetune[w] * move;
            sprayStep[w] = wheelStep[w] * (std::pow(2.0f, cents / 1200.0f) - 1.0f);
        }
    }
    const float sprayWidth = paramOf(SprayWidth);
    const float leak = paramOf(Leakage);
    const float hum = paramOf(Hum);
    const float windSag = clampf(paramOf(WindSag) + blockMod[DstWindSag], 0.0f, 1.0f);
    const float windCoeff = onePoleCoeff(paramOf(WindResponse), sampleRate);
    const float windNoise = paramOf(WindNoise);
    // Two tremulants, one output. They used to be *added*, so a reed patch
    // asking for a strong one on each knob got 0.95 and swung its own level
    // down to a twentieth twice a second - and anything over 1.0 sent the
    // multiplier negative, which inverts the signal rather than quietening
    // it. Combined the way two independent depths combine, and bounded.
    const float tremA = paramOf(TremDepth);
    const float tremB = model == ReedOrgan ? paramOf(ReedTremolo) : 0.0f;
    const float tremDepth = 1.0f - (1.0f - clampf(tremA, 0.0f, 1.0f)) * (1.0f - clampf(tremB, 0.0f, 1.0f));
    const float tremStep = paramOf(TremRate) / sampleRate;
    const float percLevel = clampf(paramOf(PercLevel) + blockMod[DstPercLevel], 0.0f, 1.5f);
    const int32_t percBar = steppedOf(PercHarmonic) != 0 ? 3 : 4; // 2nd is the 4', 3rd the 2⅔'
    const bool percSteal = steppedOf(PercSteal) != 0;
    const float drive = clampf(paramOf(Drive) + blockMod[DstDrive], 0.0f, 1.0f);
    const float bias = paramOf(Bias);
    const float volume = clampf(paramOf(Volume) + blockMod[DstVolume], 0.0f, 2.5f);
    const float pan = clampf(paramOf(Pan) + blockMod[DstPan], -1.0f, 1.0f);
    const int32_t expr = steppedOf(Expression);
    const float exprGain = expr == 0 ? 1.0f : (expr == 1 ? 0.25f + 0.75f * modWheel : 0.25f + 0.75f * pressure);
    const float upperGain = paramOf(UpperLevel), lowerGain = paramOf(LowerLevel), pedalGain = paramOf(PedalLevel);
    const float velAmt = paramOf(VelocityAmount);
    const float pitchScale = std::pow(2.0f, (bendSemis + paramOf(Octave) * 12.0f + paramOf(Transpose) +
                                             paramOf(Fine) * 0.01f + blockMod[DstPitch] * 12.0f) /
                                                12.0f);
    const float reedPress = model == ReedOrgan ? 0.35f + 0.65f * paramOf(ReedPressure) : 1.0f;
    const float buzz = model == ReedOrgan ? paramOf(ReedBuzz) : 0.0f;
    const float chiffAmt = model == Pipe ? paramOf(Chiff) : 0.0f;
    const float trackerAmt = model == Pipe ? paramOf(Tracker) : 0.0f;
    // Pipes and reeds are one sound source each, so they really do add; a
    // generator is shared, so it does not.
    const bool busLoaded = model == Tonewheel || model == Transistor;
    const bool rotOn = steppedOf(RotOn) != 0;
    const bool eqActive = std::fabs(paramOf(Bass)) + std::fabs(paramOf(Mid)) + std::fabs(paramOf(Treble)) > 0.05f;
    int slotTimbre[kSlots];
    for (int sl = 0; sl < kSlots; ++sl) slotTimbre[sl] = model == Pipe ? timbreForRank(sl) : timbreFor(0);
    // What each footage draws from each rank, once a block: the stop list
    // times the five rank knobs.
    float stopGain[kBars][kSlots] = {};
    if (model == Pipe) {
        static const int rankParam[kSlots] = {RankPrincipal, RankFlute, RankString, RankReed, RankMixture};
        for (int r = 0; r < kSlots; ++r) {
            const float knob = paramOf(rankParam[r]);
            for (int b = 0; b < kBars; ++b) stopGain[b][r] = kPipeStops[b][r] * knob;
        }
    }

    // The scanner: a delay swept by a triangle, mixed dry or not at all
    // depending on which of the six positions the switch is in.
    const int32_t vibType = steppedOf(VibType);
    const float vibDepthP = clampf(paramOf(VibDepth) + blockMod[DstVibDepth], 0.0f, 1.0f);
    const bool chorusMode = vibType >= 3;
    const float vibStage = static_cast<float>((vibType % 3) + 1) / 3.0f;
    const float vibSamples = 0.0009f * sampleRate * vibStage * vibDepthP;
    const float vibStep = paramOf(VibRate) / sampleRate;
    const bool vibUp = steppedOf(VibUpper) != 0, vibLow = steppedOf(VibLower) != 0;

    // Demand on the wind supply, for the sag: every sounding drawbar pulls.
    float demand = 0.0f;
    for (auto &v : voices) if (v.used) demand += v.amp.value() * (v.manual == MPedal ? 1.4f : 1.0f);

    bool anyVoice = false;
    for (auto &v : voices) if (v.used) { anyVoice = true; break; }
    if (!anyVoice && leak < 0.0005f && hum < 0.0005f && !rotOn) {
        // The generator keeps turning - a wheel that stopped would come back
        // in the wrong place - but nothing else needs doing.
        for (int32_t i = 0; i < frames; ++i) {
            for (int w = 0; w < WheelBank::kWheels; ++w) {
                wheelPhase[w] += wheelStep[w] * pitchScale;
                if (wheelPhase[w] >= 1.0f) wheelPhase[w] -= 1.0f;
            }
            L[i] = 0.0f;
            R[i] = 0.0f;
        }
        return true;
    }

    for (int32_t i = 0; i < frames; ++i) {
        for (int w = 0; w < WheelBank::kWheels; ++w) {
            wheelPhase[w] += wheelStep[w] * pitchScale * (0.997f + 0.003f * windPressure);
            if (wheelPhase[w] >= 1.0f) wheelPhase[w] -= 1.0f;
        }
        if (spray > 0.0005f) {
            for (int w = 0; w < WheelBank::kWheels; ++w) {
                sprayPhase[w] += sprayStep[w];
                if (sprayPhase[w] >= 1.0f) sprayPhase[w] -= 1.0f;
                else if (sprayPhase[w] < 0.0f) sprayPhase[w] += 1.0f;
            }
        }

        // Wind: one supply, everybody drawing on it.
        // How far the supply is pulled down by what is drawing on it.
        //
        // The 0.55 that used to be here made this a power cut rather than a
        // sag: a five-note chord on the default registration lost 2.5 dB,
        // Full Organ lost 9.5, and the patch built to show the effect off
        // lost 11.5 and slid 158 cents downhill - a minor second and a half -
        // every time a chord landed. A large organ under a full chord loses a
        // few decibels and a dozen cents. That is what this is worth.
        const float target = 1.0f / (1.0f + windSag * demand * 0.16f);
        windPressure += (target - windPressure) * windCoeff;
        float trem = 1.0f;
        if (tremDepth > 0.0005f) {
            tremPhase += tremStep;
            if (tremPhase >= 1.0f) tremPhase -= 1.0f;
            // Full depth is a deep tremulant - about ten decibels - and not
            // a gate. A tremolo that reaches silence is a gate.
            trem = 1.0f - tremDepth * 0.35f * (1.0f - std::cos(6.2831853f * tremPhase));
        }

        // `click` is the electric contact, scaled by the click knob; `mech`
        // is the key itself moving, which is what a tracker action makes a
        // noise with and has nothing to do with electricity. They were one
        // accumulator, so a pipe patch's tracker noise was silently set by a
        // knob labelled key click.
        float dry = 0.0f, click = 0.0f, mech = 0.0f;
        ++frameStamp;
        for (int sl = 0; sl < kSlots; ++sl) usedCount[sl] = 0;
        for (auto &v : voices) {
            if (!v.used) continue;
            const float env = v.amp.next();
            if (!v.gate && env < 0.0002f) { v.used = false; continue; }
            const int bars = v.manual == MPedal ? kPedalBars : kBars;
            const float manualGain = v.manual == MUpper ? upperGain : (v.manual == MLower ? lowerGain : pedalGain);
            const float velGain = 1.0f - velAmt + velAmt * v.velocity;
            const float voiceGain = env * manualGain * velGain * (1.0f + v.mod[DstVolume] * 0.5f);
            for (int b = 0; b < bars; ++b) {
                float level = v.barLevel[b];
                if (percSteal && v.manual == MUpper && b == kBars - 1 && v.perc > 0.0f) level = 0.0f;
                if (level <= 0.0005f) continue;
                if (v.contactPhase[b] > 0.0f) {
                    v.contactPhase[b] -= 1.0f;
                    if (v.contactPhase[b] <= 0.0f) {
                        click += v.click * level;   // the contact makes
                        mech += level;              // and the key moves
                    }
                    continue;
                }
                const int w = v.wheel[b];
                const float g = level * voiceGain;
                // One footage, one entry - except in pipes, where a footage
                // is however many ranks are drawn at it and each of those is
                // a separate rank of pipes on the same note.
                auto draw = [&](int sl, float gain) {
                    if (gain <= 0.0005f) return;
                    if (wheelStamp[sl][w] != frameStamp) {
                        wheelStamp[sl][w] = frameStamp;
                        wheelGain[sl][w] = 0.0f;
                        wheelPeak[sl][w] = 0.0f;
                        usedWheel[sl][usedCount[sl]++] = static_cast<int16_t>(w);
                    }
                    wheelGain[sl][w] += gain;
                    if (gain > wheelPeak[sl][w]) wheelPeak[sl][w] = gain;
                };
                if (model == Pipe) {
                    for (int r = 0; r < kSlots; ++r) draw(r, g * stopGain[b][r]);
                } else {
                    draw(0, g);
                }
            }
            if (v.perc > 0.0f) {
                const int w = v.wheel[percBar];
                dry += bank->sample(w, WheelBank::Sine, wheelPhase[w]) * v.perc * percLevel * 0.5f * voiceGain;
                v.perc *= v.percCoeff;
                if (v.perc < 0.0005f) v.perc = 0.0f;
            }
            if (v.chiff > 0.0f) {
                rngState = rngState * 1664525u + 1013904223u;
                const float n = (static_cast<float>((rngState >> 9) & 0xffff) / 32768.0f) - 1.0f;
                // Not scaled by the envelope, because the chiff *is* the
                // first few milliseconds and the envelope is still at nothing
                // there - but scaled by everything else about how hard the
                // note was played, or a chord whispered on the choir organ
                // arrives with five full-sized puffs of wind in front of it.
                dry += chiffFilter.process(n) * v.chiff * chiffAmt * 0.5f * manualGain * velGain;
                v.chiff *= v.chiffCoeff;
            }
        }
        // One pass over the wheels that anybody asked for. A wheel feeding
        // two keys is barely louder than one, because the keys are loading a
        // single source, not adding two: that is why an organ chord does not
        // swell where the notes share a harmonic.
        float wideL = 0.0f, wideR = 0.0f;
        for (int sl = 0; sl < kSlots; ++sl) {
            if (usedCount[sl] == 0) continue;
            const int timbre = slotTimbre[sl];
            for (int k = 0; k < usedCount[sl]; ++k) {
                const int w = usedWheel[sl][k];
                const float sum = wheelGain[sl][w], peak = wheelPeak[sl][w];
                const float g = busLoaded ? peak + (sum - peak) * 0.25f : sum;
                // **Spray is a second rank, not a bent one.**
                //
                // A generator has one wheel per pitch, so detuning that wheel
                // moves the whole note: it puts a chord out of tune with
                // itself and nothing ever beats, because there is nothing for
                // it to beat against. That is not what a celeste or a musette
                // is - those are two ranks at the same pitch a few cents
                // apart, and the beat *is* the sound. So the detuned phase
                // reads a second copy of the same wheel and the two are
                // summed; the difference between them is the beating, and
                // that is what gets thrown across the stereo field.
                const float x0 = bank->sample(w, timbre, wheelPhase[w]);
                float x = x0;
                float beat = 0.0f;
                if (spray > 0.0005f) {
                    float ph = wheelPhase[w] + sprayPhase[w];
                    if (ph >= 1.0f) ph -= 1.0f;
                    else if (ph < 0.0f) ph += 1.0f;
                    const float x1 = bank->sample(w, timbre, ph);
                    x = (x0 + x1) * 0.5f;
                    beat = (x1 - x0) * 0.5f;
                }
                const float gain = g * wheelTrim[w] * 0.32f;
                x *= gain;
                dry += x;
                if (sprayWidth > 0.0f && beat != 0.0f) {
                    wideL += beat * gain * sprayPan[w];
                    wideR -= beat * gain * sprayPan[w];
                }
            }
        }
        // Leakage and hum: the generator is always turning and the shielding
        // is never perfect, which is a lot of why an idle organ is not silent.
        if (leak > 0.0005f || hum > 0.0005f) {
            leakSum *= 0.995f;
            const int w = i % WheelBank::kWheels;
            leakSum += bank->sample(w, WheelBank::Wheel, wheelPhase[w]) * 0.05f;
            humPhase += 60.0f / sampleRate;
            if (humPhase >= 1.0f) humPhase -= 1.0f;
            dry += leakSum * leak * 0.25f + std::sin(6.2831853f * humPhase) * hum * 0.004f;
        }
        if (windNoise > 0.0005f) {
            rngState = rngState * 1664525u + 1013904223u;
            const float n = (static_cast<float>((rngState >> 9) & 0xffff) / 32768.0f) - 1.0f;
            dry += n * windNoise * 0.01f * (0.3f + demand);
        }
        if (trackerAmt > 0.0005f && mech > 0.0f) dry += mech * trackerAmt * 0.06f;

        // Key click is a *contact* - two bits of metal meeting under a key -
        // so it belongs to the two electric models and to nothing else. The
        // pipes and the reeds got it as well as their own tracker noise,
        // which is a pipe organ wired for electricity, and on a quiet stop
        // with a slow attack the click was the loudest thing in the note.
        // The contact timing still runs on every model, because that is what
        // the tracker noise is timed by.
        if (model == Tonewheel || model == Transistor) dry += click * 0.6f;
        // The tremulant is a *wind* modulation: it moves the pressure, and
        // the pressure is what sets both how hard a reed beats its frame and
        // how loud it is. So it goes into the nonlinearity and comes out the
        // other side of it too. Applied only before, a saturator eats it -
        // six decibels of asked-for swing measured as 0.7.
        dry *= windPressure * reedPress;
        if (buzz > 0.0f) {
            // A free reed beats against its frame: the swing is stopped
            // harder one way than the other, which is an asymmetric
            // saturation. What was here was `sin(dry * k)`, which *folds* -
            // past half scale the harmonics come back down again, so the
            // rasp changed character every time the tremolo moved the level,
            // and with two tremulants adding up it did that twice a second.
            // A clip is monotonic: more level is more buzz, always.
            // **A reed beats against its own frame, and ten reeds do not beat
            // ten times as hard.** This sat on the summed mix, so a chord
            // drove it by the sum of every sounding reed: at Musette's 0.3 a
            // single note was 85% saturated and a four-note chord was 100% -
            // a square wave - so the tune stayed clean and the chords behind
            // it turned into a fuzz box. The drive is taken per reed now.
            //
            // And it is normalised on a nominal level rather than on a point
            // the signal routinely passes, which was the amplifier's fault
            // one stage later.
            // The divisor is the *square root* of how many are sounding, not
            // the count: notes on different wheels add incoherently, so four
            // of them are twice one and not four times. Dividing by four
            // over-corrected and handed chords less drive than single notes,
            // which is the original fault upside down.
            const float reeds = std::sqrt(std::fmax(1.0f, demand));
            const float k = 1.0f + buzz * 4.0f;
            const float b = buzz * 0.45f; // the frame is closer on one side
            // Normalised at the level that actually arrives here, measured,
            // rather than at a guess - the whole point of the exercise.
            constexpr float kNominal = 0.55f;
            const float norm = kNominal / std::tanh(kNominal * k);
            const float one = dry / reeds;
            dry = reeds * (std::tanh(one * k * trem + b) - std::tanh(b)) * norm;
        }
        dry *= trem;

        // Scanner vibrato, then the amp, then the cabinet.
        const bool vibActive = vibDepthP > 0.001f && (vibUp || vibLow);
        if (vibActive) scanner.write(dry);
        scanPhase += vibStep;
        if (scanPhase >= 1.0f) scanPhase -= 1.0f;
        scanValue = 2.0f * std::fabs(2.0f * scanPhase - 1.0f) - 1.0f;
        float wet = dry;
        if (vibActive) {
            const float d = scanner.read(vibSamples * (1.0f + scanValue) + 2.0f);
            wet = chorusMode ? (dry * 0.6f + d * 0.6f) : d;
        }
        float x = wet;
        if (drive > 0.001f) {
            // **A saturation, not a clipper.**
            //
            // Dividing by `tanh(0.6g)` hard-codes an input of 0.6 to exactly
            // full scale at *every* drive setting - 0.08 and 0.50 both map
            // 0.6 to 1.000 - so everything above six tenths arrived
            // flat-topped whatever the knob said. A chord puts more into this
            // stage than a melody note does, which is why the chord was the
            // part that squared off, and why patches three knob-turns apart
            // all did it. The gain range is gentler, the curve is normalised
            // so a nominal signal passes at its own size, and the bias's own
            // offset is taken back out rather than left as DC.
            const float g = 1.0f + drive * 5.0f;
            const float b = bias * 0.5f;
            const float norm = 0.4f / std::tanh(0.4f * g);
            x = (std::tanh(x * g + b) - std::tanh(b)) * norm;
        }
        if (eqActive) x = trebleEq.process(midEq.process(bassEq.process(x)));
        if (model == Transistor) x += reedyFilter.process(x) * paramOf(ComboReedy) * 0.5f;
        // The house level. Every machine leaves the same amount of room for
        // the next one, and it is taken out here rather than out of forty
        // patch volumes. See Subvert's kHouse for why.
        // This does not decide how loud the machine is - the bank is levelled
        // to the same target whatever it says - it decides **where in the
        // volume knob's travel the bank sits**. At 0.5 the thinnest stops ran
        // out of travel at the top while full organ sat near 0.3 with the
        // whole bottom of the knob unused, because the two saturations in
        // front of this now cost real level instead of supplying makeup gain.
        // 0.9 slides the bank down the knob so both ends fit.
        constexpr float kHouse = 0.9f;
        x *= volume * exprGain * kHouse;

        float outL = x, outR = x;
        if (rotOn) rotary.process(x, outL, outR);
        const float wide = sprayWidth * spray * 0.5f;
        outL += wideL * wide * volume * exprGain * kHouse;
        outR += wideR * wide * volume * exprGain * kHouse;
        const float angle = (pan + 1.0f) * 0.25f * kPi;
        L[i] = outL * std::cos(angle) * 1.4142f;
        R[i] = outR * std::sin(angle) * 1.4142f;
    }
    return true;
}

} // namespace acidulous::machine
