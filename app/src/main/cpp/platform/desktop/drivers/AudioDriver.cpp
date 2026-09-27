#include "AudioDriver.h"

#define MINIAUDIO_IMPLEMENTATION
#define MA_NO_DECODING
#define MA_NO_ENCODING
#define MA_NO_GENERATION
#include <miniaudio.h>

#include <algorithm>
#include <android/log.h>
#include <chrono>
#include <cmath>
#include <cstring>
#include <engine/dsp/Denormals.h>

#define LOG_TAG "Acidulous.Audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/**
 * A period of 256 frames - 5.3 ms at 48 kHz - is what a desktop's sound
 * server copes with comfortably; the phone's burst is in the same range, so
 * the setting's "bursts" mean about the same thing on both.
 */
constexpr ma_uint32 kPeriodFrames = 256;

int64_t monotonicNanos() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

void onOutput(ma_device *device, void *out, const void * /*in*/, ma_uint32 frames) {
    static_cast<AudioDriver *>(device->pUserData)->render(static_cast<float *>(out), static_cast<int32_t>(frames));
}

void onInput(ma_device *device, void * /*out*/, const void *in, ma_uint32 frames) {
    static_cast<AudioDriver *>(device->pUserData)->capture(static_cast<const float *>(in), static_cast<int32_t>(frames));
}

/**
 * The context inputs are listed and opened through, made once: an id from one
 * context is only good for opening a device in the same one. The output opens
 * its own, as it always has.
 */
ma_context *inputContext() {
    static ma_context context;
    static bool ok = [] {
        const bool opened = ma_context_init(nullptr, 0, nullptr, &context) == MA_SUCCESS;
        if (!opened) LOGE("could not open a context to list inputs in");
        return opened;
    }();
    return ok ? &context : nullptr;
}

/** The server's own name for a device, where the backend has one worth keeping. */
std::string keyOf(const ma_context *context, const ma_device_id &id) {
    switch (context->backend) {
        case ma_backend_pulseaudio: return std::string(id.pulse);
        case ma_backend_alsa: return std::string(id.alsa);
#ifdef _WIN32
        case ma_backend_wasapi: {
            // An endpoint id, all ASCII ("{0.0.0.00000000}.{guid}").
            std::string key;
            for (const wchar_t *c = reinterpret_cast<const wchar_t *>(id.wasapi); *c != 0; ++c) key += static_cast<char>(*c);
            return key;
        }
#endif
        default: return {};
    }
}

/**
 * An input's id: FNV-1a of the server's name for it, or of what it is called
 * when there is none, so the one chosen is still the one chosen after it has
 * been unplugged and plugged back in. Nought is the default and never an id.
 */
int32_t idOf(const std::string &text) {
    uint32_t hash = 2166136261u;
    for (unsigned char ch : text) {
        hash ^= ch;
        hash *= 16777619u;
    }
    const auto id = static_cast<int32_t>(hash & 0x7fffffffu);
    return id == 0 ? 1 : id;
}

} // namespace

namespace {

/** The devices one way or the other, with the ids this driver knows them by. */
std::vector<AudioDriver::InputInfo> listDevices(bool capture) {
    std::vector<AudioDriver::InputInfo> out;
    ma_context *context = inputContext();
    if (context == nullptr) return out;
    ma_device_info *playbacks = nullptr, *captures = nullptr;
    ma_uint32 playCount = 0, captureCount = 0;
    if (ma_context_get_devices(context, &playbacks, &playCount, &captures, &captureCount) != MA_SUCCESS) return out;
    ma_device_info *list = capture ? captures : playbacks;
    const ma_uint32 count = capture ? captureCount : playCount;
    for (ma_uint32 i = 0; i < count; i++) {
        AudioDriver::InputInfo info;
        info.name = list[i].name;
        info.key = keyOf(context, list[i].id);
        info.id = idOf(info.key.empty() ? info.name : info.key);
        out.push_back(std::move(info));
    }
    return out;
}

/** The id [want] among the devices there are now, into [into]; false when it is not there. */
bool findDevice(bool capture, int32_t want, ma_device_id &into) {
    ma_context *context = inputContext();
    if (want == 0 || context == nullptr) return false;
    ma_device_info *playbacks = nullptr, *captures = nullptr;
    ma_uint32 playCount = 0, captureCount = 0;
    if (ma_context_get_devices(context, &playbacks, &playCount, &captures, &captureCount) != MA_SUCCESS) return false;
    ma_device_info *list = capture ? captures : playbacks;
    const ma_uint32 count = capture ? captureCount : playCount;
    for (ma_uint32 i = 0; i < count; i++) {
        const std::string key = keyOf(context, list[i].id);
        if (idOf(key.empty() ? std::string(list[i].name) : key) == want) {
            into = list[i].id;
            return true;
        }
    }
    return false;
}

} // namespace

