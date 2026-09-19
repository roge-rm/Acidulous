#pragma once
#include <cstdint>
#include <string>
#include <vector>

// A sung take, with its pitch found: what Molt reads.
//
// A Take next door is the same audio asked a different question - where are
// the transients, so a grain or a slice can land on one. This asks where the
// *glottal pulses* are, which is what lets a voice be moved in pitch without
// being moved in size.
//
// The whole of it is built on a worker and never mutated afterwards, exactly
// as a Take is: the audio thread only ever reads, and a new one arrives by
// being swapped in.
namespace acidulous::audio {

/**
 * One pitch mark.
 *
 * [at] is where a period begins, [period] how long it is in frames, and
 * [voiced] whether this part of the take has a pitch at all. Consonants do
 * not, and pitching them is what makes a cheap shifter sound cheap, so they
 * carry marks too - laid at a fixed rate - and are copied through rather than
 * stretched onto a note.
 */
struct Epoch {
    int32_t at = 0;
    float period = 0.0f;
    bool voiced = false;
};

/**
 * Take everything under [hz] out of [x], in place: one-pole high-passes in
 * series, [poles] of them.
 *
 * Here rather than in the tracker that first needed it, because the take
 * itself wants the same treatment and a rumble filter written twice is two
 * places for it to be wrong.
 */
void removeRumble(std::vector<float> &x, float sampleRate, float hz, int poles);

struct Utterance {
    /** Mono, because a voice is. Filled by the caller, then analysed. */
    std::vector<float> mono;
    int32_t frames = 0;
    std::vector<Epoch> epochs;
    /**
     * What the take sings when it is left alone: the median of its voiced
     * pitch. It is the root note of the whole machine - a take is played at
     * its own pitch by the note nearest this, exactly as a sampler is played
     * at its own speed by the note its sample was recorded at.
     */
    float rootHz = 0.0f;
    std::string name;

    /**
     * Find the pitch marks - and clean the take first.
     *
     * The rumble comes out of `mono` itself, not just out of the copy the
     * tracker correlates. Nothing under `PitchTrack::kMinHz` can be a pitch
     * this machine reports, so by its own definition nothing under it is the
     * voice; what it is instead is a hand holding a phone, and a take can be
     * more than half that. Measured on a real recording: fifty-six per cent
     * of its energy under seventy hertz, and its loudest partial at fifty-nine
     * - below the note it was asked to sing.
     *
     * It has to be `mono` because `mono` is what everything downstream reads.
     * The marks are snapped to the largest sample within a quarter period, so
     * on a rumbling take they snap to the rumble's peak rather than the
     * glottal pulse and the grains are cut at arbitrary phase; and the grains
     * are cut from `mono` too, so each one carries a pedestal that the
     * overlap-add lays down again at a spacing it never had.
     *
     * Worker thread; allocates; takes a few hundred milliseconds for a ten
     * second take.
     */
    void analyse(float sampleRate);

    bool usable() const { return frames > 1 && !epochs.empty(); }

    /**
     * The index of the last epoch at or before [pos]. Binary search, because
     * the audio thread asks this for every grain it lays down.
     */
    int32_t epochAt(float pos) const;
};

/**
 * How the pitch track is found, kept separate so the harness can drive it
 * on its own: autocorrelation at a decimated rate.
 *
 * Voice f0 lives between about 70 and 800 Hz, so eight kHz is plenty to find
 * it in and is six times less work than forty-eight. The decimation is a
 * six-tap average, which is a poor low-pass and an ample one for this: what
 * it lets through above 4 kHz cannot be mistaken for a pitch that low.
 */
struct PitchTrack {
    static constexpr float kMinHz = 70.0f;
    static constexpr float kMaxHz = 800.0f;
    static constexpr float kHopMs = 10.0f;
    static constexpr float kWindowMs = 40.0f;
    /**
     * How periodic a window has to be to be called voiced. Normalised
     * autocorrelation at the winning lag: a held vowel is above 0.8, a
     * fricative below 0.2, and this sits where neither is in doubt.
     */
    static constexpr float kVoiced = 0.35f;
    /**
     * And how periodic it has to *stay*, once it is.
     *
     * One threshold makes a vowel flap in and out wherever it sits near it,
     * and a mark that changes its mind about being voiced changes the grain's
     * length and its spacing with it. A real take flapped twelve times a
     * second at one threshold. Hysteresis is the usual answer and this is the
     * usual shape of it.
     */
    static constexpr float kVoicedHold = 0.25f;

    std::vector<float> hz;    // per hop, 0 where unvoiced
    std::vector<float> clarity; // the winning correlation, for the harness
    float hopFrames = 0.0f;   // at the *original* rate

    /**
     * [cleaned] says the caller has already taken the rumble out, so this
     * does not do it again. It matters: the filter is four poles at kMinHz
     * and applying it twice is eight, which on a speaker whose fundamental
     * dips under eighty hertz removes the fundamental along with the room.
     * Measured on a real take, doubling it cost sixteen points of voiced
     * share and thirteen of clarity.
     */
    void find(const std::vector<float> &mono, int32_t frames, float sampleRate,
              bool cleaned = false);

    /** The period in frames at [pos], interpolated between hops. 0 if unvoiced. */
    float periodAt(float pos, float sampleRate) const;
};

} // namespace acidulous::audio
