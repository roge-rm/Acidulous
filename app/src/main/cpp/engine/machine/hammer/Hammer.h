#pragma once
#include <cstdint>
#include <engine/machine/Machine.h>
#include <engine/machine/hammer/Board.h>
#include <engine/machine/hammer/Course.h>
#include <engine/machine/hammer/Felt.h>
#include <engine/machine/hammer/Keys.h>

// Hammer: a modelled piano, and in time everything else a hammer strikes
// (PLAN §4.24, M71).
//
// A key is a felt hammer thrown at a course of one to three stiff strings
// (Felt.h, Course.h), its sound through one soundboard shared by every key
// (Board.h). What each key is - how stiff its strings, how long they ring,
// how far the prompt sound falls before the aftersound, how bright the
// strike - is measured from reference recordings (Keys.h, KeyTables.h).
//
// One voice per key: striking a key that is still ringing strikes the same
// strings again, as on the instrument.
namespace acidulous::machine {

class Hammer final : public Machine {
  public:
    enum P : int32_t {
        // The instrument.
        Model = 0, Size, Age, Seed,
        // The hammer.
        Hardness, HardKey, Weight, Position, FeltSoft, Tacks, VelocityAmount,
        // The strings.
        Sustain, SustainKey, Tone, ToneKey, Stiffness, Stretch, Unison, Strings, Couple, Polar, Tension, Clang,
        // Dampers and pedals.
        Dampers, DampTime, PedalAt, PedalSpan, UnaCorda, Sympathy, Duplex, Noises,
        // The board.
        BoardLevel, Lid, Tail, Mic, Width,
        // Electric.
        Pickup, Offset, Tonebar, Pickups, Mute, Drive, Tremolo, TremRate, TremSync, TremWide,
        // Preparations.
        Prep, PrepKeys, PrepAt, PrepAmt,
        // Out.
        Detail, Voices, Volume, Pan, BendRange, Octave, Transpose, Fine,
        Count
    };
    static_assert(Count <= kMaxParams, "too many parameters");

    Hammer();

    const char *typeName() const override { return "Hammer"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void reset() override;
    void noteOn(uint8_t note, uint8_t velocity) override;
    void noteOff(uint8_t note) override;
    void allNotesOff() override;
    void setDampers(bool lifted) override;
    void pitchBend(int16_t value14) override;
    bool render(float *L, float *R, int32_t frames) override;

    // For tools/hammer_test and tools/hammer_render: what each key is, to
    // read or adjust (then refreshKeys) when calibrating against references.
    int activeVoices() const;
    int lastContactSamples() const { return lastContact; }
    hammer::KeySpec &keySpec(int key) { return keys[key < 0 ? 0 : (key > kKeys - 1 ? kKeys - 1 : key)]; }
    void refreshKeys();

  private:
    static constexpr int kVoices = 32;
    static constexpr int kKeys = 128;
    /** Longest strike-to-bridge delay, samples (A0 is about 800). */
    static constexpr int kKnockLine = 2048;

    struct Voice {
        bool used = false;
        /** The key is down (the dampers are off it while it is, and while the pedal is). */
        bool held = false;
        int key = 0;
        int note = 0;
        hammer::Course course;
        hammer::Felt felt;
        hammer::Course::Design design;
        bool designed = false;
        /** The damper: 0 off the strings, 1 on, moving between over a few blocks. */
        float damp = 0.0f, dampTarget = 0.0f;
        /** The output gain for the velocity law, and where it's ramping to. */
        float gain = 0.0f, gainTarget = 0.0f;
        float panL = 0.7071f, panR = 0.7071f;
        /** The phantom partials' high-pass. */
        float phantomIn = 0.0f, phantomOut = 0.0f;
        /** The blow's force last sample, N: the knock is its change. */
        float force = 0.0f;
        /** This blow's knock against the note's level. */
        float knock = 1.0f;
        /**
         * The knock on its way to the bridge: the board hears the blow when
         * the string brings it, not when the felt lands.
         */
        float knockLine[kKnockLine] = {};
        int knockAt = 0, knockDelay = 0, knockLive = 0;
        int64_t age = 0;
        int quietBlocks = 0;
    };

    /** The key's strike: the course designed for it (if it changed) and the hammer thrown. */
    void strike(Voice &v, int key, float hz, float velocity01);
    /** A voice for [key]: its own if it still rings, otherwise a free one or the quietest let go. */
    Voice *voiceFor(int key);
    /** The damper's decay times blended in for [v] at its damper position. */
    void applyDamper(Voice &v);
    int voiceCap() const;
    bool fullDetail() const;

    float paramOf(int32_t i) const { return params_.get(i); }

    float sampleRate = 48000.0f;
    Voice voices[kVoices];
    hammer::KeySpec keys[kKeys];
    float stiffness[kKeys] = {};
    hammer::Board board;
    bool dampersUp = false;
    float bend = 0.0f;
    int64_t clock = 0;
    int lastContact = 0;
    int quietBlocks = 0;
    bool asleep = true;
    float busL[kBlockFrames] = {}, busR[kBlockFrames] = {}, knockBus[kBlockFrames] = {};
    /** The bass keys' own bus, for the bass bridge's way across the board. */
    float lowL[kBlockFrames] = {}, lowR[kBlockFrames] = {};
};

} // namespace acidulous::machine
