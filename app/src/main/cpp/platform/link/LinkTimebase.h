#pragma once
#include <atomic>
#include <cstdint>
#include <engine/core/Timebase.h>
#include <memory>

// Ableton Link, behind the engine's Timebase.
//
// Everything that knows Link exists is in here and in the .cpp: the engine
// sees an interface with a tempo and a beat on it, which is what lets the
// following be proven in a harness with no network in it at all.
//
// Link is header-only and drags in asio, so `ableton::Link` is held through
// a pimpl - otherwise every file that mentions the engine host would compile
// four megabytes of networking headers.
namespace acidulous {

class LinkTimebase final : public Timebase {
  public:
    LinkTimebase();
    ~LinkTimebase() override;

    /** UI thread. Off means the peer discovery sockets are closed. */
    void setEnabled(bool on);
    bool enabled() const;

    /** How many other machines are in the session. UI thread. */
    int32_t peers() const;
    /** The session tempo, or 0 when it is off. UI thread. */
    double sessionTempo() const;

    /**
     * Beats to a bar. Set from the song's signature, because the bar is what
     * a phase means: four peers in 4/4 and one in 7/8 would otherwise agree
     * on the beat and disagree about where the bar starts.
     */
    void setQuantum(double beats);

    /**
     * The stream's own tie between the engine's frame count and the clock:
     * frame [frame] is heard at [nanos] on CLOCK_MONOTONIC, at [rate] Hz.
     * Refreshed from the UI thread; without one, [fallbackMicros] stands in.
     *
     * Link is told when a block will be *heard*, not when it was computed;
     * getting that wrong puts us out by exactly the output buffer, which at
     * a 960-frame burst is twenty milliseconds - an audible slap against
     * another machine.
     */
    void setAnchor(int64_t frame, int64_t nanos, int32_t rate);
    void setFallbackLatency(int64_t micros);

    /** UI thread: offer the song's tempo without waiting for a block. */
    void tempoFromApp(double bpm);

    // --- Timebase, on the audio thread --------------------------------------
    State capture(int64_t framesRendered) override;
    void proposeTempo(double bpm) override;
    void proposePlaying(bool playing) override;

    /** Frames in an engine block, for working out where the block ends. */
    void setBlockFrames(int32_t frames, int32_t sampleRate);

  private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};

} // namespace acidulous
