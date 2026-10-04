#include "AudioDriver.h"
#include <unistd.h>
#include <thread>
#include <atomic>

#include <chrono>
#include <engine/dsp/Denormals.h>
#include <algorithm>
#include <android/log.h>
#include <cmath>
#include <cstring>

#define LOG_TAG "Acidulous.Audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include <sched.h>
#include <cstdio>
#include <sys/resource.h>

namespace {

/**
 * The phone's fast cores, if it has more than one kind: those Android rates
 * (cpu_capacity, else the top frequency) at three quarters of the fastest or
 * more, so a prime core and the big ones beside it. Empty on a phone of one
 * kind of core, where there's nothing to choose.
 */
bool findFastCores(cpu_set_t &mask, int &count) {
    CPU_ZERO(&mask);
    count = 0;
    const long cores = sysconf(_SC_NPROCESSORS_CONF);
    if (cores <= 1 || cores > CPU_SETSIZE) return false;
    long rating[CPU_SETSIZE] = {};
    long best = 0, worst = 0;
    for (long c = 0; c < cores; ++c) {
        long v = 0;
        for (const char *file : {"cpu_capacity", "cpufreq/cpuinfo_max_freq"}) {
            char path[96];
            std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%ld/%s", c, file);
            if (FILE *f = std::fopen(path, "r")) {
                if (std::fscanf(f, "%ld", &v) != 1) v = 0;
                std::fclose(f);
            }
            if (v > 0) break;
        }
        if (v <= 0) return false;
        rating[c] = v;
        best = std::max(best, v);
        worst = c == 0 ? v : std::min(worst, v);
    }
    if (worst * 4 >= best * 3) return false; // one kind of core
    for (long c = 0; c < cores; ++c) {
        if (rating[c] * 4 >= best * 3) {
            CPU_SET(static_cast<int>(c), &mask);
            ++count;
        }
    }
    return count > 0;
}

} // namespace

int32_t AudioDriver::workerCores() {
    cpu_set_t fast;
    int count = 0;
    if (findFastCores(fast, count)) return count;
    return static_cast<int32_t>(sysconf(_SC_NPROCESSORS_CONF));
}

namespace {
/** The track workers' threads, so the audio thread's helper can place them beside it. */
constexpr int kMaxWorkerTids = 8;
std::atomic<pid_t> workerTids[kMaxWorkerTids]{};
} // namespace

void AudioDriver::prepareWorker(int32_t index) {
    const pid_t tid = gettid();
    if (index >= 0 && index < kMaxWorkerTids) workerTids[index].store(tid, std::memory_order_relaxed);
    cpu_set_t fast;
    int count = 0;
    if (findFastCores(fast, count) && sched_setaffinity(tid, sizeof(fast), &fast) != 0) {
        LOGI("couldn't put track worker %d on the fast cores", index);
    }
    // The urgent-audio niceness an app may give its own threads, so the
    // scheduler doesn't put a worker aside while the audio thread waits on it.
    if (setpriority(PRIO_PROCESS, tid, -19) != 0) LOGI("track worker %d stays at normal priority", index);
}

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
        // The engine runs at kSampleRate. If the device can't, Oboe resamples
        // so the engine doesn't play out of tune.
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setUsage(oboe::Usage::Game)
        // Room to grow (see onAudioReady). Left to Oboe, a device without a
        // fast mixer (a Fire tablet) gave two bursts, 1920 frames, against
        // a mixer taking 1536 at a time, and it ran dry several times a
        // second however little the engine had to do.
        ->setBufferCapacityInFrames(kBufferCapacity)
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

    // Two bursts is low enough to feel responsive and deep enough to absorb a
    // late callback. AAudio tunes down from here and Settings can change it.
    stream->setBufferSizeInFrames(actualFramesPerBurst * bufferBursts);
    {
        const auto x = stream->getXRunCount();
        xrunsSeen = x ? x.value() : 0;
    }

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

    // Open the performance hint session off the audio thread. A session is
    // per thread and the thread belongs to AAudio, so we don't know its id
    // until the first callback stores it. Creating a session allocates and
    // talks to a system service, so it can't happen in the callback. A
    // detached thread waits for the id because start() is called from the UI
    // thread and mustn't block.
    audioThreadId.store(0, std::memory_order_relaxed);
    // The same helper puts the audio thread on the fast cores. Left to
    // itself Android ran it on the slow ones: on a phone with four of each
    // (Dan's, 2026-10-03), every sample of a playing song had it on cores
    // 0-3, 2.3 times slower, and with no hint sessions on that phone
    // nothing told the scheduler otherwise.
    const bool hints = perfHint.load();
    {
        const int64_t targetNanos = static_cast<int64_t>(budgetFor(actualFramesPerBurst)) * 1000;
        const int32_t generation = hintGeneration.fetch_add(1, std::memory_order_relaxed) + 1;
        std::thread([this, targetNanos, generation, hints] {
            const auto ours = [this, generation] {
                return hintGeneration.load(std::memory_order_relaxed) == generation;
            };
            int32_t tid = 0;
            for (int i = 0; i < 200 && ours(); ++i) { // wait up to two seconds
                tid = audioThreadId.load(std::memory_order_acquire);
                if (tid != 0) break;
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
            if (tid == 0) {
                if (ours() && hints) perfHint.gaveUp();
                return;
            }
            cpu_set_t fast;
            int fastCount = 0;
            if (findFastCores(fast, fastCount)) {
                if (sched_setaffinity(tid, sizeof(fast), &fast) == 0) {
                    pinnedCores.store(fastCount, std::memory_order_relaxed);
                    LOGI("audio thread %d on the %d fast cores", tid, fastCount);
                } else {
                    LOGI("couldn't put audio thread %d on the fast cores", tid);
                }
            }
            // Some devices (Fire OS) run the callback as an ordinary thread,
            // at nice -16. There it goes to -19, the most an app may give, and
            // the workers to -18: at -19 themselves they'd outrank the thread
            // they work for, and a worker spinning between blocks would take
            // its core from it.
            const int policy = sched_getscheduler(tid);
            if (policy != SCHED_FIFO && policy != SCHED_RR) {
                const bool raised = setpriority(PRIO_PROCESS, tid, -19) == 0;
                for (auto &w : workerTids) {
                    if (const pid_t worker = w.load(std::memory_order_relaxed)) setpriority(PRIO_PROCESS, worker, -18);
                }
                LOGI("audio thread %d isn't real-time: %s", tid, raised ? "at -19, workers -18" : "couldn't raise it");
            }
            if (!hints) return;
            // Some devices refuse a session right after the stream starts but
            // accept one later, once the power HAL is up or the app counts as
            // foreground. Try four times over about seventeen seconds before
            // giving up. A refusal is cheap, just one call returning null.
            static constexpr int kWaits[] = {0, 2000, 5000, 10000};
            constexpr int kTries = static_cast<int>(sizeof(kWaits) / sizeof(kWaits[0]));
            for (int n = 0; n < kTries; ++n) {
                if (kWaits[n] > 0) std::this_thread::sleep_for(std::chrono::milliseconds(kWaits[n]));
                if (!ours()) return; // the stream this was for is gone
                if (!hintWanted.load(std::memory_order_relaxed)) return;
                if (perfHint.begin(tid, targetNanos, n == kTries - 1)) return;
            }
        }).detach();
    }
    return true;
}

