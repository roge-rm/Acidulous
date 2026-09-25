#pragma once
#include "MasterBus.h"
#include "Rack.h"
#include <atomic>
#include <chrono>
#include <engine/core/Audition.h>
#include <engine/core/Capture.h>
#include <engine/core/Expression.h>
#include <engine/core/InputBus.h>
#include <engine/core/Handover.h>
#include <engine/core/Messages.h>
#include <engine/core/RtQueue.h>
#include <engine/core/Timebase.h>
#include <engine/core/Tuner.h>
#include <sequencer/RecordQueue.h>
#include <sequencer/SceneScheduler.h>
#include <sequencer/TickClock.h>
#include <sequencer/CaptureMarks.h>
#include <sequencer/Transport.h>

// The engine: everything the audio thread owns, and the queues by which the
// rest of the app talks to it. renderBlock() is the audio thread's entry.
namespace acidulous {

class Engine : public Rack::ModifiedNoteSink {
  public:
    Engine();
    ~Engine();

    void start(); // starts the retire worker; the audio stream is the host's job
    void stop();

    // --- Audio thread: exactly kBlockFrames interleaved stereo frames ------------
    // `in` is one block of interleaved stereo from the input stream, or
    // null when nothing is open. It is published on the input bus for the
    // whole block, monitored if asked, and captured if a recording is armed.
    void renderBlock(const float *inInterleaved, float *outInterleaved);

    // --- Any thread ------------------------------------------------------------------
    bool mount(const Mount &m) { return mounts.push(m); }
    bool pushMidi(const MidiMessage &m) { return midiIn.push(m); }
    bool pushClock(const MidiInEvent &e) { return clockIn.push(e); }
    bool pushParam(const ParamMessage &m) { return paramsIn.push(m); }

    seq::Transport transport;
    seq::TickClock clock;
    seq::SceneScheduler scheduler;
    seq::RecordQueue recordQueue;
    /** Notes and clock on their way to hardware. Drained by the MIDI sender. */
    MidiOutQueue midiOut;
    /** Realtime bytes from a master, stamped with when they were heard. */
    MidiClockQueue clockIn;
    seq::ClockFollower follower;

    /**
     * A network timebase - Link - or null. Set from the host before it is
     * switched on and cleared after; the audio thread only ever reads it,
     * and only calls it when the transport says Link owns the tempo.
     */
    std::atomic<Timebase *> timebase{nullptr};
    /** Does a peer starting or stopping start and stop us too? */
    std::atomic<bool> syncStartStop{true};
    MasterBus master;
    Rack racks[kRackCount];

    // --- MPE ----------------------------------------------------------------
    /**
     * The channel zone. [kind] is 0 off, 1 lower (master channel 1, members
     * climbing from 2), 2 upper (master 16, members descending from 15).
     *
     * One zone, here rather than on a rack, because a zone is a property of
     * what is plugged in and not of a track: with notes following whichever
     * track is open, the instrument it plays changes as you work.
     */
    void setMpeZone(int32_t kind, int32_t members, float bendSemis) {
        mpeKind = kind < 0 ? 0 : (kind > 2 ? 2 : kind);
        mpeMembers = members < 1 ? 1 : (members > 15 ? 15 : members);
        mpeBendSemis = bendSemis < 1.0f ? 1.0f : (bendSemis > 96.0f ? 96.0f : bendSemis);
        // The notes held are kept: a zone switched on mid-chord (auto, from
        // the second finger) must already know where the first one is.
        for (auto &ch : lastExprSent) for (float &v : ch) v = -1.0f;
    }
    bool mpeMember(uint8_t channel) const {
        if (mpeKind == 0 || channel > 15) return false;
        if (mpeKind == 1) return channel >= 1 && channel <= mpeMembers;
        return channel <= 14 && channel >= 15 - mpeMembers;
    }
    /** Which member channels are holding a note, as a bit per channel. */
    int32_t mpeHeldMask() const {
        int32_t mask = 0;
        for (int32_t c = 0; c < 16; ++c) if (mpeChannelNote[c] >= 0) mask |= 1 << c;
        return mask;
    }
    Retirer retirer;

