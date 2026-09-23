#pragma once

#include <engine/core/Constants.h>
#include <engine/core/Params.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace acidulous {

/**
 * The effects you hold rather than set: beat repeat, tape stop, and a pad
 * with a filter across it and a delay throw up it.
 *
 * They run on the whole mix, after the master inserts and before the master
 * fader and the limiter, in that order: what is repeated is what the tape
 * stops, and what the filter shapes is what gets thrown into the echo.
 *
 * **Held parameters are ordinary parameters.** Repeat, stop and the pad's two
 * axes arrive as `ParamMessage`s on `Unit::Perform`, so pressing one while
 * recording writes a lane the way turning a knob does, and a lane plays it
 * back the same way. The rest (how long a stop takes, the echo's time and
 * feedback) are the song's settings, in the same table.
 *
 * **Untouched, the mix comes out bit for bit.** Every stage has a resting
 * state that returns its input unmodified, and the echo sleeps once its tail
 * has gone quiet. Exports of songs that never used this must not change.
 */
class Perform {
  public:
    enum P : int32_t {
        /** 0 off; 1..5 a slice of 1, 1/2, 1/4, 1/8 or 1/16 of a beat. */
        Repeat,
        /** Held: the tape slows to a stop. Let go and it spins back up. */
        Stop,
        /** The pad's filter: low pass left of centre, high pass right of it. */
        X,
        /** The pad's throw: how much of the mix goes into the echo. */
        Y,
        /** How long the tape takes to stop: 1/4, 1/2, 1 or 2 beats. */
        StopLen,
        /** The echo's time: 1/16, 1/8, dotted 1/8, 1/4 or dotted 1/4. */
        ThrowTime,
        /** The echo's feedback. */
        Feedback,
        Count
    };

    /** Each buffer's length: about 2.7 s at 48 kHz, which bounds every time here. */
    static constexpr int32_t kSize = 1 << 17;
    static constexpr int32_t kMask = kSize - 1;

