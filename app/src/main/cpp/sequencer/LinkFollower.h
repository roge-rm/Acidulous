#pragma once
#include <cmath>
#include <cstdint>
#include <engine/core/Constants.h>
#include <engine/core/Timebase.h>

// What following a network session comes down to, with no network in it.
//
// A Link session hands over a tempo and a beat that are already smooth -
// Link does the estimating - so unlike the MIDI follower next door there is
// no loop here. What is left is arithmetic, and it is the arithmetic that is
// easy to get wrong: a phase that wraps the short way round, a pull that has
// to be gentle enough not to be heard, and a downbeat that has to be caught
// in the block it falls in rather than the one after.
//
// Separated out so all of that can be proven at a desk. The engine then only
// has to hand over where it is and do what this says.

namespace acidulous::seq {

class LinkFollower {
  public:
    /**
     * How hard to lean on the tempo per tick of error. At 0.002 a full
     * eight-tick error moves the rate by 1.6%, which closes a third of a
     * beat over a bar or two without being heard as wobble.
     */
    static constexpr double kPull = 0.002;
    /** No more than this many ticks of error are acted on at once. */
    static constexpr double kMaxPullTicks = 8.0;

    struct Advice {
        /** Run the clock at this. */
        double framesPerTick = 0.0;
        /** The phase error, in ticks: ours minus theirs, shortest way round. */
        double errorTicks = 0.0;
        /** A downbeat falls inside this block: a waiting transport starts. */
        bool downbeat = false;
    };

    /**
     * [ourTickInBar] is where we are in our own bar, [barTicks] how long that
     * bar is, and [framesPerTickAtTempo] how long a tick would be at the
     * session's tempo. [pulling] is false when the phase is not to be
     * corrected - stopped, or still waiting to start - in which case the
     * tempo is taken but the bar line is left alone.
     */
    static Advice advise(const Timebase::State &s,
                         double ourTickInBar,
                         double barTicks,
                         double framesPerTickAtTempo,
                         bool pulling) {
        Advice out;
        out.framesPerTick = framesPerTickAtTempo;
        if (!s.valid || s.quantum <= 0.0 || barTicks <= 0.0) return out;

        // Does the session's bar line fall inside this block? Asked of the
        // block's own span rather than of a single instant, because a block
        // is 1.3 ms and a downbeat that is only ever *looked at* is missed.
        const double endBeat = s.beat + s.beatsPerBlock;
        out.downbeat = std::floor(s.beat / s.quantum) != std::floor(endBeat / s.quantum);

        if (!pulling) return out;

        // Phase, both as a fraction of a bar, so a 7/8 bar and a 4/4 quantum
        // still compare. The wrap is the point: at 0.02 and 0.98 we are two
        // hundredths apart, not ninety-six.
        const double ours = wrap01(std::fmod(ourTickInBar, barTicks) / barTicks);
        const double theirs = wrap01(std::fmod(s.beat, s.quantum) / s.quantum);
        double err = ours - theirs;
        if (err > 0.5) err -= 1.0;
        if (err < -0.5) err += 1.0;
        out.errorTicks = err * barTicks;

        const double pull = out.errorTicks > kMaxPullTicks
                                ? kMaxPullTicks
                                : (out.errorTicks < -kMaxPullTicks ? -kMaxPullTicks : out.errorTicks);
        // Ahead of them means our ticks must get longer, which is what the
        // plus sign is: a positive error is us being further through the bar.
        out.framesPerTick = framesPerTickAtTempo * (1.0 + pull * kPull);
        return out;
    }

  private:
    static double wrap01(double v) { return v < 0.0 ? v + 1.0 : v; }
};

} // namespace acidulous::seq
