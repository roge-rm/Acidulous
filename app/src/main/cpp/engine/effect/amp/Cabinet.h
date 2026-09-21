#pragma once
#include <cmath>
#include <cstdint>
#include <engine/dsp/Biquad.h>
#include <engine/dsp/DelayLine.h>
#include <engine/dsp/Filter.h>
#include <engine/dsp/Math.h>

// A speaker in a box, modelled rather than sampled.
//
// **Why not an impulse response.** Two reasons pointing the same way. An IR is
// a measurement of somebody else's cabinet with somebody else's microphone, and
// this project ships no recording it did not make. And a model buys the thing
// convolution cannot: **a cabinet you can resize**, continuously, so you can
// sit between a practice combo and a four-by-twelve and a bass eight-by-ten.
//
// A real guitar cab's response has four features that matter, and one of them
// dominates:
//
//   - a **box rolloff** with a bump at its cutoff, from ~130 Hz on a sealed
//     1x12 down to ~50 Hz on an 8x10;
//   - **baffle colour** in the low mids;
//   - **cone breakup** between 1.5 and 4 kHz, which is the speaker's voice;
//   - a **cliff** at 4-6 kHz where the cone stops moving as one piece. This is
//     the one that matters most: a cab model without a hard cliff sounds like a
//     distortion pedal with an EQ, every time.
//
// The size law is `Filament`'s, verbatim, because one app should have one idea
// of what a box being bigger means.
namespace acidulous::effect::amp {

class Cabinet {
  public:
    void prepare(float sampleRate) {
        sr = sampleRate;
        box.setSampleRate(sr);
        comb.prepare(128);
        room.prepare(static_cast<int32_t>(sr * 0.05f));
        reset();
    }

    void reset() {
        box.reset();
        for (auto &b : peaks) b.reset();
        cliffA.reset();
        cliffB.reset();
        roomTone.reset();
        comb.clear();
        room.clear();
        builtFor = -1.0f;
    }

    /**
     * Coefficients, once a block and only when something moved.
     *
     * Twelve sections of transcendentals per block per channel, across a
     * songful of racks, is real time spent in `std::pow` for knobs nobody is
     * touching - so this is guarded the way Filament and Resonance guard
     * theirs. The threshold is 2e-4 and not 1e-6 because a smoothed parameter
     * converges geometrically and would never quite arrive.
     */
    void set(float size, float cone, float mic, float edge, float roomAmt) {
        const float key = size + cone * 3.0f + mic * 7.0f + edge * 11.0f + roomAmt * 13.0f;
        if (std::fabs(key - builtFor) < 2e-4f) return;
        builtFor = key;
        this->edgeAmt = edge;
        this->roomAmt = roomAmt;

        // Filament's law: 2.14 at nought, 1.0 in the middle, 0.466 at the top.
        const float s = std::pow(2.0f, (0.5f - size) * 2.2f);

        // **The fractional exponents are the musicality of the sweep.** The box
        // sets the low end nearly proportionally, but breakup is a property of
        // the *cone* and not of the enclosure - a ten inch and a fifteen do not
        // differ in breakup by the ratio their boxes differ in cutoff. Scale
        // everything by s and this stops being a cabinet and becomes a tone
        // control: dark at one end, thin at the other, monotonic and useless.
        const float boxHz = 95.0f * s;
        const float coneScale = std::pow(s, 0.5f);
        const float cliffScale = std::pow(s, 0.35f) * std::pow(2.0f, (cone - 0.5f) * 0.7f) *
                                 std::pow(2.0f, -1.4f * mic) * std::pow(2.0f, -0.5f * edge) *
                                 std::pow(2.0f, -0.25f * roomAmt);

        boxCorner = dsp::clampf(boxHz, 30.0f, 400.0f);
        box.set(boxCorner, 0.42f);
        // The bump at the box's corner, which is what a sealed cabinet does.
        peaks[0].peak(dsp::clampf(110.0f * s, 30.0f, 400.0f), 4.5f - 2.0f * size + 1.5f * mic, 1.6f, sr);
        // The baffle, and the woody mid a rim mic hears.
        peaks[1].peak(dsp::clampf(210.0f * std::pow(s, 0.9f), 60.0f, 900.0f),
                      -2.5f + 1.2f * size + 2.0f * edge, 1.2f, sr);
        // Cone breakup: three, because two read as one wide hump and four is
        // past where anybody hears the difference.
        const float flat = 1.0f - 0.55f * mic;
        const float q = 1.2f + 2.3f * cone;
        peaks[2].peak(dsp::clampf(1500.0f * coneScale, 400.0f, 6000.0f),
                      (-2.0f + 6.0f * cone) * flat, q, sr);
        peaks[3].peak(dsp::clampf(2600.0f * coneScale, 600.0f, 9000.0f),
                      (-4.0f + 10.0f * cone) * (1.0f - 0.25f * size) * flat, q, sr);
        peaks[4].peak(dsp::clampf(4000.0f * std::pow(s, 0.4f), 900.0f, 12000.0f),
                      (-3.0f + 7.0f * cone) * flat, q, sr);
        cliffA.lowpass(dsp::clampf(4800.0f * cliffScale, 700.0f, 16000.0f), 0.9f, sr);
        cliffB.lowpass(dsp::clampf(6200.0f * cliffScale, 900.0f, 18000.0f), 0.6f, sr);
        roomTone.lowpass(3500.0f, 0.707f, sr);

        // The comb a rim mic hears, between the near and far edge of the cone.
        combDelay = (0.12f + 0.28f * edge) * 0.001f * sr;
        trim = measuredTrim();
    }

