#pragma once
#include <cmath>
#include <cstdint>
#include <engine/dsp/Math.h>
#include <vector>

// One player's instrument: a tube, a bell, and a pair of lips.
//
// A brass instrument is a pressure wave going round a loop. The lips are a
// valve the player holds nearly shut; the pressure behind them and the
// pressure inside the tube together decide how far they open, and that is
// the whole nonlinearity that makes brass behave like brass - the notes lock
// to the tube's resonances, the tone opens out as you blow harder, and the
// thing screams rather than simply getting louder.
//
// Such a loop plays the tube's lowest mode, and *where* that mode sits is
// decided by everything in the loop, not just the length of the line: the
// lips, the bell and the DC blocker each hold the wave up a little. Models
// of this kind are famous for playing a quarter-tone flat because of it.
// So the line is not set to a period and hoped for - the loop is linearised
// about the note, its phase evaluated there, and the line set to whatever
// makes the total come to exactly one turn. See tune().
//
// Two additions to the textbook loop:
//
//   - **Brassiness.** A loud wave in a real tube steepens as it travels - the
//     crest catches up with the trough - until it is very nearly a shock.
//     That is why a fortissimo trombone is a different instrument from a
//     mezzo one and not just a louder one. The steepening here grows with
//     the wave's own amplitude, so the brightness arrives with the effort.
//   - **A bell that radiates rather than reflects.** What leaves the
//     instrument is the part the bell does *not* send back, which is why the
//     high end is outside the horn and the low end is still inside it.
namespace acidulous::machine::brazen {

using dsp::clampf;

/**
 * The DC blocker's pole, and it is not a free choice on this instrument.
 *
 * 0.997 is a corner at 22.9 Hz - one octave below a tuba's pedal F, so the
 * filter meant to remove what cannot be heard is standing on the lowest thing
 * the machine can play. Measured at F2, moving it to 3.8 Hz returns **18% of
 * the tuba's fundamental** and 15% of its peak, and 12% to a bass trombone.
 *
 * And it is left where it is anyway, which is worth writing down so nobody
 * finds the same 18% and takes it. The corner is not only removing the
 * fundamental, it is holding the whole instrument's timbre where it is: moved
 * down, the harmonic ladder runs Tuba 7, Trombone 12, Trumpet 12, Harmon 12
 * where it ran 3, 4, 9, 12 - every instrument bright and none of them
 * distinguishable, which is precisely the fault this bank was written to fix.
 * Taking the 18% means re-voicing all fourteen against a different machine,
 * and that is a decision rather than a tidy-up.
 *
 * Used twice on purpose: once as the filter and once in `tune`, where its
 * phase is part of the loop the line length is solved against. Changing one
 * without the other detunes the instrument.
 */
constexpr float kDcPole = 0.997f;

class Bore {
  public:
    void prepare(float sampleRate) {
        sr = sampleRate;
        // A tuba's pedal, and room for the read to wrap.
        line.assign(static_cast<size_t>(sampleRate / 18.0f) + 8, 0.0f);
        clear();
    }

    /**
     * Everything the tube is holding, and not a chosen subset of it.
     *
     * `lastArrive` was missing, which is the wave that was at the bell one
     * sample ago and which sets where the *next* sample is read from - the
     * brass steepening bends the read by a fraction of it. So a cleared tube
     * carried one number of the note before it into the note after, and it
     * survived reset_test only because the difference used to decay to
     * nothing before the comparison; priming the line made it audible.
     *
     * Nothing here is configuration - the machine sets every parameter on
     * every block - so this can take the lot back, and `dirty` makes sure the
     * loop is solved again rather than reusing a delay worked out for a note
     * that is over.
     *
     * `delay` has to go with it and not merely be recomputed later, because
     * `prime` runs before the next `tune` does and reads it. Left behind, the
     * fade was cut to the length of the *previous* note's tube and a render
     * that followed a panic differed from one that did not, at the very
     * first sample. Twice now the answer here has been that a reset takes
     * back everything or it takes back nothing useful.
     */
    void clear() {
        for (auto &v : line) v = 0.0f;
        write = 0;
        bellState = 0.0f;
        lipX1 = lipX2 = lipY1 = lipY2 = 0.0f;
        lipEnv = 0.0f;
        dcIn = dcOut = 0.0f;
        radiated = 0.0f;
        lastArrive = 0.0f;
        loopMag = 0.0f;
        delay = 0.0f;
        dirty = true;
    }

