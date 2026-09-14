#pragma once
#include <cmath>
#include <cstdint>
#include <engine/dsp/Math.h>
#include <vector>

// One woodwind: a mouthpiece, a row of holes, and whatever is left of the
// instrument below them.
//
// Brazen's lips are blown *open* - pressure in the tube pushes them apart,
// and the gain comes from the valve opening in step with the wave. A reed
// is the other way round, and it is not enough to flip a sign: the gain
// comes from somewhere else entirely. The mouthpiece is a *reflection*
// whose strength falls as the pressure across the reed rises, so what the
// tube gets back is `mouth + difference x reflection`, and the slope of
// that product against the returning wave is bigger than one as soon as the
// player blows. Written as a crossfade instead - as Brazen's valve is - a
// reed model cannot oscillate at all, whichever way its sign points.
//
// A flute has no reed. A ribbon of air crosses the mouth hole and takes
// time doing it, and that time is the instrument's second clock.
//
// The part nobody models: **the tube below your fingers**. Every modelled
// woodwind is one delay line set to the pitch, as though the instrument
// stopped where the note does. A real one does not. The first open hole
// reflects the low end and lets the high end straight past into the rest of
// the horn, which is still there, still ringing, and still radiating. That
// is where a woodwind's cutoff comes from, why the same pitch fingered two
// ways is two sounds, and why a forked fingering can set two modes arguing
// and sound a chord.
namespace acidulous::machine::timber {

/**
 * What the loop is asked for, and how far a breath attack may lean past it.
 *
 * 1.9 is what keeps a held note in bounds. An attack is not a held note, so
 * the lift has its own ceiling - the same argument, and the same fault, as
 * the brass: growth per round trip is fixed and a round trip is a period, so
 * without a lift the time to speak is one over the frequency.
 *
 * Lowering the steady target was tried here first, because the reed's clamp
 * is what puts this instrument flat and a gentler loop keeps the reed off
 * its stops. On a bare pipe it works - at F3 the error goes from -18.6 cents
 * to -3.0 at pressure 0.7. On the bank it does nothing at all, because
 * 0.9 + 0.4 p d never reaches even 1.4 at the pressures these patches use,
 * and pulling the slope down instead made some patches better and others
 * worse: every patch has its own embouchure and its own reed, so where the
 * clamp bites is not a function of loop gain alone. Left at 1.9.
 */
constexpr float kWantSlope = 0.4f, kWantMax = 1.9f, kLiftCeiling = 2.6f;
/** What the loop settles at once the note is under way, for sizing the lift. */
constexpr float kSettled = 1.15f;
/** How much of the top the walls take, per round trip. See tune(). */
constexpr float kWallDepth = 0.2f;

using dsp::clampf;

class Pipe {
  public:
    enum Excite : int32_t { Single = 0, Double, Jet };

    void prepare(float sampleRate) {
        sr = sampleRate;
        upper.assign(static_cast<size_t>(sr / 22.0f) + 8, 0.0f);
        lower.assign(static_cast<size_t>(sr / 22.0f) + 8, 0.0f);
        jetLine.assign(1024, 0.0f);
        clear();
    }

    void clear() {
        for (auto &v : upper) v = 0.0f;
        for (auto &v : lower) v = 0.0f;
        for (auto &v : jetLine) v = 0.0f;
        wUpper = wLower = wJet = 0;
        holeLp = holeLp2 = bellLp = inertia = breathLp = ventLp = wallLp = 0.0f;
        dcIn = dcOut = 0.0f;
        radiated = 0.0f;
        // The tube length goes back with the rest of it. tune() rewrites it
        // before step() ever reads it, so nothing depends on this today -
        // but a cleared pipe holding the last note's length is exactly the
        // omission that broke reset_test twice on the brass, and it costs a
        // line to not find out again.
        //
        // Gliding the read toward the solved length, which is what the brass
        // needed, was tried here and does not earn its place: it helps the
        // reeds a little (Bass Clarinet 2.8 to 2.3, Alto Sax 3.8 to 3.0) and
        // hurts the jets more (Flute 3.3 to 4.6), because the jet line is
        // read against this length and a moving one detunes it.
        upperDelay = 0.0f;
        onsetBoost = 1.0f;
        onsetFall = 0.0f;
        // ...and having taken the length back, ask for it again. Without
        // this a cleared pipe given the *same* note is not dirty, and reads
        // its line at a length of nothing. It happened to be saved by the
        // pressure being different at note-on, which is not a reason.
        dirty = true;
    }