int32_t AudioDriver::sChosenOutput = 0;
AudioDriver *AudioDriver::sLive = nullptr;

std::vector<AudioDriver::InputInfo> AudioDriver::listInputs() { return listDevices(true); }
std::vector<AudioDriver::InputInfo> AudioDriver::listOutputs() { return listDevices(false); }

void AudioDriver::chooseOutput(int32_t id) {
    if (id == sChosenOutput) return;
    sChosenOutput = id;
    if (sLive != nullptr && sLive->device != nullptr) sLive->reopen();
}

void AudioDriver::reopen() {
    const bool listening = capturer != nullptr;
    const int32_t listeningTo = actualInputDevice;
    stop();
    start();
    if (listening) startInput(listeningTo);
}

struct AudioDriver::InputQueue {
    ma_pcm_rb rb;
};

AudioDriver::AudioDriver() = default;

AudioDriver::~AudioDriver() {
    stop();
    if (sLive == this) sLive = nullptr;
}

bool AudioDriver::start() {
    if (device != nullptr) return true;
    if (!callback) {
        LOGE("start() called before registerCallback()");
        return false;
    }
    engineBlockFrames = acidulous::kBlockFrames;
    carry.assign(static_cast<size_t>(engineBlockFrames) * 2, 0.0f);
    carryFrames = 0;
    carryOffset = 0;

    ma_device_config config = ma_device_config_init(ma_device_type_playback);
    config.playback.format = ma_format_f32;
    config.playback.channels = 2;
    // The engine runs at kSampleRate; miniaudio resamples if the device will not.
    config.sampleRate = acidulous::kSampleRate;
    config.periodSizeInFrames = kPeriodFrames;
    config.periods = static_cast<ma_uint32>(std::max(2, bufferBursts));
    config.performanceProfile = ma_performance_profile_low_latency;
    config.noPreSilencedOutputBuffer = MA_TRUE;
    config.dataCallback = onOutput;
    config.pUserData = this;
    // The output chosen in Settings, found among what is there now; gone,
    // it is the default, as a vanished input is.
    ma_device_id chosen{};
    const bool found = findDevice(false, sChosenOutput, chosen);
    if (sChosenOutput != 0 && !found) LOGI("output %d is not there now; the default instead", sChosenOutput);
    if (found) config.playback.pDeviceID = &chosen;

    auto opened = std::make_unique<ma_device>();
    if (ma_device_init(inputContext(), &config, opened.get()) != MA_SUCCESS) {
        LOGE("failed to open the output");
        return false;
    }
    actualSampleRate = static_cast<int32_t>(opened->sampleRate);
    actualFramesPerBurst = static_cast<int32_t>(opened->playback.internalPeriodSizeInFrames);
    actualPeriods = static_cast<int32_t>(opened->playback.internalPeriods);
    framesWritten = 0;
    anchors[0].frame = anchors[1].frame = -1;
    device = std::move(opened);
    if (ma_device_start(device.get()) != MA_SUCCESS) {
        LOGE("failed to start the output");
        ma_device_uninit(device.get());
        device.reset();
        return false;
    }
    sLive = this;
    LOGI("stream open: %s, %s, %d Hz, period %d frames x %d, engine block %d frames",
         ma_get_backend_name(device->pContext->backend), device->playback.name, actualSampleRate, actualFramesPerBurst,
         actualPeriods, engineBlockFrames);
    return true;
}

void AudioDriver::stop() {
    if (device == nullptr) return;
    stopInput();
    ma_device_uninit(device.get());
    device.reset();
    carryFrames = 0;
    carryOffset = 0;
    LOGI("stream stopped");
}

