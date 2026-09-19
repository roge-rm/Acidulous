#pragma once
#include <atomic>
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

    int32_t engineBlockFrames = 0;
    int32_t bufferBursts = 2;
    int32_t actualSampleRate = 0;
    int32_t actualFramesPerBurst = 0;
    bool actualLowLatency = false;
    std::atomic<float> peakLevel{0.0f};
};
