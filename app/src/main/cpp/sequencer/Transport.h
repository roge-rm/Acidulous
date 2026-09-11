#pragma once
#include <atomic>
#include <cstdint>
#include <engine/core/Constants.h>

// Play/stop state plus the handshake that moves it across threads.
// The UI *requests*; the audio thread *applies* at a block boundary, so state
// never changes in the middle of a render.

namespace acidulous::seq {

class Transport {
  public:
    enum class Request : uint8_t { None, Play, Stop };
    static constexpr int32_t kCurrentScene = -1;

    // --- UI thread ------------------------------------------------------------
    // Play from the top of `sceneIdx`, or of whichever scene the scheduler is
    // on when kCurrentScene.
    void requestPlay(int32_t sceneIdx = kCurrentScene) {
        startScene.store(sceneIdx, std::memory_order_relaxed);
        request.store(Request::Play, std::memory_order_release);
    }
    void requestStop() { request.store(Request::Stop, std::memory_order_release); }

    /**
     * Let the current scene finish the repeats it owes and then stop. Armed
     * from the UI, disarmed by arming it off, by any play or stop request, or
     * by the scheduler when it fires.
     */
    void setStopAtEnd(bool on) {
        stopAtEndFlag.store(on, std::memory_order_relaxed);
        if (on) queuedScene.store(-1, std::memory_order_relaxed); // the two are alternatives
    }
    bool stopAtEndArmed() const { return stopAtEndFlag.load(std::memory_order_relaxed); }

    /**
     * Line a scene up to start when the current one has finished the repeats
     * it owes. -1 cancels. Queuing and arming a finish are alternatives, so
     * each clears the other.
     */
    void queueScene(int32_t idx) {
        queuedScene.store(idx, std::memory_order_relaxed);
        if (idx >= 0) stopAtEndFlag.store(false, std::memory_order_relaxed);
    }
    int32_t queuedSceneIndex() const { return queuedScene.load(std::memory_order_relaxed); }

    // The reference sequencer's two loop toggles: the transport button loops the current scene;
    // the one beside Add Scene loops the whole song.
    void setLoopScene(bool on) { loopSceneFlag.store(on, std::memory_order_relaxed); }

    // The reference sequencer's REC is a stand-by: arm now, and playing (or starting to play)
    // records. Disarm at any time.
    void setRecordArmed(bool on) { recordArmed.store(on, std::memory_order_relaxed); }
    bool isRecordArmed() const { return recordArmed.load(std::memory_order_relaxed); }
    void setLoopSong(bool on) { loopSongFlag.store(on, std::memory_order_relaxed); }

    // --- Clip mode ------------------------------------------------------------
    // The same discipline as queueScene: the UI stores, the audio thread
    // exchanges at a boundary. Sixteen slots instead of one, because in clip
    // mode every rack has its own idea of what happens next.

    void setClockOut(bool on) { clockOutFlag.store(on, std::memory_order_relaxed); }
    bool clockOut() const { return clockOutFlag.load(std::memory_order_relaxed); }

    void setLauncher(bool on) { launcherFlag.store(on, std::memory_order_relaxed); }
    bool launcherMode() const { return launcherFlag.load(std::memory_order_relaxed); }

    /** 0 swaps at the end of the playing clip's cycle; otherwise a tick grid. */
    void setLaunchQuantise(int32_t ticks) { launchQ.store(ticks < 0 ? 0 : ticks, std::memory_order_relaxed); }
    int32_t launchQuantise() const { return launchQ.load(std::memory_order_relaxed); }

    /**
     * A cell was tapped: the *intent*, not the outcome. What it means -
     * start, cancel or stop - is decided on the audio thread against what is
     * actually playing, so a tap can never be interpreted against a readback
     * that is eighty milliseconds stale.
     */
    void launchClip(int32_t rack, int64_t sceneId) {
        if (rack >= 0 && rack < kRackCount) {
            queuedClip[rack].store(sceneId, std::memory_order_relaxed);
        }
    }
    int64_t takeQueuedClip(int32_t rack) {
        return (rack >= 0 && rack < kRackCount) ? queuedClip[rack].exchange(0, std::memory_order_relaxed) : 0;
    }
    void requestStopAll() { stopAllFlag.store(true, std::memory_order_relaxed); }
    bool takeStopAll() { return stopAllFlag.exchange(false, std::memory_order_relaxed); }

