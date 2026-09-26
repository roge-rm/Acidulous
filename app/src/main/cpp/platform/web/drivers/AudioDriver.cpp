#include "AudioDriver.h"

#include <algorithm>
#include <android/log.h>
#include <chrono>
#include <cmath>
#include <cstring>
#include <emscripten/emscripten.h>
#include <emscripten/webaudio.h>

#define LOG_TAG "Acidulous.Audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// The microphone, on the page's side: globalThis.acidInput holds the stream
// (which the app's permission request may already have got - see
// Platform.wasmJs.kt), whether it is wanted, and the context and node to
// connect it to. Whichever of the three arrives last connects it.
EM_JS_DEPS(acid_input, "$emscriptenGetAudioObject");

EM_JS(void, acid_input_attach, (int context, int node), {
    const s = (globalThis.acidInput ??= {});
    s.context = emscriptenGetAudioObject(context);
    s.node = emscriptenGetAudioObject(node);
    s.connect && s.connect();
});

EM_JS(void, acid_input_want, (int on), {
    const s = (globalThis.acidInput ??= {});
    s.connect = () => {
        if (!s.wanted || !s.stream || !s.context || !s.node || s.source) return;
        s.source = s.context.createMediaStreamSource(s.stream);
        s.source.connect(s.node);
        console.info('I/Acidulous.Audio: input connected: ' + (s.stream.getAudioTracks()[0]?.label || 'the microphone'));
    };
    s.wanted = !!on;
    if (on) {
        if (s.stream && s.stream.active) { s.connect(); return; }
        navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false } })
            .then((stream) => { s.stream = stream; s.connect(); })
            .catch((e) => console.warn('W/Acidulous.Audio: no microphone', e));
    } else {
        // Off is off: the tracks stopped, so the browser's recording light goes out.
        if (s.source) { s.source.disconnect(); s.source = null; }
        if (s.stream) { s.stream.getTracks().forEach((t) => t.stop()); s.stream = null; }
    }
});

namespace {

/** The audio thread's own stack, which Emscripten needs handed to it. */
alignas(16) uint8_t workletStack[128 * 1024];

int64_t nowNanos() { return static_cast<int64_t>(emscripten_get_now() * 1e6); }

bool onProcess(int numInputs, const AudioSampleFrame *inputs, int numOutputs, AudioSampleFrame *outputs, int,
               const AudioParamFrame *, void *user) {
    if (numOutputs < 1) return true;
    AudioSampleFrame &out = outputs[0];
    const int n = out.samplesPerChannel;
    float *left = out.data;
    float *right = out.numberOfChannels > 1 ? out.data + n : nullptr;
    // Nothing connected is no channels; a microphone is often one.
    const float *inLeft = nullptr;
    const float *inRight = nullptr;
    if (numInputs > 0 && inputs[0].numberOfChannels > 0 && inputs[0].samplesPerChannel == n) {
        inLeft = inputs[0].data;
        inRight = inputs[0].numberOfChannels > 1 ? inputs[0].data + n : inLeft;
    }
    static_cast<AudioDriver *>(user)->render(inLeft, inRight, left, right, n);
    return true; // keep the node alive
}

void onProcessorCreated(EMSCRIPTEN_WEBAUDIO_T context, bool success, void *user) {
    if (!success) {
        LOGE("the worklet processor could not be created");
        return;
    }
    static_cast<AudioDriver *>(user)->connect(context);
}

void onThreadStarted(EMSCRIPTEN_WEBAUDIO_T context, bool success, void *user) {
    if (!success) {
        LOGE("the audio worklet did not start: is the page cross-origin isolated?");
        return;
    }
    WebAudioWorkletProcessorCreateOptions opts{};
    opts.name = "acidulous";
    emscripten_create_wasm_audio_worklet_processor_async(context, &opts, onProcessorCreated, user);
}

} // namespace

AudioDriver *AudioDriver::sLive = nullptr;

AudioDriver::AudioDriver() = default;

AudioDriver::~AudioDriver() {
    close();
}

