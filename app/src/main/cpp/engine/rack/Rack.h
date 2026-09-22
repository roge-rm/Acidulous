#pragma once
#include <engine/dsp/Wsola.h>
#include <atomic>
#include <engine/core/Constants.h>
#include <engine/core/Frozen.h>
#include <engine/core/Messages.h>
#include <engine/core/Params.h>
#include <engine/core/Settings.h>
#include <engine/effect/Effect.h>
#include <engine/inputmod/InputMod.h>
#include <engine/machine/Machine.h>
#include <sequencer/ClipPlayer.h>

// One rack: clip player -> modifiers -> machine -> effects -> channel strip.
// Renders one block into bufL/bufR. Audio thread only, apart from swap*()
// being called from the audio thread by the Engine when a Mount arrives.
namespace acidulous {

class Rack {
  public:
    enum ChannelParam : int32_t { Gain, Pan, Mute, Solo, SendReverb, SendDelay, MidiMode, MidiChannel, Swing, Output, ChannelCount };

    /**
     * A channel parameter where it is going, not where it has smoothed to.
     *
     * For the ones that are decisions rather than levels: the scheduler asks
     * for the swing once a block, and a swing that ramped would slide the
     * offbeats across a bar and make an export unrepeatable.
     */
    float channelTarget(ChannelParam p) const { return channel.target(p); }

    /** internal: the machine only. both: and the hardware. midi: the
     *  hardware only, and the machine is not asked - which is the point,
     *  because driving something else should give the CPU back. */
    enum MidiOutMode : int32_t { OutInternal = 0, OutBoth, OutMidi };

    Rack();

    seq::ClipPlayer clipPlayer;

    bool isActive() const { return machine != nullptr; }
    Machine *currentMachine() const { return machine; }
    Effect *currentEffect(int32_t slot) const { return (slot >= 0 && slot < kEffectSlots) ? effects[slot] : nullptr; }
    InputMod *currentInputMod(int32_t slot) const { return (slot >= 0 && slot < kInputModSlots) ? modifiers[slot] : nullptr; }

    /**
     * Where a note that has been through the modifiers goes to be written down.
     *
     * The engine implements it. A modifier turns what somebody played into
     * what is heard, and what is heard is what the clip keeps - so the tap
     * for recording is here, at the end of the chain, rather than on the raw
     * message as it arrives.
     */
    struct ModifiedNoteSink {
        virtual ~ModifiedNoteSink() = default;
        virtual void onModifiedNote(int32_t rack, uint8_t status, uint8_t d1, uint8_t d2) = 0;
    };
    void setModifiedNoteSink(ModifiedNoteSink *sink) { modifiedSink = sink; }

    /**
     * Live MIDI - a finger, a controller - enters here and runs the modifier
     * chain. Whatever comes out the far end is played and, while recording,
     * written down.
     */
    void handleMidi(uint8_t status, uint8_t d1, uint8_t d2);

    /**
     * A note from a clip: straight to the machine, past the modifiers.
     *
     * **The modifiers are not in the playback path at all.** They act on the
     * way in, once, and what they produced is in the clip; running the clip
     * back through them would apply them a second time - an arpeggio of an
     * arpeggio, a chord of a chord. It is also why a clip now plays exactly
     * what the roll shows, which is the whole point of the change.
     */
    void playSequenced(uint8_t status, uint8_t d1, uint8_t d2);
    void allNotesOff();

    /**
     * Expression belonging to one note, straight to the machine.
     *
     * Straight, and not through the modifier chain, on purpose: an
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

  private:
    /** The frozen clip at its own rate, straight out of memory. */
    void readFrozenPlain(int32_t frames);

  public:

    // --- Freeze ---------------------------------------------------------
    // Which scene is playing decides whether this rack plays its machine or
    // the audio that machine already made. Called before the scheduler fires
    // notes, because a frozen rack is not sent any.
    /**
     * [ramping] relaxes the tempo match, and only that.
     *
     * A frozen clip is tempo-bound because audio does not stretch - except it
     * does, for about nine microseconds. A scene with a smooth tempo change is
     * between two tempos for its first bar, so it matches no clip's rendered
     * tempo and every freeze in it fell back to its machine: 87 us a rack for
     * Trinity, in the scene most likely to be why anything was frozen. While
     * the clock ramps, the mismatch is taken as a rate and the audio is
     * stretched to it instead.
     *
     * Deliberately *only* while ramping. Playing a freeze at any tempo at all
     * is a bigger change than this - it is the end of a freeze being tempo
     * bound, which is a promise the interface makes in two places - and it
     * wants to be that on purpose rather than as a side effect of a ramp.
     */
    void updateFrozen(int64_t sceneId, float bpm, bool playing, bool ramping = false);
    /** Pass the rack's place in the arrangement to a machine that wants it. */
    void updateScene(int64_t sceneId, int64_t cycleTick, bool playing);
    bool frozenActive() const { return frozenNow != nullptr; }
    /** Where in the frozen clip this block starts. Called before render(). */
    void syncFrozen(int64_t tickInIteration, float bpm);
    /** Returns the displaced set for the caller to retire. */
    const FrozenSet *swapFrozen(const FrozenSet *next) {
        const FrozenSet *old = frozenSet;
        frozenSet = next;
        frozenNow = nullptr; // re-decided at the next block
        // And nothing may go on ringing out of a set that is about to be
        // retired - the clip the tail cursor points into belongs to it.
        tailClip = nullptr;
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
    InputMod *swapInputMod(int32_t slot, InputMod *next);

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
    /**
     * What a sidechain listening to this rack hears: mono, after the inserts,
     * before the fader and the mute. Written by every `render`; see
     * `Engine::renderRacks` for when a listener reads it.
     */
    float keyBuf[kBlockFrames]{};

    /** Whether this rack is a group: its machine sums other racks. */
    bool isBus() const;
    /**
     * The rack this one's output is asked to go to, or -1 for the master.
     * What it *actually* goes to this block is `routedTo`, which the engine
     * settles - a bus that is not there, or a group routed into a group, goes
     * to the master instead.
     */
    int32_t outputRequested() const { return static_cast<int32_t>(channel.target(Output) + 0.5f) - 1; }
    int32_t routedTo = -1;

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
        int32_t stage = 0; // which modifier slot output this is
        void send(uint8_t status, uint8_t d1, uint8_t d2) override;
    };

    MidiOutQueue *outQueue = nullptr;
    int32_t rackIndex = 0;
    int64_t outFrame = 0;
    int32_t lastOutMode = OutInternal;
    uint8_t lastOutChannel = 0;

    void deliver(int32_t fromStage, uint8_t status, uint8_t d1, uint8_t d2);
    // Everything bound for the machine goes through here, so the voice limit
    // has one place to stand and modifier-generated notes are counted too.
    /** [live] says it came through the modifier chain, and so may be recorded. */
    void toMachine(uint8_t status, uint8_t d1, uint8_t d2, bool live);
    ModifiedNoteSink *modifiedSink = nullptr;
    void forgetHeld(uint8_t note);

    // Held notes, oldest first. Room for more than the largest limit on
    // offer, so the count stays honest when the limit is off.
    static constexpr int32_t kMaxHeld = 128;
    uint8_t held[kMaxHeld]{};
    int32_t heldCount = 0;

    const FrozenSet *frozenSet = nullptr;
    const FrozenClip *frozenNow = nullptr;
    int64_t frozenCursor = 0;
    /**
     * The ring-out, read alongside the loop and after it.
     *
     * It carries its own clip pointer because what is ringing is usually the
     * clip we have just left - at a scene change it is the one thing left of
     * it. One cursor, so a loop shorter than its own tail rings the newest
     * pass rather than stacking every pass; live they would stack, and the
     * newest is both the loudest and the one worth spending a read on.
     */
    const FrozenClip *tailClip = nullptr;
    int64_t tailCursor = 0;
    /**
     * How fast the frozen audio is read, as a multiple of the rate it was
     * rendered at. Exactly one for all but a ramping bar, and one means the
     * plain buffer read rather than the stretcher - there is no sense paying
     * nine microseconds for what one and a half will do, and none in smearing
     * a transient that did not need moving.
     */
    float frozenRate = 1.0f;
    dsp::StereoStretch frozenStretch;
    /** Last block's tick-derived read position, to notice a cycle coming round. */
    int64_t frozenSyncTarget = 0;
    /** Whether the stretcher holds this clip at this moment in it. */
    const FrozenClip *stretching = nullptr;
    /**
     * How much of the output is the stretched read rather than the plain one,
     * and the ramp between them.
     *
     * Both transitions are discontinuities. Going in, the stretcher's first
     * hop has nothing to overlap onto, so the window ramps it in over a hop -
     * half the level for fifteen milliseconds. Coming out is worse than a
     * click: `sourcePosition()` is where the *next* hop will read, which runs
     * ahead of the audio already emitted by up to a hop, so resuming the plain
     * read there skips up to fifteen milliseconds outright.
     *
     * Ten milliseconds of crossfade covers both, and costs what it mixes:
     * two buffer reads instead of one, for ten milliseconds, twice a ramp.
     * The plain read is skipped entirely once the blend is all the way over,
     * so a ramp that is not transitioning pays nothing at all.
     */
    float frozenBlend = 0.0f;
    static constexpr int32_t kBlendFrames = 480; // 10 ms at 48k

    Machine *machine = nullptr;
    Effect *effects[kEffectSlots]{};
    InputMod *modifiers[kInputModSlots]{};
    Sink sinks[kInputModSlots + 1];

    ParamSet channel;
    bool stereo = false;
    std::atomic<float> peakHold{0.0f};
    static constexpr int32_t kMaxTouched = 16;
    uint32_t touched[kMaxTouched]{};
    int32_t touchedCount = 0;
};

} // namespace acidulous