    /** The note. tune() works out how long the line has to be for it. */
    void setFrequency(float hz) {
        const float f = clampf(hz, 20.0f, 4000.0f);
        if (f != freq) { freq = f; dirty = true; }
    }

    /**
     * [tension] multiplies where the lips want to buzz against the note. At
     * one they buzz it; below, they lean on the fundamental and the tone
     * goes round and dark; above, they favour the partials over it and the
     * instrument brightens the way a player leaning in does. The loop is
     * retuned for it, so it colours the note rather than bending it.
     */
    void setLips(float tension, float damping) {
        const float t = clampf(tension, 0.25f, 3.0f), d = clampf(damping, 0.0f, 1.0f);
        if (t != lipTension || d != lipDamp) { lipTension = t; lipDamp = d; dirty = true; }
    }

    /** How loud the lips ring back into the valve. */
    void setLipGain(float g) {
        const float v = clampf(g, 0.0f, 4.0f);
        if (v != lipGain) { lipGain = v; dirty = true; }
    }

    /** How hard the player is blowing, which the tuning depends on. */
    void setPressure(float p) {
        const float v = clampf(p, 0.0f, 2.0f);
        if (std::fabs(v - pressure) > 0.02f) { pressure = v; dirty = true; }
    }

    /** Bell: how much comes back, and how dull what comes back is. */
    void setBell(float reflection, float cutoff01) {
        const float rf = clampf(reflection, 0.5f, 0.995f), c = clampf(cutoff01, 0.02f, 0.98f);
        if (rf != reflect || c != bellCoeff) { reflect = rf; bellCoeff = c; dirty = true; }
    }
    void setBrass(float amount) { brass = clampf(amount, 0.0f, 1.0f); }
    /** How far the lips close up as the note takes hold. */
    void setBite(float amount) { bite = clampf(amount, 0.0f, 1.6f); }
    /** How far open the lips sit before anything happens. */
    void setRest(float amount) {
        const float v = clampf(amount, 0.0f, 0.9f);
        if (v != rest) { rest = v; dirty = true; }
    }
    void setLoss(float amount) { loss = clampf(amount, 0.8f, 1.0f); }

