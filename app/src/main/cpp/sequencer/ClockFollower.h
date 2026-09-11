#pragma once
#include <cmath>
#include <cstdint>
#include <engine/core/Constants.h>

// Following somebody else's clock.
//
// Twenty-four pulses a quarter note arrive, each one stamped with the frame
// it was heard at. The naive reading - bpm = 60e9 / (24 x the last gap) - is
// unusable: every pulse carries the jitter of the transport it came over,
// and a tempo recomputed from each one makes the whole song wobble at
// whatever rate the jitter happens to have. USB is a millisecond of it; BLE
// batches on a 7.5-15 ms connection interval and is far worse.
//
// So this is a delay-locked loop over the *period*, the same second-order
// loop a DAW uses. It keeps where it expects the next pulse and how long a
// pulse lasts, and corrects both by the error between expectation and
// arrival - the rate slowly, the phase a little faster. Its bandwidth is
// well under a hertz, so jitter is averaged away and a real tempo change is
// still followed within a bar or two.
//
// Position, and therefore everything the sequencer does with it, is read off
// the *model* rather than off the last arrival. That is the whole point: the
// model is smooth, and the arrivals are not.
//
// Pure arithmetic over frames. No audio, no clock, no transport - which is
// why it can be proven before it is wired to any of them.

namespace acidulous::seq {

class ClockFollower {
  public:
    /** Ticks between one MIDI clock pulse and the next: 240 / 24. */
    static constexpr int32_t kTicksPerPulse = kPPQN / 24;

    void reset(float sr) {
        sampleRate = sr > 0.0f ? sr : static_cast<float>(kSampleRate);
        seen = 0;
        gathered = 0;
        anchorPulse = 0;
        anchorFrame = 0.0;
        expected = 0.0;
        periodFrames = 0.0;
        lastFrame = 0;
        errorFrames = 0.0;
        settled = 0;
    }

    /** Hertz. Lower follows jitter less and a real tempo change slower. */
    void setBandwidth(float hz) { bandwidth = hz < 0.001f ? 0.001f : (hz > 5.0f ? 5.0f : hz); }

    /**
     * A clock byte arrived, heard at [frame].
     *
     * The first several are spent working out how long a pulse is, from the
     * *median* of the intervals rather than from the first one. A single
     * interval is a terrible estimate over a transport that jitters by half
     * a pulse - it can read half the true tempo - and a loop seeded with
     * half the true period never recovers, because the guard that catches a
     * runaway is itself scaled to the period and shrinks with it. The median
     * throws the outliers away instead of averaging them in.
     */
    void pulse(int64_t frame) {
        if (seen == 0) {
            anchorPulse = 0;
            anchorFrame = static_cast<double>(frame);
            lastFrame = frame;
            gathered = 0;
            seen = 1;
            return;
        }
        anchorPulse += 1;

        if (seen == 1) {
            if (gathered < kGather) {
                intervals[gathered++] = frame - lastFrame;
            }
            lastFrame = frame;
            if (gathered < kGather) {
                return;
            }
            int64_t sorted[kGather];
            for (int i = 0; i < kGather; ++i) {
                sorted[i] = intervals[i];
            }
            for (int i = 1; i < kGather; ++i) { // a sort this small is a sort like any other
                const int64_t v = sorted[i];
                int j = i - 1;
                while (j >= 0 && sorted[j] > v) { sorted[j + 1] = sorted[j]; --j; }
                sorted[j + 1] = v;
            }
            periodFrames = static_cast<double>(sorted[kGather / 2]);
            periodFrames = clampPeriod(periodFrames);
            anchorFrame = static_cast<double>(frame);
            expected = anchorFrame + periodFrames;
            settled = 0;
            seen = 2;
            return;
        }

        lastFrame = frame;
        // Where the loop thought this pulse would land, against where it did.
        const double e = static_cast<double>(frame) - expected;
        errorFrames = e;

        // A jump far larger than a pulse is not jitter - it is the master
        // having been stopped, relocated or unplugged. Work the period out
        // again from scratch rather than re-anchoring on the old one, which
        // is how a wrong period used to survive for ever.
        if (std::fabs(e) > periodFrames * 4.0) {
            seen = 1;
            gathered = 0;
            settled = 0;
            return;
        }

        // No single arrival may move the model by more than half a pulse.
        // Over a transport that batches - Bluetooth does, on a 7.5 to 15 ms
        // connection interval - two pulses can arrive close enough together
        // to land out of order, and an unclamped loop takes that at face
        // value and walks away. Clamped, the same noise simply averages.
        const double bound = periodFrames * 0.5;
        const double ce = e > bound ? bound : (e < -bound ? -bound : e);

        // Second-order loop. omega is in radians per pulse, so a bandwidth
        // in hertz has to be divided by how many pulses a second there are.
        const double pulseRate = sampleRate / (periodFrames > 1.0 ? periodFrames : 1.0);
        const double omega = 6.283185307179586 * static_cast<double>(bandwidth) / pulseRate;
        const double b = 1.4142135623730951 * omega; // 2 zeta omega, zeta = 1/sqrt(2)
        const double c = omega * omega;

        anchorFrame = expected + b * ce; // the corrected position of *this* pulse
        periodFrames = clampPeriod(periodFrames + c * ce);
        expected = anchorFrame + periodFrames;

        if (std::fabs(e) < periodFrames * 0.05) {
            if (settled < 64) {
                ++settled;
            }
        } else {
            settled = 0;
        }
    }