    // --- what the player and the instrument are ------------------------------

    void setNote(float hz) {
        const float f = clampf(hz, 20.0f, 5000.0f);
        if (f != freq) { freq = f; dirty = true; }
    }

    /**
     * [cylindrical] closes the tube at the mouthpiece, so it is a quarter
     * of a wavelength long and has only its odd partials - a clarinet,
     * hollow underneath and overblowing a twelfth. A cone is half a
     * wavelength, has every partial and overblows an octave. One sign, and
     * it is the largest single fact about a woodwind.
     * [mode] is which partial the register vent is holding the note on.
     */
    void setShape(bool cylindrical, int32_t mode) {
        const int32_t m = mode < 1 ? 1 : (mode > 3 ? 3 : mode);
        if (cylindrical != cylinder || m != regMode) { cylinder = cylindrical; regMode = m; dirty = true; }
    }

    /** The whole instrument, as the lowest note it can play. */
    void setTube(float lowestHz) {
        const float f = clampf(lowestHz, 20.0f, 4000.0f);
        if (f != lowest) { lowest = f; dirty = true; }
    }

    /**
     * The row of open holes. [hz] is where the lattice stops reflecting and
     * starts radiating - the one number that says clarinet or saxophone
     * louder than any other. [fingering] is how forked the fingering is: a
     * cross fingering closes holes *below* the open one, which drags that
     * cutoff down and veils the note, and is the sound of an instrument
     * playing a chromatic note it does not really have.
     */
    void setLattice(float hz, float fingering, float holes) {
        const float h = clampf(hz, 200.0f, 8000.0f);
        const float f = clampf(fingering, 0.0f, 1.0f);
        const float n = clampf(holes, 0.0f, 1.0f);
        if (h != latticeHz || f != finger || n != holeDepth) {
            latticeHz = h; finger = f; holeDepth = n; dirty = true;
        }
    }

    /**
     * How much of the bore below the holes answers back.
     */
    void setFork(float amount) {
        const float a = clampf(amount, 0.0f, 1.0f);
        if (a != fork) { fork = a; dirty = true; }
    }

    /**
     * How long that bore is, against the length the note implies. At one it
     * is simply what is left of the instrument. Away from one it is a tube
     * the reed cannot reconcile with the one it is playing, and two
     * resonances sharing one reed is what a multiphonic is.
     */
    void setBelow(float scale) {
        const float v = clampf(scale, 0.2f, 4.0f);
        if (v != belowScale) { belowScale = v; dirty = true; }
    }

    /**
     * [kind] single reed, double reed or air jet. [stiffness] is how high
     * the reed's own inertia lets it follow, [embouchure] how hard the lip
     * holds it - which is the rest reflection of the mouthpiece and so the
     * thing the whole loop is built on.
     */
    void setReed(int32_t kind, float stiffness, float embouchure) {
        const int32_t k = kind < 0 ? 0 : (kind > 2 ? 2 : kind);
        const float s = clampf(stiffness, 0.05f, 1.0f);
        const float e = clampf(embouchure, 0.25f, 0.9f);
        if (k != excite || s != reedStiff || e != offset) {
            excite = k; reedStiff = s; offset = e; dirty = true;
        }
    }

    /**
     * The flute's ribbon of air: how long it takes to cross, as a fraction
     * of the note's own period, and how far off the edge it is aimed. A
     * player sets that fraction with their lip and changes it by blowing
     * harder, which is not a metaphor for overblowing - it is the mechanism.
     */
    void setJet(float ratio, float aim) {
        const float l = clampf(ratio, 0.05f, 2.0f);
        const float o = clampf(aim, -0.9f, 0.9f);
        if (l != jetRatio || o != jetAim) { jetRatio = l; jetAim = o; dirty = true; }
    }

