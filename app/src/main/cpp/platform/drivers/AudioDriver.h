#pragma once
#include <platform/android/PerfHint.h>
#include <atomic>
#include <ctime>
#include <engine/core/Constants.h>
#include <functional>
#include <oboe/Oboe.h>
#include <string>
#include <vector>

// The Oboe output stream. The engine renders fixed blocks of kBlockFrames;
// AAudio hands us whatever burst size the device likes, which is rarely 64, so
// onAudioReady() pulls engine blocks and serves them out through a carry
// buffer. Routing is the platform's job: start() opens the default output.

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

    // Recording and the vocoder both want what is coming in. The input
    // stream is opened on demand - a synth has no business holding the
    // microphone open - and is read from inside the output callback rather
    // than running a second callback of its own, so there is one audio
    // thread and no drift to reconcile between two.
    /**
     * Open the ear. [deviceId] names one from the platform's own list, or
     * nought for whatever it would have chosen.
     *
     * Nothing asked for a device until there was a screen to choose one on,
     * so this took no argument and you got the default - which on a phone
     * with a mic, a headset and an interface plugged in is a coin toss the
     * player cannot see, let alone settle.
     *
     * Asking again with a different device reopens the stream; asking for the
     * one already open does nothing.
     */
    bool startInput(int32_t deviceId = 0);
    void stopInput();
    bool isInputRunning() const { return inputStream != nullptr; }
    int32_t inputChannels() const { return actualInputChannels; }
    /** What the stream actually opened at, which is not always what was asked. */
    int32_t inputRate() const { return actualInputRate; }
    int32_t inputDevice() const { return actualInputDevice; }
    /**
     * The loudest thing that has come in since anybody looked - decayed
     * rather than cleared.
     *
     * It used to `exchange(0)`, which works for exactly one reader. The
     * recording screen and a panel's own `input` meter are both pollers, and
     * with a destructive read the two steal from each other and both show a
     * meter that flickers at half height. Decaying by a fixed fraction leaves
     * the same reading for everyone and still falls at a readable rate: this
     * is about three decibels every hundred milliseconds at the panel's poll.
     */
    float readInputPeak() {
        const float now = inputPeak.load(std::memory_order_relaxed);
        inputPeak.store(now * kMeterDecay, std::memory_order_relaxed);
        return now;
    }

    bool isRunning() const { return stream != nullptr; }

    /**
     * How many bursts deep the output buffer is: 1 is as tight as the device
     * allows and will glitch on a slow phone, 2 is the default, more is
     * safer and slower. Applied immediately when a stream is open, and
     * remembered for the next one.
     */
    void setBufferBursts(int32_t bursts);
    int32_t getBufferBursts() const { return bufferBursts; }
    int32_t getBufferFrames() const;

    // What the device actually gave us, which may differ from what we asked.
    int32_t getSampleRate() const { return actualSampleRate; }
    int32_t getFramesPerBurst() const { return actualFramesPerBurst; }
    bool isLowLatency() const { return actualLowLatency; }
    int64_t getXRunCount() const;
    /** Whether the scheduler is being told about our deadline, and why not when it is not. */
    bool hintRunning() const { return perfHint.running(); }
    bool hintAvailable() const { return perfHint.available(); }
    int32_t hintState() const { return static_cast<int32_t>(perfHint.state()); }
    void setHintWanted(bool on) { hintWanted.store(on, std::memory_order_relaxed); }

    /** Worst callback since the last read, in microseconds. Reading clears it. */
    int32_t readCallbackPeakUs() { return callbackPeakUs.exchange(0, std::memory_order_relaxed); }
    /** The decaying one: read as often as you like, by as many as you like. */
    int32_t recentCallbackUs() const { return callbackRecentUs.load(std::memory_order_relaxed); }
    /** The CPU time of the worst callback. Far below the wall figure means preemption. */
    int32_t readCallbackCpuPeakUs() { return callbackCpuPeakUs.exchange(0, std::memory_order_relaxed); }
    /** Of the late callbacks, those that were late without doing the work. */
    int64_t getStalledCallbacks() const { return stalledCallbacks.load(std::memory_order_relaxed); }
    /** Callbacks that overran their own budget, since the engine started. */
    int64_t getLateCallbacks() const { return lateCallbacks.load(std::memory_order_relaxed); }
    /** The callback's budget in microseconds, from the stream's own rate and burst. */
    int32_t callbackBudgetUs() const {
        const int32_t rate = actualSampleRate > 0 ? actualSampleRate : acidulous::kSampleRate;
        const int32_t frames = actualFramesPerBurst > 0 ? actualFramesPerBurst : acidulous::kBlockFrames;
        return static_cast<int32_t>(static_cast<int64_t>(frames) * 1000000 / rate);
    }

    /**
     * When the audio being written now will actually be heard.
     *
     * The engine counts the frames it has rendered; the stream counts the
     * frames it has presented, and the two are the same timeline offset by
     * whatever is sitting in the buffers. One (frame, nanosecond) pair from
     * the stream ties them together, and from it any future frame converts
     * to a wall-clock time - which is the only way MIDI sent to hardware can
     * be made to land with the app's own audio rather than ahead of it.
     *
     * Returns false until the stream has run long enough to have an answer.
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

    // Peak absolute sample seen since the last read, then reset. Lets the UI
    // (and bring-up on a silent emulator) confirm the engine is actually
    // producing signal, and is the basis for a real level meter later.
    /** The master's own, on the same terms - see `readInputPeak`. */
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

    // What the input stream handed us, waiting to be served to the engine
    // one block at a time. Read and written only on the audio thread.
    std::vector<float> inputRing;   // interleaved stereo
    int32_t inputRingFrames = 0;
    int32_t inputRingRead = 0;
    std::vector<float> inputScratch;
    std::vector<float> inputBlock;
    int32_t actualInputChannels = 0;
    int32_t actualInputRate = 0;
    int32_t actualInputDevice = 0;
    /** What a meter read leaves behind. See `readInputPeak`. */
    static constexpr float kMeterDecay = 0.7f;
    std::atomic<float> inputPeak{0.0f};

    void pumpInput(int32_t frames);
    const float *nextInputBlock();

    // Double-buffered so the audio thread can publish a pair without the
    // reader ever seeing half of one. Two 64-bit values cannot share an
    // atomic, and a seqlock is more than this needs.
    struct Anchor {
        int64_t frame = -1;
        int64_t nanos = 0;
    };
    Anchor anchors[2];
    std::atomic<int32_t> anchorSlot{0};

    /**
     * How long the callback took, and how often it ran out of time.
     *
     * The engine times its own render; this times the thing that actually has
     * a deadline. At a burst of 192 the callback renders three engine blocks
     * and then does its own work - the input pump, a memcpy out of the carry
     * buffer and a peak loop over every sample - and none of that was inside
     * any measurement. A callback can miss while all three of its blocks look
     * cheap.
     *
     * `lateCallbacks` is ours and cumulative. Oboe's xrun counter is the
     * stream's, and it resets whenever the stream is reopened, which is
     * exactly what happens after a dropout bad enough to disconnect.
     */
    /**
     * Telling the scheduler this work has a deadline.
     *
     * The session wants the audio thread's own id, which only the audio thread
     * knows, and creating one allocates - so the callback publishes its id
     * here and `start` opens the session off the audio thread once it appears.
     */
    acidulous::platform::PerfHint perfHint;
    std::atomic<int32_t> audioThreadId{0};
    std::atomic<bool> hintWanted{true};
    /**
     * Which stream the waiting thread belongs to.
     *
     * It sleeps between attempts, and a stream can be stopped and started
     * under it - a freeze does exactly that. Without this, the old thread
     * wakes up and opens a session naming a thread that no longer exists.
     */
    std::atomic<int32_t> hintGeneration{0};

    std::atomic<int32_t> callbackPeakUs{0};
    /**
     * The same peak, falling by itself instead of being cleared by whoever
     * asks first.
     *
     * `callbackPeakUs` is right for "the worst since you pressed play" and
     * useless for a meter, because a clearing read means two readers see half
     * the spikes each. This one decays on the audio thread, so the status line
     * and the load meter can both watch without robbing each other.
     */
    std::atomic<int32_t> callbackRecentUs{0};
    std::atomic<int32_t> callbackCpuPeakUs{0};
    std::atomic<int64_t> lateCallbacks{0};
    std::atomic<int64_t> stalledCallbacks{0};

    /** This thread's own CPU time, in microseconds. Not the wall clock. */
    static int64_t threadCpuUs() {
        timespec ts{};
        if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts) != 0) return 0;
        return static_cast<int64_t>(ts.tv_sec) * 1000000 + ts.tv_nsec / 1000;
    }

    /** What [frames] of audio is worth, at the rate the stream actually opened. */
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