    /** Nothing for a few pulses running: the master has gone quiet. */
    bool stale(int64_t frameNow) const {
        if (seen < 2) {
            return true;
        }
        return static_cast<double>(frameNow - lastFrame) > periodFrames * 8.0;
    }

    bool running() const { return seen >= 2; }
    /** Settled for long enough to be worth trusting. */
    bool locked() const { return settled >= 8; }

    double framesPerPulse() const { return periodFrames; }
    double framesPerTick() const {
        return periodFrames > 0.0 ? periodFrames / static_cast<double>(kTicksPerPulse) : 0.0;
    }

    float bpm() const {
        if (periodFrames <= 0.0) {
            return 0.0f;
        }
        return static_cast<float>(60.0 * static_cast<double>(sampleRate) / (periodFrames * 24.0));
    }

    /**
     * Where the external clock is, in ticks, at [frame] - read off the model
     * and not off the last arrival, so it does not carry the jitter.
     */
    double tickAt(int64_t frame) const {
        if (seen < 2) {
            return 0.0;
        }
        const double perTick = framesPerTick();
        if (perTick <= 0.0) {
            return 0.0;
        }
        return static_cast<double>(anchorPulse) * kTicksPerPulse +
               (static_cast<double>(frame) - anchorFrame) / perTick;
    }

    /** The last pulse's error, in milliseconds, for the readout. */
    float phaseErrorMs() const {
        return static_cast<float>(errorFrames * 1000.0 / static_cast<double>(sampleRate));
    }
    int64_t pulseCount() const { return anchorPulse; }

    /** A locate arrived: the external position is now this, in ticks. */
    void relocate(int64_t tick) { anchorPulse = tick / kTicksPerPulse; }

  private:
    /** Enough intervals for a median to mean something, at 48 a second. */
    static constexpr int kGather = 9;

    /** Twenty to three hundred a minute. Outside that it is not a tempo. */
    double clampPeriod(double p) const {
        const double fastest = static_cast<double>(sampleRate) * 60.0 / (300.0 * 24.0);
        const double slowest = static_cast<double>(sampleRate) * 60.0 / (20.0 * 24.0);
        return p < fastest ? fastest : (p > slowest ? slowest : p);
    }

    int64_t intervals[kGather] = {};
    int32_t gathered = 0;
    float sampleRate = static_cast<float>(kSampleRate);
    float bandwidth = 0.5f;
    int32_t seen = 0;
    int32_t settled = 0;
    int64_t anchorPulse = 0;
    double anchorFrame = 0.0;
    double expected = 0.0;
    double periodFrames = 0.0;
    int64_t lastFrame = 0;
    double errorFrames = 0.0;
};

} // namespace acidulous::seq