    /**
     * Where a block's time went, coarsely.
     *
     * Five, not sixteen. A `steady_clock::now()` per rack would be its own
     * measurement cost paid twelve thousand times a second, and the question
     * this has to answer is only ever "which part of the engine", which five
     * answers and sixteen answers no better.
     */
    enum class Phase : int32_t { Input, Sequencer, Racks, Master, Capture, Count };
    static constexpr size_t kPhases = static_cast<size_t>(Phase::Count);

    float loadPercent() const { return load.load(std::memory_order_relaxed); }

    /**
     * The worst block since somebody last asked, in microseconds.
     *
     * **Peak-hold, and reading it clears it**, exactly as the output meter
     * behaves - because the question a dropout asks is "how bad did it get",
     * and an average cannot answer it. `load` above is a one-pole with a
     * 27 ms memory, polled every 80 ms and smoothed again on the way to the
     * screen: a block that took four times its budget moves it by a few
     * points and has decayed before anybody looks. That is the right shape
     * for a level ladder and the wrong shape for finding a spike, so both
     * exist and neither pretends to be the other.
     */
    int32_t worstBlockUs() { return blockPeak.exchange(0, std::memory_order_relaxed); }

    /**
     * The worst single rack, in microseconds, and which one it was.
     *
     * Sixteen clock reads a block is twelve thousand a second, which measured
     * at about three hundredths of one per cent - worth paying, because "the
     * racks are eighty-seven per cent of the worst block" is only half an
     * answer and the other half is *which* rack. It is what decides whether to
     * freeze a track, swap a machine, or leave it alone.
     */
    /**
     * Was this rack playing frozen audio when it set that peak?
     *
     * Read before `worstRackUs`, which clears. It exists because the readout
     * said Pad 0.92 and Keys 0.83 on a phone where both were frozen, and a
     * frozen rack measures 1.5 us off-device - so the list could not say
     * whether the freeze was not working or the number was not about the
     * freeze. Now it says which.
     */
    /**
     * How often a block is interrupted rather than slow, 0 to 100.
     *
     * The peaks above are only taken from blocks that ran uninterrupted, so
     * this is also how much of the picture they are not seeing. High here
     * means the fix is priority and scheduling; low here with a high worst
     * block means the fix is the DSP.
     */
    float interruptedPercent() const { return interruptedPct.load(std::memory_order_relaxed); }

    bool worstRackWasFrozen(int32_t rack) const {
        return rack >= 0 && rack < kRackCount && rackPeakFrozen[rack].load(std::memory_order_relaxed);
    }

    int32_t worstRackUs(int32_t rack) {
        return rack >= 0 && rack < kRackCount ? rackPeak[rack].exchange(0, std::memory_order_relaxed) : 0;
    }

    /**
     * What a rack costs, as a distribution rather than as its worst moment.
     *
     * **A peak over a whole song is the least repeatable number there is.**
     * Three runs of one build on the same phone put the per-track peaks 3% to
     * 26% apart, and the worst block 19% - so a change worth twenty per cent,
     * which is most of what is left to find, could not be told from the same
     * build measured twice. One unlucky block sets a peak for the rest of the
     * run and nothing afterwards can lower it.
     *
     * A high percentile does not have that problem: it needs one per cent of
     * the blocks to agree before it moves, so an interrupt or a scene change
     * cannot carry it. The engine keeps a coarse histogram per rack - eight
     * buckets an octave, so a bucket is about nine per cent, finer than
     * anything worth acting on - and this reads a percentile out of it.
     *
     * Counted only over blocks where the rack did something, so a track that
     * plays in one scene reports what it costs *while playing* rather than
     * being averaged down by the scenes it sits out.
     *
     * Not cleared by reading: any number of readers may ask. [resetRackCosts]
     * is the reset button.
     */
    int32_t rackPercentileUs(int32_t rack, int32_t perMille = 990) const;
    /**
     * The key an effect on rack [self] hears from rack [source]: null for its
     * own input (no source, or itself), silence for a rack with nothing on it,
     * and otherwise that rack's pre-fader tap. [self] is -1 for a send.
     */
    const float *keyFor(int32_t source, int32_t self) const;
    /**
     * The order racks render in this block: every sidechain source before its
     * listeners. Groups live in the master, after every rack, so need no place.
     */
    void sidechainOrder(int32_t *order) const;
    /** Where each rack's output goes this block; see `Rack::routedTo`. */
    void settleRouting();