    // Per-rack state for the grid: the scene sounding, the scene queued, and
    // how far through its own cycle it is. One atomic each, so a cell never
    // sees a torn pair.
    static constexpr int32_t kNoScene = 0xff;   // nothing there
    static constexpr int32_t kStopQueued = 0xfe; // queued to stop
    static int64_t packLaunch(int32_t scene, int32_t pending, int64_t tickInCycle) {
        return (static_cast<int64_t>(scene & 0xff) << 56) |
               (static_cast<int64_t>(pending & 0xff) << 48) |
               (tickInCycle & 0xffffffffffffLL);
    }
    void publishLaunch(int32_t rack, int64_t packed) {
        if (rack >= 0 && rack < kRackCount) {
            launchForUi[rack].store(packed, std::memory_order_relaxed);
        }
    }
    int64_t launchState(int32_t rack) const {
        return (rack >= 0 && rack < kRackCount) ? launchForUi[rack].load(std::memory_order_relaxed) : 0;
    }
    void clearLaunchRequests() {
        for (auto &q : queuedClip) {
            q.store(0, std::memory_order_relaxed);
        }
        stopAllFlag.store(false, std::memory_order_relaxed);
    }

    // --- Audio thread ---------------------------------------------------------
    // Returns true if the state changed this block.
    /** True once, when the scheduler should stop at this repeat boundary. */
    bool takeStopAtEnd() { return stopAtEndFlag.exchange(false, std::memory_order_relaxed); }
    int32_t takeQueuedScene() { return queuedScene.exchange(-1, std::memory_order_relaxed); }

    bool applyRequests() {
        const Request r = request.exchange(Request::None, std::memory_order_acq_rel);
        if (r == Request::None) {
            return false;
        }
        stopAtEndFlag.store(false, std::memory_order_relaxed); // a new play or stop cancels both
        queuedScene.store(-1, std::memory_order_relaxed);
        // Launch requests are deliberately *not* cleared here. Starting the
        // transport is how the first tapped clip gets to sound at all: the UI
        // queues the clip and then asks for play, and wiping the queue in
        // between would cost that clip a whole cycle.
        const bool wanted = (r == Request::Play);
        if (wanted == playing) {
            return false;
        }
        playing = wanted;
        playingForUi.store(playing, std::memory_order_relaxed);
        return true;
    }
    int32_t requestedStartScene() const { return startScene.load(std::memory_order_relaxed); }
    bool loopScene() const { return loopSceneFlag.load(std::memory_order_relaxed); }
    bool loopSong() const { return loopSongFlag.load(std::memory_order_relaxed); }

    // The song ran out and loopSong is off.
    void stopFromAudioThread() {
        playing = false;
        playingForUi.store(false, std::memory_order_relaxed);
        stopAtEndFlag.store(false, std::memory_order_relaxed);
        queuedScene.store(-1, std::memory_order_relaxed);
        clearLaunchRequests();
    }
    bool isPlaying() const { return playing; }
    bool isRecording() const { return playing && recordArmed.load(std::memory_order_relaxed); }

    // Packed position: scene (8 bits) | repeat (8 bits) | tick within the
    // current iteration (48 bits). One atomic, so the UI never sees a torn
    // scene/tick pair.
    static int64_t pack(int32_t scene, int32_t repeat, int64_t tickInIteration) {
        return (static_cast<int64_t>(scene & 0xff) << 56) |
               (static_cast<int64_t>(repeat & 0xff) << 48) |
               (tickInIteration & 0xffffffffffffLL);
    }
    void publishPosition(int64_t packed) { positionForUi.store(packed, std::memory_order_relaxed); }

    // --- Either thread --------------------------------------------------------
    int64_t position() const { return positionForUi.load(std::memory_order_relaxed); }
    bool isPlayingForUi() const { return playingForUi.load(std::memory_order_relaxed); }

  private:
    std::atomic<Request> request{Request::None};
    std::atomic<int32_t> startScene{kCurrentScene};
    std::atomic<bool> loopSceneFlag{false};
    std::atomic<bool> loopSongFlag{true};
    std::atomic<bool> stopAtEndFlag{false};
    std::atomic<int32_t> queuedScene{-1};
    std::atomic<bool> recordArmed{false};
    std::atomic<bool> launcherFlag{false};
    std::atomic<bool> clockOutFlag{false};
    std::atomic<int32_t> launchQ{0};
    std::atomic<bool> stopAllFlag{false};
    std::atomic<int64_t> queuedClip[kRackCount]{};
    std::atomic<int64_t> launchForUi[kRackCount]{};
    bool playing = false; // audio-thread truth
    std::atomic<bool> playingForUi{false};
    std::atomic<int64_t> positionForUi{0};
};

} // namespace acidulous::seq