    /** One sample, one channel. */
    float process(float x) {
        float y = box.highpass(x);
        for (auto &b : peaks) y = b.process(y);
        y = cliffB.process(cliffA.process(y));
        if (edgeAmt > 1e-4f) {
            comb.write(y);
            y -= 0.45f * edgeAmt * comb.read(combDelay);
        }
        if (roomAmt > 1e-4f) {
            room.write(y);
            // Three early reflections and no feedback, so a room cannot ring.
            // Non-harmonic spacings, so the taps do not sum into a pitch.
            const float r = 0.42f * room.read(0.0068f * sr) + 0.30f * room.read(0.0113f * sr) +
                            0.22f * room.read(0.0191f * sr);
            y += roomAmt * 0.55f * roomTone.process(r);
        }
        return y * trim;
    }

    /** What the chain does at [hz], for the harness and for the trim. */
    float magnitudeAt(float hz) const {
        float g = 1.0f;
        for (const auto &b : peaks) g *= b.magnitudeAt(hz, sr);
        g *= cliffA.magnitudeAt(hz, sr) * cliffB.magnitudeAt(hz, sr);
        // The box is an `Svf` and has no `at`, so it is stood in for by two
        // poles' worth of highpass at its own corner. Close enough for a
        // weighting whose job is to keep levels together - and it has to be
        // **its own corner**, not the one it starts at, or the measurement
        // cannot see `size` move at all and the trim is made against a
        // cabinet that is not there.
        const float w = hz / boxCorner;
        const float hp = w * w / (1.0f + w * w);
        g *= hp;
        return g;
    }

    float ringSeconds(int32_t which) const { return peaks[which].ringSeconds(sr); }
    /**
     * The level compensation, which [magnitudeAt] deliberately leaves out.
     *
     * The trim is *computed from* the untrimmed response, so folding it into
     * the same function would be circular. A caller asking what the cabinet
     * actually does multiplies the two.
     */
    float outputTrim() const { return trim; }
    static constexpr int32_t kPeaks = 5;

  private:
    /**
     * Keep every cabinet within a couple of decibels of every other.
     *
     * The cliff moves with five controls and the peaks move with `cone`, so
     * broadband level moves a lot - and a `size` knob that is also a volume
     * knob is a `size` knob nobody can judge. Measured from the chain's own
     * response at twelve log-spaced frequencies rather than from a table of
     * numbers somebody tuned once.
     */
    float measuredTrim() const {
        double sum = 0.0;
        for (int32_t i = 0; i < 12; ++i) {
            const float hz = 80.0f * std::pow(2.0f, static_cast<float>(i) * 0.55f);
            const float g = magnitudeAt(hz);
            sum += static_cast<double>(g) * g;
        }
        const double rms = std::sqrt(sum / 12.0);
        return rms > 1e-6 ? static_cast<float>(dsp::clampf(0.55f / static_cast<float>(rms), 0.25f, 4.0f))
                          : 1.0f;
    }

    dsp::Svf box;
    dsp::Biquad peaks[kPeaks], cliffA, cliffB, roomTone;
    dsp::DelayLine comb, room;
    float sr = 48000.0f;
    float combDelay = 8.0f, edgeAmt = 0.0f, roomAmt = 0.0f, trim = 1.0f;
    float boxCorner = 95.0f;
    float builtFor = -1.0f;
};

} // namespace acidulous::effect::amp
