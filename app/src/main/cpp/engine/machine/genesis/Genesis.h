#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <engine/dsp/Filter.h>
#include <engine/machine/Machine.h>

// Genesis - the big box.
//
// Hexbeat is the small one: dry, short, and it was built to be. Genesis is
// the pair of machines that came after it and never left - the long
// pitch-swept kick you feel before you hear, a snare that is two tones and a
// cloud of noise, six detuned squares through a high-pass for everything
// metal, and a clap that is four bursts and a room.
//
// Two things make it more than a second drum synthesizer:
//
//   - **Drift.** No two hits are identical. Tune, decay and level each move
//     a little on every trigger, the way a circuit does, so a four-bar loop
//     stops sounding like one bar copied four times.
//   - **A bus compressor with the kick wired to its side chain.** The pump
//     is not an effect on these records, it is the sound of the record, and
//     it belongs in the machine rather than three menus away.
namespace acidulous::machine {

class Genesis final : public Machine {
  public:
    enum Voice : int32_t {
        Kick, Snare, Clap, Rim, TomLo, TomMid, TomHi,
        HatClosed, HatOpen, Crash, Ride, Cowbell, VoiceCount
    };
    static constexpr uint8_t kBaseNote = 36;

    enum P : int32_t {
        KickTune, KickDecay, KickPunch, KickSweep, KickClick, KickDrive, KickLevel,
        SnareTune, SnareDecay, SnareSnap, SnareTone, SnareLevel,
        ClapSpread, ClapDecay, ClapTone, ClapLevel,
        RimTune, RimDecay, RimLevel,
        TomLoTune, TomMidTune, TomHiTune, TomDecay, TomBend, TomLevel,
        HatTune, HatClosedDecay, HatOpenDecay, HatTone, HatLevel,
        CrashDecay, CrashTone, CrashLevel,
        RideDecay, RideTone, RideBell, RideLevel,
        BellTune, BellDecay, BellLevel,
        Drift, Accent,
        CompAmount, CompAttack, CompRelease, Duck,
        Drive, Volume, Pan,
        Count
    };
    static_assert(Count <= kMaxParams, "Genesis declares more parameters than a unit can hold");

    Genesis();
    const char *typeName() const override { return "Genesis"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void reset() override;
    void noteOn(uint8_t note, uint8_t velocity) override;
    void noteOff(uint8_t) override {} // drums do not sustain
    void allNotesOff() override;
    bool render(float *L, float *R, int32_t frames) override;

  private:
    struct Env {
        float level = 0.0f, coeff = 0.01f;
        bool active = false;
        /**
         * [seconds] is how long the sound *lasts* - the time to fall 60 dB -
         * and not one time constant.
         *
         * It used to be one time constant, which is 60 dB in 6.9 of them, so
         * every decay in this machine ran nearly seven times longer than the
         * number beside it said. A crash set to 2.2 seconds was still only
         * 20 dB down after 4.8 and took 14.8 to finish: Dan heard it as "a
         * metallic constant noise... the only sound for the last 15 seconds
         * of each sample", which is exactly what it was. A control in seconds
         * has to mean seconds - the number is the promise.
         */
        void fire(float sr, float seconds, float amp = 1.0f) {
            level = amp;
            constexpr float kLn1000 = 6.907755f; // 60 dB, in time constants
            coeff = 1.0f - std::exp(-kLn1000 / (std::max(0.002f, seconds) * sr));
            active = true;
        }

        /**
         * The same curve read the old way: [seconds] is one time constant.
         *
         * For the envelopes that are a *shape* rather than a length - the
         * kick's pitch sweep, the tom's bend, the click - where the number
         * was never a duration anybody reads off a panel and was tuned by ear
         * as a curve. Changing those to mean 60 dB made them seven times
         * faster and quietly rewrote how the kick speaks, which is not what
         * was wrong.
         */
        void fireTau(float sr, float seconds, float amp = 1.0f) {
            level = amp;
            coeff = 1.0f - std::exp(-1.0f / (std::max(0.002f, seconds) * sr));
            active = true;
        }
        float next() {
            level -= level * coeff;
            if (level < 1e-5f) { level = 0.0f; active = false; }
            return level;
        }
    };
    struct Osc {
        float phase = 0.0f;
        float step(float hz, float sr) {
            phase += hz / sr;
            if (phase >= 1.0f) phase -= 1.0f;
            return phase;
        }
    };

    float noise() {
        rng ^= rng << 13;
        rng ^= rng >> 17;
        rng ^= rng << 5;
        return static_cast<float>(rng) * (2.0f / 4294967296.0f) - 1.0f;
    }
    /** A hit is never quite the last one: this is how much off it is. */
    float wobble(float amount) { return 1.0f + noise() * amount; }
    float metallic(float tune);
    void trigger(int32_t voice, float velocity);

    float sr = 48000.0f;
    static constexpr uint32_t kRngSeed = 0x1f123bb5u;
    uint32_t rng = kRngSeed;

    Env amp[VoiceCount], pitch[VoiceCount], aux[VoiceCount];
    float gain[VoiceCount]{};
    float tuneOf[VoiceCount]{};   // this hit's tune, drift included
    Osc osc[VoiceCount], osc2[VoiceCount];
    Osc metal[6];
    dsp::Svf bp[VoiceCount];
    dsp::Svf hp[VoiceCount];
    float clapPhase = 0.0f;
    int32_t clapLeft = 0;

    // The bus compressor, and what the kick is telling it to do.
    float compEnv = 0.0f, duckEnv = 0.0f;
};

} // namespace acidulous::machine
