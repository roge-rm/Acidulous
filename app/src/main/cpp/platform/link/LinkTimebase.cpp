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
    /** The stream's (frame, nanosecond) anchor, one atomic each. */
    std::atomic<int64_t> anchorFrame{-1};
    std::atomic<int64_t> anchorNanos{0};
    std::atomic<int32_t> anchorRate{0};
    std::atomic<int64_t> fallbackMicros{0};
    std::atomic<double> blockSeconds{0.0};

    /** CLOCK_MONOTONIC, which the audio stream stamps its anchor in. */
    static int64_t monotonicNanos() {
        ::timespec ts{};
        ::clock_gettime(CLOCK_MONOTONIC, &ts);
        return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
    }

    /**
     * How far ahead of now this block is heard, in microseconds.
     *
     * A duration on purpose. On Android Link uses CLOCK_MONOTONIC_RAW and the
     * audio stream uses CLOCK_MONOTONIC, which run at slightly different
     * rates, so a timestamp can't be moved between them but a short interval
     * can.
     */
    int64_t aheadMicros(int64_t framesRendered) const {
        const int64_t frame = anchorFrame.load(std::memory_order_relaxed);
        const int32_t rate = anchorRate.load(std::memory_order_relaxed);
        if (frame < 0 || rate <= 0) return fallbackMicros.load(std::memory_order_relaxed);
        const double heardNanos = static_cast<double>(anchorNanos.load(std::memory_order_relaxed)) +
                                  static_cast<double>(framesRendered - frame) * 1e9 /
                                      static_cast<double>(rate);
        const double ahead = (heardNanos - static_cast<double>(monotonicNanos())) / 1000.0;
        // More than half a second means the anchor is stale.
        if (ahead < 0.0 || ahead > 500000.0) return fallbackMicros.load(std::memory_order_relaxed);
        return static_cast<int64_t>(ahead);
    }
};

LinkTimebase::LinkTimebase() : impl(new Impl(kDefaultBpm)) {
    // Start/stop sync is always on here. Whether the engine acts on it is an
    // engine setting.
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
 * Audio thread. The session state at the moment this block will be heard,
 * which is an output buffer after now. Reading it at now would put us late by
 * that buffer (20 ms at a 960-frame burst).
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
