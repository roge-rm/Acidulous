#pragma once
#include "Filter.h"
#include "Math.h"

// Twelve slopes from two SVF stages and a one-pole, with a drive stage at the
// input - the arrangement that lets a filter change character, not just
// brightness. Resonance lives on the first stage.
namespace acidulous::dsp {

// The level a drive stage should treat as nominal: what the signal reaching it
// actually reaches, rather than full scale or the curve's own ceiling.
constexpr float kDriveNominal = 0.3f;

class MultiFilter {
  public:
    enum Type : int32_t { LP6, LP12, LP18, LP24, HP6, HP12, HP18, HP24, BP6, BP12, Notch, Peak, TypeCount };
    enum Drive : int32_t { Clean, Valve, Diode, Clip, Fold, Crush, DriveCount };

    static const char *typeName(int t) {
        static const char *n[TypeCount] = {"LP6", "LP12", "LP18", "LP24", "HP6", "HP12", "HP18", "HP24", "BP6", "BP12", "notch", "peak"};
        return n[t < 0 ? 0 : (t >= TypeCount ? TypeCount - 1 : t)];
    }
    static const char *driveName(int d) {
        static const char *n[DriveCount] = {"clean", "valve", "diode", "clip", "fold", "crush"};
        return n[d < 0 ? 0 : (d >= DriveCount ? DriveCount - 1 : d)];
    }

    void setSampleRate(float sr) {
        sampleRate = sr;
        a.setSampleRate(sr);
        b.setSampleRate(sr);
        lastFc = -1.0f; // the coefficients below are about to mean something else
    }
    void reset() { a.reset(); b.reset(); z = 0.0f; }

    /**
     * Coefficients, and three things not to do.
     *
     * This is `exp2` in the caller plus two `tan` and an `exp` here, and every
     * machine with a filter per voice calls it four times a block per voice -
     * a hundred and ninety-two libm calls a block for Ratio's twelve, two
     * hundred and fifty-six for Trinity's sixteen. So:
     *
     *  - **nothing is recomputed when nothing moved.** The cutoff only moves
     *    if something is modulating it; with the filter envelope at nought it
     *    is the same number four times a block, for ever;
     *  - `b` is the second pole pair and only three of the thirteen types
     *    have one;
     *  - `onePole` is the six-decibel path and only four types use it.
     *
     * The drive is stored before any of that, because it changes on its own.
     */
    void set(float cutoffHz, float resonance01, int type, int drive, float driveAmount) {
        this->type = type < 0 ? 0 : (type >= TypeCount ? TypeCount - 1 : type);
        this->drive = drive;
        this->driveAmount = driveAmount;
        const float fc = clampf(cutoffHz, 20.0f, sampleRate * 0.45f);
        if (fc == lastFc && resonance01 == lastRes && this->type == lastType) return;
        lastFc = fc;
        lastRes = resonance01;
        lastType = this->type;
        a.set(fc, resonance01);
        if (this->type == LP24 || this->type == HP24 || this->type == BP12) b.set(fc, 0.0f);
        if (this->type == LP6 || this->type == LP18 || this->type == HP6 || this->type == HP18) {
            onePole = clampf(1.0f - std::exp(-kTwoPi * fc / sampleRate), 0.0f, 1.0f);
        }
    }

    float process(float x) {
        x = shape(x);
        switch (type) {
        case LP6: return lp1(x);
        case LP12: return a.step(x).lp;
        case LP18: return lp1(a.step(x).lp);
        case LP24: return b.step(a.step(x).lp).lp;
        case HP6: return x - lp1(x);
        case HP12: return a.step(x).hp;
        case HP18: { const float y = a.step(x).hp; return y - lp1(y); }
        case HP24: return b.step(a.step(x).hp).hp;
        case BP6: return a.step(x).bp * a.bandNorm();
        case BP12: return b.step(a.step(x).bp * a.bandNorm()).bp * b.bandNorm();
        case Notch: { const auto o = a.step(x); return o.lp + o.hp; }
        default: { const auto o = a.step(x); return o.lp - o.hp; }
        }
    }

  private:
    float lastFc = -1.0f, lastRes = -1.0f;
    int lastType = -1;

    float lp1(float x) { z += (x - z) * onePole; return z; }
    float shape(float x) {
        if (drive == Clean || driveAmount <= 0.0f) return x;
        const float g = 1.0f + driveAmount * 24.0f;
        switch (drive) {
        // Normalised on a nominal level, not by 1/sqrt(g).
        //
        // `tanh(x * g) / sqrt(g)` is a see-saw, not a drive: for a small
        // signal the tanh is near-linear, so the whole thing reduces to
        // x * sqrt(g) - a *boost* of up to ten decibels - while a large one is
        // held at a ceiling of 1/sqrt(g), ten decibels down. The knob changes
        // the level far more than the character, in opposite directions
        // depending on how loud the signal already is, which is an extreme
        // compressor with a tone control attached. Resonance's coupling loop
        // oscillated for exactly this reason: nine decibels of hidden gain
        // handed to small signals inside a feedback path.
        //
        // Divide by the curve's own response at a nominal level instead, so a
        // signal of that size comes out the size it went in.
        case Valve: return fastTanh(x * g) * (kDriveNominal / fastTanh(kDriveNominal * g));
        case Diode: {
            // The bias offset is taken back out before normalising, or the DC
            // it adds is what gets scaled.
            const float bias = fastTanh(0.35f);
            const float at = fastTanh(kDriveNominal * g + 0.35f) - bias;
            const float norm = at > 1e-6f ? kDriveNominal / at : 1.0f;
            return (fastTanh(x * g + 0.35f) - bias) * norm;
        }
        case Clip: return clampf(x * g, -1.0f, 1.0f) / std::sqrt(g);
        case Fold: { const float t = x * g * 0.25f + 0.25f; return (4.0f * std::fabs(t - std::floor(t + 0.5f)) - 1.0f) / std::sqrt(g); }
        default: { // Crush: bit reduction, harsher the more drive
            const float levels = std::exp2(12.0f - driveAmount * 9.0f);
            return std::round(x * levels) / levels;
        }
        }
    }

    Svf a, b;
    float sampleRate = 48000.0f, onePole = 0.5f, z = 0.0f, driveAmount = 0.0f;
    int type = LP12, drive = Clean;
};

} // namespace acidulous::dsp