    void setBell(float reflection, float cutoff01) {
        const float r = clampf(reflection, 0.3f, 0.995f), c = clampf(cutoff01, 0.02f, 0.95f);
        if (r != bellGain || c != bellCoeff) { bellGain = r; bellCoeff = c; dirty = true; }
    }

    void setPressure(float p) {
        const float v = clampf(p, 0.0f, 2.0f);
        if (std::fabs(v - pressure) > 0.015f) { pressure = v; dirty = true; }
    }

    /** How hard the player leans past the point where it speaks at all. */
    void setDrive(float d) {
        const float v = clampf(d, 0.0f, 2.0f);
        if (v != drive) { drive = v; dirty = true; }
    }

    void setLoss(float amount) { loss = clampf(amount, 0.8f, 1.0f); }

    /** The tongue on the reed. 1 holds it shut, which is what a tongue is
     *  for and what every sampled staccato is a photograph of. */
    void setTongue(float amount) { tongue = clampf(amount, 0.0f, 1.0f); }

    // --- tuning --------------------------------------------------------------

    /**
     * Set both tubes so the loop comes round in exactly one turn at the
     * note, and solve the mouthpiece for the gain it needs to speak.
     *
     * Same argument as Brazen's, with one more term: the bore below the
     * holes feeds its own delayed return back into the junction, which
     * pulls the pitch exactly as a real cross fingering does. So the
     * junction is evaluated as a single complex number with both paths in
     * it, and the upper tube takes whatever phase is left over.
     */
    /**
     * Lean on the note for its first few round trips, then let go.
     *
     * The same arithmetic as the brass: to grow by a factor A in T seconds
     * at frequency f the loop needs ln(A)/(fT) per round trip, and a round
     * trip is a period. Over a fixed *time*, so a low note takes as long to
     * speak as a high one instead of proportionally longer.
     */
    void lift(float seconds = 0.08f) {
        if (!(freq > 0.0f)) return;
        // Not the jet. Its gain solve is steep - g = cos(ph) + sqrt(cos^2(ph)
        // - 1 + t^2) - so a modest lift on the target drives it a long way,
        // and the flute came out five decibels louder, half again as peaky
        // and nineteen cents flat. Solving the tube at the steady gain does
        // not rescue it either, because what moves is the jet's own comb and
        // not the tube. A jet is also the one exciter here that is *started*
        // by the turbulence in the airstream rather than by the loop alone,
        // so it has least need of a lift and most to lose from one.
        if (excite == Jet) return;
        constexpr float kGrowth = 6.9f; // ln(1000): silence to a sounding note
        onsetBoost = clampf(std::exp(kGrowth / (freq * seconds)) / kSettled, 1.0f, 4.0f);
        onsetFall = std::exp(-64.0f / (seconds * sr));
        dirty = true;
    }

