#pragma once
#include <atomic>
#include <cstdint>
#include <engine/core/Constants.h>
#include <functional>
#include <thread>

// An audio driver with no device, for hosts that replay engine lines and
// render offline (tools/engine_replay). A thread renders blocks into nothing
// at about the speed a device would, so the engine takes its mounts and
// parameters the way it does in the app. A render takes the engine off it and
// pulls blocks by hand, as with any driver.
//
// Same class and public API as the other drivers (platform/drivers,
// platform/desktop/drivers, platform/web/drivers), so EngineHost builds
// against it unchanged.

class AudioDriver {
  public:
    AudioDriver() = default;
    ~AudioDriver() { stop(); }

    AudioDriver(const AudioDriver &) = delete;
    AudioDriver &operator=(const AudioDriver &) = delete;

    void registerCallback(std::function<void(float *, float *, unsigned long)> cb) { callback = std::move(cb); }

    bool start();
    void stop();
    /** Blocks rendered by any driver's thread so far, so a host can wait for the queues to drain. */
    static int64_t blocks() { return rendered.load(std::memory_order_acquire); }

    bool startInput(int32_t = 0) { return false; }
    void stopInput() {}
    void setInputClean(bool) {}
    int32_t inputSession() const { return 0; }
    bool isInputRunning() const { return false; }
    int32_t inputChannels() const { return 0; }
    int32_t inputRate() const { return 0; }
    int32_t inputDevice() const { return 0; }
    float readInputPeak() { return 0.0f; }

    bool isRunning() const { return running.load(); }
    void setBufferBursts(int32_t) {}
    int32_t getBufferFrames() const { return acidulous::kBlockFrames * 2; }
    int32_t getSampleRate() const { return acidulous::kSampleRate; }
    int32_t getFramesPerBurst() const { return acidulous::kBlockFrames; }
    bool isLowLatency() const { return false; }
    int64_t getXRunCount() const { return 0; }
    bool hintRunning() const { return false; }
    bool hintAvailable() const { return false; }
    int32_t hintState() const { return 0; }
    int32_t fastCores() const { return 0; }
    void setHintWanted(bool) {}
    int32_t readCallbackPeakUs() { return 0; }
    int32_t recentCallbackUs() const { return 0; }
    int32_t readCallbackCpuPeakUs() { return 0; }
    int64_t getStalledCallbacks() const { return 0; }
    int64_t getLateCallbacks() const { return 0; }
    int32_t callbackBudgetUs() const {
        return static_cast<int32_t>(static_cast<int64_t>(acidulous::kBlockFrames) * 1000000 / acidulous::kSampleRate);
    }
    bool presentationAnchor(int64_t &, int64_t &) const { return false; }
    float readPeakLevel() { return 0.0f; }

  private:
    std::function<void(float *, float *, unsigned long)> callback;
    std::thread thread;
    std::atomic<bool> running{false};
    static std::atomic<int64_t> rendered;
};
