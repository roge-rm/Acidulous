#pragma once
#include "Filter.h"
#include "Math.h"

// Twelve slopes from two SVF stages and a one-pole, with a drive stage at the
// input - the arrangement that lets a filter change character, not just
// brightness. Resonance lives on the first stage.
namespace acidulous::dsp {

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

    void setSampleRate(float sr) { sampleRate = sr; a.setSampleRate(sr); b.setSampleRate(sr); }
    void reset() { a.reset(); b.reset(); z = 0.0f; }

    void set(float cutoffHz, float resonance01, int type, int drive, float driveAmount) {
        this->type = type < 0 ? 0 : (type >= TypeCount ? TypeCount - 1 : type);
        this->drive = drive;
        this->driveAmount = driveAmount;
        const float fc = clampf(cutoffHz, 20.0f, sampleRate * 0.45f);
        a.set(fc, resonance01);
        b.set(fc, 0.0f);
        onePole = clampf(1.0f - std::exp(-kTwoPi * fc / sampleRate), 0.0f, 1.0f);
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
    float lp1(float x) { z += (x - z) * onePole; return z; }
    float shape(float x) {
        if (drive == Clean || driveAmount <= 0.0f) return x;
        const float g = 1.0f + driveAmount * 24.0f;
        switch (drive) {
        case Valve: return fastTanh(x * g) / std::sqrt(g);
        case Diode: return (fastTanh(x * g + 0.35f) - 0.336f) / std::sqrt(g);
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