    void tune() {
        if (onsetBoost > 1.001f) {
            dirty = true; // the loop is changing under us while the lift lasts
        } else {
            onsetBoost = 1.0f;
        }
        if (!dirty) return;
        dirty = false;

        const float period = sr / freq;
        const float w = 6.28318530718f * freq / sr;

        // The lattice. A forked fingering drags the cutoff down, and that
        // is the whole of why forked notes sound veiled.
        // ...but never below the note it has to reflect. A real instrument's
        // cutoff sits above the range it is asked to play, and one that did
        // not would simply not speak up there.
        const float cut = clampf(latticeHz * (1.0f - finger * 0.7f),
                                 std::max(120.0f, freq * 1.6f), sr * 0.45f);
        holeCoeff = 1.0f - std::exp(-6.28318530718f * cut / sr);
        holeSecond = holeDepth > 0.5f;
        holeReflect = 0.98f - fork * 0.12f;
        throat = 0.2f + fork * 0.75f;

        // How much instrument is still hanging below the hole the note is
        // fingered on. Nobody sets this: it falls out of where the note is
        // and how big the instrument is, which is why the same machine
        // sounds different at the bottom of its range and at the top.
        const float base = cylinder ? 0.5f : 1.0f;
        const float whole = base * (sr / lowest);
        // A vent puts the note on a higher partial of a *longer* tube, and
        // the instrument only has so much tube. Asking for a twelfth from a
        // note near the bottom of the range wants a bore the thing does not
        // have, and the honest answer is the one a player would give: it
        // comes out in the natural register instead. Without this guard the
        // note simply lands between two modes and plays whatever it likes,
        // which measured as a confident and entirely wrong +95 cents.
        int32_t useMode = regMode;
        while (useMode > 1 && base * static_cast<float>(useMode) * period > whole * 1.02f) {
            useMode = cylinder ? (useMode > 3 ? 3 : 1) : useMode - 1;
        }
        sounding = useMode;
        const float nominal = base * static_cast<float>(useMode) * period;

        // The vent itself. Lengthening the tube is only half of a register
        // key: the hole also has to *stop* the partials below the one you
        // want, or the tube simply plays its own fundamental and you have
        // built a longer instrument rather than a higher note. A small hole
        // near the top of the bore is a high-pass on the loop, so that is
        // what this is, cornered between the tube's fundamental and the
        // note being asked for.
        //
        // And when no vent is open, the same high-pass stands in for a
        // fact about the tube: it has no mode below its own lowest note.
        // A cone's loop closes a whole turn at DC - that is what makes its
        // series complete - and the DC blocker's phase lead then closes one
        // again a little way above its corner, where the reed's static gain
        // is still over one. The oboe measured that mode at 40 Hz with a
        // gain of 1.03 at its centre note and 1.06 four semitones up, at
        // which point it wins and the note is not a pitch at all; and every
        // cone in the bank rang it for the first tenth of a second of every
        // note. A high-pass under the instrument's bottom note starves it:
        // the turn now closes where this filter passes half, and the mode
        // reads 0.5 instead of 1.0. Cylinders have half a turn at DC and
        // never had the mode, and lose a few degrees the solve puts back.
        float ventHz = lowest * 0.3f;
        if (useMode > 1) ventHz = std::max(ventHz, (freq / static_cast<float>(useMode)) * 1.5f);
        ventCoeff = 1.0f - std::exp(-6.28318530718f * ventHz / sr);
        float below = (whole - nominal) * (1.0f + finger * 0.4f) * belowScale;
        lowerDelay = clampf(below, 1.0f, static_cast<float>(lower.size() - 3));

        // The reed's own inertia: a plain one-pole, because a resonance
        // sharp enough to be interesting is also sharp enough to win the
        // argument about which mode sounds. Brazen learnt that the hard way.
        reedCoeff = clampf(1.0f - std::exp(-6.28318530718f * (400.0f + reedStiff * 3600.0f) / sr), 0.01f, 0.999f);

        float jr, ji, br, bi, fr, fi;
        junction(w, jr, ji);
        dcBlock(w, br, bi);
        {   // the vent rides with the DC blocker: both are losses in the loop
            float vr, vi;
            ventAt(w, vr, vi);
            const float nr2 = br * vr - bi * vi, ni2 = br * vi + bi * vr;
            br = nr2; bi = ni2;
        }
        // The walls. A real bore loses more of a wave the higher it is -
        // the boundary layer goes as the root of the frequency - and that
        // is what puts a tube's fundamental ahead of its own partials: at
        // a clarinet's bottom note the third mode is four percent lossier
        // than the first. Without it every mode here sat within a few
        // percent of the same gain, the bottom note of the bank's default
        // instrument lost the race to its own third partial, and a bassoon
        // had four modes over unity fighting through its attack.
        //
        // A shelf, not a low-pass: a fifth off above four times the
        // instrument's bottom note, which is the root law to within a
        // percent or two from the third partial to the twentieth. A plain
        // one-pole there was tried first and halved every centroid in the
        // bank, because a partial whose loop gain sits at 0.95 is amplified
        // twenty times by the tube and one at 0.75 four times - the tube's
        // resonance is most of a woodwind's brightness, and a loss that
        // looks small on paper is a loss of that.
        wallCoeff = 1.0f - std::exp(-6.28318530718f * clampf(lowest * 4.0f, 100.0f, sr * 0.45f) / sr);
        {
            float wr, wi;
            wallAt(w, wr, wi);
            const float nr2 = br * wr - bi * wi, ni2 = br * wi + bi * wr;
            br = nr2; bi = ni2;
        }

        // Everything outside the mouthpiece, which is what it has to beat.
        const float outside = std::sqrt((br * br + bi * bi) * (jr * jr + ji * ji)) * loss;
        const float steady = clampf(0.9f + kWantSlope * pressure * drive, 0.0f, kWantMax);
        const float ceiling = onsetBoost > 1.001f ? kLiftCeiling : kWantMax;
        const float want = clampf(steady * onsetBoost, 0.0f, ceiling);
        const float t = want / (outside > 1e-6f ? outside : 1e-6f);
        const float mouth = clampf(pressure, 0.05f, 2.0f);

        if (excite == Jet) {
            // The ribbon of air takes time to cross, and blowing harder
            // shortens it. F = 1 + G e^-jw.tau, so solve for G.
            // Half a period, and blowing harder shortens it. The jet
            // *opposes* what it finds when it lands, so F = 1 - g e^-jw.tau
            // is largest exactly when the crossing takes half a period -
            // which is the real number for a real flute, and puts the note
            // on the peak of the comb while leaving the octave in its
            // trough at 2 - t. Blow harder, the crossing shortens, the comb
            // slides down, and the octave comes up to meet it. That is not
            // a model of overblowing; it is overblowing.
            jetDelay = clampf(jetRatio * period / (0.75f + pressure * 0.35f),
                              1.0f, static_cast<float>(jetLine.size() - 3));
            const float ph = -w * jetDelay;
            const float cp = std::cos(ph), sp = std::sin(ph);
            const float disc = cp * cp - 1.0f + t * t;
            const float g = disc > 0.0f ? clampf(cp + std::sqrt(disc), 0.0f, 40.0f) : 1.0f;
            const float sech = 1.0f / std::cosh(jetAim * 2.0f);
            jetSlope = sech * sech;
            jetGain = clampf(g / (jetSlope > 1e-3f ? jetSlope : 1e-3f), 0.0f, 60.0f);
            jetRest = std::tanh(jetAim);
            fr = 1.0f - g * cp;
            fi = -g * sp;
        } else {
            // The reed table: r = offset + slope . (reed position), and
            // what goes back down the tube is mouth + difference x r. Its
            // slope against the returning wave is
            //     F(w) = offset - slope . mouth . (1 + L(w)),
            // which is over one as soon as the player blows - and that is
            // where a clarinet's gain comes from. Solve |F| = t for slope,
            // taking the negative root because a reed closes as the bore
            // fills and an open one is a saxophone that will not play.
            float lr, li;
            onePole(reedCoeff, std::cos(w), std::sin(w), lr, li);
            const float bre = -mouth * (1.0f + lr), bim = -mouth * li;
            const float bb = bre * bre + bim * bim + 1e-20f;
            const float disc = offset * offset * bre * bre - bb * (offset * offset - t * t);
            slope = disc >= 0.0f ? (-offset * bre - std::sqrt(disc)) / bb : -40.0f;
            // ...but no steeper than the reed can be without sitting in its
            // own clamp before a note has even started. At rest the table
            // reads offset + |slope| x mouth, and if that is already one the
            // mouthpiece is a mirror: the tube reflects perfectly, nothing
            // is added, and the instrument is silent however much gain the
            // arithmetic thinks it has. The reed must have somewhere left
            // to go. The ceiling this puts on the loop is 2 - offset, which
            // is why a tight embouchure chokes a real one too.
            const float steepest = 0.9f * (1.0f - offset) / mouth;
            slope = clampf(slope, -steepest, -0.0005f);
            fr = offset + slope * bre;
            fi = slope * bim;
        }

        float phase = std::atan2(fi, fr) + std::atan2(bi, br) + std::atan2(ji, jr);
        // Whatever is left goes in the tube, at the length nearest the one
        // the instrument would physically be - so the note sits on the mode
        // the register vent chose and not on whichever one the arctangent
        // happened to land in.
        float extra = phase / w - nominal;
        while (extra > period * 0.5f) extra -= period;
        while (extra <= -period * 0.5f) extra += period;
        upperDelay = clampf(nominal + extra, 4.0f, static_cast<float>(upper.size() - 3));
        onsetBoost = 1.0f + (onsetBoost - 1.0f) * onsetFall;
        loopMag = std::sqrt((fr * fr + fi * fi) * (br * br + bi * bi) * (jr * jr + ji * ji)) * loss;
    }

