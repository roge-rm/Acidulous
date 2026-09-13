#pragma once
#include <cstdint>

// Somebody else's clock, with no idea whose.
//
// The engine follows this; Ableton Link implements it over the network. The
// interface is here rather than in the library for two reasons: nothing in
// engine/ then has to include a networking stack, and the following - the
// tempo, the phase pull, the waiting for a downbeat - can be proven in a
// harness against a timebase that is twenty lines of arithmetic.
namespace acidulous {

class Timebase {
  public:
    virtual ~Timebase() = default;

    struct State {
        /** Is there a session to follow? False while it is switched off. */
        bool valid = false;
        double bpm = 120.0;
        /**
         * Where the session is, in beats, *at the moment this block will be
         * heard* - not when it is rendered. The two differ by the whole
         * output buffer, which is the difference between playing in time and
         * playing ten milliseconds late.
         */
        double beat = 0.0;
        /** Beats to a bar: what a phase is measured against. */
        double quantum = 4.0;
        /** Is the session running? Only meaningful with start/stop sync on. */
        bool playing = false;
        /** Beats per block, so a caller can see where the block ends. */
        double beatsPerBlock = 0.0;
    };

    /**
     * Audio thread, once a block. Must not lock, allocate, or block - Link's
     * own audio-thread capture is written to that standard and this is a
     * thin wrapper over it.
     *
     * [framesRendered] is the engine's own frame count at the start of this
     * block, which is what lets the implementation work out *when the block
     * will be heard* rather than when it was computed.
     */
    virtual State capture(int64_t framesRendered) = 0;

    /**
     * Audio thread: how many beats are in our bar. The engine tells the
     * timebase rather than the other way round, because the bar is the
     * song's - a scene in 7/8 measures its phase against seven eighths.
     */
    virtual void setQuantum(double beats) = 0;

    /** Audio thread: the tempo the song wants, offered to the session. */
    virtual void proposeTempo(double bpm) = 0;

    /** Audio thread: we started or stopped, and everyone should know. */
    virtual void proposePlaying(bool playing) = 0;
};

} // namespace acidulous