    /**
     * The tongue coming off the note: start the tube with air already moving.
     *
     * A loop whose gain is just over one grows by the same factor every round
     * trip, so it takes the same number of *round trips* to speak whatever it
     * is playing - and a round trip is a period. That is why this instrument
     * was taking 47 ms to speak at 700 Hz and 758 at 44: identical certainty,
     * sixteen times the wait, and nothing wrong with the solve. Measured, the
     * two agreed to a percent.
     *
     * A player does not start from an empty tube. The tongue releases air
     * that is already under pressure and the horn is sounding within a few
     * cycles; the loop's job is then to sustain the wave, not to grow one out
     * of nothing. So the line is filled with one period of the note at
     * roughly the level it is going to settle at, and the loop takes it from
     * there - which is a couple of round trips either way rather than thirty.
     *
     * It also removes the click that was the real complaint. An empty tube
     * returns its first reflection about two round trips in, long before the
     * wave has grown into anything, so what came out was a pip and then
     * ninety milliseconds of nearly silence and then the note. There is no
     * gap to stand in now.
     *
     * [level] is the mouth pressure the note is being blown with; the shape
     * is a sine because the fundamental is most of the standing wave and the
     * loop fills in the rest within a few cycles.
     */
    void prime(float level) {
        if (!(level > 0.0f)) return;
        const int32_t size = static_cast<int32_t>(line.size());
        const auto period = static_cast<int32_t>(sr / freq + 0.5f);
        if (period < 4 || period > size) return;
        // Only where it is needed, which is where the tube is long. A short
        // one fills in a few cycles on its own - a piccolo trumpet speaks in
        // 30 ms untouched - and priming it as hard overshoots by half again
        // and has to be pulled back down, which is a blat on the front of
        // every note. Full below 150 Hz, nothing above 300, measured.
        const float need = clampf((300.0f - freq) / 150.0f, 0.0f, 1.0f);
        if (need <= 0.0f) return;
        // A quarter, and not more, because the curve is not symmetric.
        // Measured on a tuba, against how loud the first ten milliseconds
        // are and how far the loop's own settling wanders off zero:
        //
        //   prime  starts at   worst DC   speaks
        //    0.00      0.0%       4.8%     500 ms
        //    0.22     16.2%       5.1%     110 ms
        //    0.44     32.5%       7.5%      50 ms
        //    0.66     48.7%       8.7%      10 ms
        //
        // The whole of the gain is bought by the first quarter: from nothing
        // to 0.22 takes 500 ms down to 110 for three tenths of a percent
        // more wander, and everything past that buys tens of milliseconds
        // for a note that starts halfway up - which is heard as a thump, and
        // was. 4.8% is the loop settling on its own and is the floor.
        const float amp = level * 0.26f * need;
        const float w = 6.28318530718f / static_cast<float>(period);
        // Written backwards from the write head, because that is the order
        // the read head takes it in: it is `delay` behind, so it meets what
        // was written furthest back first and arrives at the newest sample
        // one period later, by which time the loop is writing its own.
        //
        // And faded in across that period rather than written flat. The
        // envelope in this machine drives the *mouth pressure* and not the
        // output - a player leans harder, they do not turn a volume knob -
        // so it cannot shape a wave that is already in the tube. A flat
        // prime therefore arrived as a step from silence to nine tenths of
        // the note in a single sample, which is a thump whatever the attack
        // time says. A raised cosine over the one period that gets read
        // costs nothing and is the tongue leaving the reed rather than a
        // door slamming.
        const auto reach = static_cast<int32_t>(delay > 4.0f ? delay : static_cast<float>(period));
        double sum = 0.0;
        for (int32_t i = 0; i < size; ++i) {
            const size_t at = static_cast<size_t>((write - i + size) % size);
            const float t = i < reach ? 1.0f - static_cast<float>(i) / static_cast<float>(reach) : 0.0f;
            const float fade = 0.5f - 0.5f * std::cos(3.14159265359f * t);
            line[at] = amp * fade * std::sin(w * static_cast<float>(i));
            if (i < reach) sum += line[at];
        }
        // A faded sine is not a balanced one: the window weights the two
        // halves of the cycle differently and what is left over is DC, which
        // is the thump the fade was supposed to remove. Taking the mean of
        // the part that actually gets read back out costs one pass and
        // leaves the tube holding a wave rather than a wave and a step.
        const auto mean = static_cast<float>(sum / (reach > 0 ? reach : 1));
        for (int32_t i = 0; i < size; ++i) {
            line[static_cast<size_t>((write - i + size) % size)] -= mean;
        }
        // The lips have to know something is happening too, or they sit at
        // rest and clamp the wave the tube just handed them.
        //
        // Three times the primed amplitude, and that is measured rather than
        // reasoned: `lipEnv` is a one-pole with a 35 ms time constant that
        // walks the valve's rest point, so seeding it wrong leaves the whole
        // output drifting for a hundred milliseconds at a few hertz, which
        // is heard as a thump on the front of the note. Against the 30 Hz in
        // the onset, on a tuba: seed 0 gives 8.3x the settled level, 0.5x
        // gives 6.8, 1.5x gives 4.7, 3x gives 3.8, and 5x and 8x climb back
        // to 5.7 and 7.4. The bottom of that curve is where the smoother was
        // going to end up anyway.
        lipEnv = amp * 3.0f;
    }