    /**
     * How much gain the loop has at any frequency, with the instrument as
     * it currently stands. Over one and that partial will sound; the
     * loudest one wins, and this is how you find out *which* before
     * spending an afternoon wondering why a clarinet is playing a fifth.
     */
    float loopAt(float hz) const {
        const float w = 6.28318530718f * clampf(hz, 1.0f, sr * 0.49f) / sr;
        float jr, ji, br, bi, fr, fi, vr, vi, wr, wi;
        junction(w, jr, ji);
        dcBlock(w, br, bi);
        ventAt(w, vr, vi);
        wallAt(w, wr, wi);
        mouthpiece(w, fr, fi);
        return std::sqrt((fr * fr + fi * fi) * (br * br + bi * bi) * (jr * jr + ji * ji) *
                         (vr * vr + vi * vi) * (wr * wr + wi * wi)) * loss;
    }

    /**
     * How far round the loop has come at [hz], in turns. A partial can only
     * sound where this is a whole number - the magnitude above decides
     * which of those wins, not which exist.
     */
    float loopTurns(float hz) const {
        const float w = 6.28318530718f * clampf(hz, 1.0f, sr * 0.49f) / sr;
        float jr, ji, br, bi, fr, fi;
        junction(w, jr, ji);
        dcBlock(w, br, bi);
        mouthpiece(w, fr, fi);
        float vr, vi, wr, wi;
        ventAt(w, vr, vi);
        wallAt(w, wr, wi);
        const float phase = std::atan2(fi, fr) + std::atan2(bi, br) + std::atan2(ji, jr) +
                            std::atan2(vi, vr) + std::atan2(wi, wr) - w * upperDelay;
        return phase / 6.28318530718f;
    }