    void resetRackCosts();

    /**
     * What this rack has been costing lately, in microseconds - read without
     * clearing, so anything may ask as often as it likes.
     *
     * The peak-hold above is cleared by reading, which is right for "the worst
     * since you opened this page" and wrong for a light on the screen: two
     * readers would rob each other and the grid would flicker against the
     * settings page. This one decays on the audio thread instead, a fixed
     * amount per block, so it falls at the same rate no matter who is looking
     * or how often.
     */
    int32_t rackCostUs(int32_t rack) const {
        return rack >= 0 && rack < kRackCount ? rackRecent[rack].load(std::memory_order_relaxed) : 0;
    }

    /** The same, for the five phases of a block. Cleared by reading. */
    int32_t worstPhaseUs(Phase p) {
        const auto i = static_cast<size_t>(p);
        return i < kPhases ? phasePeak[i].exchange(0, std::memory_order_relaxed) : 0;
    }

    // Input, monitoring and recording. Set from the UI thread, read on the
    // audio thread; plain atomics because they are single values.
    std::atomic<float> inputGain{1.0f};
    /**
     * The tuner, which listens to the input before anything touches it.
     *
     * Before the gain and before the input chain on purpose: you tune an
     * instrument, not a recording, and the chain may hold a gate that has
     * shut on a string being plucked gently or an amp that has buried the
     * fundamental under a cabinet. Off until the record window asks, and
     * while it is off `push` returns on its first line.
     */
    audio::Tuner tuner;
    /**
     * Which pair the swing bends: `Swing::kSixteenths` or `kEighths`.
     *
     * Song-wide, because it is a feel rather than a setting - a song that
     * shuffles its eighths and its sixteenths at once is two songs. The
     * amount is per track and rides the channel; only the unit lives here.
     */
    std::atomic<int64_t> swingPair{seq::Swing::kSixteenths};
    std::atomic<float> monitorLevel{0.0f};
    /**
     * The input chain: what the incoming audio goes through before anything
     * sees it - the recorder, the monitor, and every machine that reads the
     * input bus.
     *
     * Deliberately before `InputBus::publish` rather than anywhere later,
     * because that is what makes "printed into the take" true without the
     * capture having to know anything about effects.
     */
    Effect *inputFx[kInputSlots] = {nullptr, nullptr};

    Capture capture;
    /**
     * Where the song was as the capture was made - see CaptureMarks.
     *
     * The rack is what makes it meaningful: in clip mode every rack is
     * somewhere else, so "where the song is" is only a question about one of
     * them. `kNoRack` means nobody is armed and nothing is stamped, which is
     * every recording made before audio tracks existed.
     */
    static constexpr int32_t kNoRack = -1;
    seq::CaptureMarks marks;
    std::atomic<int32_t> armedRack{kNoRack};
    /** Playing one file to hear what it is - see engine/core/Audition.h. */
    Audition audition;

    /**
     * Stop everything, now. Set from any thread; acted on at the next block
     * boundary, because that is the only safe moment to reset a machine that
     * the audio thread is otherwise in the middle of.
     */
    std::atomic<bool> panicFlag{false};
    // Frames left of a count-in, and the frames-per-tick it was measured
    // at, so the screen can be told about it in ticks.
    double countInFrames = 0.0;
    double countInPerTick = 0.0;

