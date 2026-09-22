#include "AudioDriver.h"
#include <unistd.h>
#include <thread>

#include <chrono>
#include <engine/dsp/Denormals.h>
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
    // deep enough to absorb a late callback. AAudio tunes down from here,
    // and Settings can ask for a different depth.
    stream->setBufferSizeInFrames(actualFramesPerBurst * bufferBursts);

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

    // **The hint, opened off the audio thread once the audio thread exists.**
    //
    // A session is per *thread* and the thread is AAudio's, not ours, so its
    // id is not known until it has run once. Creating the session allocates
    // and talks to a system service, which is not something to do in a
    // callback - so the callback leaves its id behind and this waits for it.
    // A detached thread rather than a wait here, because `start` is called
    // from the UI and a second of it is a second of blank screen.
    audioThreadId.store(0, std::memory_order_relaxed);
    if (perfHint.load()) {
        const int64_t targetNanos = static_cast<int64_t>(budgetFor(actualFramesPerBurst)) * 1000;
        const int32_t generation = hintGeneration.fetch_add(1, std::memory_order_relaxed) + 1;
        std::thread([this, targetNanos, generation] {
            const auto ours = [this, generation] {
                return hintGeneration.load(std::memory_order_relaxed) == generation;
            };
            int32_t tid = 0;
            for (int i = 0; i < 200 && ours(); ++i) { // two seconds to name itself
                tid = audioThreadId.load(std::memory_order_acquire);
                if (tid != 0) break;
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
            if (tid == 0) {
                if (ours()) perfHint.gaveUp();
                return;
            }
            // **Asked again, later.** Dan's phone answered "refused" to a
            // session asked for within ten milliseconds of the first callback,
            // and a device that declines in the first moments of a process's
            // life may accept once it has settled - the power HAL may not be
            // up, or the app may not yet count as foreground. Four tries over
            // about seventeen seconds, then it is a fact about the device
            // rather than about our timing.
            //
            // Cheap to be wrong about: a refusal is one call that returns
            // null, and a success on the second try is a session for the rest
            // of the stream's life.
            static constexpr int kWaits[] = {0, 2000, 5000, 10000};
            constexpr int kTries = static_cast<int>(sizeof(kWaits) / sizeof(kWaits[0]));
            for (int n = 0; n < kTries; ++n) {
                if (kWaits[n] > 0) std::this_thread::sleep_for(std::chrono::milliseconds(kWaits[n]));
                if (!ours()) return; // the stream this belonged to has gone
                if (!hintWanted.load(std::memory_order_relaxed)) return;
                if (perfHint.begin(tid, targetNanos, n == kTries - 1)) return;
            }
        }).detach();
    }
    return true;
}

bool AudioDriver::startInput(int32_t deviceId) {
    // Already open on the device that was asked for - including nought,
    // which means "whatever you were going to pick" and cannot be compared
    // against what was picked.
    if (inputStream != nullptr && (deviceId == 0 || deviceId == actualInputDevice)) return true;
    if (inputStream != nullptr) stopInput();
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setDeviceId(deviceId > 0 ? deviceId : oboe::kUnspecified)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(acidulous::kSampleRate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        // Unprocessed asks the platform to leave it alone: no AGC, no noise
        // suppression, no echo canceller. Those are for voice calls and they
        // would eat a guitar or a synth alive. Not every device honours it.
        ->setInputPreset(oboe::InputPreset::Unprocessed);

    oboe::Result result = builder.openStream(inputStream);
    if (result != oboe::Result::OK) {
        LOGE("failed to open input: %s", oboe::convertToText(result));
        inputStream.reset();
        return false;
    }
    actualInputChannels = inputStream->getChannelCount();
    actualInputRate = inputStream->getSampleRate();
    actualInputDevice = inputStream->getDeviceId();
    const size_t ringFrames = static_cast<size_t>(acidulous::kSampleRate) / 4; // a quarter second
    inputRing.assign(ringFrames * 2, 0.0f);
    inputScratch.assign(ringFrames * 2, 0.0f);
    inputBlock.assign(static_cast<size_t>(acidulous::kBlockFrames) * 2, 0.0f);
    inputRingFrames = 0;
    inputRingRead = 0;

    result = inputStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("failed to start input: %s", oboe::convertToText(result));
        inputStream->close();
        inputStream.reset();
        return false;
    }
    LOGI("input open: %d Hz, %d ch, %s", inputStream->getSampleRate(), actualInputChannels,
         inputStream->getPerformanceMode() == oboe::PerformanceMode::LowLatency ? "LowLatency" : "Normal");
    return true;
}