    // --- one sample ----------------------------------------------------------

    /**
     * [mouth] is the pressure the player is making, [noise] the hiss in it.
     * Returns what the instrument puts into the room - the holes and the
     * bell added together, not just the far end, because most of what you
     * hear from a woodwind leaves through the holes.
     */
    float step(float mouth, float noise) {
        const float arrive = read(upper, wUpper, upperDelay);

        // The lattice splits the wave: the low end turns round, the high
        // end goes on down the rest of the instrument.
        holeLp += (arrive - holeLp) * holeCoeff;
        float low = holeLp;
        if (holeSecond) { holeLp2 += (holeLp - holeLp2) * holeCoeff; low = holeLp2; }
        const float past = (arrive - low) * throat;
        const float fromHoles = arrive - 0.8f * low;

        // ...and the rest of the instrument, which is still down there.
        const float belowArrive = read(lower, wLower, lowerDelay);
        bellLp += (belowArrive - bellLp) * bellCoeff;
        const float fromBell = belowArrive - 0.8f * bellLp;
        write(lower, wLower, past);

        // An open hole is a pressure node, so it inverts - which is why a
        // cylinder keeps only its odd partials whichever hole is open. A
        // cone's mode series is complete, and taking the sign off the
        // return is the cheap and honest way to say so.
        const float sign = cylinder ? 1.0f : -1.0f;
        const float bore = sign * (-low * holeReflect - bellLp * bellGain * throat);

        const float breath = mouth + noise;
        float in;
        if (excite == Jet) {
            // The jet is pushed about by the sound in the mouth hole, not
            // by the steady stream behind it. Feed it the breath as well
            // and it sits pinned at the end of its travel before a note has
            // started, which is the same silence the reed table gave.
            //
            // Which leaves the question of how it ever starts, because a
            // loop fed only on its own output stays at nothing forever. A
            // real flute is started by the turbulence in the airstream and
            // by the shove of the breath arriving - so those are what
            // starts this one, and a flute patch with the breath noise
            // turned all the way down is a flute nobody can get a note out
            // of, which is correct.
            breathLp += (breath - breathLp) * 0.002f;
            write(jetLine, wJet, bore + noise + (breath - breathLp) * 0.5f);
            const float late = read(jetLine, wJet, jetDelay);
            in = bore - (dsp::fastTanh(jetGain * late + jetAim) - jetRest);
        } else {
            // mouth + difference x reflection, and the reflection saturates
            // when the reed beats shut against the mouthpiece. That clamp
            // is the whole nonlinearity, and a clarinet is what it sounds
            // like.
            const float pd = bore - breath;
            inertia += (pd - inertia) * reedCoeff;
            // The tongue holds the reed *shut*: the reflection goes to one
            // and the flow to nothing, and the reed's answer to the wave is
            // damped in proportion. Released, the flow steps up by the
            // little it was held back by - which is what a tongued attack
            // is, and it is what starts the note.
            //
            // It used to scale the whole table, rest and all, which *opened*
            // the reed while the tongue was on - a third of the mouth
            // pressure flowing into the tube for twenty milliseconds - and
            // then shut it by a quarter at the release. Every note started
            // from that thump, backwards: measured on the clarinet, fifty
            // cents sharp at thirty milliseconds and gone by sixty; on the
            // bassoon, a pulse circulating the whole bore for a tenth of a
            // second.
            float r = offset - slope * mouth;
            r += (1.0f - r) * tongue + slope * (inertia + mouth) * (1.0f - tongue);
            if (r > 1.0f) r = 1.0f;
            else if (r < -1.0f) r = -1.0f;
            in = breath + pd * r;
        }

        in = dsp::fastTanh(in);
        float hp = in - dcIn + 0.997f * dcOut;
        dcIn = in;
        dcOut = hp;
        if (ventCoeff > 0.0f) {
            ventLp += (hp - ventLp) * ventCoeff;
            hp -= ventLp;
        }
        wallLp += (hp - wallLp) * wallCoeff;
        write(upper, wUpper, (hp - kWallDepth * (hp - wallLp)) * loss);

        radiated = fromHoles * 0.7f + fromBell * 0.5f;
        return radiated;
    }

