#pragma once
#include <engine/machine/Machine.h>
#include <engine/machine/nexus/Graph.h>
#include <memory>

// Nexus - the modular.
//
// Every other machine in this app is a fixed instrument with knobs. This one
// is a bag of parts and a soldering iron, and what makes it worth having on
// a phone is not breadth - a desktop modular will always have more modules -
// but that its parts are the rest of Acidulous. Filament's string, Manual's
// tonewheel generator and its rotary cabinet, Trinity's wavetables: all of
// them are blocks here, with a jack on each side.
//
// Two things about the shape of it:
//
//   - **A module's type is not a parameter.** Changing a slot from a filter
//     to a string means building a waveguide, and the audio thread may not
//     allocate. Types live in the patch text and come in through the loader,
//     the way Mosaic's zone map does. Knobs, cable depths and the morph are
//     parameters, so they are smooth, recordable and automatable.
//   - **Per-voice state lives in the graph**, never on the voice, because the
//     graph is what gets replaced when the patch changes.
namespace acidulous::machine {

class Nexus final : public Machine {
  public:
    static constexpr int kVoices = nexus::kVoices;

    enum P : int32_t {
        SlotBase = 0,                                   // 16 slots x 8 knobs
        CableBase = SlotBase + nexus::kSlots * nexus::kKnobs,  // 24 cables x 2 depths
        MacroBase = CableBase + nexus::kCables * 2,     // 8 macros
        Morph = MacroBase + nexus::kMacros,
        VoiceMode, Glide, BendRange, Octave, Transpose, Fine, Volume, Pan, Drive,
        Count
    };
    static_assert(Count <= kMaxParams, "Nexus declares more parameters than a unit can hold");

    Nexus();

    const char *typeName() const override { return "Nexus"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void onBlock(int64_t tickStart, int64_t tickEnd, float bpm) override;
    void reset() override;
    void noteOn(uint8_t note, uint8_t velocity) override;
    void noteOff(uint8_t note) override;
    void allNotesOff() override;
    void controlChange(uint8_t cc, uint8_t value) override;
    void channelPressure(uint8_t value) override;
    void pitchBend(int16_t value14) override;
    void noteBend(uint8_t note, float semitones) override;
    void notePressure(uint8_t note, uint8_t value) override;
    void noteTimbre(uint8_t note, uint8_t value) override;
    bool render(float *L, float *R, int32_t frames) override;
    void *swapObject(int32_t slot, void *object) override;

    /** UI polling: the scope trace. Audio thread writes it, this only copies. */
    int32_t readScope(float *dest, int32_t max) const;
    /** Slot levels then cable levels, for the editor to light the patch up. */
    int32_t readActivity(float *dest, int32_t max) const;

  private:
    struct Voice {
        bool used = false, gate = false;
        uint8_t note = 0;
        float pitch = 60.0f, velocity = 1.0f, random = 0.5f, trigger = 0.0f;
        int64_t age = 0;
        float quiet = 0.0f;   // seconds below the floor, for the watchdog
        // Per-note expression (MPE). `bend` is in semitones and adds to
        // whatever the channel is bending.
        float bend = 0.0f;
    };

    float paramOf(int32_t p) const { return params_.get(p); }

    float sampleRate = 48000.0f;
    nexus::Graph *graph = nullptr;
    nexus::Context ctx;

    Voice voices[kVoices];
    float pitchOf[kVoices] = {}, gateOf[kVoices] = {}, velocityOf[kVoices] = {};
    float randomOf[kVoices] = {}, triggerOf[kVoices] = {};
    // A finger's pressure and slide, -1 until it sends any: the touch module.
    float pressureOf[kVoices] = {}, timbreOf[kVoices] = {};
    int32_t active[kVoices] = {};
    int64_t counter = 0;

    float knobBuffer[nexus::kSlots * nexus::kKnobs] = {};
    float cableBuffer[nexus::kCables * 2] = {};
    float bendSemis = 0.0f;
    static constexpr uint32_t kRngSeed = 0x13579bdfu;
    uint32_t rng = kRngSeed;
    double tickCursor = 0.0, tickStep = 0.0;
};

} // namespace acidulous::machine
