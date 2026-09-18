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
        dcIn = dcOut = 0.0f;
    }

    /**
     * Ask again for the note it is already playing and nothing is recomputed.
     *
     * `refreshLoop()` below is a sine, a cosine, four arctangents and an
     * exponential - it solves the loop's phase, which is the whole reason this
     * string plays in tune. That is a fine price to pay when the note changes.
     * It is not a fine price to pay *per sample*: Nexus's string block sets
     * frequency, dispersion, damping, tension and damper position from inside
     * its `step()`, because a module's inputs are per-sample signals and it
     * has no way to know which of them actually moved. Two of those five
     * setters recomputed unconditionally, so a held note solved its own loop
     * twice a sample, sixteen voices at once - and on a phone the audio thread
     * ran out of time and the patch crackled. Filament itself never noticed,
     * because it calls these once a block.
     *
     * A string whose pitch really is being modulated still pays, and should:
     * the loop has genuinely changed. This only declines to redo work whose
     * answer cannot have moved.
     */
    void setFrequency(float hz) {
        const float d = clampf(sr / clampf(hz, 18.0f, 8000.0f), 2.0f,
                               static_cast<float>(buffer.size() - 2));
        if (d == baseDelay) return;
        baseDelay = d;
        refreshDispersion();
        refreshLoop();
    }

    /**
     * [loopGain] is what one whole turn should come to, and the string works
     * out what to ask its own feedback for to get it.
     *
     * The other things in the loop are not free: the tone filter takes a
     * little at the note and the DC blocker takes four parts in a thousand,
     * which at a hundred and sixty-five turns a second is most of the loss
     * on a long-ringing string. Asked for two seconds, the first version
     * rang for three quarters of one. The caller says the number it means
     * and this divides out what the rest of the loop already costs - the
     * same reason the brass and the woodwinds solve their loops instead of
     * setting a coefficient and hoping.
     */
    void setDamping(float loopGain, float toneCutoff01) {
        gain = clampf(loopGain, 0.0f, 1.02f);
        const float t = clampf(toneCutoff01, 0.02f, 1.0f);
        if (t != tone) { tone = t; refreshLoop(); }
        else effGain = clampf(gain / loopKeeps, 0.0f, 1.05f);
    }
    void setDispersion(float amount01, int stages) {
        const float a = clampf(amount01, 0.0f, 1.0f);
        const int n = stages < 0 ? 0 : (stages > kAllpass ? kAllpass : stages);
        if (a == dispersion && n == allpassStages) return; // see setFrequency
        dispersion = a;
        allpassStages = n;
        refreshDispersion();
        refreshLoop();
    }
    void setTension(float amount01) { tension = clampf(amount01, 0.0f, 1.0f); }
    /** Where the damper sits, 0..1 along the string, and how hard it presses. */
    void setDamper(float position01, float pressure01) {
        const float p = clampf(position01, 0.0f, 1.0f), f = clampf(pressure01, 0.0f, 1.0f);
        if (p != damperPos || f != damperPressure) {
            damperPos = p; damperPressure = f;
            refreshLoop();
        }
    }

    /**
     * Add energy from outside - a neighbouring string, a coupling, a knock.
     * It is held until the next step rather than written into the buffer,
     * because step() writes that slot itself and would throw it away.
     */
    void excite(float value) { pending += value; }

    /**
     * The same, but stated as a fraction of one trip round the string.
     *
     * A string is D samples round, so anything poured in every sample goes in
     * D times a turn - and D is three hundred at the bottom of the range and
     * thirty at the top. A coupling written per sample is therefore ten times
     * stronger on the low string than the high one, which is how two strings
     * meant to share a bridge came to add two and a half to each other's loop
     * gain and run away. Per turn, it means the same thing at every pitch.
     */
    void exciteOverTurn(float value) { pending += value / baseDelay; }
    /** What one sample's worth is, as a fraction of a turn. */
    float turnScale() const { return 1.0f / baseDelay; }

    float step(float input) {
        // Tension modulation: the harder it is ringing, the shorter the loop.
        // The loop is longer than the delay line - one sample for the write,
        // and the loop filter's own phase delay, which grows as the filter
        // closes. Uncompensated, a string plays flat and further flat the
        // higher it is asked to go.
        // The buffer itself contributes exactly `delay` samples, no more:
        // what is written now is read when the pointer comes round to it.
        // Everything else in the loop has phase of its own, and `loopTrim` is
        // what that comes to at this note. See refreshLoop().
        const float wanted = baseDelay * (1.0f - tension * 0.02f * energy) - loopTrim;
        const float delay = clampf(wanted, 2.0f, static_cast<float>(buffer.size() - 2));
        float out = read(delay);
        if (damperPressure > 0.0f) {
            // A finger on a string can only take away. Subtracting a delayed
            // tap on its own is a comb, and a comb has peaks: where the tap
            // comes back inverted this read `1 + k`, so a damper set to 0.4
            // handed the loop a gain of 1.36 at every other null and the
            // prepared patch grew a partial until it was louder at the end of
            // its tail than the start. Divided by the peak it still nulls the
            // note at a node - which is the whole point of putting a finger
            // there - and never returns more than it was given.
            const float k = damperPressure * 0.9f;
            const float tapped = read(clampf(delay * damperPos, 1.0f, delay - 1.0f));
            out = (out - tapped * k) / (1.0f + k);
        }
        // Loop filter: one pole, so the top goes first.
        loopState += (out - loopState) * tone;
        float fed = loopState * effGain;
        for (int i = 0; i < allpassStages; ++i) {
            const float y = disperse * fed + allpassState[i];
            allpassState[i] = fed - disperse * y;
            fed = y;
        }
        float next = fed + input + pending;
        pending = 0.0f;
        // A string cannot hold a steady displacement: both its ends are
        // fixed, and whatever a hammer or a bow leans on it with has to come
        // back out. Nothing here said so, and the loop passes DC whole - so a
        // hammer, whose blow is a squared sine and therefore never negative,
        // and a jet, whose pressure has an offset by definition, each left a
        // standing offset going round for ever. It was a seventh of full
        // scale on the sympathetic patch: headroom spent on nothing, a thump
        // at every note, and enough of a pedestal that the harness could not
        // find a pitch in six of these ten patches at all.
        const float hp = next - dcIn + dcPole * dcOut;
        dcIn = next;
        dcOut = hp;
        next = hp;
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
    /** How much of the string's own length full stiffness may bend. */
    static constexpr float kStiffBudget = 0.085f;
    /**
     * Where the loop's high-pass sits, as a fraction of the note.
     *
     * A fixed eight hertz is not low enough to be out of the way and not high
     * enough to be any use. A waveguide's loop gain at frequencies well under
     * its own note is the loop filter's gain, near one - and a bow adds its
     * own negative resistance on top of that, which took the total over one
     * and turned the corner of this very filter into a relaxation oscillator:
     * the bowed patch grew an eleven-hertz wander that the harness read as a
     * note two and a half octaves under the one asked for.
     *
     * A tenth of the note leaves the fundamental four parts in a thousand
     * lighter per turn and takes eleven hertz down to a half, which is under
     * one once the bow is added. A string has no motion below its own
     * fundamental, so tying this to the note is what the instrument says
     * anyway.
     */
    static constexpr float kDcBelow = 0.1f;

    // How much extra delay each allpass adds at the bottom of the range,
    // and therefore where its dispersion happens. A coefficient chosen
    // without reference to the string puts the whole effect up near Nyquist,
    // where none of the partials are, which is why a first attempt at this
    // sounds like nothing at all. Tie it to the string's own length instead.
    //
    // **A twelfth of the string at full stiffness, not a third.** A real wire
    // is sharp in its partials by a fraction of a percent at the bottom of a
    // piano and a few percent at the top; a third of the string's own length
    // is not a stiff string, it is a different instrument every note, and it
    // is why the two stiffest patches here had no findable pitch at all - not
    // a wrong one, none. The parameter still reaches further than any real
    // wire at 1.
    void refreshDispersion() {
        if (dispersion < 0.005f || allpassStages == 0) {
            disperse = 0.0f;
            stageDelay = allpassStages > 0 ? 1.0f : 0.0f;
            return;
        }
        const float budget = baseDelay * kStiffBudget / static_cast<float>(allpassStages);
        stageDelay = clampf(1.0f + dispersion * budget, 1.0f, 96.0f);
        disperse = (1.0f - stageDelay) / (1.0f + stageDelay);
    }

    /**
     * How much of the note's own period the rest of the loop is already using.
     *
     * The loop is not the delay line: it is the line, the one-pole that takes
     * the top off, an allpass chain if the string is stiff, and a damper tap
     * if something is resting on it. Each has phase, and it is the phase **at
     * the note** that decides where the string sounds - not the phase at DC,
     * which is what the first version subtracted. A first-order allpass has
     * its full group delay at DC and less at every frequency above it, so a
     * stiff string came out sharp and a stiffer one came out a long way
     * sharp; the damper tap was not counted at all, and a damper a third of
     * the way along put the note fifty cents up.
     *
     * The tap is placed along the delay we are solving for, so that part is
     * circular: three passes settle it to well under a cent.
     */
    void refreshLoop() {
        const float w = 6.28318530718f / baseDelay; // radians a sample, at the note
        const float sw = std::sin(w), cw = std::cos(w);
        // The loop filter, one pole at `tone`.
        float phase = -std::atan2((1.0f - tone) * sw, 1.0f - (1.0f - tone) * cw);
        // The DC blocker, which leads rather than lags, and which sits at a
        // tenth of whatever note this is.
        dcPole = std::exp(-6.28318530718f * kDcBelow / baseDelay);
        {
            const float nr = 1.0f - cw, ni = sw;
            const float dr = 1.0f - dcPole * cw, di = dcPole * sw;
            phase += std::atan2(ni, nr) - std::atan2(di, dr);
        }
        // The stiffness chain. Counted even when the string is not stiff at
        // all: at a coefficient of zero an allpass is still a one-sample
        // delay, and two of those on a 367-sample string is nine cents. The
        // formula gives exactly -w there, so there is nothing to guard.
        if (allpassStages > 0) {
            const float one = std::atan2(-sw, disperse + cw) -
                              std::atan2(-disperse * sw, 1.0f + disperse * cw);
            phase += one * static_cast<float>(allpassStages);
        }
        float d = baseDelay + phase / w;
        if (damperPressure > 0.0f) {
            const float k = damperPressure * 0.9f;
            for (int i = 0; i < 3; ++i) {
                // The tap is nearer the write head than the main read, so it
                // is an *advance* against it.
                const float back = w * d * (1.0f - damperPos);
                const float re = 1.0f - k * std::cos(back), im = -k * std::sin(back);
                // A tap can lead as well as lag, so the answer is allowed to
                // be longer than the period as well as shorter.
                d = clampf(baseDelay + (phase + std::atan2(im, re)) / w,
                           2.0f, baseDelay * 1.5f);
            }
        }
        loopTrim = clampf(baseDelay - d, -baseDelay * 0.5f, baseDelay - 2.0f);

        // ...and what the same three things do to the *size* of a partial at
        // the note, which is what the feedback has to make up if `sustain` is
        // to mean seconds. The allpass chain is not in here: it is an
        // allpass, and takes nothing.
        const float toneMag = tone / std::sqrt((1.0f - (1.0f - tone) * cw) * (1.0f - (1.0f - tone) * cw) +
                                               ((1.0f - tone) * sw) * ((1.0f - tone) * sw));
        const float dcMag = std::sqrt((1.0f - cw) * (1.0f - cw) + sw * sw) /
                            std::sqrt((1.0f - dcPole * cw) * (1.0f - dcPole * cw) +
                                      (dcPole * sw) * (dcPole * sw));
        // ...and the straight line drawn between two samples to read a
        // fractional delay, which is a low-pass and not a free one. It costs
        // a thousandth of a turn at the top of a range, which sounds like
        // nothing until you remember the string goes round six hundred times
        // a second there: a note that should ring as long as the one three
        // octaves below it rang a third as long. The last of the losses in
        // this loop that nothing was counting.
        const float frac = d - std::floor(d);
        const float ir = 1.0f - frac + frac * cw, ii = -frac * sw;
        const float interp = std::sqrt(ir * ir + ii * ii);
        loopKeeps = clampf(toneMag * dcMag * interp, 0.05f, 1.0f);
        effGain = clampf(gain / loopKeeps, 0.0f, 1.05f);
    }

    /**
     * The loop, `delay` samples back.
     *
     * Through `wrappedReadIndex` for the reason given there: this was the
     * third copy of that arithmetic and the second one that was wrong. A
     * string reads its own loop, so anything it picks up from past the end of
     * the buffer goes round and round rather than passing once.
     */
    float read(float delay) const {
        const auto len = static_cast<int32_t>(buffer.size());
        float frac = 0.0f;
        const int32_t i0 = dsp::wrappedReadIndex(write, delay, len, frac);
        const int32_t i1 = i0 + 1 >= len ? 0 : i0 + 1;
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
    float loopTrim = 0.0f; // what the rest of the loop already costs, in samples
    float dcIn = 0.0f, dcOut = 0.0f, dcPole = 0.999f;
    // What one turn keeps of a partial at the note, and the feedback that
    // makes the asked-for gain come out the other side of it.
    float loopKeeps = 1.0f, effGain = 0.995f;
    float tension = 0.0f;
    float damperPos = 0.5f;
    float damperPressure = 0.0f;
    float energy = 0.0f;
    float lastOut = 0.0f;
    float pending = 0.0f;
};

} // namespace acidulous::machine