    float lastRadiated() const { return radiated; }
    float loopGain() const { return loopMag; }
    float upperLength() const { return upperDelay; }
    /** Which partial the vent actually managed to put the note on. */
    int32_t soundingMode() const { return sounding; }
    float lowerLength() const { return lowerDelay; }

  private:
    static void onePole(float c, float cw, float sw, float &re, float &im) {
        // c / (1 - (1-c) z^-1) on the unit circle.
        const float dr = 1.0f - (1.0f - c) * cw, di = (1.0f - c) * sw;
        const float den = dr * dr + di * di + 1e-20f;
        re = c * dr / den;
        im = -c * di / den;
    }

    /** The whole tube, as one complex number: the holes and everything
     *  below them, seen from the mouthpiece. */
    void junction(float w, float &re, float &im) const {
        const float c1 = std::cos(w), s1 = std::sin(w);
        float lr, li;
        onePole(holeCoeff, c1, s1, lr, li);
        if (holeSecond) {
            const float ar = lr, ai = li;
            lr = ar * ar - ai * ai;
            li = 2.0f * ar * ai;
        }
        re = -lr * holeReflect;
        im = -li * holeReflect;
        // What went past the holes, down the horn, off the bell and back.
        float bre, bim;
        onePole(bellCoeff, c1, s1, bre, bim);
        const float tr = (1.0f - lr) * throat, ti = -li * throat;
        const float ph = -w * lowerDelay;
        const float er = std::cos(ph), ei = std::sin(ph);
        const float ar = tr * er - ti * ei, ai = tr * ei + ti * er;
        const float cr = ar * bre - ai * bim, ci = ar * bim + ai * bre;
        const float g = -bellGain * throat;
        re += g * cr;
        im += g * ci;
        const float sign = cylinder ? 1.0f : -1.0f;
        re *= sign;
        im *= sign;
        // Note what is *not* here: the upper tube's own delay. tune() calls
        // this to find out how much phase is left for that tube, so putting
        // it in would be counting it twice - and the pipe would solve for a
        // length it had already spent. It cost an afternoon once.
    }