    Perform() {
        static const ParamDef kDefs[Count] = {
            {"repeat", 0.0f, 5.0f, 0.0f, Curve::Stepped, 6, ""},
            {"stop", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
            {"x", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
            {"y", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
            {"stoplen", 0.0f, 3.0f, 2.0f, Curve::Stepped, 4, ""},
            {"throwtime", 0.0f, 4.0f, 2.0f, Curve::Stepped, 5, ""},
            {"feedback", 0.0f, 0.9f, 0.55f, Curve::Linear, 0, ""},
        };
        params_.init(kDefs, Count);
        // Allocated here as well as in [prepare], so an engine that is never
        // started - every host harness - has buffers to write to.
        prepare(static_cast<float>(kSampleRate));
    }

    /** Not the audio thread: this is where the buffers are allocated. */
    void prepare(float sampleRate) {
        sr = sampleRate;
        ring[0].assign(kSize, 0.0f);
        ring[1].assign(kSize, 0.0f);
        slice[0].assign(kSize, 0.0f);
        slice[1].assign(kSize, 0.0f);
        echo[0].assign(kSize, 0.0f);
        echo[1].assign(kSize, 0.0f);
        smoothA = 1.0f - std::exp(-1.0f / (0.005f * sr));
        edgeFrames = std::max(1, static_cast<int32_t>(0.0015f * sr));
        mixStep = 1.0f / std::max(1.0f, 0.005f * sr);
        rejoinStep = 1.0f / std::max(1.0f, 0.010f * sr);
        params_.jumpAll();
        reset();
    }

    ParamSet &params() { return params_; }

    /**
     * Back to a known state; the held controls are [release]'s.
     *
     * Only the echo is cleared. This runs on the audio thread at a panic, and
     * the other two buffers are never read before they are written: the
     * repeat reads no further back than [filled], which starts again at
     * nought, and the tape reads from where it was pressed.
     */
    void reset() {
        for (int c = 0; c < 2; ++c) {
            std::fill(echo[c].begin(), echo[c].end(), 0.0f);
            ic1[c] = ic2[c] = 0.0f;
            fbLow[c] = fbHigh[c] = 0.0f;
        }
        w = 0;
        filled = 0;
        repHeld = false;
        repActive = false;
        repMix = 0.0f;
        repK = 0;
        tape = Tape::Off;
        rate = 1.0f;
        rejoin = 0.0f;
        xs = 0.5f;
        ys = 0.0f;
        filterOn = false;
        echoLen = -1.0f;
        lastLoud = -(int64_t{1} << 40);
    }

    /** Every held control back to rest: a transport stop, or a panic. */
    void release() {
        params_.set(Repeat, 0.0f);
        params_.set(Stop, 0.0f);
        params_.set(X, 0.5f);
        params_.set(Y, 0.0f);
    }

    /**
     * Where the transport is at the start of the next block, in fractional
     * ticks. A repeat started while playing takes its slice from the grid
     * line before the press, so it lands in time; stopped, from the press.
     *
     * Fractional because a block is well under a tick (64 frames against a
     * hundred at 120 bpm): the whole ticks the clock hands the players are up
     * to a tick late, and two of them are often the same number while the
     * transport is running.
     */
    void setTransport(bool playing, double tick) {
        transportPlaying = playing;
        transportTick = tick;
    }

    /** In place, on the mix. */
    void process(float *L, float *R, int32_t frames, float bpm) {
        params_.tick();
        const float spb = 60.0f / std::max(bpm, 1.0f) * sr; // samples per beat
        const bool playing = transportPlaying;

        // --- Repeat: engage, change length, let go -------------------------
        const int32_t k = static_cast<int32_t>(params_.get(Repeat) + 0.5f);
        if (k > 0) {
            const float beats = 1.0f / static_cast<float>(1 << (k - 1));
            const int32_t len = std::clamp(static_cast<int32_t>(beats * spb + 0.5f), 64, kSize);
            if (!repActive) {
                // A fresh slice, begun at the last grid line of its own length
                // - the part since then is already in the ring.
                int32_t offset = 0;
                if (playing) {
                    const double tickLen = static_cast<double>(beats) * kPPQN;
                    const double perTick = static_cast<double>(len) / tickLen;
                    const double into = std::fmod(std::max(0.0, transportTick), tickLen);
                    offset = std::clamp(static_cast<int32_t>(into * perTick + 0.5), 0, len - 1);
                }
                for (int32_t j = 0; j < offset; ++j) {
                    const int64_t at = w - offset + j;
                    const bool known = w - at <= filled;
                    slice[0][j] = known ? ring[0][at & kMask] : 0.0f;
                    slice[1][j] = known ? ring[1][at & kMask] : 0.0f;
                }
                repCaptured = offset;
                repElapsed = offset;
                repLen = len;
                repActive = true;
            } else if (k != repK) {
                // Shorter is always there; longer only as far as was caught.
                repLen = repCaptured >= repLen ? std::min(len, repCaptured) : len;
            }
            repK = k;
            repHeld = true;
        } else {
            repHeld = false;
        }

        // --- Tape: stop and start ------------------------------------------
        const bool stopHeld = params_.get(Stop) >= 0.5f;
        const float stopBeats = 0.25f * static_cast<float>(1 << static_cast<int32_t>(params_.get(StopLen) + 0.5f));
        const float stopFrames = std::min(stopBeats * spb, static_cast<float>(kSize) * 0.5f);
        if (stopHeld && tape != Tape::Stopping) {
            if (tape == Tape::Off) readPos = static_cast<double>(w);
            tape = Tape::Stopping;
        } else if (!stopHeld && tape == Tape::Stopping) {
            // Let go before it was silent: spin up from where it got to.
            // After: from now, since nothing was being heard anyway.
            if (rate < 0.01f) readPos = static_cast<double>(w);
            tape = Tape::Starting;
        }

        // --- The pad ------------------------------------------------------
        const float xTarget = params_.target(X);
        const float yTarget = params_.target(Y);
        const float throwBeats[5] = {0.25f, 0.5f, 0.75f, 1.0f, 1.5f};
        const float echoTarget = std::min(
            throwBeats[std::clamp(static_cast<int32_t>(params_.get(ThrowTime) + 0.5f), 0, 4)] * spb,
            static_cast<float>(kSize - 4));
        if (echoLen < 0.0f) echoLen = echoTarget;
        const float fb = params_.get(Feedback);
        const bool echoAwake = yTarget > 0.0f || ys > 1e-7f || w - lastLoud <= static_cast<int64_t>(echoLen) + 2;
        const bool padIdle = xTarget == 0.5f && xs == 0.5f && !filterOn;

        if (!repActive && tape == Tape::Off && padIdle && !echoAwake) {
            // At rest: only the ring is fed, so a repeat or a stop pressed next
            // has something behind it, and the echo is written silent, so
            // waking it never replays something from a lap of the buffer ago.
            for (int32_t i = 0; i < frames; ++i) {
                ring[0][w & kMask] = L[i];
                ring[1][w & kMask] = R[i];
                echo[0][w & kMask] = echo[1][w & kMask] = 0.0f;
                ++w;
            }
            filled = std::min<int64_t>(filled + frames, kSize);
            ys = 0.0f;
            return;
        }

        const float lpCoef = 1.0f - std::exp(-6.2831853f * 3500.0f / sr);
        const float hpCoef = 1.0f - std::exp(-6.2831853f * 120.0f / sr);
        for (int32_t i = 0; i < frames; ++i) {
            float l = L[i], r = R[i];

            // Repeat.
            if (repActive) {
                if (repCaptured < repLen) {
                    slice[0][repCaptured] = l;
                    slice[1][repCaptured] = r;
                    ++repCaptured;
                }
                const int32_t pos = static_cast<int32_t>(repElapsed % repLen);
                float edge = 1.0f;
                if (repElapsed >= repLen && pos < edgeFrames) edge = static_cast<float>(pos) / edgeFrames;
                if (repLen - pos <= edgeFrames) edge = std::min(edge, static_cast<float>(repLen - pos) / edgeFrames);
                const float sL = slice[0][pos] * edge, sR = slice[1][pos] * edge;
                ++repElapsed;
                repMix = repHeld ? std::min(1.0f, repMix + mixStep) : std::max(0.0f, repMix - mixStep);
                l += repMix * (sL - l);
                r += repMix * (sR - r);
                if (!repHeld && repMix <= 0.0f) repActive = false;
            }

            // The ring hears what the repeat made, so the tape stops that.
            ring[0][w & kMask] = l;
            ring[1][w & kMask] = r;
            if (filled < kSize) ++filled;

            // Tape.
            if (tape != Tape::Off) {
                if (tape == Tape::Stopping) rate = std::max(0.0f, rate - 1.0f / stopFrames);
                else rate = std::min(1.0f, rate + 2.0f / stopFrames);
                readPos = std::min(readPos + rate, static_cast<double>(w));
                const int64_t base = static_cast<int64_t>(readPos);
                const float frac = static_cast<float>(readPos - static_cast<double>(base));
                const int64_t next = std::min<int64_t>(base + 1, w);
                const float gain = std::min(1.0f, rate * 2.5f);
                float tl = (ring[0][base & kMask] + frac * (ring[0][next & kMask] - ring[0][base & kMask])) * gain;
                float tr = (ring[1][base & kMask] + frac * (ring[1][next & kMask] - ring[1][base & kMask])) * gain;
                if (tape == Tape::Starting && rate >= 1.0f) {
                    // Up to speed, but behind: fade across to the live mix.
                    rejoin = std::min(1.0f, rejoin + rejoinStep);
                    tl += rejoin * (l - tl);
                    tr += rejoin * (r - tr);
                    if (rejoin >= 1.0f) { tape = Tape::Off; rejoin = 0.0f; }
                } else {
                    rejoin = 0.0f;
                }
                l = tl;
                r = tr;
            }
            ++w;

            // The filter.
            xs += (xTarget - xs) * smoothA;
            if (std::fabs(xs - xTarget) < 1e-6f) xs = xTarget;
            const float d = xs - 0.5f;
            const float amt = std::clamp((std::fabs(d) - 0.02f) / 0.06f, 0.0f, 1.0f);
            if (amt > 0.0f) {
                if (!filterOn) { ic1[0] = ic1[1] = ic2[0] = ic2[1] = 0.0f; filterOn = true; coefAge = 0; }
                if (coefAge-- <= 0) {
                    const float t = std::clamp((std::fabs(d) - 0.02f) / 0.48f, 0.0f, 1.0f);
                    const float f = d < 0.0f ? 20000.0f * std::pow(100.0f / 20000.0f, t)
                                             : 20.0f * std::pow(6000.0f / 20.0f, t);
                    const float g = std::tan(3.14159265f * std::min(f, 0.45f * sr) / sr);
                    a1 = 1.0f / (1.0f + g * (g + kDamp));
                    a2 = g * a1;
                    a3 = g * a2;
                    highPass = d > 0.0f;
                    coefAge = 15;
                }
                l += amt * (svf(0, l) - l);
                r += amt * (svf(1, r) - r);
            } else if (filterOn && xs == 0.5f) {
                filterOn = false;
            }

            // The throw.
            ys += (yTarget - ys) * smoothA;
            if (ys < 1e-7f && yTarget == 0.0f) ys = 0.0f;
            if (echoAwake) {
                echoLen += std::clamp(echoTarget - echoLen, -1.0f, 1.0f); // glide, don't jump
                float frac = 0.0f;
                const float at = static_cast<float>(w & kMask) - echoLen;
                const float wrapped = at < 0.0f ? at + static_cast<float>(kSize) : at;
                const int32_t i0 = static_cast<int32_t>(wrapped) & kMask;
                frac = wrapped - std::floor(wrapped);
                const int32_t i1 = (i0 + 1) & kMask;
                const float dl = echo[0][i0] + frac * (echo[0][i1] - echo[0][i0]);
                const float dr = echo[1][i0] + frac * (echo[1][i1] - echo[1][i0]);
                // Crossed, so each repeat answers from the other side, and
                // darkened and thinned on every pass, the way a tape echo is.
                const float inL = l * ys + fb * tone(0, dr, lpCoef, hpCoef);
                const float inR = r * ys + fb * tone(1, dl, lpCoef, hpCoef);
                echo[0][w & kMask] = inL;
                echo[1][w & kMask] = inR;
                if (std::fabs(inL) > 1e-6f || std::fabs(inR) > 1e-6f) lastLoud = w;
                l += dl;
                r += dr;
            } else {
                echo[0][w & kMask] = echo[1][w & kMask] = 0.0f;
            }

            L[i] = l;
            R[i] = r;
        }
    }

  private:
    enum class Tape : uint8_t { Off, Stopping, Starting };

    float svf(int c, float x) {
        const float v3 = x - ic2[c];
        const float v1 = a1 * ic1[c] + a2 * v3;
        const float v2 = ic2[c] + a2 * ic1[c] + a3 * v3;
        ic1[c] = 2.0f * v1 - ic1[c];
        ic2[c] = 2.0f * v2 - ic2[c];
        return highPass ? x - kDamp * v1 - v2 : v2;
    }

    float tone(int c, float x, float lp, float hp) {
        fbLow[c] += (x - fbLow[c]) * lp;
        fbHigh[c] += (fbLow[c] - fbHigh[c]) * hp;
        return fbLow[c] - fbHigh[c];
    }

    ParamSet params_;
    float sr = 48000.0f;
    bool transportPlaying = false;
    double transportTick = 0.0;
    float smoothA = 0.004f;
    float mixStep = 0.004f;
    float rejoinStep = 0.002f;
    int32_t edgeFrames = 72;

    std::vector<float> ring[2], slice[2], echo[2];
    int64_t w = 0;       // frames written to the ring, ever
    int64_t filled = 0;  // how many of the ring's frames are real

    bool repHeld = false, repActive = false;
    int32_t repK = 0, repLen = 0, repCaptured = 0;
    int64_t repElapsed = 0;
    float repMix = 0.0f;

    Tape tape = Tape::Off;
    float rate = 1.0f;
    float rejoin = 0.0f;
    double readPos = 0.0;

    float xs = 0.5f, ys = 0.0f;
    bool filterOn = false, highPass = false;
    int32_t coefAge = 0;
    static constexpr float kDamp = 0.9f; // a little resonance, as a DJ filter has
    float a1 = 1.0f, a2 = 0.0f, a3 = 0.0f;
    float ic1[2] = {}, ic2[2] = {};

    float echoLen = -1.0f;
    int64_t lastLoud = 0;
    float fbLow[2] = {}, fbHigh[2] = {};
};

} // namespace acidulous
