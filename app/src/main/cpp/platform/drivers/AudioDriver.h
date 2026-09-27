#pragma once
#include <cstdint>
#include <platform/android/PerfHint.h>
#include <atomic>
#include <ctime>
#include <engine/core/Constants.h>
#include <functional>
#include <oboe/Oboe.h>
#include <string>
#include <vector>

// The Oboe output stream. The engine renders fixed blocks of kBlockFrames but
// AAudio uses whatever burst size the device likes, so onAudioReady() pulls
// engine blocks and serves them out through a carry buffer. start() opens the
// default output and the platform handles routing.

class AudioDriver : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
  public:
    AudioDriver() = default;
    ~AudioDriver() override;

    AudioDriver(const AudioDriver &) = delete;
    AudioDriver &operator=(const AudioDriver &) = delete;

    // Must be called before start().
    void registerCallback(std::function<void(float *, float *, unsigned long)> cb) {
        callback = std::move(cb);
    }

    bool start();
    void stop();

    // Input for recording and the vocoder. The input stream is only opened
    // when needed and is read inside the output callback, so there's one
    // audio thread and no drift between two callbacks.
    /**
     * Start the input. [deviceId] is one from the platform's list, or 0 for
     * the system default.
     *
     * Asking again with a different device reopens the stream. Asking for the
     * one already open does nothing.
     */
    bool startInput(int32_t deviceId = 0);
    void stopInput();
    bool isInputRunning() const { return inputStream != nullptr; }
    int32_t inputChannels() const { return actualInputChannels; }
    /** The rate the stream actually opened at, which may differ from what was asked. */
    int32_t inputRate() const { return actualInputRate; }
    int32_t inputDevice() const { return actualInputDevice; }
    /**
     * The input peak. Each read decays it instead of clearing it, so several
     * meters (the recording screen and a panel's input meter) can poll it
     * without stealing from each other. About 3 dB per 100 ms at the panel's
     * poll rate.
     */
    float readInputPeak() {
        const float now = inputPeak.load(std::memory_order_relaxed);
        inputPeak.store(now * kMeterDecay, std::memory_order_relaxed);
        return now;
    }

    bool isRunning() const { return stream != nullptr; }

    /**
     * How many bursts deep the output buffer is. 1 is the tightest and will
     * glitch on a slow phone, 2 is the default, more is safer but slower.
     * Applied right away to an open stream and kept for the next one.
     */
    void setBufferBursts(int32_t bursts);
    int32_t getBufferBursts() const { return bufferBursts; }
    int32_t getBufferFrames() const;

    // What the device actually gave us, which may differ from what we asked for.
    int32_t getSampleRate() const { return actualSampleRate; }
    int32_t getFramesPerBurst() const { return actualFramesPerBurst; }
    bool isLowLatency() const { return actualLowLatency; }
    int64_t getXRunCount() const;
    /** Whether the performance hint is running, and why not if it isn't. */
    bool hintRunning() const { return perfHint.running(); }
    bool hintAvailable() const { return perfHint.available(); }
    int32_t hintState() const { return static_cast<int32_t>(perfHint.state()); }
    void setHintWanted(bool on) { hintWanted.store(on, std::memory_order_relaxed); }

    /** Worst callback since the last read, in microseconds. Reading clears it. */
    int32_t readCallbackPeakUs() { return callbackPeakUs.exchange(0, std::memory_order_relaxed); }
    /** Decaying version, safe for any number of readers. */
    int32_t recentCallbackUs() const { return callbackRecentUs.load(std::memory_order_relaxed); }
    /** The CPU time of the worst callback. Far below the wall figure means preemption. */
    int32_t readCallbackCpuPeakUs() { return callbackCpuPeakUs.exchange(0, std::memory_order_relaxed); }
    /** Late callbacks that were late without using the CPU (descheduled). */
    int64_t getStalledCallbacks() const { return stalledCallbacks.load(std::memory_order_relaxed); }
    /** Callbacks that overran their budget since the engine started. */
    int64_t getLateCallbacks() const { return lateCallbacks.load(std::memory_order_relaxed); }
    /** The callback's budget in microseconds, from the stream's rate and burst. */
    int32_t callbackBudgetUs() const {
        const int32_t rate = actualSampleRate > 0 ? actualSampleRate : acidulous::kSampleRate;
        const int32_t frames = actualFramesPerBurst > 0 ? actualFramesPerBurst : acidulous::kBlockFrames;
        return static_cast<int32_t>(static_cast<int64_t>(frames) * 1000000 / rate);
    }

    /**
     * When the audio being written now will actually be heard.
     *
     * One (frame, nanosecond) pair from the stream lets any future frame be
     * converted to clock time. That's how MIDI sent to hardware lines up with
     * the app's own audio instead of arriving early.
     *
     * Returns false until the stream has run long enough to know.
     */
    bool presentationAnchor(int64_t &frame, int64_t &nanos) const {
        const int32_t s = anchorSlot.load(std::memory_order_acquire);
        if (anchors[s].frame < 0) {
            return false;
        }
        frame = anchors[s].frame;
        nanos = anchors[s].nanos;
        return true;
    }

    /** The master output peak, decayed on read like readInputPeak. */
    float readPeakLevel() {
        const float now = peakLevel.load(std::memory_order_relaxed);
        peakLevel.store(now * kMeterDecay, std::memory_order_relaxed);
        return now;
    }

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream,
                                          void *audioData,
                                          int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result result) override;

  private:
    std::shared_ptr<oboe::AudioStream> stream;
    std::shared_ptr<oboe::AudioStream> inputStream;
    std::function<void(float *, float *, unsigned long)> callback;

    // Carry buffer: one engine block of interleaved stereo, partially drained.
    std::vector<float> carry;
    int32_t carryFrames = 0;  // frames still unread in `carry`
    int32_t carryOffset = 0;  // read cursor, in frames

    // Input waiting to be passed to the engine one block at a time. Sized
    // once, here, so the callback never sees a buffer being resized. Touched
    // on the audio thread while the input is live, and by startInput and
    // stopInput only while it isn't.
    static constexpr int32_t kInputRingFrames = acidulous::kSampleRate / 4; // a quarter second
    std::vector<float> inputRing = std::vector<float>(static_cast<size_t>(kInputRingFrames) * 2); // interleaved stereo
    int32_t inputRingFrames = 0;
    int32_t inputRingRead = 0;
    std::vector<float> inputScratch = std::vector<float>(static_cast<size_t>(kInputRingFrames) * 2);
    std::vector<float> inputBlock = std::vector<float>(static_cast<size_t>(acidulous::kBlockFrames) * 2);
    /**
     * The input as the callback sees it. startInput sets it once the stream
     * and buffers are ready. stopInput clears it, then waits for any callback
     * still using it before closing the stream. Dekker-style, like the web
     * driver's stand-in: the callback sets inputBusy before reading
     * liveInput, stopInput clears liveInput before reading inputBusy, and
     * both are sequentially consistent, so one of them always sees the other.
     */
    std::atomic<oboe::AudioStream *> liveInput{nullptr};
    std::atomic<bool> inputBusy{false};
    int32_t actualInputChannels = 0;
    int32_t actualInputRate = 0;
    int32_t actualInputDevice = 0;
    /** How much of the peak each meter read leaves (see readInputPeak). */
    static constexpr float kMeterDecay = 0.7f;
    std::atomic<float> inputPeak{0.0f};

    void pumpInput(oboe::AudioStream *input, int32_t frames);
    const float *nextInputBlock(bool live);

    // Double-buffered so a reader never sees half of a pair. Two 64-bit values
    // can't share an atomic and a seqlock would be overkill.
    struct Anchor {
        int64_t frame = -1;
        int64_t nanos = 0;
    };
    Anchor anchors[2];
    std::atomic<int32_t> anchorSlot{0};

    // Callback timing below covers the whole callback, not just the engine's
    // render (the input pump, carry copy and peak loop too). lateCallbacks is
    // cumulative, unlike Oboe's xrun count which resets when the stream
    // reopens.
    /**
     * Performance hint session. It needs the audio thread's id, so the
     * callback publishes it and start() opens the session on another thread.
     */
    acidulous::platform::PerfHint perfHint;
    std::atomic<int32_t> audioThreadId{0};
    std::atomic<bool> hintWanted{true};
    /**
     * Which stream the waiting hint thread belongs to. The stream can be
     * restarted while it sleeps (a freeze does this), and without this check
     * it would open a session for a thread that's gone.
     */
    std::atomic<int32_t> hintGeneration{0};

    std::atomic<int32_t> callbackPeakUs{0};
    /**
     * The same peak, decaying on the audio thread instead of being cleared on
     * read, so the status line and the load meter can both watch it.
     */
    std::atomic<int32_t> callbackRecentUs{0};
    std::atomic<int32_t> callbackCpuPeakUs{0};
    std::atomic<int64_t> lateCallbacks{0};
    std::atomic<int64_t> stalledCallbacks{0};

    /** This thread's CPU time in microseconds, not wall clock. */
    static int64_t threadCpuUs() {
        timespec ts{};
        if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts) != 0) return 0;
        return static_cast<int64_t>(ts.tv_sec) * 1000000 + ts.tv_nsec / 1000;
    }

    /** How long [frames] of audio lasts in microseconds at the stream's actual rate. */
    int32_t budgetFor(int32_t frames) const {
        const int32_t rate = actualSampleRate > 0 ? actualSampleRate : acidulous::kSampleRate;
        return static_cast<int32_t>(static_cast<int64_t>(frames) * 1000000 / rate);
    }

    int32_t engineBlockFrames = 0;
    int32_t bufferBursts = 2;
    int32_t actualSampleRate = 0;
    int32_t actualFramesPerBurst = 0;
    bool actualLowLatency = false;
    std::atomic<float> peakLevel{0.0f};
};