bool AudioDriver::start() {
    if (context != 0) {
        detached.store(false);
        LOGI("stream attached again");
        return true;
    }
    if (!callback) {
        LOGE("start() called before registerCallback()");
        return false;
    }
    carry.assign(static_cast<size_t>(acidulous::kBlockFrames) * 2, 0.0f);
    silence.assign(static_cast<size_t>(acidulous::kBlockFrames) * 2, 0.0f);
    inputRing.assign(static_cast<size_t>(kInputRingFrames) * 2, 0.0f);
    inputBlock.assign(static_cast<size_t>(acidulous::kBlockFrames) * 2, 0.0f);
    inputRingFrames = 0;
    inputRingRead = 0;
    carryFrames = 0;
    carryOffset = 0;
    framesWritten = 0;
    anchors[0].frame = anchors[1].frame = -1;

    EmscriptenWebAudioCreateAttributes attrs{};
    attrs.latencyHint = "interactive";
    attrs.sampleRate = static_cast<uint32_t>(acidulous::kSampleRate);
    attrs.renderSizeHint = AUDIO_CONTEXT_RENDER_SIZE_DEFAULT;
    context = emscripten_create_audio_context(&attrs);
    if (context == 0) {
        LOGE("no AudioContext");
        return false;
    }
    actualSampleRate = emscripten_audio_context_sample_rate(context);
    sLive = this;
    detached = false;
    workletOwns = false;
    standbyStop = false;
    standbyThread = std::thread([this] { standby(); });
    emscripten_start_wasm_audio_worklet_thread_async(context, workletStack, sizeof workletStack, onThreadStarted, this);
    LOGI("audio context %d at %d Hz; the worklet follows", context, actualSampleRate);
    return true;
}

void AudioDriver::connect(int ctx) {
    int outputChannels[1] = {2};
    EmscriptenAudioWorkletNodeCreateOptions opts{};
    opts.numberOfInputs = 1;
    opts.numberOfOutputs = 1;
    opts.outputChannelCounts = outputChannels;
    node = emscripten_create_wasm_audio_worklet_node(ctx, "acidulous", &opts, onProcess, this);
    emscripten_audio_node_connect(node, ctx, 0, 0);
    acid_input_attach(ctx, node);
    LOGI("stream open: Web Audio worklet, %d Hz, quantum %d frames, engine block %d frames",
         actualSampleRate, kQuantum, acidulous::kBlockFrames);
}

void AudioDriver::resume() {
    if (sLive != nullptr && sLive->context != 0) emscripten_resume_audio_context_sync(sLive->context);
}

double AudioDriver::liveFrames() { return sLive != nullptr ? static_cast<double>(sLive->framesWritten) : -1.0; }
int AudioDriver::liveState() { return sLive != nullptr && sLive->context != 0 ? emscripten_audio_context_state(sLive->context) : -1; }

void AudioDriver::standby() {
    std::vector<float> in(static_cast<size_t>(acidulous::kBlockFrames) * 2, 0.0f);
    std::vector<float> out(in.size(), 0.0f);
    int blocks = 0;
    // Dekker's hand-over, sequentially consistent: busy is raised before the
    // worklet's flag is read, and the worklet raises its flag before it reads
    // busy - so a block is never rendered on both threads at once.
    while (!standbyStop.load()) {
        standbyBusy.store(true);
        const bool mine = !workletOwns.load();
        if (mine && !detached.load()) callback(in.data(), out.data(), static_cast<unsigned long>(acidulous::kBlockFrames));
        standbyBusy.store(false);
        if (!mine) break;
        ++blocks;
        std::this_thread::sleep_for(std::chrono::milliseconds(4));
    }
    LOGI("stand-in rendered %d blocks before the worklet", blocks);
}

bool AudioDriver::startInput(int32_t) {
    inputOn = true;
    acid_input_want(1);
    LOGI("input wanted: the browser's microphone");
    return true;
}

void AudioDriver::stopInput() {
    if (!inputOn.exchange(false)) return;
    acid_input_want(0);
    LOGI("input stopped");
}

void AudioDriver::pushInput(const float *left, const float *right, int32_t frames) {
    const int32_t capacity = kInputRingFrames;
    float peak = 0.0f;
    for (int32_t i = 0; i < frames; ++i) {
        if (inputRingFrames == capacity) { // behind: the oldest goes
            inputRingRead = (inputRingRead + 1) % capacity;
            --inputRingFrames;
        }
        const int32_t slot = (inputRingRead + inputRingFrames) % capacity;
        const float l = left[i];
        const float r = right[i];
        inputRing[static_cast<size_t>(slot) * 2] = l;
        inputRing[static_cast<size_t>(slot) * 2 + 1] = r;
        ++inputRingFrames;
        peak = std::fmax(peak, std::fmax(std::fabs(l), std::fabs(r)));
    }
    if (peak > inputPeak.load(std::memory_order_relaxed)) inputPeak.store(peak, std::memory_order_relaxed);
}

