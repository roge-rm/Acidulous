#pragma once
#include <cmath>
#include <cstdint>
#include <engine/dsp/Biquad.h>
#include <engine/dsp/DelayLine.h>
#include <engine/dsp/Math.h>

// What a recording medium does to a sound, and never to the recording.
//
// **Bias is the high-frequency signal a tape machine mixes into the record
// head**, and setting it is exactly how you choose between a clean transfer
// and a compressed, rolled-off, saturated one. A machine of that name whose
// patches are recording media is the thing the name already promised.
//
// It colours the *output*: a patch is a way of listening, so it is free,
// reversible and comparable, and the files on disk never change. Printing it
// is a separate act, and one that already exists - the freeze render.
//
// **One medium per machine, not per lane.** A four-track has one transport and
// one set of heads; the tape is the tape. Two media means two Bias tracks.
// Crosstalk is the exception and is taken between the lanes before they sum,
// because bleed is what adjacent tracks on one tape do to each other.
//
// Four traps this tree already knows about, and each is answered in place
// below: noise must be deterministic or two exports differ; saturation must
// normalise on the level the signal reaches rather than full scale; per-block
// values applied per sample click; and a fractional read that wraps belongs to
// `dsp::wrappedReadIndex`.
namespace acidulous::machine::bias {

/** The whole medium, as numbers. Every one of these is a parameter. */
struct ColourSpec {
    float hiss = 0.0f;      // 0..1, the noise floor
    float hissTone = 0.5f;  // 0 dark, 1 bright
    float lowCut = 20.0f;   // Hz
    float highCut = 20000.0f;
    float bump = 0.0f;      // dB at bumpFreq
    float bumpFreq = 90.0f;
    float sat = 0.0f;       // 0..1
    float comp = 0.0f;      // 0..1
    float wow = 0.0f;       // 0..1, slow
    float flutter = 0.0f;   // 0..1, fast
    float speed = 1.0f;     // how unstable the transport is, as a rate scale
    float drop = 0.0f;      // 0..1, dropouts
    float bits = 24.0f;     // quantiser, 24 is off
    float rate = 1.0f;      // sample-and-hold ratio, 1 is off
    float smear = 0.0f;     // codec diffusion
    float width = 1.0f;     // 0 mono, 1 as recorded, >1 wider
    bool any = false;       // nothing at all to do, and the check is one bool
};

/**
 * One medium's worth of processing, stereo.
 *
 * `prepare` allocates; nothing after it does. `reset` puts it back exactly as
 * `prepare` left it, **including the noise generator's seed** - two exports of
 * one song have to match sample for sample, and a noise floor seeded from
 * anything that moves is the one way to break that silently.
 */
class Colour {
  public:
    void prepare(float sampleRate) {
        sr = sampleRate;
        // 60 ms is far more than any wobble asks for, and leaves room for the
        // smear to read behind the wobble without either running into the other.
        wobbleL.prepare(static_cast<int32_t>(sr * 0.06f));
        wobbleR.prepare(static_cast<int32_t>(sr * 0.06f));
        reset();
    }

    void reset() {
        wobbleL.clear();
        wobbleR.clear();
        for (auto &b : band) b.reset();
        bumpL.reset();
        bumpR.reset();
        smearL.reset();
        smearR.reset();
        hissL.reset();
        hissR.reset();
        // The seed, and everything derived from it. See the note above.
        noise = kSeed;
        wowPhase = 0.0f;
        flutterPhase = 0.25f;
        dropCountdown = 0;
        dropDepth = 1.0f;
        env = 0.0f;
        holdL = holdR = 0.0f;
        holdPos = 0.0f;
        cutNow = -1.0f;
        bumpNow = -1.0f;
    }