bool AudioDriver::startInput(int32_t deviceId) {
    // Already open on the one asked for - including nought, which means
    // "whatever the system picks" and cannot be compared: see the Oboe driver.
    if (capturer != nullptr && (deviceId == 0 || deviceId == actualInputDevice)) return true;
    if (capturer != nullptr) stopInput();
    const size_t ringFrames = static_cast<size_t>(acidulous::kSampleRate) / 4; // a quarter second
    inputQueue = std::make_unique<InputQueue>();
    if (ma_pcm_rb_init(ma_format_f32, 2, static_cast<ma_uint32>(ringFrames), nullptr, nullptr, &inputQueue->rb) != MA_SUCCESS) {
        inputQueue.reset();
        return false;
    }
    inputRing.assign(ringFrames * 2, 0.0f);
    inputScratch.assign(ringFrames * 2, 0.0f);
    inputBlock.assign(static_cast<size_t>(acidulous::kBlockFrames) * 2, 0.0f);
    inputRingFrames = 0;
    inputRingRead = 0;

    ma_device_config config = ma_device_config_init(ma_device_type_capture);
    config.capture.format = ma_format_f32;
    config.capture.channels = 2;
    config.sampleRate = acidulous::kSampleRate;
    config.periodSizeInFrames = kPeriodFrames;
    config.performanceProfile = ma_performance_profile_low_latency;
    config.dataCallback = onInput;
    config.pUserData = this;
    // The one chosen, found by its id among what is there now. Gone - an
    // interface unplugged since - is the default, as it is on the phone.
    ma_context *context = inputContext();
    ma_device_id chosen{};
    const int32_t found = findDevice(true, deviceId, chosen) ? deviceId : 0;
    if (deviceId != 0 && found == 0) LOGI("input %d is not there now; the default instead", deviceId);
    if (found != 0) config.capture.pDeviceID = &chosen;
    auto opened = std::make_unique<ma_device>();
    if (ma_device_init(context, &config, opened.get()) != MA_SUCCESS) {
        LOGE("failed to open the input");
        ma_pcm_rb_uninit(&inputQueue->rb);
        inputQueue.reset();
        return false;
    }
    actualInputChannels = 2;
    actualInputRate = static_cast<int32_t>(opened->sampleRate);
    actualInputDevice = found;
    capturer = std::move(opened);
    if (ma_device_start(capturer.get()) != MA_SUCCESS) {
        LOGE("failed to start the input");
        stopInput();
        return false;
    }
    LOGI("input open: %s, %d Hz, %d ch", capturer->capture.name, actualInputRate, actualInputChannels);
    return true;
}

void AudioDriver::stopInput() {
    if (capturer == nullptr) return;
    ma_device_uninit(capturer.get());
    capturer.reset();
    ma_pcm_rb_uninit(&inputQueue->rb);
    inputQueue.reset();
    inputRingFrames = 0;
    inputRingRead = 0;
    actualInputChannels = 0;
    actualInputRate = 0;
    actualInputDevice = 0;
}

// On the capture thread: into the queue, and never waiting. A full queue is
// a hole in the recording, not a stalled input.
void AudioDriver::capture(const float *in, int32_t numFrames) {
    ma_pcm_rb *queue = inputQueue ? &inputQueue->rb : nullptr;
    if (queue == nullptr || in == nullptr) return;
    int32_t done = 0;
    while (done < numFrames) {
        ma_uint32 n = static_cast<ma_uint32>(numFrames - done);
        void *dst = nullptr;
        if (ma_pcm_rb_acquire_write(queue, &n, &dst) != MA_SUCCESS || n == 0) break;
        std::memcpy(dst, in + static_cast<size_t>(done) * 2, static_cast<size_t>(n) * 2 * sizeof(float));
        ma_pcm_rb_commit_write(queue, n);
        done += static_cast<int32_t>(n);
    }
}