bool AudioDriver::startInput(int32_t deviceId) {
    // Already open on the device asked for. 0 means "whatever the system
    // picks" and can't be compared, so it always counts.
    if (inputStream != nullptr && openClean == inputClean && (deviceId == 0 || deviceId == actualInputDevice)) return true;
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
        // Unprocessed asks for no AGC, noise suppression or echo cancelling.
        // Those are for voice calls and ruin instruments. Not every device
        // honours it. Clean is the ordinary recording path instead, with a
        // session for the platform's effects (see setInputClean).
        ->setInputPreset(inputClean ? oboe::InputPreset::Generic : oboe::InputPreset::Unprocessed);
    if (inputClean) builder.setSessionId(oboe::SessionId::Allocate);

    oboe::Result result = builder.openStream(inputStream);
    if (result != oboe::Result::OK) {
        LOGE("failed to open input: %s", oboe::convertToText(result));
        inputStream.reset();
        return false;
    }
    actualInputChannels = inputStream->getChannelCount();
    actualInputRate = inputStream->getSampleRate();
    actualInputDevice = inputStream->getDeviceId();
    openClean = inputClean;
    actualInputSession = inputClean ? std::max(0, static_cast<int32_t>(inputStream->getSessionId())) : 0;
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
    liveInput.store(inputStream.get()); // the callback may use it from here on
    return true;
}

void AudioDriver::stopInput() {
    if (inputStream == nullptr) return;
    // Take it back from the callback, and wait out one that's still using it.
    // At most one callback, a few milliseconds.
    liveInput.store(nullptr);
    while (inputBusy.load()) std::this_thread::yield();
    inputStream->requestStop();
    inputStream->close();
    inputStream.reset();
    inputRingFrames = 0;
    inputRingRead = 0;
    actualInputChannels = 0;
    actualInputRate = 0;
    actualInputDevice = 0;
    actualInputSession = 0;
}

void AudioDriver::setInputClean(bool on) {
    if (on == inputClean) return;
    inputClean = on;
    if (inputStream == nullptr) return;
    const int32_t device = actualInputDevice;
    stopInput();
    startInput(device);
}

