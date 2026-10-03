#pragma once
#include <cstdint>
#include <atomic>
#include <engine/core/Constants.h>
#include <functional>
#include <thread>
#include <vector>

// The browser audio stream: a Web Audio AudioWorklet run by Emscripten's Wasm
// Audio Worklets on shared memory, so the UI thread and the audio thread use
// the same engine, like on the phone.
//
// Same class and public API as the Oboe driver (platform/drivers) and the
// desktop one (platform/desktop/drivers) so EngineHost builds against any of
// them. Browsers give no device choice, scheduler hint or presentation
// timestamp, so the anchor comes from the frames written and the context's
// latency.
//
// The input is the browser's microphone, a MediaStream the page asks for,
// connected to the worklet's input and read in the same quantum the output is
// written in.
//
// Web Audio runs the worklet in 128-frame quanta and the engine renders
// kBlockFrames (64) blocks, served through a carry buffer like the other
// drivers. An AudioContext stays suspended until the page is clicked, and
// resume() is called on that first gesture.
//
// Until then a stand-in thread renders. The engine only takes a new machine,
// effect or sample at the top of a block on its audio thread, and the app
// waits for that before sending the patch. With no audio thread before the
// first click, the song opened at startup would send every patch to an empty
// rack. So a driver thread renders blocks into nothing, a few hundred a
// second, until the worklet's first quantum takes over (the two atomics
// below).
//
// The buffer setting maps to the context's latency hint (tight, balanced,
// safe = interactive, balanced, playback). It's fixed when a context is made,
// so changing it makes a new one (reopen). The actual latency comes from the
// context's base and output latency, which the page reads for the driver
// (acid_latency_watch).

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
     * Creates the context and asks for the worklet. Sound starts once the page
     * has been clicked (resume). After a stop(), reattaches the engine.
     */
    bool start();
    /**
     * Detaches the engine so a render or freeze can pull its blocks by hand.
     * The stream keeps running silently. It isn't closed because browser audio
     * can only be created on the page's thread (this runs on an engine thread)
     * and restarting would need another click.
     */
    void stop();
    /** Call on the first user gesture. An AudioContext can only start from one. */
    static void resume();
    /** For the page's checks: frames rendered so far, and the context state (0 suspended, 1 running). */
    static double liveFrames();
    static int liveState();

    /**
     * Start the input: the page's stream, or a new one from the browser,
     * connected once there's both a stream and a worklet. The first few
     * blocks may be silent while the browser opens the microphone. The
     * browser picks which microphone.
     */
    bool startInput(int32_t = 0);
    void stopInput();
    /** The browser's noise suppression and level control, off for raw (see the Oboe driver). */
    void setInputClean(bool on);
    int32_t inputSession() const { return 0; }
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
    /** The browser's figure once it has one, two quanta until then. */
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
    int32_t fastCores() const { return 0; }
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

    // The worklet's callbacks. Public only so the C trampolines can reach them.
    void render(const float *inLeft, const float *inRight, float *left, float *right, int32_t numFrames);
    void connect(int context, void *stream);
    /** Whether a worklet's context is still current and not one left over from a reopen. */
    bool isCurrent(int ctx) const { return ctx != 0 && ctx == context.load(std::memory_order_relaxed); }
    /** Where the page writes the context's latency, in seconds. */
    std::atomic<double> latencySeconds{0.0};

  private:
    bool inputClean = false; // see setInputClean
    static constexpr int32_t kQuantum = 128;
    static constexpr int32_t kInputRingFrames = 2048;
    static constexpr float kMeterDecay = 0.7f;
    static AudioDriver *sLive;

    std::atomic<int> context{0}; // EMSCRIPTEN_WEBAUDIO_T, 0 for none. Read on the worklet's thread
    int node = 0;
    std::function<void(float *, float *, unsigned long)> callback;

    std::vector<float> carry;
    std::vector<float> silence;

    // The input, like the desktop's: a quantum's frames in, a block's out.
    // Only the worklet's thread touches the ring.
    std::atomic<bool> inputOn{false};
    std::atomic<float> inputPeak{0.0f};
    std::vector<float> inputRing;
    std::vector<float> inputBlock;
    int32_t inputRingFrames = 0;
    int32_t inputRingRead = 0;
    void pushInput(const float *left, const float *right, int32_t frames);
    const float *nextInputBlock();

    /** Really closes the context and worklet, on the page's thread. */
    void close();
    /** Opens a context with the buffer setting's latency hint, its worklet, and the stand-in until it's ready. */
    bool openContext();
    /** Replaces the context and worklet with new ones (see the top of this file). */
    void reopen();
    int32_t generation = 0;
    std::atomic<bool> detached{false};
    /** The worklet is inside a quantum. stop() waits for it. */
    std::atomic<bool> inCallback{false};

    /** The stand-in renderer before the worklet (see the top of this file). */
    void standby();
    std::thread standbyThread;
    /** Set by the worklet's first quantum. The stand-in stops when it sees it. */
    std::atomic<bool> workletOwns{false};
    /** The stand-in is inside a block. The worklet waits for it. */
    std::atomic<bool> standbyBusy{false};
    std::atomic<bool> standbyStop{false};

    /** Writes the time for the audio thread (see acid_clock_ms). */
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
