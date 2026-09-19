#pragma once
#include <engine/core/Utterance.h>
#include <engine/dsp/Adsr.h>
#include <engine/dsp/MultiFilter.h>
#include <engine/machine/Machine.h>
#include <vector>

// Molt - a voice you write for.
//
// Nothing else here can be one. Every other machine makes a sound from
// nothing or plays a recording back at pitch, which turns a singer into a
// chipmunk; Cipher is a vocoder and needs a carrier to impose a voice on.
// This takes a sung take and makes it an instrument.
//
// The one capability everything rests on is moving **pitch and formant
// independently**. Speed a voice up and it rises *and* shrinks, because the
// resonances of the throat move with it. Separate the two and a line can go
// up an octave and still be a person, or stay where it is and be sung by
// somebody twice the size. Time-domain pitch-synchronous overlap-add does
// both: lay the take's own glottal pulses down at a new spacing and the pitch
// moves while the formants stay; resample each pulse before laying it and the
// formants move while the pitch stays.
//
// The classic box's set follows from that - robot, hard tune, megaphone - and
// the extra is that **the piano roll does the tuning**. Elsewhere harmony is
// a knob: "a third above", plus a key to be right in. Here the notes in the
// clip are the target pitches, so drawing a chord makes the take sing that
// chord, and there is no harmony knob and no key setting because the clip is
// already both.
//
// One read head, four voices. Every note sings the same word at the same
// moment and differs only in what it is pulled to, which is what makes a
// chord harmony rather than a round.
namespace acidulous::machine {

class Molt final : public Machine {
  public:
    static constexpr int kVoices = 4;
    /**
     * The overlap-add tail, a power of two so the ring wraps by mask. Two
     * periods at the lowest pitch this tracks (70 Hz) is 1371 frames, and a
     * formant an octave down doubles it; 4096 clears that with room.
     */
    static constexpr int kAccum = 4096;

    enum P : int32_t {
        Start = 0, Loop,
        Tune, Rate, Robot,
        Formant, Mega,
        Cutoff, Resonance, FilterType,
        AmpAttack, AmpDecay, AmpSustain, AmpRelease,
        Glide, BendRange, Octave, Transpose, VelocityAmount,
        Drive, Volume, Pan,
        Count
    };

    Molt();

    const char *typeName() const override { return "Molt"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void reset() override;
    void noteOn(uint8_t note, uint8_t velocity) override;
    void noteOff(uint8_t note) override;
    void allNotesOff() override;
    void controlChange(uint8_t cc, uint8_t value) override;
    void pitchBend(int16_t value14) override;
    void noteBend(uint8_t note, float semitones) override;
    void notePressure(uint8_t note, uint8_t value) override;
    bool render(float *L, float *R, int32_t frames) override;
    void *swapObject(int32_t slot, void *object) override;

    /** Where the read head is, 0..1 through the take, for the panel. */
    float headPosition() const {
        const audio::Utterance *u = source;
        if (u == nullptr || u->frames <= 1) return 0.0f;
        return static_cast<float>(head) / static_cast<float>(u->frames);
    }
    bool takeLoaded() const { return source != nullptr && source->usable(); }

  private:
    struct Voice {
        bool used = false, gate = false;
        uint8_t note = 0;
        float velocity = 1.0f;
        float bend = 0.0f, pressure = 0.0f;
        /** The pitch it is being pulled to, in log2 Hz, glided by `rate`. */
        float logPitch = 0.0f;
        bool primed = false;
        /** Frames until the next grain is laid down. */
        float untilGrain = 0.0f;
        dsp::Adsr amp;
        int32_t accHead = 0;
        std::vector<float> acc;
    };

    /**
     * Lay one grain for [v] at the read head and return the spacing until the
     * next. This is the whole machine: which pulse to copy, how fast to read
     * it (formant), and how far apart to lay them (pitch).
     */
    float layGrain(Voice &v, float formantRatio, float tune, float rateSec, bool robot);

    float paramOf(int32_t i) const { return params_.get(i); }
    /**
     * The target rather than the smoothed value, for a switch: smoothing a
     * two-step parameter turns a rising edge into a ramp.
     */
    float rawOf(int32_t i) const { return params_.normalized(i); }
    int32_t steppedOf(int32_t p) const { return static_cast<int32_t>(paramOf(p) + 0.5f); }

    float sampleRate = 48000.0f;
    const audio::Utterance *source = nullptr;

    // The read head belongs to the machine, not to a voice.
    double head = 0.0;
    bool running = false;

    Voice voices[kVoices];
    /** One filter: a voice is mono until it is panned. */
    dsp::MultiFilter filter;
    /** The megaphone's band, with its own clipping drive. */
    dsp::MultiFilter horn;

    float channelBend = 0.0f;
};

} // namespace acidulous::machine