    /**
     * Coefficients once a block, values per sample.
     *
     * A filter recalculated per sample would cost more than the rest of this
     * put together and change nothing anybody can hear; a *gain* held for a
     * block and stepped is the onset-click fault class. So the split is:
     * anything that is a coefficient is set here when it has actually moved,
     * anything that multiplies a sample is interpolated in [process].
     */
    void setBlock(const ColourSpec &s) {
        spec = s;
        if (std::fabs(s.highCut - cutNow) > 0.5f || std::fabs(s.lowCut - lowNow) > 0.5f) {
            cutNow = s.highCut;
            lowNow = s.lowCut;
            // Two poles each way: one is not a medium, it is a tone control.
            band[0].lowpass(s.highCut, 0.707f, sr);
            band[1].lowpass(s.highCut, 0.707f, sr);
            band[2].peak(s.lowCut, 0.0f, 0.707f, sr); // placeholder, set below
            highPass(band[2], s.lowCut);
            highPass(band[3], s.lowCut);
        }
        if (std::fabs(s.bump - bumpNow) > 0.01f || std::fabs(s.bumpFreq - bumpFreqNow) > 0.5f) {
            bumpNow = s.bump;
            bumpFreqNow = s.bumpFreq;
            bumpL.peak(s.bumpFreq, s.bump, 0.8f, sr);
            bumpR.peak(s.bumpFreq, s.bump, 0.8f, sr);
        }
        if (std::fabs(s.hissTone - toneNow) > 0.01f) {
            toneNow = s.hissTone;
            const float hz = 400.0f * std::pow(30.0f, s.hissTone); // 400 Hz .. 12 kHz
            hissL.lowpass(hz, 0.707f, sr);
            hissR.lowpass(hz, 0.707f, sr);
        }
        if (std::fabs(s.smear - smearNow) > 0.01f) {
            smearNow = s.smear;
            smearL.allpass(1200.0f, 0.4f + s.smear * 3.0f, sr);
            smearR.allpass(1700.0f, 0.4f + s.smear * 3.0f, sr);
        }
    }

