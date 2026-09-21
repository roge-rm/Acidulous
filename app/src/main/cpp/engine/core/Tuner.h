#pragma once
#include <atomic>
#include <cstdint>
#include <vector>

// Naming the note somebody is playing, from the input, while they tune it.
//
// The app has had an input since M17 and an amp since M56, and the one thing
// between a guitar and a song that neither of those covers is that the guitar
// is out of tune. This is a readout and not an effect: nothing it does reaches
// the audio, and it costs nothing at all until the record window asks for it.
//
// **It listens before the input chain.** You tune an instrument, not a
// recording, and the chain may well hold a gate that has shut on a string
// somebody is plucking gently and an amp that has buried the fundamental.
namespace acidulous::audio {

/**
 * How the pitch is found, kept out of the ring so the harness can drive it on
 * a buffer it made itself.
 *
 * Two stages, because one cannot do both jobs:
 *
 *   - **Coarse**, decimated to six kilohertz: normalised autocorrelation over
 *     every lag from the top of the range to the bottom. Cheap enough to
 *     search the whole range - a guitar's bottom B is 31 Hz and its top fret
 *     is over 1.3 kHz, which is five and a half octaves - and far too coarse
 *     to report in cents. At 6 kHz one sample of lag at 440 Hz is 73 cents.
 *   - **Fine**, at the full rate, over the handful of lags around what the
 *     coarse stage found, with a parabola through the winning peak. At 48 kHz
 *     one sample of lag at 440 Hz is 9 cents and the parabola is good for a
 *     small fraction of that.
 *
 * The **octave error** is the classic fault of every autocorrelation pitch
 * detector and it is worth naming: the correlation at twice the true lag is
 * nearly as strong as at the true one, and on a string with a weak
 * fundamental - a bridge pickup, a bass through a small speaker - it is
 * stronger. Picking the largest peak therefore reports an octave low perhaps
 * one time in five. The fix is to take the *shortest* lag whose peak is
 * within a margin of the best rather than the best itself, which is what
 * every detector that works does.
 */
struct PitchFinder {
    /** The range worth searching: a five-string bass's low B to well past a guitar's top fret. */
    static constexpr float kMinHz = 27.0f;
    static constexpr float kMaxHz = 1400.0f;
    /** What the coarse stage runs at. Two and a half times kMaxHz, and a whole divisor of 48 kHz. */
    static constexpr float kCoarseRate = 12000.0f;
    /**
     * How periodic it has to be before a note is named.
     *
     * A plucked string is above 0.9 for its whole useful life. A room, a hum
     * and a hand on the strings are under 0.5. Naming a note for something
     * this is unsure about is worse than naming none: a tuner that twitches
     * is a tuner nobody trusts.
     */
    static constexpr float kClarity = 0.72f;
    /** How close to the best peak a shorter lag has to be to win it. */
    static constexpr float kOctaveMargin = 0.86f;

    /**
     * The frequency in [mono], or 0 when there is no note in it.
     *
     * [clarity], when given, comes back with the winning correlation, which
     * is what the harness asserts against and what a meter could show.
     */
    static float find(const float *mono, int32_t frames, float sampleRate, float *clarity = nullptr);
};

/**
 * The ring the audio thread fills and the reader analyses.
 *
 * Single producer on the audio thread, single consumer on whichever thread
 * polls. The consumer copies the newest window out and checks that the write
 * index has not run past it; a window torn across a wrap is not a small error
 * in the answer but a discontinuity in the middle of it, and a discontinuity
 * has its own period.
 */
class Tuner {
  public:
    /** A little over half a second: sixteen periods of the lowest note in range. */
    static constexpr int32_t kWindow = 24576;

    Tuner() : ring(static_cast<size_t>(kWindow) * 2, 0.0f) {}

    void setEnabled(bool on) {
        if (!on) hzOut.store(0.0f, std::memory_order_relaxed);
        enabled.store(on, std::memory_order_release);
    }
    bool isEnabled() const { return enabled.load(std::memory_order_acquire); }

    /** Audio thread. Interleaved stereo, or nothing when no stream is open. */
    void push(const float *interleaved, int32_t frames);

    /**
     * Reader thread. The note in the last half second, or 0.
     *
     * Does the analysis itself rather than handing back audio, so the cost
     * lands on whoever asked and at whatever rate they ask.
     */
    float analyse(float sampleRate);

    /** The last answer, without analysing again. */
    float hz() const { return hzOut.load(std::memory_order_relaxed); }

  private:
    std::vector<float> ring;
    std::atomic<int64_t> writeIndex{0};
    std::atomic<bool> enabled{false};
    std::atomic<float> hzOut{0.0f};
    std::vector<float> window;  // reader thread only
    std::vector<float> coarse;  // reader thread only
};

} // namespace acidulous::audio
