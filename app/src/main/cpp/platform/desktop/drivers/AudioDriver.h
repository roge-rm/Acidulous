#pragma once
#include <cstdint>
#include <atomic>
#include <ctime>
#include <engine/core/Constants.h>
#include <functional>
#include <memory>
#include <string>
#include <vector>

// The desktop audio stream through miniaudio: PulseAudio first on Linux
// (PipeWire shows up as it), then ALSA, then JACK.
//
// Same class and public API as the Oboe driver in platform/drivers so
// EngineHost builds against either, and it works the same way: fixed engine
// blocks through a carry buffer, input read inside the output callback, peak
// meters that decay, and the callback timed in wall clock and CPU time. There
// are no scheduler hints on desktop, so those always say unavailable. There's
// no presentation timestamp either, so the anchor comes from the frames
// written and the buffer depth.

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

    /** One of the sound server's inputs, as the input chooser lists it. */
    struct InputInfo {
        int32_t id;       // a hash of the name, so stable while the name is, never 0
        std::string name; // display name, e.g. "Built-in Audio Analog Stereo"
        std::string key;  // the server's internal name (PulseAudio's "alsa_input.usb-...") or empty
    };
    /** The current inputs. The ids are what startInput takes. */
    static std::vector<InputInfo> listInputs();
    /** The current outputs. The ids are what chooseOutput takes. */
    static std::vector<InputInfo> listOutputs();
    /**
     * Play through the output with this id from listOutputs, 0 for the
     * system default. Kept for the next start, and a running stream is
     * reopened on it right away. An id that isn't there means the default.
     */
    static void chooseOutput(int32_t id);

    /** Start the input: 0 is the system default, anything else an id from listInputs. */
    bool startInput(int32_t deviceId = 0);
    void stopInput();
    /** The sound server gives the input as it is, so there's nothing to switch (see the Oboe driver). */
    void setInputClean(bool) {}
    int32_t inputSession() const { return 0; }
    bool isInputRunning() const { return capturer != nullptr || driverInput.load(); }
    int32_t inputChannels() const { return actualInputChannels; }
    int32_t inputRate() const { return actualInputRate; }
    int32_t inputDevice() const { return actualInputDevice; }
    /** Decays rather than clears so several meters can read it (see the Oboe driver). */
    float readInputPeak() {
        const float now = inputPeak.load(std::memory_order_relaxed);
        inputPeak.store(now * kMeterDecay, std::memory_order_relaxed);
        return now;
    }

    bool isRunning() const { return device != nullptr || driverOn; }

    /** The number of periods the output keeps queued; applied by reopening the stream. */
    void setBufferBursts(int32_t bursts);
    int32_t getBufferBursts() const { return bufferBursts; }
    int32_t getBufferFrames() const { return driverOn ? driverLatency : actualFramesPerBurst * actualPeriods; }

    int32_t getSampleRate() const { return actualSampleRate; }
    int32_t getFramesPerBurst() const { return actualFramesPerBurst; }
    bool isLowLatency() const { return true; }
    /** miniaudio doesn't count xruns. Use the late count below instead. */
    int64_t getXRunCount() const { return 0; }
    bool hintRunning() const { return false; }
    bool hintAvailable() const { return false; }
    int32_t hintState() const { return 0; }
    int32_t fastCores() const { return 0; }
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

    /** When the audio being written now will be heard (see the Oboe driver). */
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

    // miniaudio's callbacks. Public only so the C trampolines can reach them.
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

    // Sized once, here, so the callback never sees a buffer being resized
    // (see the Oboe driver).
    static constexpr int32_t kInputRingFrames = acidulous::kSampleRate / 4; // a quarter second
    std::vector<float> inputRing = std::vector<float>(static_cast<size_t>(kInputRingFrames) * 2);
    int32_t inputRingFrames = 0;
    int32_t inputRingRead = 0;
    std::vector<float> inputScratch = std::vector<float>(static_cast<size_t>(kInputRingFrames) * 2);
    std::vector<float> inputBlock = std::vector<float>(static_cast<size_t>(acidulous::kBlockFrames) * 2);
    /**
     * The capture queue as the output callback sees it, set once the capture
     * is running and cleared before it's taken down. inputBusy is held while
     * a callback might use it or driverInput, and stopInput waits for it,
     * Dekker-style as in the Oboe driver.
     */
    std::atomic<InputQueue *> liveQueue{nullptr};
    std::atomic<bool> inputBusy{false};
    int32_t actualInputChannels = 0;
    int32_t actualInputRate = 0;
    int32_t actualInputDevice = 0;
    static constexpr float kMeterDecay = 0.7f;
    std::atomic<float> inputPeak{0.0f};

    void pumpInput(InputQueue *queue, int32_t frames);
    const float *nextInputBlock(bool live);

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

    /** Restart on the currently chosen output, keeping the input open if it was. */
    void reopen();
    static int32_t sChosenOutput;
    static AudioDriver *sLive;

    // An ASIO driver used instead of miniaudio (Asio.h, Windows only): whether
    // it's open, its input pairs, the pair being read, and its output latency.
    bool driverOn = false;
    std::atomic<bool> driverInput{false};
    std::string driverName;
    std::vector<std::string> driverPairs;
    int32_t driverLatency = 0;
    bool startDriver(const std::string &name);
    /** ASIO input, on the driver's audio thread, straight into the ring the output reads. */
    void pushInput(const float *in, int32_t numFrames);

    int32_t engineBlockFrames = 0;
    int32_t bufferBursts = 2;
    int32_t actualSampleRate = 0;
    int32_t actualFramesPerBurst = 0;
    int32_t actualPeriods = 0;
    std::atomic<float> peakLevel{0.0f};
};