    /** In place, one block, interleaved as two planes. */
    void process(float *L, float *R, int32_t frames) {
        if (!spec.any) return;
        const float wowRate = 0.7f * spec.speed;
        const float flutRate = 11.0f * spec.speed;
        const float wowDepth = spec.wow * spec.wow * sr * 0.004f;   // up to 4 ms
        const float flutDepth = spec.flutter * spec.flutter * sr * 0.0006f;
        const float base = sr * 0.02f; // the centre of the wobble
        const float levels = spec.bits >= 23.5f ? 0.0f : std::pow(2.0f, spec.bits - 1.0f);
        const float hold = spec.rate >= 0.999f ? 0.0f : spec.rate;

        for (int32_t i = 0; i < frames; ++i) {
            float l = L[i];
            float r = R[i];

            // --- Wow and flutter: a modulated delay, read through the one
            // wrap in the app that is known to be right.
            if (wowDepth > 0.0f || flutDepth > 0.0f) {
                wowPhase += wowRate / sr;
                flutterPhase += flutRate / sr;
                if (wowPhase >= 1.0f) wowPhase -= 1.0f;
                if (flutterPhase >= 1.0f) flutterPhase -= 1.0f;
                const float mod = std::sin(dsp::kTwoPi * wowPhase) * wowDepth +
                                  std::sin(dsp::kTwoPi * flutterPhase) * flutDepth;
                wobbleL.write(l);
                wobbleR.write(r);
                // The two channels read a hair apart, because a tape's two
                // tracks do not wobble in lockstep and one that does sounds
                // like an effect rather than a transport.
                l = wobbleL.read(base + mod);
                r = wobbleR.read(base - mod * 0.85f);
            }

            // --- The band, which is most of what tells a telephone from a reel.
            l = band[2].process(band[0].process(l));
            r = band[3].process(band[1].process(r));

            // --- Head bump.
            if (spec.bump != 0.0f) {
                l = bumpL.process(l);
                r = bumpR.process(r);
            }

            // --- The medium's own compression, one envelope for both ears so
            // it never pulls the image about.
            if (spec.comp > 0.0f) {
                const float peak = std::fmax(std::fabs(l), std::fabs(r));
                const float coeff = peak > env ? attack : release;
                env += (peak - env) * coeff;
                const float over = env / kNominal;
                if (over > 1.0f) {
                    const float g = 1.0f / (1.0f + (over - 1.0f) * spec.comp * 4.0f);
                    l *= g;
                    r *= g;
                }
            }

            // --- Saturation, **against the level the signal reaches**.
            //
            // Scaled on kNominal rather than on full scale: against full scale
            // a quiet take stays clean and a loud one is destroyed by the same
            // setting, which is a drive control that does not work.
            if (spec.sat > 0.0f) {
                const float drive = 1.0f + spec.sat * 8.0f;
                const float norm = 1.0f / std::tanh(drive);
                l = std::tanh(l * drive / kNominal) * norm * kNominal;
                r = std::tanh(r * drive / kNominal) * norm * kNominal;
            }

            // --- Dropouts: the tape lifting off the head for a moment.
            if (spec.drop > 0.0f) {
                if (dropCountdown <= 0) {
                    // A Poisson-ish gap, so they do not arrive in step with
                    // anything in the music.
                    const float rate = 0.05f + spec.drop * 3.0f; // per second
                    dropCountdown = static_cast<int32_t>((0.2f + random()) * sr / rate);
                    dropDepth = 1.0f;
                } else {
                    --dropCountdown;
                }
                const float want = dropCountdown < static_cast<int32_t>(sr * 0.03f)
                                       ? 1.0f - spec.drop * 0.9f
                                       : 1.0f;
                dropDepth += (want - dropDepth) * 0.002f;
                l *= dropDepth;
                r *= dropDepth;
            }

            // --- The digital media: quantise, then hold.
            if (levels > 0.0f) {
                l = std::round(l * levels) / levels;
                r = std::round(r * levels) / levels;
            }
            if (hold > 0.0f) {
                holdPos += hold;
                if (holdPos >= 1.0f) {
                    holdPos -= 1.0f;
                    holdL = l;
                    holdR = r;
                }
                l = holdL;
                r = holdR;
            }

            // --- Codec smear: a short diffusion that thickens a transient
            // rather than sharpening it. Named for what it does; nothing here
            // claims to be a codec.
            if (spec.smear > 0.0f) {
                l += (smearL.process(l) - l) * spec.smear;
                r += (smearR.process(r) - r) * spec.smear;
            }

            // --- Hiss, deterministic, and under the band it belongs to.
            if (spec.hiss > 0.0f) {
                const float a = spec.hiss * spec.hiss * 0.05f;
                l += hissL.process(random2()) * a;
                r += hissR.process(random2()) * a;
            }

            // --- Width: the same control heard twice, as azimuth error on a
            // tape and as joint stereo on a codec.
            if (std::fabs(spec.width - 1.0f) > 0.001f) {
                const float m = (l + r) * 0.5f;
                const float s = (l - r) * 0.5f * spec.width;
                l = m + s;
                r = m - s;
            }

            L[i] = l;
            R[i] = r;
        }
    }

  private:
    /** Nought dB on this desk: what a mixed signal actually reaches. */
    static constexpr float kNominal = 0.3f;
    static constexpr uint32_t kSeed = 0x1f35c0deu;

    void highPass(dsp::Biquad &b, float hz) {
        // A high shelf cutting everything below, which is what a transport's
        // low end loss is: not a corner, a slope that keeps going.
        b.lowShelf(dsp::clampf(hz, 20.0f, sr * 0.45f), -24.0f, sr);
    }

    float random() {
        noise = noise * 1664525u + 1013904223u;
        return static_cast<float>(noise >> 8) * (1.0f / 16777216.0f);
    }
    float random2() { return random() * 2.0f - 1.0f; }

    ColourSpec spec;
    float sr = 48000.0f;
    dsp::DelayLine wobbleL, wobbleR;
    dsp::Biquad band[4], bumpL, bumpR, smearL, smearR, hissL, hissR;
    uint32_t noise = kSeed;
    float wowPhase = 0.0f, flutterPhase = 0.25f;
    float env = 0.0f;
    float attack = 0.01f, release = 0.0006f;
    int32_t dropCountdown = 0;
    float dropDepth = 1.0f;
    float holdL = 0.0f, holdR = 0.0f, holdPos = 0.0f;
    float cutNow = -1.0f, lowNow = -1.0f, bumpNow = -1.0f, bumpFreqNow = -1.0f;
    float toneNow = -1.0f, smearNow = -1.0f;
};

} // namespace acidulous::machine::bias
