#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include <engine/core/Sample.h>
#include <engine/core/SampleMap.h>
#include <engine/core/Take.h>
#include <engine/core/Utterance.h>

// The material six machines need before they make any sound at all.
//
// Forage wants thirteen samples, Mosaic a zone map, Dice a loop, Molt a sung
// take, Pollen either a file or the live ring, and Cipher wants something on
// the input bus or it is a vocoder with nothing to vocode. The app ships no
// samples and never should - users bring their own - so the harness makes its
// own, from oscillators and noise, deterministically from a fixed seed.
//
// That is molt_test.cpp's answer, which is why `vowel`, `noise` and
// `resonate` live here now and molt_test includes them: a source whose pitch
// and formants are known exactly beats a recording somebody has to make.
//
// None of this is ever compiled into the app.

namespace acidulous::audition {

constexpr float kMatSr = 48000.0f;

/** A deterministic noise source. Seeded per caller so nothing depends on order. */
class Rng {
  public:
    explicit Rng(uint32_t seed = 0x1234567u) : state(seed) {}
    float next() {
        state = state * 1664525u + 1013904223u;
        return static_cast<float>(state >> 8) * (1.0f / 8388608.0f) - 1.0f;
    }

  private:
    uint32_t state;
};

/** A two-pole resonator, which is all a formant is. */
inline void resonate(const std::vector<float> &in, std::vector<float> &out, float hz, float q, float gain,
                     float sr = kMatSr) {
    const float w = 2.0f * static_cast<float>(M_PI) * hz / sr;
    const float r = std::exp(-w / (2.0f * q));
    const float a1 = 2.0f * r * std::cos(w), a2 = -r * r;
    float y1 = 0.0f, y2 = 0.0f;
    for (size_t i = 0; i < in.size(); ++i) {
        const float y = in[i] + a1 * y1 + a2 * y2;
        y2 = y1;
        y1 = y;
        out[i] += gain * y;
    }
}

/** A sung vowel: pulses at [f0], shaped by two formants. */
inline std::vector<float> vowel(float f0, float seconds, float f1 = 700.0f, float f2 = 1220.0f) {
    const int32_t n = static_cast<int32_t>(kMatSr * seconds);
    std::vector<float> pulses(static_cast<size_t>(n), 0.0f);
    const float period = kMatSr / f0;
    for (float p = 0.0f; p < static_cast<float>(n); p += period) {
        pulses[static_cast<size_t>(p)] = 1.0f;
    }
    std::vector<float> out(static_cast<size_t>(n), 0.0f);
    resonate(pulses, out, f1, 12.0f, 1.0f);
    resonate(pulses, out, f2, 12.0f, 0.5f);
    float peak = 1e-9f;
    for (float v : out) peak = std::max(peak, std::abs(v));
    for (float &v : out) v *= 0.7f / peak;
    return out;
}

inline std::vector<float> noise(float seconds, float level = 0.5f, uint32_t seed = 0x1234567u) {
    const int32_t n = static_cast<int32_t>(kMatSr * seconds);
    std::vector<float> out(static_cast<size_t>(n), 0.0f);
    Rng rng(seed);
    for (auto &v : out) v = rng.next() * level;
    return out;
}

/**
 * A phrase, not a note: "ah", a fricative, then "ee".
 *
 * Cipher cannot be judged on a steady vowel. Its band map is the instrument,
 * and a map only shows what it does when the thing going through it moves -
 * a sustained "ah" through any map at all sounds like an "ah".
 */
inline std::vector<float> voicePhrase() {
    std::vector<float> out;
    const std::vector<float> ah = vowel(140.0f, 1.1f, 730.0f, 1090.0f);
    const std::vector<float> ss = noise(0.35f, 0.25f, 0x51ced5u);
    const std::vector<float> ee = vowel(165.0f, 1.1f, 270.0f, 2290.0f);
    out.insert(out.end(), ah.begin(), ah.end());
    out.insert(out.end(), ss.begin(), ss.end());
    out.insert(out.end(), ee.begin(), ee.end());
    // Ten milliseconds of fade at each join, or the steps are the loudest
    // transients in the file and every onset detector finds them first.
    const size_t fade = static_cast<size_t>(kMatSr * 0.01f);
    for (size_t i = 0; i < fade && i < out.size(); ++i) {
        const float g = static_cast<float>(i) / static_cast<float>(fade);
        out[i] *= g;
        out[out.size() - 1 - i] *= g;
    }
    return out;
}

/**
 * Twelve seconds of synthetic speech, for the vocoder.
 *
 * `voicePhrase` above is two vowels and a fricative, two and a half seconds
 * long, at a fixed pitch. It is enough to prove Cipher's bands are wired to
 * the right places and no use at all for voicing a bank: a map only shows what
 * it does when what goes through it moves, and a vocoder is only audible while
 * its carrier sounds, so the material has to keep going for as long as the
 * phrase holds.
 *
 * Built to the measurements taken from a real recording rather than to a guess
 * about what a voice is like, because the guess was wrong in a way that
 * mattered. That recording's median fundamental was 125 Hz with a quarter of
 * its voiced frames below 80 - low enough to sing underneath a vocoder bank
 * that started at 110 Hz, which is the fault that found Cipher's `low`
 * default. A synthetic modulator pitched at a comfortable 200 Hz would have
 * hidden it again, so this one is deliberately low and deliberately varied:
 * the pitch walks between 70 and 190 Hz across the utterance.
 *
 * Nine syllables over twelve seconds, each a vowel with its own two formants,
 * separated by fricatives and by silence - about a tenth of the whole, which
 * is what the real recording measured. Each syllable's pitch glides, because a
 * flat pitch is the one thing no speaker does and the thing a pitch tracker
 * most needs to see.
 */
inline std::vector<float> speechPhrase() {
    struct Syllable {
        float f0Start, f0End;  // Hz, gliding across the syllable
        float f1, f2;          // the vowel
        float seconds;
        float fricative;       // seconds of noise after it, 0 for none
        float silence;         // seconds of pause after that
    };
    // The vowels are the usual measured formant pairs: "ah" 730/1090,
    // "ee" 270/2290, "oo" 300/870, "eh" 530/1840, "aw" 570/840.
    static const Syllable kLine[] = {
        {110.0f,  95.0f, 730.0f, 1090.0f, 0.75f, 0.18f, 0.10f}, // ah-s
        { 98.0f, 130.0f, 530.0f, 1840.0f, 0.60f, 0.00f, 0.00f}, // eh
        {132.0f, 118.0f, 270.0f, 2290.0f, 0.70f, 0.22f, 0.35f}, // ee-sh .
        { 88.0f,  74.0f, 300.0f,  870.0f, 0.95f, 0.00f, 0.12f}, // oo
        {120.0f, 155.0f, 570.0f,  840.0f, 0.65f, 0.15f, 0.00f}, // aw-f
        {160.0f, 128.0f, 730.0f, 1090.0f, 0.80f, 0.00f, 0.30f}, // ah .
        { 92.0f, 112.0f, 270.0f, 2290.0f, 0.70f, 0.20f, 0.10f}, // ee-s
        {135.0f, 188.0f, 530.0f, 1840.0f, 0.90f, 0.00f, 0.00f}, // eh
        {150.0f,  78.0f, 300.0f,  870.0f, 1.10f, 0.00f, 0.40f}, // oo, falling away
    };

    std::vector<float> out;
    Rng rng(0xc1fe42u);
    for (const Syllable &sy : kLine) {
        // A glottal pulse train whose period changes as it goes. Written here
        // rather than reusing `vowel`, which holds one pitch for its whole
        // length - and a held pitch is the thing `track` cannot be judged on.
        const auto n = static_cast<int32_t>(kMatSr * sy.seconds);
        std::vector<float> pulses(static_cast<size_t>(n), 0.0f);
        for (float pos = 0.0f; pos < static_cast<float>(n);) {
            pulses[static_cast<size_t>(pos)] = 1.0f;
            const float t = pos / static_cast<float>(n);
            const float f0 = sy.f0Start + (sy.f0End - sy.f0Start) * t;
            pos += kMatSr / f0;
        }
        std::vector<float> body(static_cast<size_t>(n), 0.0f);
        resonate(pulses, body, sy.f1, 12.0f, 1.0f);
        resonate(pulses, body, sy.f2, 12.0f, 0.5f);
        // A third formant, quiet and high, so there is something for the top
        // of the bank to measure that is not the consonant path.
        resonate(pulses, body, 2900.0f, 10.0f, 0.15f);
        float peak = 1e-9f;
        for (float v : body) peak = std::max(peak, std::fabs(v));
        for (float &v : body) v *= 0.7f / peak;
        // Ten milliseconds on and forty off, so a syllable arrives with an
        // edge and leaves without one.
        const auto in = static_cast<size_t>(kMatSr * 0.01f);
        const auto off = static_cast<size_t>(kMatSr * 0.04f);
        for (size_t i = 0; i < in && i < body.size(); ++i) {
            body[i] *= static_cast<float>(i) / static_cast<float>(in);
        }
        for (size_t i = 0; i < off && i < body.size(); ++i) {
            body[body.size() - 1 - i] *= static_cast<float>(i) / static_cast<float>(off);
        }
        out.insert(out.end(), body.begin(), body.end());

        if (sy.fricative > 0.0f) {
            const auto fn = static_cast<int32_t>(kMatSr * sy.fricative);
            // Shaped, not flat: an "s" is a band around four kilohertz, and
            // flat noise here would be the same mistake the machine's own
            // sibilance path was making.
            float z1 = 0.0f, z2 = 0.0f;
            for (int32_t i = 0; i < fn; ++i) {
                const float w = rng.next();
                z1 += (w - z1) * 0.55f;      // a gentle top-end tilt
                z2 += (z1 - z2) * 0.55f;
                const float t = static_cast<float>(i) / static_cast<float>(fn);
                const float env = std::sin(t * 3.14159265f);
                out.push_back((w - z2) * 0.30f * env);
            }
        }
        if (sy.silence > 0.0f) {
            out.insert(out.end(), static_cast<size_t>(kMatSr * sy.silence), 0.0f);
        }
    }
    // Normalised to -20 dBFS rms, the same nominal recording level a file on
    // the input bus is held to. A vocoder's output follows its input, so the
    // machine's house level only means anything if every modulator arrives at
    // the same size.
    double sum = 0.0;
    for (float v : out) sum += static_cast<double>(v) * v;
    const auto rms = static_cast<float>(std::sqrt(sum / std::max<size_t>(1, out.size())));
    if (rms > 1e-6f) {
        const float gain = 0.1f / rms;
        for (float &v : out) v *= gain;
    }
    return out;
}

// --- A thirteen-piece kit ----------------------------------------------------
//
// In Hexbeat's voice order, because that is the order Forage's pads are laid
// out in and the order the drum grid draws. Each piece is given a distinctly
// different spectrum and length on purpose: a pad envelope or filter that is
// doing nothing should be audible as doing nothing, and it will not be if
// every sample underneath is the same click.

enum class Piece {
    Kick, Rim, Snare, Clap, TomLo, TomMid, TomHi, HatClosed, HatOpen, Cymbal, Ride, Cowbell, Clave, Count
};

inline const char *pieceName(Piece p) {
    switch (p) {
    case Piece::Kick: return "kick";
    case Piece::Rim: return "rim";
    case Piece::Snare: return "snare";
    case Piece::Clap: return "clap";
    case Piece::TomLo: return "tom lo";
    case Piece::TomMid: return "tom mid";
    case Piece::TomHi: return "tom hi";
    case Piece::HatClosed: return "hat closed";
    case Piece::HatOpen: return "hat open";
    case Piece::Cymbal: return "cymbal";
    case Piece::Ride: return "ride";
    case Piece::Cowbell: return "cowbell";
    default: return "clave";
    }
}

/** One drum, mono, peak-normalised to 0.9. */
inline std::vector<float> drum(Piece piece) {
    auto env = [](float t, float decay) { return std::exp(-t / decay); };
    std::vector<float> out;
    Rng rng(0xd2u + static_cast<uint32_t>(piece) * 7919u);

    auto make = [&](float seconds, auto sample) {
        const int32_t n = static_cast<int32_t>(kMatSr * seconds);
        out.assign(static_cast<size_t>(n), 0.0f);
        for (int32_t i = 0; i < n; ++i) {
            out[static_cast<size_t>(i)] = sample(static_cast<float>(i) / kMatSr);
        }
    };
    // Band-passed noise: two resonators is enough to place a hat or a cymbal.
    auto metallic = [&](float seconds, float hz, float q, float decay) {
        const int32_t n = static_cast<int32_t>(kMatSr * seconds);
        std::vector<float> src(static_cast<size_t>(n), 0.0f);
        for (int32_t i = 0; i < n; ++i) {
            src[static_cast<size_t>(i)] = rng.next() * env(static_cast<float>(i) / kMatSr, decay);
        }
        out.assign(static_cast<size_t>(n), 0.0f);
        resonate(src, out, hz, q, 1.0f);
        resonate(src, out, hz * 1.61f, q, 0.6f);
    };

    switch (piece) {
    case Piece::Kick:
        make(0.55f, [&](float t) {
            const float hz = 52.0f + 130.0f * env(t, 0.020f); // the sweep is the kick
            return std::sin(2.0f * static_cast<float>(M_PI) * hz * t) * env(t, 0.16f) +
                   rng.next() * env(t, 0.003f) * 0.4f;        // and the click is the beater
        });
        break;
    case Piece::Rim:
        make(0.09f, [&](float t) {
            return (std::sin(2.0f * static_cast<float>(M_PI) * 1720.0f * t) +
                    0.7f * std::sin(2.0f * static_cast<float>(M_PI) * 2630.0f * t)) * env(t, 0.012f);
        });
        break;
    case Piece::Snare:
        make(0.28f, [&](float t) {
            const float tone = std::sin(2.0f * static_cast<float>(M_PI) * 185.0f * t) +
                               0.8f * std::sin(2.0f * static_cast<float>(M_PI) * 278.0f * t);
            return tone * env(t, 0.05f) * 0.6f + rng.next() * env(t, 0.11f);
        });
        break;
    case Piece::Clap:
        make(0.35f, [&](float t) {
            // Four bursts and a tail - a clap is several hands, slightly apart.
            float g = 0.0f;
            for (float d : {0.0f, 0.011f, 0.022f, 0.033f}) {
                if (t >= d) g = std::max(g, env(t - d, 0.008f));
            }
            if (t > 0.033f) g = std::max(g, 0.45f * env(t - 0.033f, 0.13f));
            return rng.next() * g;
        });
        break;
    case Piece::TomLo:
    case Piece::TomMid:
    case Piece::TomHi: {
        const float base = piece == Piece::TomLo ? 95.0f : (piece == Piece::TomMid ? 145.0f : 215.0f);
        make(0.42f, [&](float t) {
            const float hz = base * (1.0f + 0.45f * env(t, 0.045f));
            return std::sin(2.0f * static_cast<float>(M_PI) * hz * t) * env(t, 0.14f) +
                   rng.next() * env(t, 0.010f) * 0.18f;
        });
        break;
    }
    case Piece::HatClosed: metallic(0.075f, 7400.0f, 2.2f, 0.016f); break;
    case Piece::HatOpen: metallic(0.42f, 7100.0f, 2.4f, 0.15f); break;
    case Piece::Cymbal: metallic(1.30f, 4600.0f, 1.5f, 0.55f); break;
    case Piece::Ride: metallic(0.90f, 5600.0f, 3.0f, 0.34f); break;
    case Piece::Cowbell:
        make(0.30f, [&](float t) {
            return (std::sin(2.0f * static_cast<float>(M_PI) * 587.0f * t) +
                    std::sin(2.0f * static_cast<float>(M_PI) * 845.0f * t)) * env(t, 0.10f);
        });
        break;
    default: // Clave
        make(0.12f, [&](float t) {
            return std::sin(2.0f * static_cast<float>(M_PI) * 2400.0f * t) * env(t, 0.018f);
        });
        break;
    }

    float peak = 1e-9f;
    for (float v : out) peak = std::max(peak, std::abs(v));
    for (float &v : out) v *= 0.9f / peak;
    return out;
}

inline std::unique_ptr<SampleData> pieceSample(Piece piece) {
    auto s = std::make_unique<SampleData>();
    s->name = pieceName(piece);
    s->left = drum(piece);
    s->frames = static_cast<int32_t>(s->left.size());
    s->stereo = false;
    s->rate = static_cast<int32_t>(kMatSr);
    return s;
}

/**
 * Two bars at 120 bpm of the kit above, as a Take with its onsets found.
 *
 * Dice slices this and Pollen reads it. Real onsets rather than a grid, so
 * the detector has something to detect and slicing is being tested rather
 * than the fallback that divides evenly.
 */
inline std::unique_ptr<audio::Take> breakLoop(float bpm = 120.0f) {
    const float beat = 60.0f / bpm;
    const int32_t frames = static_cast<int32_t>(kMatSr * beat * 8.0f); // two bars of four
    auto take = std::make_unique<audio::Take>();
    take->name = "break";
    take->frames = frames;
    take->left.assign(static_cast<size_t>(frames), 0.0f);
    take->right.assign(static_cast<size_t>(frames), 0.0f);

    struct Hit { Piece piece; float sixteenth; float gain; };
    static const Hit kPattern[] = {
        {Piece::Kick, 0, 1.0f},   {Piece::HatClosed, 2, 0.5f}, {Piece::Snare, 4, 0.9f},
        {Piece::HatClosed, 6, 0.5f}, {Piece::Kick, 8, 0.8f},   {Piece::Kick, 10, 0.6f},
        {Piece::Snare, 12, 0.9f}, {Piece::HatOpen, 14, 0.55f},
        {Piece::Kick, 16, 1.0f},  {Piece::HatClosed, 18, 0.5f}, {Piece::Snare, 20, 0.9f},
        {Piece::Clap, 20, 0.5f},  {Piece::HatClosed, 22, 0.5f}, {Piece::Kick, 24, 0.8f},
        {Piece::TomMid, 26, 0.6f}, {Piece::Snare, 28, 0.9f},   {Piece::Cymbal, 30, 0.45f},
    };
    for (const Hit &h : kPattern) {
        const std::vector<float> s = drum(h.piece);
        const auto at = static_cast<size_t>(kMatSr * beat * h.sixteenth / 4.0f);
        for (size_t i = 0; i < s.size() && at + i < static_cast<size_t>(frames); ++i) {
            take->left[at + i] += s[i] * h.gain;
            take->right[at + i] += s[i] * h.gain;
        }
    }
    float peak = 1e-9f;
    for (float v : take->left) peak = std::max(peak, std::abs(v));
    for (int32_t i = 0; i < frames; ++i) {
        take->left[static_cast<size_t>(i)] *= 0.85f / peak;
        take->right[static_cast<size_t>(i)] = take->left[static_cast<size_t>(i)];
    }
    take->detect(kMatSr);
    return take;
}

/**
 * Eight seconds of music, for the machines that granulate a buffer.
 *
 * A drum break is the right seed for a slicer and the wrong one for almost
 * everything else: it proves onset snap and says nothing at all about what a
 * cloud, a pitch spray or a pollination sounds like, which between them are
 * two thirds of Pollen's bank. Dan asked for something musical, and this is
 * it - four chords in C Dorian, two seconds each, the mode the `scale`
 * patches are set to so a cloud quantised to it agrees with the source.
 *
 * Each chord is plucked: a short filtered-noise attack so `detect` has a real
 * onset to find, then a bass root and a triad above it, every note a small
 * harmonic stack whose upper partials die first. The notes are struck a few
 * milliseconds apart, the way a hand does, which gives the attack width; and
 * left and right take slightly different detunings, so the seed has a little
 * of its own stereo before any grain is panned.
 *
 * Cm - F - Bb - Gm, which is Dorian's own progression: the major fourth is
 * what makes it Dorian rather than minor, and it is the chord change worth
 * hearing a scan travel through.
 */
inline std::unique_ptr<audio::Take> musicSeed() {
    auto take = std::make_unique<audio::Take>();
    take->name = "seed";
    const float chordSeconds = 2.0f;
    // Two seconds of tail past the last chord, so the buffer's own end is
    // silence. A granular read wraps round it, and a buffer that stops while
    // it is still sounding puts a cliff in the middle of the cloud.
    const float tailSeconds = 2.0f;
    const int32_t frames = static_cast<int32_t>(kMatSr * (chordSeconds * 4.0f + tailSeconds));
    take->frames = frames;
    take->left.assign(static_cast<size_t>(frames), 0.0f);
    take->right.assign(static_cast<size_t>(frames), 0.0f);

    // Cm, F, Bb, Gm - bass root, then the triad. Hertz, so the tuning is
    // stated rather than computed from a table nobody can check.
    struct Chord { float note[4]; };
    static const Chord kChords[] = {
        {{ 65.41f, 130.81f, 155.56f, 196.00f }},   // Cm  : C2  C3  Eb3 G3
        {{ 87.31f, 174.61f, 220.00f, 261.63f }},   // F   : F2  F3  A3  C4
        {{116.54f, 233.08f, 293.66f, 349.23f }},   // Bb  : Bb2 Bb3 D4  F4
        {{ 98.00f, 196.00f, 233.08f, 293.66f }},   // Gm  : G2  G3  Bb3 D4
    };
    Rng rng(0x9e10c7u);
    for (int c = 0; c < 4; ++c) {
        const auto chordAt = static_cast<size_t>(kMatSr * chordSeconds * static_cast<float>(c));
        for (int n = 0; n < 4; ++n) {
            const float hz = kChords[c].note[n];
            // A hand does not strike four notes at the same instant.
            const auto at = chordAt + static_cast<size_t>(kMatSr * 0.006f * static_cast<float>(n));
            const int partials = n == 0 ? 10 : 7;          // the bass is richer
            const float amp = n == 0 ? 0.5f : 0.34f;
            for (int ch = 0; ch < 2; ++ch) {
                std::vector<float> &out = ch == 0 ? take->left : take->right;
                const float det = ch == 0 ? 1.0f : 1.0006f;  // a little width
                float lp = 0.0f;
                for (size_t i = 0; at + i < static_cast<size_t>(frames); ++i) {
                    const float t = static_cast<float>(i) / kMatSr;
                    // No cutting the note short: it used to stop at 2.6 s,
                    // where its fundamental is still at 18% - sixteen hard
                    // steps buried in the buffer, and a click from every
                    // grain that read across one. Dan heard them as
                    // "scratching sort of noises throughout", in every patch.
                    // The exponential is what ends a note here.
                    float v = 0.0f;
                    for (int h = 1; h <= partials; ++h) {
                        // The top of a plucked note goes first.
                        const float tau = 1.5f / std::sqrt(static_cast<float>(h));
                        v += std::sin(2.0f * static_cast<float>(M_PI) * hz * det * static_cast<float>(h) * t) *
                             std::exp(-t / tau) / static_cast<float>(h);
                    }
                    // The pluck: brief, filtered, and only on the attack.
                    const float hit = std::exp(-t / 0.004f);
                    lp += (rng.next() - lp) * 0.25f;
                    v += lp * hit * 0.8f;
                    out[at + i] += v * amp;
                }
            }
        }
    }
    float peak = 1e-9f;
    for (float v : take->left) peak = std::max(peak, std::abs(v));
    for (float v : take->right) peak = std::max(peak, std::abs(v));
    // Silence at both edges, so wrapping from the end to the start is a join
    // between two zeros rather than a step.
    // A long way in, not ten milliseconds.
    //
    // `position` defaults to the start of the buffer, so every note of every
    // patch that does not move it begins by granulating whatever is at sample
    // zero - and in a musical phrase that is a pluck. Dan: "a little pop to
    // the start of every or almost every sample". A buffer that is read from
    // its own beginning has to begin gently; the chord is barely touched,
    // because its attack is four milliseconds and this is a fade over a
    // hundred and eighty.
    const auto fadeIn = static_cast<int32_t>(kMatSr * 0.18f);
    const auto fadeOut = static_cast<int32_t>(kMatSr * 0.25f);
    for (int32_t i = 0; i < frames; ++i) {
        float g = 0.85f / peak;
        if (i < fadeIn) g *= static_cast<float>(i) / static_cast<float>(fadeIn);
        const int32_t fromEnd = frames - 1 - i;
        if (fromEnd < fadeOut) g *= static_cast<float>(fromEnd) / static_cast<float>(fadeOut);
        take->left[static_cast<size_t>(i)] *= g;
        take->right[static_cast<size_t>(i)] *= g;
    }
    take->detect(kMatSr);
    return take;
}

/** The voice phrase as a Take, for Pollen's file path. */
inline std::unique_ptr<audio::Take> voiceTake() {
    auto take = std::make_unique<audio::Take>();
    take->name = "voice";
    take->left = voicePhrase();
    take->right = take->left;
    take->frames = static_cast<int32_t>(take->left.size());
    take->detect(kMatSr);
    return take;
}

/** The voice phrase, analysed, for Molt. */
inline std::unique_ptr<audio::Utterance> voiceUtterance() {
    auto u = std::make_unique<audio::Utterance>();
    u->name = "voice";
    u->mono = voicePhrase();
    u->frames = static_cast<int32_t>(u->mono.size());
    u->analyse(kMatSr);
    return u;
}

/**
 * A small multisampled instrument: three key zones at two velocity layers.
 *
 * Built by hand rather than read from a SoundFont, so the harness needs no
 * file and no Sf2Reader. The top of the keyboard is deliberately left
 * uncovered - a Mosaic patch that only works because every key happens to
 * find a zone is not proven, and `--phrase chromatic` walks straight off the
 * end of the map to show it.
 *
 * **Each zone is a struck note, not a tone.** It used to be a sum of
 * harmonics at constant amplitude, looped, and against that map half of
 * Mosaic did nothing measurable: `reverse` moved the centroid two hertz,
 * `start` twelve, and the three loop modes were identical to each other. Of
 * course they were - a signal with no attack sounds the same from either end
 * and from anywhere in the middle, and a sampler fed one cannot be voiced,
 * only assumed. The same mistake as judging Cipher on a held vowel.
 *
 * So a zone now has the three parts a real sampled note has, and each one is
 * there because some control needs it:
 *
 *   - a **transient**: a noise burst through a low-pass, 12 ms, which is what
 *     `start` scrubs past and what `reverse` puts at the end;
 *   - a **body** whose upper partials die faster than its lower ones, so the
 *     note gets darker as it goes - that is what makes a grain's position
 *     audible, and what `scan` and `gpos` have to have to mean anything;
 *   - a **sustain** that is level and loops cleanly over whole periods, very
 *     slightly detuned against itself so it breathes rather than sits.
 */
/** How long a zone takes to arrive. See the note at the write, below. */
constexpr float kOnset = 0.004f;

inline std::unique_ptr<SampleMap> zoneMap() {
    auto map = std::make_unique<SampleMap>();
    map->name = "audition";
    // Three roots two octaves apart, each a different harmonic mix, and each
    // velocity layer brighter than the one below it - which is what a
    // velocity crossfade has to have to be worth testing.
    struct Layer { int root; int lo; int hi; int loVel; int hiVel; int partials; float odd; };
    static const Layer kLayers[] = {
        // Partial counts sized so that a zone played at the top of its range
        // - eleven semitones above its root, so read 1.89x faster - still has
        // its highest partial under Nyquist. The low zone can afford forty;
        // the high one cannot afford more than twelve.
        {36, 24, 47, 1, 79, 24, 1.0f},  {36, 24, 47, 80, 127, 40, 0.6f},
        {60, 48, 71, 1, 79, 16, 1.0f},  {60, 48, 71, 80, 127, 28, 0.6f},
        {84, 72, 95, 1, 79, 8, 1.0f},   {84, 72, 95, 80, 127, 12, 0.6f},
    };
    Rng rng(0x5a3c19u);
    // Every partial gets a phase of its own, fixed for the life of the sample.
    //
    // Starting them all at zero is the additive-synthesis mistake: they align
    // perfectly at t=0, so the note opens on an impulse the size of the sum of
    // every harmonic and then thrashes for a few milliseconds as they beat
    // apart. Dan heard it on patch after patch - "a clapping/chopping sound
    // every note", "gated noise at the beginning of each note", "very choppy
    // with a distinct percussive sound" - and it survived every change to the
    // hammer, because it was never the hammer: the lumpiness of the first
    // twenty milliseconds sat at 0.45 whatever the noise burst was set to.
    // A random phase is still an exact harmonic, so the loop stays seamless.
    float phase[33];
    for (float &ph : phase) ph = (rng.next() + 1.0f) * static_cast<float>(M_PI);
    for (const Layer &l : kLayers) {
        SampleData s;
        s.name = "zone";
        s.rate = static_cast<int32_t>(kMatSr);
        const float hz = 440.0f * std::pow(2.0f, (static_cast<float>(l.root) - 69.0f) / 12.0f);
        // Three seconds, so `start` has somewhere to go and a grain cloud has
        // more than one thing to find.
        const int32_t n = static_cast<int32_t>(kMatSr * 3.0f);
        const auto period = static_cast<int32_t>(kMatSr / hz);
        // The sustain begins after the body has finished getting darker, and
        // is where the loop lives.
        // Late enough that the body has all but stopped moving. At 1.2 s the
        // fundamental was still falling about two per cent across one loop,
        // so every time round it stepped back up - inaudible on its own, a
        // buzz at the sixteen to sixty loops a second this runs at.
        const int32_t sustainAt = period * ((static_cast<int32_t>(kMatSr * 2.1f)) / period);
        s.left.assign(static_cast<size_t>(n), 0.0f);
        float lp = 0.0f;
        for (int32_t i = 0; i < n; ++i) {
            const float t = static_cast<float>(i) / kMatSr;
            float v = 0.0f;
            for (int h = 1; h <= l.partials; ++h) {
                const float amp = (h % 2 == 1 ? 1.0f : l.odd) / static_cast<float>(h);
                // A partial's own decay, shorter the higher it is, down to a
                // floor it holds through the sustain. The floor is what lets
                // the loop be seamless: past the body every partial is steady.
                //
                // Both fall as the *square root* of the partial, not as the
                // partial. With 1/h either way, the sustain came out at
                // 1/h-squared - a spectrum no sampled instrument has, and one
                // nothing could be high-passed out of: Mosaic's Glass Pad
                // measured forty decibels down through an 18 dB slope at 1400
                // Hz, because at 1400 Hz there was nothing. A map is material,
                // and material that is too dark cannot show what a filter does
                // any more than a steady tone can show what a start point does.
                const float rootH = std::sqrt(static_cast<float>(h));
                const float tau = 0.9f / rootH;
                // Flat across the partials, so the sustain keeps a 1/h
                // spectrum - what a sustained instrument actually has. Scaled
                // by the square root it was 1/h-to-the-three-halves, and the
                // whole bank came out with no bright patches in it at all:
                // Mosaic's centroids topped out at 1.9 kHz where Trinity
                // reaches 3.8 and Cumulus 5.0, and Dan heard the pads as
                // "very dark and hard to hear".
                const float held = 0.5f;
                const float env = held + (1.0f - held) * std::exp(-t / tau);
                // Every partial is an exact multiple of the fundamental, and
                // it has to be: the loop is a whole number of the
                // fundamental's periods, so anything that is not a harmonic
                // of it arrives at the loop point with the wrong phase and
                // clicks, once per loop. There used to be a hair of detune
                // here - 0.06% per partial, to make the sustain breathe - and
                // Dan heard the result as "many smaller pops" in Late Start,
                // the one patch in the bank that loops. Measured at 37 Hz,
                // which is the loop rate of the notes it was playing.
                v += amp * env * std::sin(2.0f * static_cast<float>(M_PI) * hz * static_cast<float>(h) * t +
                                          phase[h & 31]);
            }
            // The transient: a short noise burst, low-passed so it reads as a
            // hammer rather than as a click, and gone before the loop starts.
            const float hit = std::exp(-t / 0.012f);
            // Darker than it was by a long way. At 0.35 the burst kept most
            // of its top and read as a tick of noise on the front of every
            // note - Dan heard it on Scan Layers, Grind and Reed in turn, and
            // the harness put every patch that starts at zero at six to eight
            // times brighter on the attack than in the tone, against a
            // threshold of four. A hammer is a thump with an edge, not an
            // edge on its own.
            lp += (rng.next() - lp) * 0.20f;
            // Six was a sixteen-decibel crest on a sustained instrument, and
            // it put the Init patch within a decibel of full scale once the
            // house level was set from its loudness. A hammer is louder than
            // the note it starts, but not by that.
            v += lp * hit * 1.0f;
            // And a breath of it through the body, because every real
            // instrument has some and it is most of what survives a
            // high-pass. Gone before the loop starts: noise is the one thing
            // here that is not periodic, so a loop that contains any repeats
            // the same 60 ms of it and jumps at the seam every time round.
            v += lp * 0.012f * std::exp(-t / 0.35f);
            // A real sample does not switch its whole spectrum on in one
            // frame. Every partial here began at full amplitude at t=0, so
            // the tone arrived fully formed and instantly - and no amount of
            // amp attack hides that, because what clicks is the spectrum
            // appearing, not the level. Dan heard it on ten patches in a row
            // as "a hard chk sound at the start of every note", and it
            // survived taking the hammer out altogether: onset 115% of the
            // body with the noise burst, 114% without it.
            //
            // Four milliseconds of raised cosine. Short enough that a lead
            // still speaks immediately, long enough that the partials arrive
            // rather than appear.
            const float in = t < kOnset ? 0.5f - 0.5f * std::cos(static_cast<float>(M_PI) * t / kOnset) : 1.0f;
            s.left[static_cast<size_t>(i)] = v * 0.12f * in;
        }
        s.frames = n;
        s.stereo = false;
        // A loop over whole periods inside the sustain, so a held note does
        // not click and does not fade either.
        s.loopStart = sustainAt;
        s.loopEnd = sustainAt + period * 16;
        map->samples.push_back(std::move(s));

        MapZone z;
        z.sample = static_cast<int32_t>(map->samples.size()) - 1;
        z.lowKey = static_cast<uint8_t>(l.lo);
        z.highKey = static_cast<uint8_t>(l.hi);
        z.rootKey = static_cast<uint8_t>(l.root);
        z.lowVel = static_cast<uint8_t>(l.loVel);
        z.highVel = static_cast<uint8_t>(l.hiVel);
        z.loopStart = map->samples.back().loopStart;
        z.loopEnd = map->samples.back().loopEnd;
        map->zones.push_back(z);
    }
    return map;
}

} // namespace acidulous::audition