void AudioDriver::stopInput() {
    if (inputStream == nullptr) return;
    inputStream->requestStop();
    inputStream->close();
    inputStream.reset();
    inputRingFrames = 0;
    inputRingRead = 0;
    actualInputChannels = 0;
    actualInputRate = 0;
    actualInputDevice = 0;
}

// Drain whatever the input stream has ready, without waiting for it. A
// microphone that is behind is a hole in the recording, not a stalled
// output: never block the callback for it.
void AudioDriver::pumpInput(int32_t frames) {
    if (inputStream == nullptr) return;
    const int32_t channels = actualInputChannels > 0 ? actualInputChannels : 1;
    const int32_t capacity = static_cast<int32_t>(inputRing.size() / 2);
    const int32_t want = std::min(frames * 2, capacity - inputRingFrames);
    if (want <= 0) return;
    auto read = inputStream->read(inputScratch.data(), want, 0);
    if (!read) return;
    const int32_t got = read.value();
    float peak = 0.0f;
    for (int32_t i = 0; i < got; ++i) {
        const float l = inputScratch[static_cast<size_t>(i) * channels];
        const float r = channels > 1 ? inputScratch[static_cast<size_t>(i) * channels + 1] : l;
        const int32_t slot = (inputRingRead + inputRingFrames + i) % capacity;
        inputRing[static_cast<size_t>(slot) * 2] = l;
        inputRing[static_cast<size_t>(slot) * 2 + 1] = r;
        peak = std::fmax(peak, std::fmax(std::fabs(l), std::fabs(r)));
    }
    inputRingFrames += got;
    float previous = inputPeak.load(std::memory_order_relaxed);
    if (peak > previous) inputPeak.store(peak, std::memory_order_relaxed);
}

const float *AudioDriver::nextInputBlock() {
    if (inputStream == nullptr) return nullptr;
    const int32_t capacity = static_cast<int32_t>(inputRing.size() / 2);
    for (int32_t i = 0; i < engineBlockFrames; ++i) {
        if (inputRingFrames > 0) {
            const int32_t slot = inputRingRead % capacity;
            inputBlock[static_cast<size_t>(i) * 2] = inputRing[static_cast<size_t>(slot) * 2];
            inputBlock[static_cast<size_t>(i) * 2 + 1] = inputRing[static_cast<size_t>(slot) * 2 + 1];
            inputRingRead = (inputRingRead + 1) % capacity;
            --inputRingFrames;
        } else {
            inputBlock[static_cast<size_t>(i) * 2] = 0.0f;
            inputBlock[static_cast<size_t>(i) * 2 + 1] = 0.0f;
        }
    }
    return inputBlock.data();
}

