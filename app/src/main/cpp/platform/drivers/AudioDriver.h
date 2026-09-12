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

    bool isRunning() const { return stream != nullptr; }

    // What the device actually gave us, which may differ from what we asked.
    int32_t getSampleRate() const { return actualSampleRate; }
    int32_t getFramesPerBurst() const { return actualFramesPerBurst; }
    bool isLowLatency() const { return actualLowLatency; }
    int64_t getXRunCount() const;

    // Peak absolute sample seen since the last read, then reset. Lets the UI
    // (and bring-up on a silent emulator) confirm the engine is actually
    // producing signal, and is the basis for a real level meter later.
    float readPeakLevel() { return peakLevel.exchange(0.0f, std::memory_order_relaxed); }

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream,
                                          void *audioData,
                                          int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result result) override;

  private:
    std::shared_ptr<oboe::AudioStream> stream;
    std::function<void(float *, float *, unsigned long)> callback;

    // Carry buffer: one engine block of interleaved stereo, partially drained.
    std::vector<float> carry;
    int32_t carryFrames = 0;  // frames still unread in `carry`
    int32_t carryOffset = 0;  // read cursor, in frames

    int32_t engineBlockFrames = 0;
    int32_t actualSampleRate = 0;
    int32_t actualFramesPerBurst = 0;
    bool actualLowLatency = false;
    std::atomic<float> peakLevel{0.0f};
};
