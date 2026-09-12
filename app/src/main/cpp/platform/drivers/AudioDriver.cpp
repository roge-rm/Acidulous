#include "AudioDriver.h"
#include <algorithm>
#include <android/log.h>
#include <cmath>
#include <cstring>

#define LOG_TAG "Acidulous.Audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

AudioDriver::~AudioDriver() {
    stop();
}

bool AudioDriver::start() {
    if (stream != nullptr) {
        return true; // already running
    }
    if (!callback) {
        LOGE("start() called before registerCallback()");
        return false;
    }

    engineBlockFrames = acidulous::kBlockFrames;
    carry.assign(static_cast<size_t>(engineBlockFrames) * 2, 0.0f);
    carryFrames = 0;
    carryOffset = 0;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(acidulous::kSampleRate)
        // The engine runs at kSampleRate. If the device will not, let Oboe
        // resample rather than let the engine detune itself.
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setUsage(oboe::Usage::Game)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK) {
        LOGE("failed to open stream: %s", oboe::convertToText(result));
        stream.reset();
        return false;
    }

    actualSampleRate = stream->getSampleRate();
    actualFramesPerBurst = stream->getFramesPerBurst();
    actualLowLatency = stream->getPerformanceMode() == oboe::PerformanceMode::LowLatency;

    // Two bursts is the usual starting point: low enough to stay responsive,
    // deep enough to absorb a late callback. AAudio tunes down from here.
    stream->setBufferSizeInFrames(actualFramesPerBurst * 2);

    result = stream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("failed to start stream: %s", oboe::convertToText(result));
        stream->close();
        stream.reset();
        return false;
    }

    LOGI("stream open: %d Hz, burst %d frames, engine block %d frames, %s, %s",
         actualSampleRate, actualFramesPerBurst, engineBlockFrames,
         actualLowLatency ? "LowLatency" : "Normal",
         stream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared");
    return true;
}

void AudioDriver::stop() {
    if (stream == nullptr) {
        return;
    }
    stream->requestStop();
    stream->close();
    stream.reset();
    carryFrames = 0;
    carryOffset = 0;
    LOGI("stream stopped");
}

int64_t AudioDriver::getXRunCount() const {
    if (stream == nullptr) {
        return 0;
    }
    auto result = stream->getXRunCount();
    return result ? result.value() : 0;
}

oboe::DataCallbackResult AudioDriver::onAudioReady(oboe::AudioStream * /*audioStream*/,
                                                   void *audioData,
                                                   int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    int32_t written = 0;

    while (written < numFrames) {
        if (carryFrames == 0) {
            // Pull one fixed-size block of interleaved stereo from the engine.
            callback(nullptr, carry.data(), static_cast<unsigned long>(engineBlockFrames));
            carryFrames = engineBlockFrames;
            carryOffset = 0;
        }

        const int32_t n = std::min(carryFrames, numFrames - written);
        std::memcpy(out + static_cast<size_t>(written) * 2,
                    carry.data() + static_cast<size_t>(carryOffset) * 2,
                    static_cast<size_t>(n) * 2 * sizeof(float));

        written += n;
        carryOffset += n;
        carryFrames -= n;
    }

    // Cheap peak meter. Reads are relaxed and lossy by design - this must not
    // cost the callback anything meaningful.
    float peak = 0.0f;
    for (int32_t i = 0; i < numFrames * 2; ++i) {
        const float mag = std::fabs(out[i]);
        if (mag > peak) {
            peak = mag;
        }
    }
    float previous = peakLevel.load(std::memory_order_relaxed);
    if (peak > previous) {
        peakLevel.store(peak, std::memory_order_relaxed);
    }

    return oboe::DataCallbackResult::Continue;
}

void AudioDriver::onErrorAfterClose(oboe::AudioStream * /*audioStream*/, oboe::Result result) {
    // Typically Disconnected: headphones pulled, or a USB interface removed.
    // The stream is already closed by the time we get here.
    LOGE("stream error after close: %s - reopening", oboe::convertToText(result));
    stream.reset();
    if (!start()) {
        LOGE("reopen failed; audio is stopped");
    }
}
