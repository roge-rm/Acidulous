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

// The microphone on the page's side. globalThis.acidInput holds the stream
// (the app's permission request may already have it, see Platform.wasmJs.kt),
// whether it's wanted, and the context and node to connect it to. Whichever
// of the three arrives last connects it.
EM_JS_DEPS(acid_input, "$emscriptenGetAudioObject");

EM_JS(void, acid_input_attach, (int context, int node), {
    const s = (globalThis.acidInput ??= {});
    // After a reopen the microphone moves to the new context.
    if (s.source) { s.source.disconnect(); s.source = null; }
    s.context = emscriptenGetAudioObject(context);
    s.node = emscriptenGetAudioObject(node);
    s.connect && s.connect();
});

// [on] is 0 for off, 1 for the raw microphone, 2 for the browser's cleaned
// one. Asking for the other kind while it's open gets a new stream.
EM_JS(void, acid_input_want, (int on), {
    const s = (globalThis.acidInput ??= {});
    s.connect = () => {
        if (!s.wanted || !s.stream || !s.context || !s.node || s.source) return;
        s.source = s.context.createMediaStreamSource(s.stream);
        s.source.connect(s.node);
        console.info('I/Acidulous.Audio: input connected: ' + (s.stream.getAudioTracks()[0]?.label || 'the microphone'));
    };
    s.wanted = !!on;
    const clean = on === 2;
    if (on && s.stream && s.stream.active && s.clean !== clean) {
        if (s.source) { s.source.disconnect(); s.source = null; }
        s.stream.getTracks().forEach((t) => t.stop());
        s.stream = null;
    }
    if (on) {
        if (s.stream && s.stream.active) { s.connect(); return; }
        s.clean = clean;
        // Echo cancelling stays off either way: it would take the app's own
        // playback out of a take.
        navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: clean, autoGainControl: clean } })
            .then((stream) => { s.stream = stream; s.connect(); })
            .catch((e) => console.warn('W/Acidulous.Audio: no microphone', e));
    } else {
        // Stop the tracks so the browser's recording indicator goes out.
        if (s.source) { s.source.disconnect(); s.source = null; }
        if (s.stream) { s.stream.getTracks().forEach((t) => t.stop()); s.stream = null; }
    }
});

// The context's latency, which only the page's thread can read. Written to
// the driver every second until the context closes.
EM_JS(void, acid_latency_watch, (int context, double *out), {
    const c = emscriptenGetAudioObject(context);
    const read = () => {
        if (c.state === 'closed') { clearInterval(timer); return; }
        _acid_latency_set(out, (c.baseLatency || 0) + (c.outputLatency || 0));
    };
    const timer = setInterval(read, 1000);
    read();
});

/**
 * Actually closes the context. Emscripten's destroy only suspends it, which
 * keeps the device open, and throws if called after a close. The handle stays
 * in Emscripten's table, one closed context per reopen.
 */
EM_JS(void, acid_context_close, (int context), {
    const c = emscriptenGetAudioObject(context);
    c && c.state !== 'closed' && c.close().catch(() => {});
});

// The output on the page's side. globalThis.acidOutput holds the device the
// app chose (WebHost.kt) and apply() moves the live context to it. Called here
// for each new context and by the page when the choice changes. Browsers
// without setSinkId just use their default.
EM_JS(void, acid_output_attach, (int context), {
    const o = (globalThis.acidOutput ??= {});
    const c = emscriptenGetAudioObject(context);
    o.apply = () => {
        if (!c.setSinkId || c.state === 'closed') return;
        const id = o.sinkId || '';
        if (c.sinkId === id) return;
        c.setSinkId(id)
            .then(() => console.info('I/Acidulous.Audio: output ' + (o.sinkLabel || 'the default')))
            .catch((e) => console.warn('W/Acidulous.Audio: output not changed', e));
    };
    o.apply();
});

extern "C" EMSCRIPTEN_KEEPALIVE void acid_latency_set(double *out, double seconds) {
    reinterpret_cast<std::atomic<double> *>(out)->store(seconds, std::memory_order_relaxed);
}