// Drain whatever the input stream has ready without waiting. If the input
// is behind we get a gap in the recording, but the callback never blocks.
void AudioDriver::pumpInput(oboe::AudioStream *input, int32_t frames) {
    if (input == nullptr) return;
    const int32_t channels = actualInputChannels > 0 ? actualInputChannels : 1;
    const int32_t capacity = static_cast<int32_t>(inputRing.size() / 2);
    const int32_t want = std::min(frames * 2, capacity - inputRingFrames);
    if (want <= 0) return;
    auto read = input->read(inputScratch.data(), want, 0);
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

const float *AudioDriver::nextInputBlock(bool live) {
    if (!live) return nullptr;
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
    // End the hint session before the stream (and its thread) goes away.
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
    // First thing on this thread. Decaying tails are full of denormals, which
    // are very slow on some CPUs (the amp's idle tail went from 841 us to
    // 52 us with this). Done here rather than at thread start because the
    // thread is AAudio's. After the first call it's a single branch.
    acidulous::dsp::flushDenormalsOnce();

    const auto tCallback = std::chrono::steady_clock::now();
    const int64_t cpu0 = threadCpuUs();
    // Store our thread id once for the thread that opens the hint session.
    // After that this is just a relaxed read.
    if (audioThreadId.load(std::memory_order_relaxed) == 0) {
        audioThreadId.store(static_cast<int32_t>(gettid()), std::memory_order_release);
    }
    // Ran dry since the last callback: a burst more buffer, up to the
    // capacity. The setting is where it starts; a device whose mixer takes
    // more at a time than that grows it to what it needs, once.
    if (audioStream != nullptr) {
        const auto x = audioStream->getXRunCount();
        if (x && x.value() > xrunsSeen) {
            xrunsSeen = x.value();
            const int32_t size = audioStream->getBufferSizeInFrames();
            if (size + actualFramesPerBurst <= audioStream->getBufferCapacityInFrames()) {
                audioStream->setBufferSizeInFrames(size + actualFramesPerBurst);
            }
        }
    }
    auto *out = static_cast<float *>(audioData);
    int32_t written = 0;
    // Busy until the last engine block has read the input (see liveInput).
    inputBusy.store(true);
    oboe::AudioStream *input = liveInput.load();
    pumpInput(input, numFrames);

    // Where the stream is in frames and in time. getTimestamp fails until the
    // stream has run a little and can fail again later, so keep the last good
    // anchor.
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
            callback(const_cast<float *>(nextInputBlock(input != nullptr)), carry.data(),
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
    inputBusy.store(false);

    // Cheap peak meter. Relaxed and lossy on purpose so it costs the callback
    // next to nothing.
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

    // Everything above is inside the measurement. The engine times its own
    // render separately; this is the whole callback, which has the deadline.
    const auto us = static_cast<int32_t>(std::chrono::duration_cast<std::chrono::microseconds>(
                                             std::chrono::steady_clock::now() - tCallback)
                                             .count());
    // Wall clock and CPU time. Wall time is what the deadline is measured
    // against, CPU time is how much of it we spent computing. If they're close
    // the DSP is too slow. If wall time is much bigger the thread was taken off
    // its core, which is a scheduling problem and needs priority and the
    // performance hint instead.
    const auto cpuUs = static_cast<int32_t>(threadCpuUs() - cpu0);
    int32_t seen = callbackPeakUs.load(std::memory_order_relaxed);
    while (us > seen && !callbackPeakUs.compare_exchange_weak(seen, us, std::memory_order_relaxed)) {
    }
    // Falls by about a third every half second, slow enough to read on a meter
    // and quick enough to follow a scene change.
    const int32_t wasRecent = callbackRecentUs.load(std::memory_order_relaxed);
    const int32_t faded = static_cast<int32_t>(static_cast<int64_t>(wasRecent) * 49 / 50);
    callbackRecentUs.store(us > faded ? us : faded, std::memory_order_relaxed);
    int32_t seenCpu = callbackCpuPeakUs.load(std::memory_order_relaxed);
    while (cpuUs > seenCpu &&
           !callbackCpuPeakUs.compare_exchange_weak(seenCpu, cpuUs, std::memory_order_relaxed)) {
    }
    // Long in wall time but low CPU means the thread was descheduled. Counted
    // separately from late callbacks because the fix is different.
    if (us > budgetFor(numFrames) && cpuUs * 2 < us) {
        stalledCallbacks.fetch_add(1, std::memory_order_relaxed);
    }
    // The budget is this callback's frames at the stream's rate, since Oboe
    // may open at a different rate and pass a different frame count than the
    // burst.
    if (us > budgetFor(numFrames)) lateCallbacks.fetch_add(1, std::memory_order_relaxed);
    // Report every callback, not only the late ones, so the governor sees
    // what the work really costs. Wall time, because the session's target is
    // in wall time.
    perfHint.report(static_cast<int64_t>(us) * 1000);

    return oboe::DataCallbackResult::Continue;
}

void AudioDriver::onErrorAfterClose(oboe::AudioStream * /*audioStream*/, oboe::Result result) {
    // Usually Disconnected: headphones pulled or a USB interface removed.
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