    /** The walls' loss, as the loop sees it: 1 - depth x (1 - onepole). */
    void wallAt(float w, float &re, float &im) const {
        float lr, li;
        onePole(wallCoeff, std::cos(w), std::sin(w), lr, li);
        re = 1.0f - kWallDepth * (1.0f - lr);
        im = kWallDepth * li;
    }

    /** The register vent: 1 - onepole, or nothing at all when it is shut. */
    void ventAt(float w, float &re, float &im) const {
        if (ventCoeff <= 0.0f) { re = 1.0f; im = 0.0f; return; }
        float lr, li;
        onePole(ventCoeff, std::cos(w), std::sin(w), lr, li);
        re = 1.0f - lr;
        im = -li;
    }

    void dcBlock(float w, float &re, float &im) const {
        const float c1 = std::cos(w), s1 = std::sin(w);
        const float nr = 1.0f - c1, ni = s1;
        const float dr = 1.0f - 0.997f * c1, di = 0.997f * s1;
        const float den = dr * dr + di * di + 1e-20f;
        re = (nr * dr + ni * di) / den;
        im = (ni * dr - nr * di) / den;
    }

    void mouthpiece(float w, float &re, float &im) const {
        if (excite == Jet) {
            const float ph = -w * jetDelay;
            const float g = jetGain * jetSlope;
            re = 1.0f - g * std::cos(ph);
            im = -g * std::sin(ph);
        } else {
            float lr, li;
            onePole(reedCoeff, std::cos(w), std::sin(w), lr, li);
            const float mouth = clampf(pressure, 0.05f, 2.0f);
            re = offset + slope * (-mouth * (1.0f + lr));
            im = slope * (-mouth * li);
        }
    }

    static float read(const std::vector<float> &line, int32_t w, float delay) {
        const int32_t size = static_cast<int32_t>(line.size());
        const int32_t at = static_cast<int32_t>(delay);
        const float frac = delay - static_cast<float>(at);
        const int32_t i0 = (w - at + size + size) % size;
        const int32_t i1 = i0 == 0 ? size - 1 : i0 - 1;
        return line[static_cast<size_t>(i0)] +
               (line[static_cast<size_t>(i1)] - line[static_cast<size_t>(i0)]) * frac;
    }
    static void write(std::vector<float> &line, int32_t &w, float v) {
        line[static_cast<size_t>(w)] = v;
        w = (w + 1) % static_cast<int32_t>(line.size());
    }

    std::vector<float> upper, lower, jetLine;
    int32_t wUpper = 0, wLower = 0, wJet = 0;

    float sr = 48000.0f, freq = 220.0f, lowest = 146.8f;
    bool cylinder = true, holeSecond = false, dirty = true;
    int32_t regMode = 1, excite = Single;

    float upperDelay = 100.0f, lowerDelay = 1.0f;
    int32_t sounding = 1;
    float latticeHz = 1500.0f, finger = 0.0f, holeDepth = 0.4f;
    float holeCoeff = 0.2f, holeReflect = 0.98f, throat = 0.2f, fork = 0.0f, belowScale = 1.0f;
    float bellGain = 0.9f, bellCoeff = 0.4f;

    float reedStiff = 0.5f, offset = 0.7f, slope = -1.0f, reedCoeff = 0.2f, inertia = 0.0f;
    float jetRatio = 0.5f, jetAim = 0.0f, jetDelay = 48.0f, jetGain = 1.0f, jetSlope = 1.0f, jetRest = 0.0f;

    float pressure = 0.5f, drive = 1.0f, loss = 0.999f, tongue = 0.0f;
    float holeLp = 0.0f, holeLp2 = 0.0f, bellLp = 0.0f, breathLp = 0.0f;
    float ventCoeff = 0.0f, ventLp = 0.0f;
    float wallCoeff = 1.0f, wallLp = 0.0f;
    float dcIn = 0.0f, dcOut = 0.0f;
    float radiated = 0.0f, loopMag = 0.0f;
    /** The attack's lift and how fast it lets go. See lift(). */
    float onsetBoost = 1.0f, onsetFall = 0.0f;
};

} // namespace acidulous::machine::timber
