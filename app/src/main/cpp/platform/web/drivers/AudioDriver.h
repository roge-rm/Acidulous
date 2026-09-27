#pragma once
#include <cstdint>
#include <atomic>
#include <engine/core/Constants.h>
#include <functional>
#include <thread>
#include <vector>

// The browser's output stream: a Web Audio AudioWorklet, run by Emscripten's
// Wasm Audio Worklets on shared memory, so the engine the UI thread calls into
// is the very one the audio thread renders - as on the phone.
//
// The same class and public surface as the Oboe driver (platform/drivers) and
// the desktop's (platform/desktop/drivers): EngineHost builds against any of
// them without knowing which. What a browser does not give: a choice of
// device, a scheduler hint, or a presentation timestamp - the anchor is the
// frames written and the context's own latency.
//
// The input is the browser's microphone: a MediaStream, asked for by the page
// (the app's permission is the browser's prompt), connected to the worklet's
// one input and read in the same quantum the output is written in.
//
// Web Audio runs the worklet in quanta of 128 frames; the engine renders
// blocks of kBlockFrames (64), served out through a carry buffer as the other
// drivers do. An AudioContext starts suspended until the page is touched:
// resume() is for the first gesture.
//
// **Until then, a stand-in renders.** The engine takes a new machine, effect
// or sample on its audio thread, at the top of a block, and the app waits for
// that before sending the machine's patch - a few milliseconds on the phone.
// Here there is no audio thread until somebody clicks, so the song the app
// opens at start-up would send every patch to an empty rack. A thread of the
// driver's own renders blocks into nothing, a few hundred a second, until the
// worklet's first quantum takes over; the hand-over is the two atomics below.
//
// **The buffer setting is the context's latency hint** - interactive,
// balanced, playback for tight, balanced, safe - which the browser turns into
// how much it buffers ahead of the speakers. It is fixed when a context is
// made, so a change makes a new one (reopen), as the desktop reopens its
// device. How late sound actually is, the browser says: the context's base
// and output latency, which the page reads for the driver (acid_latency_watch).

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

    /**
     * Creates the context and asks for the worklet; sound follows once the
     * page has been touched (resume). After a stop(), attaches the engine again.
     */
    bool start();
    /**
     * Takes the engine off the stream, for a render or a freeze to pull its
     * blocks by hand: the stream goes on, silent. Not closed - a browser's
     * audio can only be made on the page's thread, and those run on an engine
     * thread here, and sound would need another click to start again.
     */
    void stop();
    /** The first user gesture: an AudioContext may only start from one. */
    static void resume();
    /** For the page's own checks: frames rendered so far, and the context's state (0 suspended, 1 running). */
    static double liveFrames();
    static int liveState();

    /**
     * Open the ear: the stream the page holds, or a new one asked of the
     * browser, connected as soon as there is both a stream and a worklet. On
     * from now; the first few blocks may be silence while the browser opens
     * the microphone. The browser chooses which microphone.
     */
    bool startInput(int32_t = 0);
    void stopInput();
    bool isInputRunning() const { return inputOn.load(std::memory_order_relaxed); }
    int32_t inputChannels() const { return isInputRunning() ? 2 : 0; }
    int32_t inputRate() const { return isInputRunning() ? getSampleRate() : 0; }
    int32_t inputDevice() const { return 0; }
    float readInputPeak() {
        const float now = inputPeak.load(std::memory_order_relaxed);
        inputPeak.store(now * kMeterDecay, std::memory_order_relaxed);
        return now;
    }

    bool isRunning() const { return context != 0 && !detached.load(); }

    void setBufferBursts(int32_t bursts);
    int32_t getBufferBursts() const { return bufferBursts; }
    /** The browser's own figure once it has given one; two quanta until then. */
    int32_t getBufferFrames() const {
        const double s = latencySeconds.load(std::memory_order_relaxed);
        const int32_t rate = actualSampleRate > 0 ? actualSampleRate : acidulous::kSampleRate;
        return s > 0.0 ? static_cast<int32_t>(s * rate + 0.5) : kQuantum * 2;
    }

    int32_t getSampleRate() const { return actualSampleRate; }
    int32_t getFramesPerBurst() const { return kQuantum; }
    bool isLowLatency() const { return true; }
    int64_t getXRunCount() const { return 0; }
    bool hintRunning() const { return false; }
    bool hintAvailable() const { return false; }
    int32_t hintState() const { return 0; }
    void setHintWanted(bool) {}

    int32_t readCallbackPeakUs() { return callbackPeakUs.exchange(0, std::memory_order_relaxed); }
    int32_t recentCallbackUs() const { return callbackRecentUs.load(std::memory_order_relaxed); }
    int32_t readCallbackCpuPeakUs() { return 0; }
    int64_t getStalledCallbacks() const { return 0; }
    int64_t getLateCallbacks() const { return lateCallbacks.load(std::memory_order_relaxed); }
    int32_t callbackBudgetUs() const {
        const int32_t rate = actualSampleRate > 0 ? actualSampleRate : acidulous::kSampleRate;
        return static_cast<int32_t>(static_cast<int64_t>(kQuantum) * 1000000 / rate);
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

    // The worklet's callbacks; public only so the C trampolines can reach them.
    void render(const float *inLeft, const float *inRight, float *left, float *right, int32_t numFrames);
    void connect(int context, void *stream);
    /** Whether a worklet's context is still this driver's, and not one a reopen left behind. */
    bool isCurrent(int ctx) const { return ctx != 0 && ctx == context.load(std::memory_order_relaxed); }
    /** Where the page writes the context's latency, in seconds. */
    std::atomic<double> latencySeconds{0.0};

  private:
    static constexpr int32_t kQuantum = 128;
    static constexpr int32_t kInputRingFrames = 2048;
    static constexpr float kMeterDecay = 0.7f;
    static AudioDriver *sLive;

    std::atomic<int> context{0}; // EMSCRIPTEN_WEBAUDIO_T; nought is none. Read on the worklet's thread
    int node = 0;
    std::function<void(float *, float *, unsigned long)> callback;

    std::vector<float> carry;
    std::vector<float> silence;

    // The input, as the desktop's: a quantum's frames in, a block's out.
    // Only the worklet's thread touches the ring.
    std::atomic<bool> inputOn{false};
    std::atomic<float> inputPeak{0.0f};
    std::vector<float> inputRing;
    std::vector<float> inputBlock;
    int32_t inputRingFrames = 0;
    int32_t inputRingRead = 0;
    void pushInput(const float *left, const float *right, int32_t frames);
    const float *nextInputBlock();

    /** The real end, on the page's thread: the context and the worklet gone. */
    void close();
    /** A context with the latency hint the buffer setting asks for, its worklet, and the stand-in until then. */
    bool openContext();
    /** The context and worklet exchanged for new ones: see the top of this file. */
    void reopen();
    int32_t generation = 0;
    std::atomic<bool> detached{false};
    /** The worklet is inside a quantum, which stop() waits out. */
    std::atomic<bool> inCallback{false};

    /** The stand-in before the worklet: see the top of this file. */
    void standby();
    std::thread standbyThread;
    /** Set by the worklet's first quantum; the stand-in stops when it sees it. */
    std::atomic<bool> workletOwns{false};
    /** The stand-in is inside a block, which the worklet waits out. */
    std::atomic<bool> standbyBusy{false};
    std::atomic<bool> standbyStop{false};

    /** Writes the time for the audio thread: see acid_clock_ms. */
    std::thread clockThread;
    std::atomic<bool> clockStop{false};
    int32_t carryFrames = 0;
    int32_t carryOffset = 0;

    struct Anchor {
        int64_t frame = -1;
        int64_t nanos = 0;
    };
    Anchor anchors[2];
    std::atomic<int32_t> anchorSlot{0};
    int64_t framesWritten = 0;

    std::atomic<int32_t> callbackPeakUs{0};
    std::atomic<int32_t> callbackRecentUs{0};
    std::atomic<int64_t> lateCallbacks{0};

    int32_t bufferBursts = 2;
    int32_t actualSampleRate = 0;
    std::atomic<float> peakLevel{0.0f};
};
