#pragma once
#include <atomic>
#include <engine/core/Constants.h>
#include <engine/core/Messages.h>
#include <engine/core/Params.h>
#include <engine/effect/Effect.h>
#include <engine/eventor/Eventor.h>
#include <engine/machine/Machine.h>
#include <sequencer/ClipPlayer.h>

// One rack: clip player -> eventors -> machine -> effects -> channel strip.
// Renders one block into bufL/bufR. Audio thread only, apart from swap*()
// being called from the audio thread by the Engine when a Mount arrives.
namespace acidulous {

class Rack {
  public:
    enum ChannelParam : int32_t { Gain, Pan, Mute, Solo, SendReverb, SendDelay, ChannelCount };

    Rack();

    seq::ClipPlayer clipPlayer;

    bool isActive() const { return machine != nullptr; }
    Machine *currentMachine() const { return machine; }

    // Live or sequenced MIDI enters here and runs the eventor chain.
    void handleMidi(uint8_t status, uint8_t d1, uint8_t d2);
    void allNotesOff();

    void onBlock(int64_t tickStart, int64_t tickEnd);
    void render(int32_t frames);
    bool isStereo() const { return stereo; }

    // Return the displaced object for the caller to retire.
    Machine *swapMachine(Machine *next);
    Effect *swapEffect(int32_t slot, Effect *next);
    Eventor *swapEventor(int32_t slot, Eventor *next);

    void setParam(Unit unit, int32_t index, float v01);

    float bufL[kBlockFrames]{};
    float bufR[kBlockFrames]{};

    // Read by the master after render(); post-fader.
    bool soloed() const { return channel.get(Solo) >= 0.5f; }
    bool muted() const { return channel.get(Mute) >= 0.5f; }
    float sendReverb() const { return channel.get(SendReverb); }
    float sendDelay() const { return channel.get(SendDelay); }
    float readPeak() { return peakHold.exchange(0.0f, std::memory_order_relaxed); }

  private:
    struct Sink final : MidiSink {
        Rack *rack = nullptr;
        int32_t stage = 0; // which eventor slot output this is
        void send(uint8_t status, uint8_t d1, uint8_t d2) override;
    };

    void deliver(int32_t fromStage, uint8_t status, uint8_t d1, uint8_t d2);

    Machine *machine = nullptr;
    Effect *effects[kEffectSlots]{};
    Eventor *eventors[kEventorSlots]{};
    Sink sinks[kEventorSlots + 1];
    ParamSet channel;
    bool stereo = false;
    std::atomic<float> peakHold{0.0f};
};

} // namespace acidulous
