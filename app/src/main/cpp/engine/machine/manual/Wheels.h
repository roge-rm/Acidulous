#pragma once
#include <cmath>
#include <cstdint>
#include <mutex>
#include <vector>

// The generator: one bank of oscillators running all the time, which every
// key taps into. That is how a tonewheel organ is actually built - 91 wheels
// spinning on one shaft whether or not anybody is playing - and it is why an
// organ sounds the way it does. Every note is in phase with every other note
// because they are drawing on the same wheels, so a chord does not beat, and
// the same wheel feeding two keys at once is no louder than it was.
//
// It is also why polyphony here is nearly free: a key costs nine multiplies,
// not nine oscillators.
//
// Each wheel's frequency is fixed, so each can carry a table band-limited
// exactly to it: every harmonic that fits under Nyquist and not one more. The
// bank is built once for the process and shared, because it depends on
// nothing but the sample rate.
namespace acidulous::machine {

class WheelBank {
  public:
    static constexpr int kWheels = 91;   // C1 up, the Hammond generator's span
    static constexpr int kTable = 256;
    static constexpr int kLowestNote = 24; // wheel 0 is C1, 32.703 Hz

    // What a wheel can be made of. A tonewheel is very nearly a sine; the
    // rest are here because a combo organ divides square waves down, and a
    // pipe rank is a fixed harmonic recipe.
    enum Timbre : int32_t { Sine, Wheel, Square, Pulse, Saw, Principal, Flute, String, Reed, kTimbres };

    static const WheelBank &shared(float sampleRate) {
        static WheelBank bank;
        static std::once_flag once;
        std::call_once(once, [&] { bank.build(sampleRate); });
        return bank;
    }

    float freq(int wheel) const { return freqs[wheel]; }

    // phase01 is wrapped by the caller.
    float sample(int wheel, int timbre, float phase01) const {
        const float x = phase01 * static_cast<float>(kTable);
        const int i = static_cast<int>(x);
        const float f = x - static_cast<float>(i);
        const float *t = &data[static_cast<size_t>((wheel * kTimbres + timbre)) * (kTable + 1)];
        return t[i] + (t[i + 1] - t[i]) * f;
    }

  private:
    // Harmonic h (1-based) of a timbre, before band-limiting.
    static float harmonic(int timbre, int h) {
        const float fh = static_cast<float>(h);
        switch (timbre) {
        case Sine: return h == 1 ? 1.0f : 0.0f;
        // Not a perfect sine: the pickup sees a little of the tooth shape,
        // and that trace of 2nd and 3rd is most of why a Hammond is not a
        // bank of sine oscillators.
        case Wheel: return h == 1 ? 1.0f : (h == 2 ? 0.035f : (h == 3 ? 0.018f : 0.0f));
        case Square: return (h % 2) ? 1.0f / fh : 0.0f;
        case Pulse: return std::fabs(std::sin(fh * 3.14159265f * 0.25f)) * 2.0f / (fh * 3.14159265f);
        case Saw: return 1.0f / fh;
        case Principal: return (1.0f / fh) * ((h % 2) ? 1.0f : 0.55f) * std::exp(-fh / 14.0f);
        case Flute: return h == 1 ? 1.0f : (h == 2 ? 0.14f : (h == 3 ? 0.045f : (h == 4 ? 0.02f : 0.0f)));
        case String: return std::pow(1.0f / fh, 0.82f) * std::exp(-fh / 22.0f);
        // A reed pipe is odd-harmonic with a formant where the shallot rings.
        case Reed: return (h % 2) ? (1.0f / fh) * (1.0f + 1.8f * std::exp(-((fh - 7.0f) * (fh - 7.0f)) / 18.0f)) : 0.08f / fh;
        default: return h == 1 ? 1.0f : 0.0f;
        }
    }

    void build(float sampleRate) {
        const float nyquist = sampleRate * 0.45f;
        freqs.resize(kWheels);
        data.assign(static_cast<size_t>(kWheels) * kTimbres * (kTable + 1), 0.0f);
        for (int w = 0; w < kWheels; ++w) {
            const float f0 = 440.0f * std::pow(2.0f, (static_cast<float>(kLowestNote + w) - 69.0f) / 12.0f);
            freqs[w] = f0;
            const int maxH = static_cast<int>(nyquist / f0);
            for (int t = 0; t < kTimbres; ++t) {
                float *tab = &data[static_cast<size_t>((w * kTimbres + t)) * (kTable + 1)];
                float peak = 0.0f;
                for (int h = 1; h <= maxH && h <= 64; ++h) {
                    const float a = harmonic(t, h);
                    if (a <= 1e-5f) continue;
                    // Phasor recurrence rather than a sin() per sample: the
                    // bank is 91 wheels wide and this is built at load.
                    const float dphi = 6.2831853f * static_cast<float>(h) / static_cast<float>(kTable);
                    const float c = std::cos(dphi), s = std::sin(dphi);
                    float re = 1.0f, im = 0.0f;
                    for (int i = 0; i < kTable; ++i) {
                        tab[i] += a * im;
                        const float nre = re * c - im * s;
                        im = re * s + im * c;
                        re = nre;
                    }
                }
                for (int i = 0; i < kTable; ++i) peak = std::fmax(peak, std::fabs(tab[i]));
                const float g = peak > 1e-6f ? 1.0f / peak : 1.0f;
                for (int i = 0; i < kTable; ++i) tab[i] *= g;
                tab[kTable] = tab[0]; // guard sample, so interpolation never wraps
            }
        }
    }

    std::vector<float> data;
    std::vector<float> freqs;
};

} // namespace acidulous::machine
