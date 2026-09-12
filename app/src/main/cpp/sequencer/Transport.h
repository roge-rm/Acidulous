#pragma once
#include <atomic>
#include <cstdint>

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

    // The reference sequencer's two loop toggles: the transport button loops the current scene;
    // the one beside Add Scene loops the whole song.
    void setLoopScene(bool on) { loopSceneFlag.store(on, std::memory_order_relaxed); }

    // The reference sequencer's REC is a stand-by: arm now, and playing (or starting to play)
    // records. Disarm at any time.
    void setRecordArmed(bool on) { recordArmed.store(on, std::memory_order_relaxed); }
    bool isRecordArmed() const { return recordArmed.load(std::memory_order_relaxed); }
    void setLoopSong(bool on) { loopSongFlag.store(on, std::memory_order_relaxed); }

    // --- Audio thread ---------------------------------------------------------
    // Returns true if the state changed this block.
    bool applyRequests() {
        const Request r = request.exchange(Request::None, std::memory_order_acq_rel);
        if (r == Request::None) {
            return false;
        }
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
    std::atomic<bool> recordArmed{false};
    bool playing = false; // audio-thread truth
    std::atomic<bool> playingForUi{false};
    std::atomic<int64_t> positionForUi{0};
};

} // namespace acidulous::seq