    /**
     * Armed, running, and past the count-in.
     *
     * The transport's own isRecording() is playing-and-armed and knows
     * nothing about a count-in, during which the transport *is* playing but
     * the scheduler has not started - so a note or a wheel moved while the
     * clicks are still counting would be filed against whatever position
     * the last pass left behind. Counting you in is not recording you.
     */
    bool recordingNow() const { return transport.isRecording() && countInFrames <= 0.0; }
    /**
     * The last moments of the count-in, where a note is *early* rather than
     * unwanted.
     *
     * A player counted in does not arrive exactly on the beat; they arrive a
     * little before it, and the take is supposed to start with that note.
     * Dropping it - which is what gating the recorder on the count-in alone
     * does - loses the first chord of every live take, and no amount of care
     * fixes it, because the error is a human one. Dan: "when I think the
     * recording starts it doesn't record the first notes".
     *
     * A thirty-second note before the downbeat, which scales with the tempo
     * the way a player's sense of "just before" does.
     */
    bool countInPreRoll() const {
        return transport.isRecording() && countInFrames > 0.0 && countInFrames <= preRollFrames;
    }
    /**
     * An early note, held until the scheduler has started.
     *
     * It cannot be filed at the moment it is played: during the count-in the
     * scheduler is deliberately not running, so it has no scene to name and
     * the far end would drop the event as belonging to nothing. Kept here for
     * at most a thirty-second note, then pushed with a tick of zero once the
     * scene is known.
     */
    struct EarlyNote {
        int32_t rack = 0;
        uint8_t status = 0, d1 = 0, d2 = 0;
    };
    static constexpr int32_t kMaxEarlyNotes = 16;
    static constexpr int32_t kPreRollTicks = kPPQN / 8; // a thirty-second
    EarlyNote earlyNotes[kMaxEarlyNotes];
    int32_t earlyCount = 0;
    double preRollFrames = 0.0;
    float inputScratch[kBlockFrames * 2] = {};
    /** The input chain, on the block about to be published. */
    void runInputChain();
    void onModifiedNote(int32_t rack, uint8_t status, uint8_t d1, uint8_t d2) override;

  private:
    void applyMounts();
    void applyMount(const Mount &m);
    static constexpr int32_t kMaxMountsPerBlock = 8;
    void drainMidi();
    /** A member channel's expression, filed against the note it belongs to. */
    void recordExpression(int32_t rack, uint8_t channel, uint8_t note, uint8_t status, uint8_t d1, uint8_t d2);
    /** The last value each member channel filed, per curve; -1 for none yet. */
    float lastExprSent[16][3] = {};

    // Which note each member channel is holding, or -1. MPE puts one note
    // on a channel at a time, so this is exact rather than a guess - it is
    // how a bend arriving on channel 4 finds the finger that made it.
    int32_t mpeChannelNote[16] = {-1, -1, -1, -1, -1, -1, -1, -1,
                                  -1, -1, -1, -1, -1, -1, -1, -1};
    int32_t mpeKind = 0;
    int32_t mpeMembers = 15;
    float mpeBendSemis = 48.0f;
    void drainParams();
    /** A rack's parameter, applied: moved, and recorded if it is a gesture while recording. */
    void applyRackParam(const ParamMessage &p);
    /** What is waiting for a bar line on each rack; see `ParamMessage::quantise`. */
    struct PendingParam {
        bool waiting = false;
        ParamMessage message;
        int64_t due = 0; // the clock's own tick
    };
    PendingParam pendingParams[kRackCount];

    RtQueue<Mount, 64> mounts;
    RtQueue<MidiMessage, 256> midiIn;
    RtQueue<ParamMessage, 512> paramsIn;

