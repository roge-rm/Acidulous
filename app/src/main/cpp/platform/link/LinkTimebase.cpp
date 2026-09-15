#include "LinkTimebase.h"

#include <ableton/Link.hpp>
#include <chrono>
#include <ctime>

namespace acidulous {

namespace {
constexpr double kDefaultBpm = 120.0;
} // namespace

struct LinkTimebase::Impl {
    explicit Impl(double bpm) : link(bpm) {}

    ableton::Link link;
    std::atomic<double> quantum{4.0};
    /** The stream's (frame, nanosecond) pair, as one word each. */
    std::atomic<int64_t> anchorFrame{-1};
    std::atomic<int64_t> anchorNanos{0};
    std::atomic<int32_t> anchorRate{0};
    std::atomic<int64_t> fallbackMicros{0};
    std::atomic<double> blockSeconds{0.0};

    /** CLOCK_MONOTONIC, which is what the audio stream stamps its anchor in. */
    static int64_t monotonicNanos() {
        ::timespec ts{};
        ::clock_gettime(CLOCK_MONOTONIC, &ts);
        return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
    }

    /**
     * How far ahead of now this block is heard, in microseconds.
     *
     * A *duration*, deliberately, and not a time. Link's clock on Android is
     * CLOCK_MONOTONIC_RAW and the audio stream's is CLOCK_MONOTONIC: the two
     * tick at very slightly different rates, so a timestamp cannot be
     * carried from one to the other - but an interval can, to a few parts
     * per million, which is nothing over the twenty milliseconds this is.
     */
    int64_t aheadMicros(int64_t framesRendered) const {
        const int64_t frame = anchorFrame.load(std::memory_order_relaxed);
        const int32_t rate = anchorRate.load(std::memory_order_relaxed);
        if (frame < 0 || rate <= 0) return fallbackMicros.load(std::memory_order_relaxed);
        const double heardNanos = static_cast<double>(anchorNanos.load(std::memory_order_relaxed)) +
                                  static_cast<double>(framesRendered - frame) * 1e9 /
                                      static_cast<double>(rate);
        const double ahead = (heardNanos - static_cast<double>(monotonicNanos())) / 1000.0;
        // Half a second of it is not latency, it is a stale anchor.
        if (ahead < 0.0 || ahead > 500000.0) return fallbackMicros.load(std::memory_order_relaxed);
        return static_cast<int64_t>(ahead);
    }
};

LinkTimebase::LinkTimebase() : impl(new Impl(kDefaultBpm)) {
    // Start/stop is always *carried*; whether the engine acts on it is the
    // engine's setting. Enabling it here costs nothing while nobody asks.
    impl->link.enableStartStopSync(true);
}

LinkTimebase::~LinkTimebase() {
    if (impl->link.isEnabled()) impl->link.enable(false);
}

void LinkTimebase::setEnabled(bool on) { impl->link.enable(on); }
bool LinkTimebase::enabled() const { return impl->link.isEnabled(); }
int32_t LinkTimebase::peers() const { return static_cast<int32_t>(impl->link.numPeers()); }

double LinkTimebase::sessionTempo() const {
    if (!impl->link.isEnabled()) return 0.0;
    return impl->link.captureAppSessionState().tempo();
}

void LinkTimebase::setQuantum(double beats) {
    impl->quantum.store(beats < 1.0 ? 1.0 : beats, std::memory_order_relaxed);
}

void LinkTimebase::setAnchor(int64_t frame, int64_t nanos, int32_t rate) {
    impl->anchorNanos.store(nanos, std::memory_order_relaxed);
    impl->anchorRate.store(rate, std::memory_order_relaxed);
    impl->anchorFrame.store(frame, std::memory_order_release);
}

void LinkTimebase::setFallbackLatency(int64_t micros) {
    impl->fallbackMicros.store(micros < 0 ? 0 : micros, std::memory_order_relaxed);
}

void LinkTimebase::setBlockFrames(int32_t frames, int32_t sampleRate) {
    if (frames <= 0 || sampleRate <= 0) return;
    impl->blockSeconds.store(static_cast<double>(frames) / static_cast<double>(sampleRate),
                             std::memory_order_relaxed);
}

void LinkTimebase::tempoFromApp(double bpm) {
    if (!impl->link.isEnabled() || bpm < 20.0 || bpm > 999.0) return;
    auto state = impl->link.captureAppSessionState();
    state.setTempo(bpm, impl->link.clock().micros());
    impl->link.commitAppSessionState(state);
}

/**
 * The audio thread's one question: where is everybody, at the moment this
 * block is heard?
 *
 * Not where they are *now*. `now` is when the block is being computed; it
 * will be heard an output buffer later, and a beat read at the wrong one of
 * those two times is late by exactly that buffer - twenty milliseconds at a
 * 960-frame burst, which is an audible slap against another machine.
 */
Timebase::State LinkTimebase::capture(int64_t framesRendered) {
    State out;
    if (!impl->link.isEnabled()) return out;

    const auto quantum = impl->quantum.load(std::memory_order_relaxed);
    const auto ahead = std::chrono::microseconds(impl->aheadMicros(framesRendered));
    const auto at = impl->link.clock().micros() + ahead;

    const auto state = impl->link.captureAudioSessionState();
    out.valid = true;
    out.bpm = state.tempo();
    out.beat = state.beatAtTime(at, quantum);
    out.quantum = quantum;
    out.playing = state.isPlaying();
    out.beatsPerBlock = impl->blockSeconds.load(std::memory_order_relaxed) * out.bpm / 60.0;
    out.peers = static_cast<int32_t>(impl->link.numPeers());
    return out;
}

void LinkTimebase::proposeTempo(double bpm) {
    if (!impl->link.isEnabled() || bpm < 20.0 || bpm > 999.0) return;
    auto state = impl->link.captureAudioSessionState();
    state.setTempo(bpm, impl->link.clock().micros());
    impl->link.commitAudioSessionState(state);
}

void LinkTimebase::proposePlaying(bool playing) {
    if (!impl->link.isEnabled()) return;
    auto state = impl->link.captureAudioSessionState();
    state.setIsPlaying(playing, impl->link.clock().micros());
    impl->link.commitAudioSessionState(state);
}

} // namespace acidulous
