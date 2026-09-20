#pragma once
#include <atomic>
#include <engine/core/Constants.h>
#include <engine/core/Frozen.h>
#include <engine/core/Messages.h>
#include <engine/core/Params.h>
#include <engine/core/Settings.h>
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
    enum ChannelParam : int32_t { Gain, Pan, Mute, Solo, SendReverb, SendDelay, MidiMode, MidiChannel, ChannelCount };

    /** internal: the machine only. both: and the hardware. midi: the
     *  hardware only, and the machine is not asked - which is the point,
     *  because driving something else should give the CPU back. */
    enum MidiOutMode : int32_t { OutInternal = 0, OutBoth, OutMidi };

    Rack();

    seq::ClipPlayer clipPlayer;

    bool isActive() const { return machine != nullptr; }
    Machine *currentMachine() const { return machine; }
    Effect *currentEffect(int32_t slot) const { return (slot >= 0 && slot < kEffectSlots) ? effects[slot] : nullptr; }
    Eventor *currentEventor(int32_t slot) const { return (slot >= 0 && slot < kEventorSlots) ? eventors[slot] : nullptr; }

    // Live or sequenced MIDI enters here and runs the eventor chain.
    void handleMidi(uint8_t status, uint8_t d1, uint8_t d2);
    void allNotesOff();

    /**
     * Expression belonging to one note, straight to the machine.
     *
     * Straight, and not through the eventor chain, on purpose: an
     * arpeggiator turns one note into a run of others and there is no
     * honest answer to which of them a finger's pressure belongs to. The
     * note is transformed; the expression follows the note that was played.
     */
    void noteExpression(uint8_t kind, uint8_t note, uint8_t d1, uint8_t d2, float bendSemis);

    /**
     * The same three, arriving from a clip rather than from a finger, in the
     * document's normalised domain rather than as MIDI. [kind] is an
     * acidulous::Expr. Down the same road as the live one, for the same
     * reason: it is the note's, not the chain's.
     */
    void noteExpressionValue(int32_t kind, uint8_t note, float v01);

    void onBlock(int64_t tickStart, int64_t tickEnd, float bpm);
    void render(int32_t frames);

    // --- Freeze ---------------------------------------------------------
    // Which scene is playing decides whether this rack plays its machine or
    // the audio that machine already made. Called before the scheduler fires
    // notes, because a frozen rack is not sent any.
    void updateFrozen(int64_t sceneId, float bpm, bool playing);
    bool frozenActive() const { return frozenNow != nullptr; }
    /** Where in the frozen clip this block starts. Called before render(). */
    void syncFrozen(int64_t tickInIteration, float bpm);
    /** Returns the displaced set for the caller to retire. */
    const FrozenSet *swapFrozen(const FrozenSet *next) {
        const FrozenSet *old = frozenSet;
        frozenSet = next;
        frozenNow = nullptr; // re-decided at the next block
        return old;
    }

    /**
     * Freezing: copy the rack's output after its effects and before its
     * channel strip, so the fader, pan, sends and mute stay live over the
     * frozen audio. Armed only by the offline render.
     */
    bool tapDry = false;
    float dryL[kBlockFrames]{};
    float dryR[kBlockFrames]{};
    bool isStereo() const { return stereo; }

    // Return the displaced object for the caller to retire.
    Machine *swapMachine(Machine *next);
    Effect *swapEffect(int32_t slot, Effect *next);
    Eventor *swapEventor(int32_t slot, Eventor *next);

    void setParam(Unit unit, int32_t index, float v01);

    // While recording, a parameter the user moves wins over its lane for the
    // rest of the current pass, so the lane cannot fight the knob it is
    // about to overwrite. Cleared at each iteration boundary.
    void touch(Unit unit, int32_t index) {
        const uint32_t key = (static_cast<uint32_t>(unit) << 16) | static_cast<uint32_t>(index & 0xffff);
        for (int32_t i = 0; i < touchedCount; ++i) if (touched[i] == key) return;
        if (touchedCount < kMaxTouched) touched[touchedCount++] = key;
    }
    bool isTouched(Unit unit, int32_t index) const {
        const uint32_t key = (static_cast<uint32_t>(unit) << 16) | static_cast<uint32_t>(index & 0xffff);
        for (int32_t i = 0; i < touchedCount; ++i) if (touched[i] == key) return true;
        return false;
    }
    void clearTouched() { touchedCount = 0; }

    float bufL[kBlockFrames]{};
    float bufR[kBlockFrames]{};

    // Read by the master after render(); post-fader.
    bool soloed() const { return channel.get(Solo) >= 0.5f; }
    bool muted() const { return channel.get(Mute) >= 0.5f; }
    /**
     * How much of this rack goes to send [slot].
     *
     * The two channel parameters are still called `sendreverb` and
     * `senddelay` - they are addresses, saved in songs and pointed at by
     * controller mappings, and renaming them would break both - but what is
     * *on* the two sends is now whichever effect the master is holding. So
     * the accessor is numbered and the names below are history.
     */
    float sendAmount(int32_t slot) const {
        return channel.get(slot == 0 ? SendReverb : SendDelay);
    }
    float readPeak() { return peakHold.exchange(0.0f, std::memory_order_relaxed); }
    float channelNormalized(int32_t index) const { return channel.normalized(index); }

    /** Where this rack's notes go when they are bound for the outside world. */
    void bindMidiOut(MidiOutQueue *queue, int32_t index) {
        outQueue = queue;
        rackIndex = index;
    }
    /**
     * Once a block: the frame its notes will be stamped with, and a chance to
     * notice the mode changing. A track switched away from sending mid-note
     * would otherwise leave the note hanging on the hardware for ever.
     */
    void updateMidiOut(int64_t frame);
    int32_t midiOutMode() const;
    bool midiOutBound() const { return outQueue != nullptr; }

  private:
    struct Sink final : MidiSink {
        Rack *rack = nullptr;
        int32_t stage = 0; // which eventor slot output this is
        void send(uint8_t status, uint8_t d1, uint8_t d2) override;
    };

    MidiOutQueue *outQueue = nullptr;
    int32_t rackIndex = 0;
    int64_t outFrame = 0;
    int32_t lastOutMode = OutInternal;
    uint8_t lastOutChannel = 0;

    void deliver(int32_t fromStage, uint8_t status, uint8_t d1, uint8_t d2);
    // Everything bound for the machine goes through here, so the voice limit
    // has one place to stand and eventor-generated notes are counted too.
    void toMachine(uint8_t status, uint8_t d1, uint8_t d2);
    void forgetHeld(uint8_t note);

    // Held notes, oldest first. Room for more than the largest limit on
    // offer, so the count stays honest when the limit is off.
    static constexpr int32_t kMaxHeld = 128;
    uint8_t held[kMaxHeld]{};
    int32_t heldCount = 0;

    const FrozenSet *frozenSet = nullptr;
    const FrozenClip *frozenNow = nullptr;
    int64_t frozenCursor = 0;

    Machine *machine = nullptr;
    Effect *effects[kEffectSlots]{};
    Eventor *eventors[kEventorSlots]{};
    Sink sinks[kEventorSlots + 1];

    ParamSet channel;
    bool stereo = false;
    std::atomic<float> peakHold{0.0f};
    static constexpr int32_t kMaxTouched = 16;
    uint32_t touched[kMaxTouched]{};
    int32_t touchedCount = 0;
};

} // namespace acidulous
