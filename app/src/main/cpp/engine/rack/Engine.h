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
    bool pushParam(const ParamMessage &m) { return paramsIn.push(m); }

    seq::Transport transport;
    seq::TickClock clock;
    seq::SceneScheduler scheduler;
    seq::RecordQueue recordQueue;
    MasterBus master;
    Rack racks[kRackCount];
    Retirer retirer;

    float loadPercent() const { return load.load(std::memory_order_relaxed); }

    // Input, monitoring and recording. Set from the UI thread, read on the
    // audio thread; plain atomics because they are single values.
    std::atomic<float> inputGain{1.0f};
    std::atomic<float> monitorLevel{0.0f};
    Capture capture;
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

    bool playing = false;
    bool startPending = false;
    std::atomic<float> load{0.0f};
};

} // namespace acidulous