    /**
     * Work out how long the line has to be for the loop to come round in
     * exactly one period of the note.
     *
     * Linearise the valve about where it sits: the wave coming back moves
     * the lips, the lips move the opening, the opening decides how much of
     * the player and how much of the tube goes back down the line. That
     * gives one complex number G at the note; the bell filter and the DC
     * blocker give two more. Their phases are how far the loop is already
     * round before the line is counted, so the line takes the rest. Called
     * whenever anything in that sentence changes, and never per sample.
     */
    void tune() {
        if (!dirty) return;
        dirty = false;

        lipHz = clampf(freq * lipTension, 20.0f, sr * 0.45f);
        // A lip is a resonance with a *quality*, not a pole radius. Set the
        // radius directly and a low note gets a filter a kilohertz wide,
        // which is not a lip at all: its gain then climbs with frequency,
        // the fourth mode of the tube wins over the first, and a tuba plays
        // a trumpet's note. Ask for a Q and let the radius follow it.
        const float q = 0.7f + (1.0f - lipDamp) * 12.0f;
        const float lw = 6.28318530718f * lipHz / sr;
        // ...but not narrower than a real pair of lips, which are a
        // centimetre of wet muscle and not a crystal. Without the floor a
        // tuba's lip filter is four hertz wide, only the fundamental gets
        // through it, and the biggest instrument in the band comes out a
        // sine wave.
        float bw = lw / (2.0f * q);
        const float floorBw = 6.28318530718f * 15.0f / sr;
        if (bw < floorBw) bw = floorBw;
        const float r = clampf(1.0f - bw, 0.3f, 0.9995f);
        lipA1 = 2.0f * r * std::cos(lw);
        lipA2 = -r * r;
        // Zeros at DC and at Nyquist: the lips must not feel the steady
        // pressure behind them, only its wobble. Driven by the whole
        // difference - lungs included - they simply slam open and stay
        // there, and the instrument makes one click and dies.
        //
        // Negative, and that sign is the difference between a clarinet and
        // a trumpet. A reed is blown *shut*: pressure inside the tube
        // pushes it against the mouthpiece. Lips are blown *open*: the same
        // pressure pushes them apart, so the valve opens as the tube fills
        // and the loop has gain instead of losing it. With the reed's sign
        // this model cannot sing at all.
        lipB0 = -1.0f;
        {   // Normalise, so lipGain means what it says at the lips' own note.
            const float c1 = std::cos(lw), s1 = std::sin(lw);
            const float c2 = std::cos(2.0f * lw), s2 = std::sin(2.0f * lw);
            const float nr = 1.0f - c2, ni = s2;
            const float dr = 1.0f - lipA1 * c1 - lipA2 * c2, di = lipA1 * s1 + lipA2 * s2;
            const float m = std::sqrt((nr * nr + ni * ni) / (dr * dr + di * di + 1e-20f));
            lipB0 = -1.0f / (m > 1e-6f ? m : 1e-6f);
        }

        const float w = 6.28318530718f * freq / sr;
        const float c1 = std::cos(w), s1 = std::sin(w);
        const float c2 = std::cos(2.0f * w), s2 = std::sin(2.0f * w);

        // The lips: b0(1 - z^-2) over 1 - a1 z^-1 - a2 z^-2.
        float nr = lipB0 * (1.0f - c2), ni = lipB0 * s2;
        float dr = 1.0f - lipA1 * c1 - lipA2 * c2, di = lipA1 * s1 + lipA2 * s2;
        float den = dr * dr + di * di + 1e-20f;
        const float hr = (nr * dr + ni * di) / den, hi = (ni * dr - nr * di) / den;

        // The DC blocker: (1 - z^-1) / (1 - 0.997 z^-1).
        nr = 1.0f - c1; ni = s1;
        dr = 1.0f - kDcPole * c1; di = kDcPole * s1;
        den = dr * dr + di * di + 1e-20f;
        const float br = (nr * dr + ni * di) / den, bi = (ni * dr - nr * di) / den;

        // The bell's reflection: c / (1 - (1-c) z^-1).
        nr = bellCoeff; ni = 0.0f;
        dr = 1.0f - (1.0f - bellCoeff) * c1; di = (1.0f - bellCoeff) * s1;
        den = dr * dr + di * di + 1e-20f;
        const float lr = (nr * dr + ni * di) / den, li = (ni * dr - nr * di) / den;

        // Now the embouchure. A player does not hold their lips still and
        // hope the horn speaks; they lean on it until it does, and lean
        // harder to play louder. So rather than setting a lip drive and
        // measuring what the loop does with it, ask for the loop gain the
        // note should have - just over one, and further over it the harder
        // the player is blowing - and solve for the drive that gives it.
        // Everything else about the horn is then free to change the *tone*
        // without deciding whether it speaks at all, which is why this one
        // plays a low F and a high C with the same certainty.
        const float open0 = clampf(rest, 0.0f, 1.0f);
        const float a0 = 1.0f - open0 * open0;
        const float k = 2.0f * open0 * clampf(pressure, 0.0f, 2.0f) + 1e-6f;
        const float outside = std::sqrt((br * br + bi * bi) * (lr * lr + li * li)) * reflect * loss;
        const float want = clampf(0.86f + 0.62f * pressure * lipGain, 0.0f, 1.9f);
        const float t = want / (outside > 1e-6f ? outside : 1e-6f);

        // The returning wave moves the lips, the lips move the opening,
        // the opening decides how much of the player and how much of the
        // tube goes back down the line: G = a0 - k H. Solve |G| = t for a
        // lip drive u >= 0.
        const float hh = hr * hr + hi * hi + 1e-20f;
        const float disc = a0 * a0 * hr * hr - hh * (a0 * a0 - t * t);
        float u = 0.0f;
        if (disc >= 0.0f) {
            const float root = (a0 * hr + std::sqrt(disc)) / (k * hh);
            if (root > 0.0f) u = root;
        }
        if (u > 40.0f) u = 40.0f;
        lipB0 *= u;
        const float gr = a0 - k * u * hr, gi = -k * u * hi;

        float phase = std::atan2(gi, gr) + std::atan2(bi, br) + std::atan2(li, lr);
        // Into (-pi, pi], so the line lands within half a period of nominal.
        const float twoPi = 6.28318530718f;
        while (phase > 3.14159265359f) phase -= twoPi;
        while (phase <= -3.14159265359f) phase += twoPi;
        const float period = sr / freq;
        delay = clampf(period + phase / w, 4.0f, static_cast<float>(line.size() - 3));
        loopMag = std::sqrt((gr * gr + gi * gi) * (br * br + bi * bi) * (lr * lr + li * li)) * reflect * loss;
    }

