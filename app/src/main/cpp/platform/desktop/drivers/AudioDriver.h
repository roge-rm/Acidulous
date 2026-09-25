#pragma once
#include <atomic>
#include <ctime>
#include <engine/core/Constants.h>
#include <functional>
#include <memory>
#include <vector>

// The desktop's output stream, through miniaudio: PulseAudio first on Linux
// (PipeWire answers as it), then ALSA, then JACK.
//
// The same class, and the same public surface, as the Oboe driver in
// platform/drivers - EngineHost builds against either without knowing which -
// and the same bookkeeping: fixed engine blocks served out through a carry
// buffer, the input read inside the output callback, peak meters that decay
// rather than clear, and the callback timed on the wall clock and the CPU
// clock both. What a desktop does not have is a scheduler hint, so that part
// always says it is unavailable; and it has no presentation timestamp, so
// the anchor is the frames written and the buffer's own depth.

struct ma_device;

class AudioDriver {
  public:
    AudioDriver();
    ~AudioDriver();

    AudioDriver(const AudioDriver &) = delete;
    AudioDriver &operator=(const AudioDriver &) = delete;

    // Must be called before start().
    void registerCallback(std::function<void(float *, float *, unsigned long)> cb) {
        callback = std::move(cb);
    }

    bool start();
    void stop();

    /** Open the ear: nought is the system's default input, the only one offered yet. */
    bool startInput(int32_t deviceId = 0);
    void stopInput();
    bool isInputRunning() const { return capturer != nullptr; }
    int32_t inputChannels() const { return actualInputChannels; }
    int32_t inputRate() const { return actualInputRate; }
    int32_t inputDevice() const { return 0; }
    /** Decayed rather than cleared, so several meters can read it: see the Oboe driver. */
    float readInputPeak() {
        const float now = inputPeak.load(std::memory_order_relaxed);
        inputPeak.store(now * kMeterDecay, std::memory_order_relaxed);
        return now;
    }

    bool isRunning() const { return device != nullptr; }

    /** The number of periods the output keeps queued; applied by reopening the stream. */
    void setBufferBursts(int32_t bursts);
    int32_t getBufferBursts() const { return bufferBursts; }
    int32_t getBufferFrames() const { return actualFramesPerBurst * actualPeriods; }

    int32_t getSampleRate() const { return actualSampleRate; }
    int32_t getFramesPerBurst() const { return actualFramesPerBurst; }
    bool isLowLatency() const { return true; }
    /** miniaudio does not count them; the late count below is ours and does. */
    int64_t getXRunCount() const { return 0; }
    bool hintRunning() const { return false; }
    bool hintAvailable() const { return false; }
    int32_t hintState() const { return 0; }
    void setHintWanted(bool) {}

    int32_t readCallbackPeakUs() { return callbackPeakUs.exchange(0, std::memory_order_relaxed); }
    int32_t recentCallbackUs() const { return callbackRecentUs.load(std::memory_order_relaxed); }
    int32_t readCallbackCpuPeakUs() { return callbackCpuPeakUs.exchange(0, std::memory_order_relaxed); }
    int64_t getStalledCallbacks() const { return stalledCallbacks.load(std::memory_order_relaxed); }
    int64_t getLateCallbacks() const { return lateCallbacks.load(std::memory_order_relaxed); }
    int32_t callbackBudgetUs() const {
        const int32_t rate = actualSampleRate > 0 ? actualSampleRate : acidulous::kSampleRate;
        const int32_t frames = actualFramesPerBurst > 0 ? actualFramesPerBurst : acidulous::kBlockFrames;
        return static_cast<int32_t>(static_cast<int64_t>(frames) * 1000000 / rate);
    }

    /** When the audio being written now will be heard: see the Oboe driver. */
    bool presentationAnchor(int64_t &frame, int64_t &nanos) const {
        const int32_t s = anchorSlot.load(std::memory_order_acquire);
        if (anchors[s].frame < 0) return false;
        frame = anchors[s].frame;
        nanos = anchors[s].nanos;
        return true;
    }

    float readPeakLevel() {
        const float now = peakLevel.load(std::memory_order_relaxed);
        peakLevel.store(now * kMeterDecay, std::memory_order_relaxed);
        return now;
    }

    // miniaudio's callbacks; public only so the C trampolines can reach them.
    void render(float *out, int32_t numFrames);
    void capture(const float *in, int32_t numFrames);

  private:
    std::unique_ptr<ma_device> device;
    std::unique_ptr<ma_device> capturer;
    struct InputQueue;                        // miniaudio's ring, capture thread -> output callback, lock-free
    std::unique_ptr<InputQueue> inputQueue;
    std::function<void(float *, float *, unsigned long)> callback;

    std::vector<float> carry;
    int32_t carryFrames = 0;
    int32_t carryOffset = 0;

    std::vector<float> inputRing;
    int32_t inputRingFrames = 0;
    int32_t inputRingRead = 0;
    std::vector<float> inputScratch;
    std::vector<float> inputBlock;
    int32_t actualInputChannels = 0;
    int32_t actualInputRate = 0;
    static constexpr float kMeterDecay = 0.7f;
    std::atomic<float> inputPeak{0.0f};

    void pumpInput(int32_t frames);
    const float *nextInputBlock();

    struct Anchor {
        int64_t frame = -1;
        int64_t nanos = 0;
    };
    Anchor anchors[2];
    std::atomic<int32_t> anchorSlot{0};
    int64_t framesWritten = 0;

    std::atomic<int32_t> callbackPeakUs{0};
    std::atomic<int32_t> callbackRecentUs{0};
    std::atomic<int32_t> callbackCpuPeakUs{0};
    std::atomic<int64_t> lateCallbacks{0};
    std::atomic<int64_t> stalledCallbacks{0};

    static int64_t threadCpuUs() {
        timespec ts{};
        if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts) != 0) return 0;
        return static_cast<int64_t>(ts.tv_sec) * 1000000 + ts.tv_nsec / 1000;
    }
    int32_t budgetFor(int32_t frames) const {
        const int32_t rate = actualSampleRate > 0 ? actualSampleRate : acidulous::kSampleRate;
        return static_cast<int32_t>(static_cast<int64_t>(frames) * 1000000 / rate);
    }

    int32_t engineBlockFrames = 0;
    int32_t bufferBursts = 2;
    int32_t actualSampleRate = 0;
    int32_t actualFramesPerBurst = 0;
    int32_t actualPeriods = 0;
    std::atomic<float> peakLevel{0.0f};
};