const float *AudioDriver::nextInputBlock() {
    if (!inputOn.load(std::memory_order_relaxed)) return silence.data();
    for (int32_t i = 0; i < acidulous::kBlockFrames; ++i) {
        const bool have = inputRingFrames > 0;
        const int32_t slot = inputRingRead;
        inputBlock[static_cast<size_t>(i) * 2] = have ? inputRing[static_cast<size_t>(slot) * 2] : 0.0f;
        inputBlock[static_cast<size_t>(i) * 2 + 1] = have ? inputRing[static_cast<size_t>(slot) * 2 + 1] : 0.0f;
        if (have) {
            inputRingRead = (inputRingRead + 1) % kInputRingFrames;
            --inputRingFrames;
        }
    }
    return inputBlock.data();
}

void AudioDriver::stop() {
    // Every block the engine renders is the caller's from here: the stream
    // goes on, silent, and neither the worklet nor the stand-in is inside one.
    detached.store(true);
    while (inCallback.load() || standbyBusy.load()) {
    }
    LOGI("stream detached");
}

void AudioDriver::close() {
    stopInput();
    standbyStop = true;
    if (standbyThread.joinable()) standbyThread.join();
    if (context == 0) return;
    if (node != 0) emscripten_destroy_web_audio_node(node);
    emscripten_destroy_audio_context(context);
    node = 0;
    context = 0;
    if (sLive == this) sLive = nullptr;
    LOGI("stream stopped");
}

// On the audio worklet's thread. The engine renders interleaved blocks; the
// worklet wants the channels apart.
void AudioDriver::render(const float *inLeft, const float *inRight, float *left, float *right, int32_t numFrames) {
    if (!workletOwns.load(std::memory_order_relaxed)) {
        workletOwns.store(true);
        while (standbyBusy.load()) {
        }
    }
    // Detached for a render or a freeze: silence, and the engine left alone.
    // Raised before the flag is read, as the stand-in's busy is: see stop().
    inCallback.store(true);
    if (detached.load()) {
        std::fill(left, left + numFrames, 0.0f);
        if (right != nullptr) std::fill(right, right + numFrames, 0.0f);
        carryFrames = 0;
        inCallback.store(false);
        return;
    }
    const double t0 = emscripten_get_now();
    {
        const int32_t rate = actualSampleRate > 0 ? actualSampleRate : acidulous::kSampleRate;
        const int32_t next = 1 - anchorSlot.load(std::memory_order_relaxed);
        anchors[next].frame = framesWritten;
        anchors[next].nanos = nowNanos() + static_cast<int64_t>(getBufferFrames()) * 1000000000LL / rate;
        anchorSlot.store(next, std::memory_order_release);
    }
    if (inLeft != nullptr && inputOn.load(std::memory_order_relaxed)) pushInput(inLeft, inRight, numFrames);
    float peak = 0.0f;
    int32_t written = 0;
    while (written < numFrames) {
        if (carryFrames == 0) {
            callback(const_cast<float *>(nextInputBlock()), carry.data(), static_cast<unsigned long>(acidulous::kBlockFrames));
            carryFrames = acidulous::kBlockFrames;
            carryOffset = 0;
        }
        const int32_t n = std::min(carryFrames, numFrames - written);
        for (int32_t i = 0; i < n; ++i) {
            const float l = carry[static_cast<size_t>(carryOffset + i) * 2];
            const float r = carry[static_cast<size_t>(carryOffset + i) * 2 + 1];
            left[written + i] = l;
            if (right != nullptr) right[written + i] = r;
            peak = std::fmax(peak, std::fmax(std::fabs(l), std::fabs(r)));
        }
        written += n;
        carryOffset += n;
        carryFrames -= n;
    }
    framesWritten += numFrames;
    if (peak > peakLevel.load(std::memory_order_relaxed)) peakLevel.store(peak, std::memory_order_relaxed);

    const auto us = static_cast<int32_t>((emscripten_get_now() - t0) * 1000.0);
    int32_t seen = callbackPeakUs.load(std::memory_order_relaxed);
    while (us > seen && !callbackPeakUs.compare_exchange_weak(seen, us, std::memory_order_relaxed)) {
    }
    const int32_t faded = static_cast<int32_t>(static_cast<int64_t>(callbackRecentUs.load(std::memory_order_relaxed)) * 49 / 50);
    callbackRecentUs.store(us > faded ? us : faded, std::memory_order_relaxed);
    if (us > callbackBudgetUs()) lateCallbacks.fetch_add(1, std::memory_order_relaxed);
    inCallback.store(false);
}
