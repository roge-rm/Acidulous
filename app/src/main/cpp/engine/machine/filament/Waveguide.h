#pragma once
#include <cmath>
#include <cstdint>
#include <engine/dsp/Math.h>
#include <vector>

// One string.
//
// A delay line the length of a wavelength, with a filter in the loop: what
// goes round loses its high end a little faster than its low, which is what
// a real string does and why a plucked note goes dull before it goes quiet.
// Everything else here is the difference between that textbook line and an
// instrument.
//
//   - **Dispersion.** Real strings are stiff, so their partials run sharp of
//     the harmonic series. An allpass chain in the loop does the same, and
//     is the difference between a guitar and a piano.
//   - **Tension.** Pull a string hard and it is briefly sharp, settling as it
//     decays. The loop length is modulated by the energy going round it, so
//     a hard pluck bends down into tune the way a real one does.
//   - **A finger on it.** A second tap partway along, subtracted, is a damper
//     where you put it; at a node it gives the harmonic instead of the note.
namespace acidulous::machine {
using dsp::clampf;

class Waveguide {
  public:
    void prepare(float sampleRate) {
        sr = sampleRate;
        buffer.assign(static_cast<size_t>(sampleRate / 18.0f) + 4, 0.0f); // down to ~18 Hz
        clear();
    }

    void clear() {
        for (auto &v : buffer) v = 0.0f;
        write = 0;
        loopState = 0.0f;
        pending = 0.0f;
        for (auto &a : allpassState) a = 0.0f;
        energy = 0.0f;
    }

    void setFrequency(float hz) {
        baseDelay = clampf(sr / clampf(hz, 18.0f, 8000.0f), 2.0f, static_cast<float>(buffer.size() - 2));
        refreshDispersion();
    }

    /** 0 = dead, 1 = rings for ever. */
    void setDamping(float loopGain, float toneCutoff01) {
        gain = clampf(loopGain, 0.0f, 1.02f);
        tone = clampf(toneCutoff01, 0.02f, 1.0f);
    }
    void setDispersion(float amount01, int stages) {
        dispersion = clampf(amount01, 0.0f, 1.0f);
        allpassStages = stages < 0 ? 0 : (stages > kAllpass ? kAllpass : stages);
        refreshDispersion();
    }
    void setTension(float amount01) { tension = clampf(amount01, 0.0f, 1.0f); }
    /** Where the damper sits, 0..1 along the string, and how hard it presses. */
    void setDamper(float position01, float pressure01) {
        damperPos = clampf(position01, 0.0f, 1.0f);
        damperPressure = clampf(pressure01, 0.0f, 1.0f);
    }

    /**
     * Add energy from outside - a neighbouring string, a coupling, a knock.
     * It is held until the next step rather than written into the buffer,
     * because step() writes that slot itself and would throw it away.
     */
    void excite(float value) { pending += value; }

    float step(float input) {
        // Tension modulation: the harder it is ringing, the shorter the loop.
        // The loop is longer than the delay line - one sample for the write,
        // and the loop filter's own phase delay, which grows as the filter
        // closes. Uncompensated, a string plays flat and further flat the
        // higher it is asked to go.
        const float filterDelay = (1.0f - tone) / std::fmax(0.02f, tone);
        const float allpassDelay = static_cast<float>(allpassStages) * stageDelay;
        // The buffer itself contributes exactly `delay` samples, no more:
        // what is written now is read when the pointer comes round to it.
        const float wanted = baseDelay * (1.0f - tension * 0.02f * energy) - filterDelay - allpassDelay;
        const float delay = clampf(wanted, 2.0f, static_cast<float>(buffer.size() - 2));
        float out = read(delay);
        if (damperPressure > 0.0f) {
            const float tapped = read(clampf(delay * damperPos, 1.0f, delay - 1.0f));
            out -= tapped * damperPressure * 0.9f;
        }
        // Loop filter: one pole, so the top goes first.
        loopState += (out - loopState) * tone;
        float fed = loopState * gain;
        for (int i = 0; i < allpassStages; ++i) {
            const float y = disperse * fed + allpassState[i];
            allpassState[i] = fed - disperse * y;
            fed = y;
        }
        float next = fed + input + pending;
        pending = 0.0f;
        if (next > 1.0f || next < -1.0f) next = std::tanh(next);
        buffer[static_cast<size_t>(write)] = next;
        energy += (std::fabs(out) - energy) * 0.001f;
        lastOut = out;
        if (++write >= static_cast<int32_t>(buffer.size())) write = 0;
        return out;
    }

    float level() const { return energy; }
    /** The last sample that came off the string, for a bow to push against. */
    float velocity() const { return lastOut; }

  private:
    static constexpr int kAllpass = 4;

    // How much extra delay each allpass adds at the bottom of the range,
    // and therefore where its dispersion happens. A coefficient chosen
    // without reference to the string puts the whole effect up near Nyquist,
    // where none of the partials are, which is why a first attempt at this
    // sounds like nothing at all. Tie it to the string's own length instead:
    // stiffness may bend the loop by up to a third of itself.
    void refreshDispersion() {
        if (dispersion < 0.005f || allpassStages == 0) {
            disperse = 0.0f;
            stageDelay = allpassStages > 0 ? 1.0f : 0.0f;
            return;
        }
        const float budget = baseDelay * 0.34f / static_cast<float>(allpassStages);
        stageDelay = clampf(1.0f + dispersion * budget, 1.0f, 96.0f);
        disperse = (1.0f - stageDelay) / (1.0f + stageDelay);
    }

    float read(float delay) const {
        float pos = static_cast<float>(write) - delay;
        while (pos < 0.0f) pos += static_cast<float>(buffer.size());
        const int32_t i0 = static_cast<int32_t>(pos);
        const int32_t i1 = (i0 + 1) % static_cast<int32_t>(buffer.size());
        const float frac = pos - static_cast<float>(i0);
        return buffer[static_cast<size_t>(i0)] * (1.0f - frac) + buffer[static_cast<size_t>(i1)] * frac;
    }

    std::vector<float> buffer;
    float sr = 48000.0f;
    int32_t write = 0;
    float baseDelay = 200.0f;
    float gain = 0.995f;
    float tone = 0.5f;
    float loopState = 0.0f;
    float disperse = 0.0f;
    float dispersion = 0.0f;
    float stageDelay = 0.0f;
    int allpassStages = 0;
    float allpassState[kAllpass] = {};
    float tension = 0.0f;
    float damperPos = 0.5f;
    float damperPressure = 0.0f;
    float energy = 0.0f;
    float lastOut = 0.0f;
    float pending = 0.0f;
};

} // namespace acidulous::machine
