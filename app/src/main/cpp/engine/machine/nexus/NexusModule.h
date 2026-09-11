#pragma once
#include <cstdint>
#include <engine/core/Constants.h>

// What a block in a patch is.
//
// A module is eight knobs, some inputs, some outputs and a step() that runs
// once per sample. It owns no note state and no voice: the graph owns both,
// because the graph is what gets replaced when the patch changes, and a
// voice holding a pointer into a module would be left pointing at freed
// memory the moment somebody adds a cable.
//
// Everything is a float and every jack accepts every other, as on a real
// modular. Audio is nominally -1..1 and control 0..1 or -1..1; the colours
// in the editor are a hint, not a rule.
namespace acidulous::machine::nexus {

constexpr int kKnobs = 8;      // per module, fixed: nine would be two modules
constexpr int kPorts = 8;      // inputs and outputs, each
constexpr int kSlots = 16;     // modules in a patch
constexpr int kCables = 24;    // cables whose depth is a parameter
constexpr int kVoices = 8;
constexpr int kMacros = 8;

/** What the whole graph shares: the transport, and the performance controls. */
struct Context {
    float sampleRate = 48000.0f;
    float bpm = 120.0f;
    double tick = 0.0;         // interpolated within the block, so clocks are sample-accurate
    double tickInc = 0.0;
    float modWheel = 0.0f;
    float pressure = 0.0f;
    float bend = 0.0f;
    float macro[kMacros] = {};
    // The voice this call is for; -1 for a mono module. The per-voice values
    // live in the machine and are read through here, so a module never holds
    // a pointer to anything that a patch rebuild could take away.
    int32_t voice = -1;
    const float *pitchOf = nullptr;
    const float *gateOf = nullptr;
    const float *velocityOf = nullptr;
    const float *randomOf = nullptr;
    const float *triggerOf = nullptr;

    float voicePitch() const { return voice >= 0 && pitchOf != nullptr ? pitchOf[voice] : 60.0f; }
    float voiceGate() const { return voice >= 0 && gateOf != nullptr ? gateOf[voice] : 0.0f; }
    float voiceVelocity() const { return voice >= 0 && velocityOf != nullptr ? velocityOf[voice] : 1.0f; }
    float voiceRandom() const { return voice >= 0 && randomOf != nullptr ? randomOf[voice] : 0.5f; }
    float voiceTrigger() const { return voice >= 0 && triggerOf != nullptr ? triggerOf[voice] : 0.0f; }
};

/**
 * One module. Constructed on a worker thread - it may allocate there - and
 * afterwards touched only by the audio thread.
 */
class Module {
  public:
    virtual ~Module() = default;

    /** Worker thread. Allocate here or not at all. */
    virtual void prepare(float sampleRate, int32_t voices) = 0;
    /** Audio thread. Forget everything that was sounding. */
    virtual void reset() = 0;

    /** Once a block: the eight knob values, already smoothed. */
    virtual void setKnobs(const float *knobs) = 0;

    /**
     * One sample. `in` is kPorts wide and already summed and scaled by the
     * cables; write up to kPorts outputs. `state` is this module's slice of
     * the graph's per-voice storage.
     */
    virtual void step(const float *in, float *out, const Context &ctx) = 0;

    /** Does this module need one instance per voice, or can it be shared? */
    virtual bool polyCapable() const { return true; }
    virtual bool monoCapable() const { return true; }
};

} // namespace acidulous::machine::nexus