namespace {

/**
 * The time for the audio thread. An AudioWorklet has no performance.now() and
 * Date.now() only has whole milliseconds, which is too coarse for the load
 * meter and timings. A driver thread with the real clock writes the time here
 * every quarter millisecond and the worklet's performance.now() reads it
 * (acid_clock_ms, worklet-clock.js).
 */
std::atomic<double> clockMs{0.0};
constexpr auto kClockTick = std::chrono::microseconds(250);

/**
 * The audio thread's stack, which Emscripten needs us to provide. There are
 * two so a reopened stream's worklet doesn't share a stack with the old one.
 * Each also holds what the worklet's callbacks get: the driver and their
 * context, so a leftover worklet from before a reopen leaves the driver alone.
 */
constexpr int kStreams = 2;
alignas(16) uint8_t workletStacks[kStreams][128 * 1024];
struct Stream {
    AudioDriver *driver = nullptr;
    int context = 0;
};
Stream streams[kStreams];

/** The buffer setting as a latency hint (see the top of AudioDriver.h). */
const char *latencyHint(int32_t bursts) { return bursts <= 1 ? "interactive" : bursts <= 2 ? "balanced" : "playback"; }

int64_t nowNanos() { return static_cast<int64_t>(emscripten_get_now() * 1e6); }

bool onProcess(int numInputs, const AudioSampleFrame *inputs, int numOutputs, AudioSampleFrame *outputs, int,
               const AudioParamFrame *, void *user) {
    if (numOutputs < 1) return true;
    AudioSampleFrame &out = outputs[0];
    const int n = out.samplesPerChannel;
    float *left = out.data;
    float *right = out.numberOfChannels > 1 ? out.data + n : nullptr;
    // No input connected means no channels. A microphone is often mono.
    const float *inLeft = nullptr;
    const float *inRight = nullptr;
    if (numInputs > 0 && inputs[0].numberOfChannels > 0 && inputs[0].samplesPerChannel == n) {
        inLeft = inputs[0].data;
        inRight = inputs[0].numberOfChannels > 1 ? inputs[0].data + n : inLeft;
    }
    auto *stream = static_cast<Stream *>(user);
    if (!stream->driver->isCurrent(stream->context)) {
        std::fill(out.data, out.data + static_cast<size_t>(n) * out.numberOfChannels, 0.0f);
        return false; // left over from a reopen, let it go
    }
    stream->driver->render(inLeft, inRight, left, right, n);
    return true; // keep the node alive
}

void onProcessorCreated(EMSCRIPTEN_WEBAUDIO_T context, bool success, void *user) {
    auto *stream = static_cast<Stream *>(user);
    if (!stream->driver->isCurrent(context)) return;
    if (!success) {
        LOGE("the worklet processor could not be created");
        return;
    }
    stream->driver->connect(context, stream);
}

void onThreadStarted(EMSCRIPTEN_WEBAUDIO_T context, bool success, void *user) {
    if (!static_cast<Stream *>(user)->driver->isCurrent(context)) return;
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
    detached = false;
    if (!openContext()) return false;
    clockStop = false;
    clockThread = std::thread([this] {
        while (!clockStop.load(std::memory_order_relaxed)) {
            clockMs.store(emscripten_get_now(), std::memory_order_relaxed);
            std::this_thread::sleep_for(kClockTick);
        }
    });
    return true;
}

bool AudioDriver::openContext() {
    EmscriptenWebAudioCreateAttributes attrs{};
    attrs.latencyHint = latencyHint(bufferBursts);
    attrs.sampleRate = static_cast<uint32_t>(acidulous::kSampleRate);
    attrs.renderSizeHint = AUDIO_CONTEXT_RENDER_SIZE_DEFAULT;
    const int made = emscripten_create_audio_context(&attrs);
    if (made == 0) {
        LOGE("no AudioContext");
        return false;
    }
    context = made;
    actualSampleRate = emscripten_audio_context_sample_rate(made);
    sLive = this;
    latencySeconds = 0.0;
    acid_latency_watch(made, reinterpret_cast<double *>(&latencySeconds));
    acid_output_attach(made);
    workletOwns = false;
    standbyStop = false;
    standbyThread = std::thread([this] { standby(); });
    const int slot = generation++ % kStreams;
    streams[slot] = {this, made};
    emscripten_start_wasm_audio_worklet_thread_async(made, workletStacks[slot], sizeof workletStacks[slot], onThreadStarted, &streams[slot]);
    LOGI("audio context %d at %d Hz, latency hint %s; the worklet follows", made, actualSampleRate, attrs.latencyHint);
    return true;
}

void AudioDriver::setBufferBursts(int32_t bursts) {
    if (bursts == bufferBursts) return;
    bufferBursts = bursts;
    if (context != 0) reopen();
}

void AudioDriver::reopen() {
    // Detach the engine like stop() does, then close. The stand-in renders
    // until the new worklet's first quantum, same as at startup.
    const bool wasDetached = detached.exchange(true);
    while (inCallback.load() || standbyBusy.load()) {
    }
    standbyStop = true;
    if (standbyThread.joinable()) standbyThread.join();
    const int old = context;
    const bool playing = emscripten_audio_context_state(old) == 1;
    context = 0;
    if (node != 0) emscripten_destroy_web_audio_node(node);
    node = 0;
    acid_context_close(old);
    carryFrames = 0;
    carryOffset = 0;
    detached.store(wasDetached);
    if (!openContext()) return;
    // Sound was already allowed, so don't wait for another click.
    if (playing) emscripten_resume_audio_context_sync(context);
}

void AudioDriver::connect(int ctx, void *stream) {
    int outputChannels[1] = {2};
    EmscriptenAudioWorkletNodeCreateOptions opts{};
    opts.numberOfInputs = 1;
    opts.numberOfOutputs = 1;
    opts.outputChannelCounts = outputChannels;
    node = emscripten_create_wasm_audio_worklet_node(ctx, "acidulous", &opts, onProcess, stream);
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
    // Dekker-style hand-over with sequentially consistent atomics: busy is set
    // before reading the worklet's flag, and the worklet sets its flag before
    // reading busy, so a block is never rendered on both threads at once.
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
    acid_input_want(inputClean ? 2 : 1);
    LOGI("input wanted: the browser's microphone");
    return true;
}

void AudioDriver::stopInput() {
    if (!inputOn.exchange(false)) return;
    acid_input_want(0);
    LOGI("input stopped");
}

void AudioDriver::setInputClean(bool on) {
    if (on == inputClean) return;
    inputClean = on;
    if (inputOn.load()) acid_input_want(inputClean ? 2 : 1);
}

void AudioDriver::pushInput(const float *left, const float *right, int32_t frames) {
    const int32_t capacity = kInputRingFrames;
    float peak = 0.0f;
    for (int32_t i = 0; i < frames; ++i) {
        if (inputRingFrames == capacity) { // full: drop the oldest
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
    // From here the caller renders the engine's blocks. The stream keeps
    // running silently, and we wait until neither the worklet nor the stand-in
    // is inside a block.
    detached.store(true);
    while (inCallback.load() || standbyBusy.load()) {
    }
    LOGI("stream detached");
}

void AudioDriver::close() {
    stopInput();
    standbyStop = true;
    if (standbyThread.joinable()) standbyThread.join();
    clockStop = true;
    if (clockThread.joinable()) clockThread.join();
    if (context == 0) return;
    const int old = context;
    context = 0;
    if (node != 0) emscripten_destroy_web_audio_node(node);
    acid_context_close(old);
    node = 0;
    if (sLive == this) sLive = nullptr;
    LOGI("stream stopped");
}

// On the audio worklet's thread. The engine renders interleaved blocks and the
// worklet wants separate channels.
void AudioDriver::render(const float *inLeft, const float *inRight, float *left, float *right, int32_t numFrames) {
    if (!workletOwns.load(std::memory_order_relaxed)) {
        workletOwns.store(true);
        while (standbyBusy.load()) {
        }
    }
    // Detached for a render or freeze: output silence and leave the engine
    // alone. Set before reading the flag, like the stand-in's busy (see stop()).
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

/** The time the clock thread last wrote, in ms since 1970, or 0 before it has (see worklet-clock.js). */
extern "C" EMSCRIPTEN_KEEPALIVE double acid_clock_ms() { return clockMs.load(std::memory_order_relaxed); }