void AudioDriver::stop() {
    // Before the stream goes: the session names a thread that is about to
    // stop existing.
    perfHint.end();
    if (stream == nullptr) {
        return;
    }
    stopInput();
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

oboe::DataCallbackResult AudioDriver::onAudioReady(oboe::AudioStream *audioStream,
                                                   void *audioData,
                                                   int32_t numFrames) {
    // **Before anything else this thread does.** A denormal is what the inside
    // of every decaying tail is made of, and on a CPU that takes the slow path
    // for them a block full of releases costs many times a block full of
    // notes. Measured on the harness: the amp's idle tail went from 841 us to
    // 52, which is 63% of a block's budget down to 4%, for one bit in a
    // register. It is set here rather than at thread start because this thread
    // is AAudio's, not ours - the guard makes it one branch a callback.
    acidulous::dsp::flushDenormalsOnce();

    const auto tCallback = std::chrono::steady_clock::now();
    const int64_t cpu0 = threadCpuUs();
    // Once, for the thread that opens the hint session. A relaxed read of an
    // already-set value is a register compare, which is what this costs on
    // every callback after the first.
    if (audioThreadId.load(std::memory_order_relaxed) == 0) {
        audioThreadId.store(static_cast<int32_t>(gettid()), std::memory_order_release);
    }
    auto *out = static_cast<float *>(audioData);
    int32_t written = 0;
    pumpInput(numFrames);

    // Where the stream is, in both of its clocks. It refuses to answer until
    // it has run a little, and it can refuse again later, so the last good
    // answer is kept rather than the anchor being lost.
    if (audioStream != nullptr) {
        const auto stamp = audioStream->getTimestamp(CLOCK_MONOTONIC);
        if (stamp) {
            const int32_t next = 1 - anchorSlot.load(std::memory_order_relaxed);
            anchors[next].frame = stamp.value().position;
            anchors[next].nanos = stamp.value().timestamp;
            anchorSlot.store(next, std::memory_order_release);
        }
    }

    while (written < numFrames) {
        if (carryFrames == 0) {
            // Pull one fixed-size block of interleaved stereo from the engine.
            callback(const_cast<float *>(nextInputBlock()), carry.data(),
                     static_cast<unsigned long>(engineBlockFrames));
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

    // Everything above is inside the measurement, which is the point: the
    // engine already times its own render and it is not the thing with the
    // deadline.
    const auto us = static_cast<int32_t>(std::chrono::duration_cast<std::chrono::microseconds>(
                                             std::chrono::steady_clock::now() - tCallback)
                                             .count());
    // **Wall clock and CPU time, both.** They answer different questions and
    // only the pair is diagnostic. Wall says how long the callback took, which
    // is what the deadline is measured against. CPU says how much of that we
    // actually spent computing. When the two agree the engine is genuinely
    // slow and the fix is DSP; when wall is far larger the thread was taken
    // off its core and no amount of optimising will help - that is a
    // scheduling problem, and it wants priority and a performance hint
    // instead. Told apart by an average, the two look identical.
    const auto cpuUs = static_cast<int32_t>(threadCpuUs() - cpu0);
    int32_t seen = callbackPeakUs.load(std::memory_order_relaxed);
    while (us > seen && !callbackPeakUs.compare_exchange_weak(seen, us, std::memory_order_relaxed)) {
    }
    // Falls by about a third every half second at this rate, which is slow
    // enough to read off a meter and quick enough to follow a scene change.
    const int32_t wasRecent = callbackRecentUs.load(std::memory_order_relaxed);
    const int32_t faded = static_cast<int32_t>(static_cast<int64_t>(wasRecent) * 49 / 50);
    callbackRecentUs.store(us > faded ? us : faded, std::memory_order_relaxed);
    int32_t seenCpu = callbackCpuPeakUs.load(std::memory_order_relaxed);
    while (cpuUs > seenCpu &&
           !callbackCpuPeakUs.compare_exchange_weak(seenCpu, cpuUs, std::memory_order_relaxed)) {
    }
    // A callback that ran long in wall time while barely using the CPU was
    // descheduled, not slow. Counted apart, because the two have different
    // cures and a single "late" number hides which one this device has.
    if (us > budgetFor(numFrames) && cpuUs * 2 < us) {
        stalledCallbacks.fetch_add(1, std::memory_order_relaxed);
    }
    // The budget is this callback's own frames at this stream's own rate, not
    // a constant: Oboe may open at a rate we did not ask for and may hand a
    // different frame count than the burst.
    if (us > budgetFor(numFrames)) lateCallbacks.fetch_add(1, std::memory_order_relaxed);
    // **Every callback, not only the late ones.** The governor is being told
    // what this work costs so it can decide what to clock the core at;
    // reporting only the misses would describe a device that is always
    // struggling and ask for more than we need. Wall time, because that is
    // what the deadline is about and what the session's target is stated in.
    perfHint.report(static_cast<int64_t>(us) * 1000);

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

void AudioDriver::setBufferBursts(int32_t bursts) {
    if (bursts < 1) bursts = 1;
    if (bursts > 8) bursts = 8;
    bufferBursts = bursts;
    if (stream != nullptr) stream->setBufferSizeInFrames(actualFramesPerBurst * bufferBursts);
}

int32_t AudioDriver::getBufferFrames() const {
    return stream != nullptr ? stream->getBufferSizeInFrames() : actualFramesPerBurst * bufferBursts;
}