    void emitClock(int64_t blockStartTick, int64_t blockEndTick);
    void drainClockIn();
    void followExternal();
    void followTimebase();
    void emitTransport(bool nowPlaying);

    bool playing = false;
    bool startPending = false;
    /** Playing was asked for, and we are waiting for the session's downbeat. */
    bool linkWaiting = false;
    /** Was anybody else in the session at the last capture? */
    bool linkInSession = false;
    /** What we last told the session we were doing, for the edge. */
    bool linkToldPlaying = false;
    /** What the session was last seen doing, and whether we have looked yet. */
    bool linkSawPlaying = false;
    bool linkSeen = false;
    /** Frames the engine has produced since the stream started - the same
     *  timeline the audio stream presents on, which is what lets a MIDI
     *  event's frame become a wall-clock time on the far side. */
    int64_t framesRendered = 0;
    int64_t lastClockTick = -1;
    std::atomic<float> load{0.0f};
    std::atomic<int32_t> blockPeak{0};
    std::atomic<int32_t> phasePeak[kPhases]{};
    /**
     * Wall time beyond CPU time that still counts as an uninterrupted block.
     *
     * Not nought: the two clock reads cost something themselves, and a cache
     * miss or an interrupt costs a little more. 50 us is 4% of a block at 48k,
     * which is far below anything a deschedule costs and far above the noise.
     */
    static constexpr int32_t kPreemptedUs = 50;
    std::atomic<float> interruptedPct{0.0f};
    std::atomic<int32_t> rackPeak[kRackCount]{};
    /**
     * Eight buckets an octave, up to about four milliseconds.
     *
     * The index is the leading bit's position and the three bits under it,
     * which is a `clz` and two shifts - no `log`, on the audio thread, once
     * per rack per block.
     */
    static constexpr int32_t kCostBuckets = 96;
    std::atomic<int32_t> rackHist[kRackCount][kCostBuckets]{};

    /** Which bucket a cost falls in, and the cost a bucket stands for. */
    static int32_t bucketOf(int32_t us) {
        if (us <= 0) return 0;
        const auto v = static_cast<uint32_t>(us);
        const int32_t oct = 31 - __builtin_clz(v);
        const int32_t sub = oct >= 3 ? static_cast<int32_t>((v >> (oct - 3)) & 7u)
                                     : static_cast<int32_t>((v << (3 - oct)) & 7u);
        const int32_t idx = oct * 8 + sub;
        return idx < kCostBuckets ? idx : kCostBuckets - 1;
    }
    static int32_t usOf(int32_t bucket) {
        const int32_t oct = bucket / 8, sub = bucket % 8;
        // The top of the bucket, so a reading is never under what was seen.
        const int32_t top = 8 + sub + 1;
        return oct >= 3 ? (top << (oct - 3)) : (top >> (3 - oct));
    }
    std::atomic<bool> rackPeakFrozen[kRackCount]{};
    std::atomic<int32_t> rackRecent[kRackCount]{};

    /**
     * A peak that falls by itself: `max(now, previous * k)`.
     *
     * 249/250 a block is about half a second to fall to a third, which is slow
     * enough to see and fast enough to follow a scene change.
     */
    static void keepDecaying(std::atomic<int32_t> &slot, int32_t us) {
        const int32_t was = slot.load(std::memory_order_relaxed);
        const int32_t faded = static_cast<int32_t>(static_cast<int64_t>(was) * 249 / 250);
        slot.store(us > faded ? us : faded, std::memory_order_relaxed);
    }

    /** Raise a peak-hold to [us] if it is higher. Relaxed: nothing orders on it. */
    static void keepPeak(std::atomic<int32_t> &slot, int32_t us) {
        int32_t seen = slot.load(std::memory_order_relaxed);
        while (us > seen && !slot.compare_exchange_weak(seen, us, std::memory_order_relaxed)) {
        }
    }
};

} // namespace acidulous