    /**
     * One sample. [mouth] is the pressure behind the lips (0 is silence),
     * [noise] the turbulence in the airstream. Returns what the bell puts
     * into the room.
     */
    float step(float mouth, float noise) {
        const int32_t size = static_cast<int32_t>(line.size());
        // Steepening. A loud wave in a real tube travels *unevenly*: the
        // crest is carried on air the crest itself has compressed, so it
        // gains on the trough ahead of it until the front is very nearly a
        // shock. That is why a fortissimo trombone is a different
        // instrument from a mezzo one and not simply a louder one.
        //
        // Which makes it a delay that depends on the wave, not a distortion
        // of it: the loud parts of the tube are short and the quiet parts
        // long. Bending the read that way puts the harmonics in without
        // touching how much gain the loop has, so the horn brightens under
        // pressure and still plays the note it was asked for.
        // In fractions of the wave's own length, not in samples: the tube
        // is the same tube whichever note is in it, and a fixed number of
        // samples would steepen a trumpet and leave a tuba alone.
        float read = delay - brass * delay * 0.06f * lastArrive;
        if (read < 4.0f) read = 4.0f;
        else if (read > static_cast<float>(size - 3)) read = static_cast<float>(size - 3);
        const int32_t at = static_cast<int32_t>(read);
        const float frac = read - static_cast<float>(at);
        const size_t i0 = static_cast<size_t>((write - at + size) % size);
        const size_t i1 = i0 == 0 ? line.size() - 1 : i0 - 1;
        const float arrive = line[i0] + (line[i1] - line[i0]) * frac;

        // The bell splits the wave that reaches it: the low end turns round
        // and goes back down the tube, the high end leaves. That split is
        // the whole reason a trumpet is bright outside and dull inside, and
        // it is why what you hear is the part the instrument loses.
        bellState += (arrive - bellState) * bellCoeff;
        // What leaves is what the bell would not send back - though not
        // quite all of it, or the note's own fundamental never reaches the
        // room and the instrument is all harmonics and no pitch. A real one
        // is close to that; a useful one is not.
        radiated = arrive - 0.8f * bellState;
        const float bore = bellState * reflect;
        lastArrive = arrive;

        // The pressure across the lips: the player behind them, the tube in
        // front. This is the quantity the whole instrument is about.
        const float breath = mouth + noise;
        const float delta = breath - bore;

        const float lip = lipB0 * (delta - lipX2) + lipA1 * lipY1 + lipA2 * lipY2;
        lipX2 = lipX1;
        lipX1 = delta;
        lipY2 = lipY1;
        lipY1 = lip;

        // How far the valve is open, squared because a gap closes in two
        // directions at once. Open, the tube gets the player; shut, it gets
        // its own reflection back, and the crossfade between those two is
        // the entire instrument.
        // As the note establishes, the lips settle *closed* and only crack
        // open on the pressure peaks - which is what makes a brass
        // instrument bright when it is loud and round when it is not. So
        // the rest point walks down with how hard the lips are working: at
        // a whisper the valve is a sine, leaned on it is a narrow pulse,
        // and the harmonics arrive with the effort rather than with a knob.
        lipEnv += (std::fabs(lip) - lipEnv) * 0.0006f;
        float open = rest - bite * lipEnv + lip;
        if (open < 0.0f) open = 0.0f;
        else if (open > 1.0f) open = 1.0f;
        const float opening = open * open;
        float in = opening * breath + (1.0f - opening) * bore;

        in = dsp::fastTanh(in);

        // A DC blocker, or the loop fills with the player's own lungs.
        const float hp = in - dcIn + kDcPole * dcOut;
        dcIn = in;
        dcOut = hp;

        line[static_cast<size_t>(write)] = hp * loss;
        write = (write + 1) % size;
        return radiated;
    }

    float lastRadiated() const { return radiated; }
    float lastInside() const { return lastArrive; }
    /** |loop| at the note: over one and it sings, under and it dies. */
    float loopGain() const { return loopMag; }
    float lineDelay() const { return delay; }

  private:
    std::vector<float> line;
    int32_t write = 0;
    float sr = 48000.0f, freq = 220.0f, delay = 434.0f;
    float lipHz = 220.0f, lipGain = 1.0f, lipTension = 1.0f, lipDamp = 0.4f, pressure = 0.5f;
    bool dirty = true;
    float lipA1 = 0.0f, lipA2 = 0.0f, lipB0 = 0.0f;
    float lipX1 = 0.0f, lipX2 = 0.0f, lipY1 = 0.0f, lipY2 = 0.0f;
    float reflect = 0.9f, bellCoeff = 0.3f, bellState = 0.0f;
    float brass = 0.3f, loss = 0.995f, rest = 0.35f, bite = 0.0f, lipEnv = 0.0f;
    float dcIn = 0.0f, dcOut = 0.0f, radiated = 0.0f, loopMag = 0.0f, lastArrive = 0.0f;
};

} // namespace acidulous::machine::brazen