// Drain whatever the capture has queued, without waiting for it.
void AudioDriver::pumpInput(int32_t frames) {
    ma_pcm_rb *queue = inputQueue ? &inputQueue->rb : nullptr;
    if (capturer == nullptr || queue == nullptr) return;
    const int32_t capacity = static_cast<int32_t>(inputRing.size() / 2);
    int32_t want = std::min(frames * 2, capacity - inputRingFrames);
    float peak = 0.0f;
    while (want > 0) {
        ma_uint32 n = static_cast<ma_uint32>(want);
        void *src = nullptr;
        if (ma_pcm_rb_acquire_read(queue, &n, &src) != MA_SUCCESS || n == 0) break;
        const auto *samples = static_cast<const float *>(src);
        for (ma_uint32 i = 0; i < n; ++i) {
            const float l = samples[i * 2];
            const float r = samples[i * 2 + 1];
            const int32_t slot = (inputRingRead + inputRingFrames) % capacity;
            inputRing[static_cast<size_t>(slot) * 2] = l;
            inputRing[static_cast<size_t>(slot) * 2 + 1] = r;
            ++inputRingFrames;
            peak = std::fmax(peak, std::fmax(std::fabs(l), std::fabs(r)));
        }
        ma_pcm_rb_commit_read(queue, n);
        want -= static_cast<int32_t>(n);
    }
    const float previous = inputPeak.load(std::memory_order_relaxed);
    if (peak > previous) inputPeak.store(peak, std::memory_order_relaxed);
}

const float *AudioDriver::nextInputBlock() {
    if (capturer == nullptr) return nullptr;
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

void AudioDriver::render(float *out, int32_t numFrames) {
    // As on the phone, and for the same reason: see the Oboe driver.
    acidulous::dsp::flushDenormalsOnce();
    const auto tCallback = std::chrono::steady_clock::now();
    const int64_t cpu0 = threadCpuUs();
    pumpInput(numFrames);

    // No presentation timestamp from the sound server, so the anchor is what
    // is known: the frame about to be written is heard once the frames
    // already queued ahead of it have played.
    {
        const int32_t rate = actualSampleRate > 0 ? actualSampleRate : acidulous::kSampleRate;
        const int32_t next = 1 - anchorSlot.load(std::memory_order_relaxed);
        anchors[next].frame = framesWritten;
        anchors[next].nanos = monotonicNanos() + static_cast<int64_t>(getBufferFrames()) * 1000000000LL / rate;
        anchorSlot.store(next, std::memory_order_release);
    }

    int32_t written = 0;
    while (written < numFrames) {
        if (carryFrames == 0) {
            callback(const_cast<float *>(nextInputBlock()), carry.data(), static_cast<unsigned long>(engineBlockFrames));
            carryFrames = engineBlockFrames;
            carryOffset = 0;
        }
        const int32_t n = std::min(carryFrames, numFrames - written);
        std::memcpy(out + static_cast<size_t>(written) * 2, carry.data() + static_cast<size_t>(carryOffset) * 2,
                    static_cast<size_t>(n) * 2 * sizeof(float));
        written += n;
        carryOffset += n;
        carryFrames -= n;
    }
    framesWritten += numFrames;

    float peak = 0.0f;
    for (int32_t i = 0; i < numFrames * 2; ++i) peak = std::fmax(peak, std::fabs(out[i]));
    const float previous = peakLevel.load(std::memory_order_relaxed);
    if (peak > previous) peakLevel.store(peak, std::memory_order_relaxed);

    const auto us = static_cast<int32_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - tCallback).count());
    const auto cpuUs = static_cast<int32_t>(threadCpuUs() - cpu0);
    int32_t seen = callbackPeakUs.load(std::memory_order_relaxed);
    while (us > seen && !callbackPeakUs.compare_exchange_weak(seen, us, std::memory_order_relaxed)) {
    }
    const int32_t wasRecent = callbackRecentUs.load(std::memory_order_relaxed);
    const int32_t faded = static_cast<int32_t>(static_cast<int64_t>(wasRecent) * 49 / 50);
    callbackRecentUs.store(us > faded ? us : faded, std::memory_order_relaxed);
    int32_t seenCpu = callbackCpuPeakUs.load(std::memory_order_relaxed);
    while (cpuUs > seenCpu && !callbackCpuPeakUs.compare_exchange_weak(seenCpu, cpuUs, std::memory_order_relaxed)) {
    }
    if (us > budgetFor(numFrames) && cpuUs * 2 < us) stalledCallbacks.fetch_add(1, std::memory_order_relaxed);
    if (us > budgetFor(numFrames)) lateCallbacks.fetch_add(1, std::memory_order_relaxed);
}

void AudioDriver::setBufferBursts(int32_t bursts) {
    bursts = std::clamp(bursts, 1, 8);
    if (bursts == bufferBursts) return;
    bufferBursts = bursts;
    // A period count is fixed when the device opens, so a change reopens it.
    if (device != nullptr) reopen();
}
