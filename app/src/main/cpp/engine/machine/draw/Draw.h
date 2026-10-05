#pragma once
#include <cstdint>
#include <engine/machine/Machine.h>
#include <engine/machine/draw/DrawKinds.h>
#include <engine/machine/draw/FreeReed.h>

namespace acidulous::machine {

/**
 * Draw: free reeds. Harmonicas, accordions, melodica, harmonium and the reeds
 * that sound through pipes (PLAN §4.26).
 *
 * Each note is a reed blown by the pressure across it (draw::FreeReed): the
 * breath or the bellows rise and fall, and the reed speaks, settles and
 * stops on its own. Velocity and pressure blow harder, which brightens the
 * sound as a real reed's does; the level follows the house law. Each reed is
 * tuned to sound its note (DrawTuning.h, as a maker files a reed).
 */
class Draw final : public Machine {
  public:
    enum P : int32_t {
        Model = 0, Tune, Pressure, Attack, Release, Set, Chamber, Air,
        Voices, VelocityAmount, BendRange, Octave, Volume,
        Reeds, Detune,
        Count
    };
    static_assert(Count <= kMaxParams, "too many parameters");

    Draw();

    const char *typeName() const override { return "Draw"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void reset() override;
    void noteOn(uint8_t note, uint8_t velocity) override;
    void noteOff(uint8_t note) override;
    void allNotesOff() override;
    void pitchBend(int16_t value14) override;
    void channelPressure(uint8_t value) override;
    void notePressure(uint8_t note, uint8_t value) override;
    bool render(float *L, float *R, int32_t frames) override;

    /** Voices sounding, for tests. */
    int activeVoices() const;
    /** A voice's first reed, for tests: the one playing [note], or null. */
    const draw::FreeReed *reedFor(uint8_t note) const;
    /** Reeds a note sounds at most. */
    static constexpr int kReeds = 3;

  private:
    static constexpr int kVoices = 8;

    struct Voice {
        /** The note's reeds, tuned apart by the detune knob; [count] of them sound. */
        draw::FreeReed reeds[kReeds];
        int count = 1;
        float share = 1.0f;
        bool used = false, held = false;
        uint8_t note = 0;
        float baseNote = 60.0f;
        int32_t kind = draw::Accordion;
        /** The pressure blowing the reed now, and where it's going, Pa. */
        float blown = 0.0f, aim = 0.0f;
        /** The pressure the note was struck at, Pa: what pressure added since is louder than. */
        float struck = 1.0f;
        /** How much louder pressure added since the note started makes it, smoothed. */
        float squeeze = 1.0f;
        /** The note's own pressure (MPE), 0 to 1, or -1 for the channel's. */
        float pressure = -1.0f;
        float velocity = 0.8f;
        float gain = 0.0f;
        float level = 0.0f;
        /** The breath's or bellows' slow wander: a smoothed noise, two poles. */
        float wander1 = 0.0f, wander2 = 0.0f;
        int quietBlocks = 0;
        uint32_t age = 0;
    };

    float paramOf(int32_t i) const { return params_.get(i); }
    Voice *voiceFor(uint8_t note);
    /** Makes the voice's reeds for its note, each tuned to sound it and then apart. */
    void retune(Voice &v);
    /** The pressure a held voice is blown at, Pa. */
    float aimFor(const Voice &v) const;
    draw::ReedMake makeFor(int32_t kind) const;

    Voice voices[kVoices];
    float sampleRate = 48000.0f;
    float bend = 0.0f, channelPressure_ = 0.0f;
    /** The body's filters: a one-pole low-pass and a resonance, per kind, on the sum. */
    float lowState = 0.0f;
    float bodyX1 = 0.0f, bodyX2 = 0.0f, bodyY1 = 0.0f, bodyY2 = 0.0f;
    float dcIn = 0.0f, dcOut = 0.0f;
    uint32_t noise = 0x2545f491u, clock = 0;
    int quietSamples = 0;
    bool asleep = true;
};

} // namespace acidulous::machine
