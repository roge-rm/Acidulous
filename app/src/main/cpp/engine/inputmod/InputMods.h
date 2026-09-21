#pragma once
#include <cstdint>
#include <engine/inputmod/InputMod.h>
#include <engine/inputmod/Scales.h>

// The play-page features as modifiers: Scale, Chord, Arp.
namespace acidulous::modifier {

#define ACIDULOUS_INPUTMOD_COMMON(Name)                                                  \
    const char *typeName() const override { return #Name; }                            \
    const ParamDef *paramDefs(int32_t &count) const override;                           \
    void reset() override;                                                              \
    void handleMidi(uint8_t status, uint8_t d1, uint8_t d2, MidiSink &out) override;    \
    void allNotesOff(MidiSink &out) override;

// Output-note bookkeeping shared by the transforms: how many inputs currently
// hold each output pitch, so a note-off only goes out when the last one lets go.
struct OutputNotes {
    uint8_t held[128]{};
    void on(uint8_t pitch, uint8_t vel, MidiSink &out) {
        if (held[pitch] < 255) ++held[pitch];
        out.send(0x90, pitch, vel);
    }
    void off(uint8_t pitch, MidiSink &out) {
        if (held[pitch] == 0) return;
        if (--held[pitch] == 0) out.send(0x80, pitch, 0);
    }
    void allOff(MidiSink &out) {
        for (int p = 0; p < 128; ++p) if (held[p] != 0) { held[p] = 0; out.send(0x80, static_cast<uint8_t>(p), 0); }
    }
};

class Scale final : public InputMod {
  public:
    enum P { Key, ScaleType, Mode, Snap, Transpose, Octave, Count };
    Scale() { initParams(); }
    ACIDULOUS_INPUTMOD_COMMON(Scale)
  private:
    int map(int note) const;
    int16_t outOf[128]; // output pitch per sounding input, -1 when silent
    OutputNotes outs;
};

class Chord final : public InputMod {
  public:
    enum P { Mode, Type, Key, ScaleType, Voicing, Inversion, Spread, Bass, Strum, StrumDir, VelSpread, Count };
    Chord() { initParams(); }
    ACIDULOUS_INPUTMOD_COMMON(Chord)
    void onBlock(int64_t tickStart, int64_t tickEnd, float bpm, MidiSink &out) override;
  private:
    static constexpr int kMaxTones = 8, kPending = 32;
    int build(int note, int *tones) const; // returns count
    struct Voice { int16_t tones[kMaxTones]; int8_t count; };
    Voice voices[128]{};
    struct Pending { int64_t tick; uint8_t src, pitch, vel; bool live; };
    Pending pending[kPending]{};
    OutputNotes outs;
    int64_t nowTick = 0;
    float bpm = 120.0f;
};

class Arp final : public InputMod {
  public:
    enum P {
        Rate, Gate, Swing, Mode, Octaves, OctMode, Length,
        S01, S02, S03, S04, S05, S06, S07, S08, S09, S10, S11, S12, S13, S14, S15, S16,
        Ratchet, RatchetChance, Chance, VelMode, Accent, Latch, Shift, Cycles, Sync, Humanise, Count
    };
    Arp() { initParams(); }
    ACIDULOUS_INPUTMOD_COMMON(Arp)
    void onBlock(int64_t tickStart, int64_t tickEnd, float bpm, MidiSink &out) override;
  private:
    static constexpr int kMaxHeld = 16, kMaxSeq = 64, kPending = 64;
    struct Held { uint8_t pitch, vel; uint32_t order; };
    Held held[kMaxHeld]{};
    int heldCount = 0;
    uint32_t orderCounter = 0;
    bool latched = false; // held[] is a latch memory, keys are up
    int seq[kMaxSeq]{};   // pitches in play order for the current mode/octaves
    uint8_t seqVel[kMaxSeq]{};
    int seqLen = 0, pos = 0, dir = 1, stepCounter = 0, cycle = 0;
    int lastPitch = -1;
    int64_t anchor = 0, nextStep = -1;
    struct Pending { int64_t tick; uint8_t pitch, vel; bool on; bool live; };
    Pending pending[kPending]{};
    OutputNotes outs;
    uint32_t rng = 0x9E3779B9u;
    float rnd() { rng ^= rng << 13; rng ^= rng >> 17; rng ^= rng << 5; return static_cast<float>(rng & 0xffffff) / 16777216.0f; }
    void rebuild();
    void addHeld(uint8_t pitch, uint8_t vel);
    void removeHeld(uint8_t pitch);
    void schedule(int64_t tick, uint8_t pitch, uint8_t vel, bool on);
    void flush(int64_t upTo, MidiSink &out);
    int nextIndex();
};

#undef ACIDULOUS_INPUTMOD_COMMON

} // namespace acidulous::modifier
