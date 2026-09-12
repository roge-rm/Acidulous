#pragma once
#include "MasterBus.h"
#include "Rack.h"
#include <atomic>
#include <chrono>
#include <engine/core/Capture.h>
#include <engine/core/InputBus.h>
#include <engine/core/Handover.h>
#include <engine/core/Messages.h>
#include <engine/core/RtQueue.h>
#include <sequencer/RecordQueue.h>
#include <sequencer/SceneScheduler.h>
#include <sequencer/TickClock.h>
#include <sequencer/Transport.h>

// The engine: everything the audio thread owns, and the queues by which the
// rest of the app talks to it. renderBlock() is the audio thread's entry.
namespace acidulous {

class Engine {
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
    MasterBus master;
    Rack racks[kRackCount];
    Retirer retirer;

    float loadPercent() const { return load.load(std::memory_order_relaxed); }

    // Input, monitoring and recording. Set from the UI thread, read on the
    // audio thread; plain atomics because they are single values.
    std::atomic<float> inputGain{1.0f};
    std::atomic<float> monitorLevel{0.0f};
    Capture capture;

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
    float inputScratch[kBlockFrames * 2] = {};

  private:
    void applyMounts();
    void applyMount(const Mount &m);
    static constexpr int32_t kMaxMountsPerBlock = 8;
    void drainMidi();
    void drainParams();

    RtQueue<Mount, 64> mounts;
    RtQueue<MidiMessage, 256> midiIn;
    RtQueue<ParamMessage, 512> paramsIn;

    void emitClock(int64_t blockStartTick, int64_t blockEndTick);
    void drainClockIn();
    void followExternal();
    void emitTransport(bool nowPlaying);

    bool playing = false;
    bool startPending = false;
    /** Frames the engine has produced since the stream started - the same
     *  timeline the audio stream presents on, which is what lets a MIDI
     *  event's frame become a wall-clock time on the far side. */
    int64_t framesRendered = 0;
    int64_t lastClockTick = -1;
    std::atomic<float> load{0.0f};
};

} // namespace acidulous
