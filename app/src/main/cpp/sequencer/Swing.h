#pragma once
#include <cstdint>
#include <engine/core/Constants.h>

// Swing: where a tick lands once the beat is not even.
//
// **A time warp, not a delay.** The naive way is to push every note that sits
// on an odd sixteenth later by a fixed amount, and it is wrong in a way that
// only shows on real material: a note written a tick *before* the odd
// sixteenth is not moved, a note written on it jumps ahead of it, and the
// order of the two reverses. Anything played in rather than drawn - which is
// most of what a swing setting is turned up for - is full of notes a tick or
// two off the grid, and they scatter.
//
// So the whole of time is bent instead. Each pair of subdivisions is mapped
// onto itself with its midpoint moved late, linearly on each side of that
// midpoint: continuous, monotonic, and exactly the identity at fifty percent.
// Two notes a tick apart stay a tick apart and stay in order, wherever they
// sit.
//
// The map is applied to a clip-relative tick, so a clip swings the same way
// wherever it is launched and whatever bar the song has reached.
namespace acidulous::seq {

struct Swing {
    /** Straight. The default, and the identity. */
    static constexpr float kStraight = 50.0f;
    /** As far as it goes: the midpoint three quarters of the way through the pair. */
    static constexpr float kMax = 75.0f;
    /** Two triplets against three, which is the shuffle everybody means. */
    static constexpr float kTriplet = 66.667f;

    /** A pair of sixteenths - the default unit, and what a groovebox means by swing. */
    static constexpr int64_t kSixteenths = kPPQN / 2;
    /** A pair of eighths, which is the jazz and shuffle feel. */
    static constexpr int64_t kEighths = kPPQN;

    static bool straight(float percent) { return percent <= kStraight + 0.01f; }

    /**
     * Where [tick] sounds, given [percent] swing over pairs of [pair] ticks.
     *
     * Both halves are mapped with integer arithmetic on purpose: the result
     * is a tick, and rounding it in one place means a note and its own
     * note-off cannot disagree about where the beat is.
     */
    static int64_t at(int64_t tick, float percent, int64_t pair) {
        if (straight(percent) || pair < 2 || tick < 0) return tick;
        const int64_t mid = midpoint(percent, pair);
        const int64_t half = pair / 2;
        const int64_t base = tick - tick % pair;
        const int64_t pos = tick % pair;
        const int64_t out = pos < half
                                ? pos * mid / half
                                : mid + (pos - half) * (pair - mid) / (pair - half);
        return base + out;
    }

    /**
     * The way back: the tick that would sound at [swung].
     *
     * Needed because a part played in while the swing is up is played against
     * what is already swung, so the times that arrive are in swung time. Store
     * those and they are swung a second time on the way out, and the harder
     * the setting the further the recording drifts from what was played. The
     * document holds straight time and always has; this is what puts a
     * performance back into it.
     */
    static int64_t from(int64_t swung, float percent, int64_t pair) {
        if (straight(percent) || pair < 2 || swung < 0) return swung;
        const int64_t mid = midpoint(percent, pair);
        const int64_t half = pair / 2;
        const int64_t base = swung - swung % pair;
        const int64_t pos = swung % pair;
        const int64_t out = pos < mid
                                ? pos * half / mid
                                : half + (pos - mid) * (pair - half) / (pair - mid);
        return base + out;
    }

  private:
    /**
     * Where the middle of the pair goes, in ticks, kept off both ends.
     *
     * A midpoint of nought or of the whole pair would flatten one half onto a
     * single instant - every note in it landing together - and make the
     * inverse a division by zero. At sixteenths the pair is 120 ticks, so the
     * clamp is a twentieth of it either way and nothing a setting can reach.
     */
    static int64_t midpoint(float percent, int64_t pair) {
        const float clamped = percent < kStraight ? kStraight : (percent > kMax ? kMax : percent);
        int64_t mid = static_cast<int64_t>(static_cast<float>(pair) * clamped * 0.01f + 0.5f);
        const int64_t guard = pair / 20 + 1;
        if (mid < guard) mid = guard;
        if (mid > pair - guard) mid = pair - guard;
        return mid;
    }
};

} // namespace acidulous::seq
