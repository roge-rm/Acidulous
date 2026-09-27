#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include <cstdlib>
#include <engine/core/Sample.h>
#include <engine/format/WavReader.h>
#include <engine/core/SampleMap.h>
#include <engine/core/Take.h>
#include <engine/core/Utterance.h>

// The material six machines need before they make any sound.
//
// Forage needs thirteen samples, Mosaic a zone map, Dice a loop, Molt a sung
// take, Pollen a file or the live buffer, and Cipher needs input to vocode.
// The app ships no samples, so the harness makes its own from oscillators and
// noise with fixed seeds. molt_test also uses `vowel`, `noise` and `resonate`
// from here, since their pitch and formants are known exactly.
//
// None of this is compiled into the app.

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

/** A two-pole resonator, used as a formant. */
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
 * A short phrase: "ah", a fricative, then "ee". Cipher can't be judged on a
 * steady vowel, since its band map only shows when the input moves.
 */
inline std::vector<float> voicePhrase() {
    std::vector<float> out;
    const std::vector<float> ah = vowel(140.0f, 1.1f, 730.0f, 1090.0f);
    const std::vector<float> ss = noise(0.35f, 0.25f, 0x51ced5u);
    const std::vector<float> ee = vowel(165.0f, 1.1f, 270.0f, 2290.0f);
    out.insert(out.end(), ah.begin(), ah.end());
    out.insert(out.end(), ss.begin(), ss.end());
    out.insert(out.end(), ee.begin(), ee.end());
    // 10 ms fades at each end, or the steps would be the loudest transients
    // and onset detectors would find them first.
    const size_t fade = static_cast<size_t>(kMatSr * 0.01f);
    for (size_t i = 0; i < fade && i < out.size(); ++i) {
        const float g = static_cast<float>(i) / static_cast<float>(fade);
        out[i] *= g;
        out[out.size() - 1 - i] *= g;
    }
    return out;
}

/**
 * Twelve seconds of synthetic speech for the vocoder. `voicePhrase` is too
 * short and too steady to voice a bank with.
 *
 * Modelled on measurements of a real recording, whose median pitch was
 * 125 Hz with a quarter of it below 80 Hz. That's low enough to fall under a
 * vocoder bank starting at 110 Hz, so this is kept low and varied too: the
 * pitch moves between 70 and 190 Hz.
 *
 * Nine syllables, each a vowel with two formants, separated by fricatives and
 * silence (about a tenth of the total, like the recording). Each syllable's
 * pitch glides, as real speech does.
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
        // A pulse train with a gliding pitch. `vowel` only holds one pitch.
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
        // A quiet high third formant, so the top bands get something besides
        // the consonants.
        resonate(pulses, body, 2900.0f, 10.0f, 0.15f);
        float peak = 1e-9f;
        for (float v : body) peak = std::max(peak, std::fabs(v));
        for (float &v : body) v *= 0.7f / peak;
        // 10 ms fade in and 40 ms fade out, so a syllable starts with an edge
        // and ends softly.
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
            // Shaped noise, since an "s" is a band around 4 kHz rather than
            // flat noise.
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
    // Normalised to -20 dBFS rms, the same level used for input files. A
    // vocoder's output follows its input, so every modulator needs the same
    // level for the bank levels to mean anything.
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
// In Hexbeat's voice order, which is Forage's pad order and the drum grid's.
// Each piece has a clearly different spectrum and length, so a pad envelope
// or filter that isn't doing anything can be heard not doing it.

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
    // Band-passed noise. Two resonators are enough for a hat or cymbal.
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
            const float hz = 52.0f + 130.0f * env(t, 0.020f); // pitch sweep
            return std::sin(2.0f * static_cast<float>(M_PI) * hz * t) * env(t, 0.16f) +
                   rng.next() * env(t, 0.003f) * 0.4f;        // beater click
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
            // Four bursts and a tail, like several hands slightly apart.
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
 * Two bars at 120 bpm of the kit above, as a Take with its onsets detected.
 * Dice slices it and Pollen reads it. The onsets are real so the detector is
 * tested, not the even-split fallback.
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
 * Eight seconds of music for machines that granulate a buffer. A drum break
 * only tests onset snap, not the clouds and pitch sprays that make up most of
 * Pollen's bank.
 *
 * Four chords in C Dorian (Cm, F, Bb, Gm), two seconds each. That's the mode
 * the `scale` patches use, so a quantised cloud agrees with the source.
 *
 * Each chord is plucked: a short noise attack so `detect` finds a real onset,
 * then a bass root and a triad, each note a small harmonic stack whose upper
 * partials die first. Notes are struck a few ms apart like a hand, and left
 * and right are detuned slightly for some stereo.
 */
inline std::unique_ptr<audio::Take> musicSeed() {
    auto take = std::make_unique<audio::Take>();
    take->name = "seed";
    const float chordSeconds = 2.0f;
    // Two seconds of silence at the end. Granular reads wrap around, and a
    // buffer that ends mid-sound puts a click in the cloud.
    const float tailSeconds = 2.0f;
    const int32_t frames = static_cast<int32_t>(kMatSr * (chordSeconds * 4.0f + tailSeconds));
    take->frames = frames;
    take->left.assign(static_cast<size_t>(frames), 0.0f);
    take->right.assign(static_cast<size_t>(frames), 0.0f);

    // Cm, F, Bb, Gm: bass root, then the triad, in Hz.
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
            // Stagger the notes slightly, like a hand.
            const auto at = chordAt + static_cast<size_t>(kMatSr * 0.006f * static_cast<float>(n));
            const int partials = n == 0 ? 10 : 7;          // the bass is richer
            const float amp = n == 0 ? 0.5f : 0.34f;
            for (int ch = 0; ch < 2; ++ch) {
                std::vector<float> &out = ch == 0 ? take->left : take->right;
                const float det = ch == 0 ? 1.0f : 1.0006f;  // a little width
                float lp = 0.0f;
                for (size_t i = 0; at + i < static_cast<size_t>(frames); ++i) {
                    const float t = static_cast<float>(i) / kMatSr;
                    // Notes aren't cut off. The exponential decay ends them,
                    // since a hard stop leaves a step that every grain
                    // reading across it would click on.
                    float v = 0.0f;
                    for (int h = 1; h <= partials; ++h) {
                        // Higher partials decay faster, like a pluck.
                        const float tau = 1.5f / std::sqrt(static_cast<float>(h));
                        v += std::sin(2.0f * static_cast<float>(M_PI) * hz * det * static_cast<float>(h) * t) *
                             std::exp(-t / tau) / static_cast<float>(h);
                    }
                    // The pluck: a short burst of filtered noise on the attack.
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
    // Fade both ends to silence so wrapping from the end to the start doesn't
    // click. The fade in is long (180 ms) because `position` defaults to the
    // start of the buffer, and a pluck there would pop at the start of every
    // note.
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

/** Nominal input level: -20 dBFS rms, a typical recording level. */
constexpr float kInputNominalRms = 0.1f;

/**
 * Reads a recording as mono, filters out rumble and sets its level. Used for
 * both the vocoder's input and Molt's take. An empty or missing path returns
 * nothing, and callers then fall back to the synthetic phrase.
 */
inline std::vector<float> fileMono(const char *path, std::string &error) {
    if (path == nullptr || *path == '\0') return {};
    const std::unique_ptr<SampleData> s = WavReader::read(path, static_cast<int32_t>(kMatSr), error);
    if (s == nullptr || s->frames <= 0) return {};

    // Fold to mono before analysis.
    std::vector<float> src(s->left.begin(), s->left.end());
    if (!s->right.empty()) {
        for (size_t i = 0; i < src.size() && i < s->right.size(); ++i) {
            src[i] = (src[i] + s->right[i]) * 0.5f;
        }
    }
    // Remove DC and rumble before setting the level, or the rumble throws
    // the level off. Hand-held recordings can have a lot of it.
    //
    // The corner is 45 Hz because a low voice can have its fundamental
    // under 80 Hz, and a higher corner would cut it.
    const float a = std::exp(-2.0f * 3.14159265f * 45.0f / kMatSr);
    for (int pass = 0; pass < 3; ++pass) {
        float px = 0.0f, py = 0.0f;
        for (float &v : src) {
            py = a * (py + v - px);
            px = v;
            v = py;
        }
    }
    double sum = 0.0;
    for (float v : src) sum += static_cast<double>(v) * v;
    const auto rms = static_cast<float>(std::sqrt(sum / std::max<size_t>(1, src.size())));
    if (rms > 1e-6f) {
        const float gain = kInputNominalRms / rms;
        for (float &v : src) v *= gain;
    }
    return src;
}

/**
 * An analysed voice take for Molt.
 *
 * Uses the recording named in `tools/local.env` if there is one, like Cipher.
 * The synthetic fricatives sound like bursts of static, and Molt copies
 * unvoiced parts as they are, so they stand out more than in a vocoder.
 *
 * The fallback is `speechPhrase`, not `voicePhrase`. Molt pulls the pitch
 * onto the written notes, so on a take with a fixed pitch `tune` would only
 * shift it and `rate` would have nothing to do.
 */
inline std::unique_ptr<audio::Utterance> voiceUtterance() {
    auto u = std::make_unique<audio::Utterance>();
    u->name = "voice";
    std::string ignored;
    u->mono = fileMono(std::getenv("ACIDULOUS_INPUT_FILE"), ignored);
    if (u->mono.empty()) u->mono = speechPhrase();
    u->frames = static_cast<int32_t>(u->mono.size());
    // Finds the marks and removes rumble. This has to happen before the
    // level is set, since removing rumble can change the peaks a lot.
    u->analyse(kMatSr);

    // Levelled by rms, not by peak. With peak normalising a single loud
    // sample sets the level of the whole take, and a clipped recording can
    // end up far too quiet. Peaks over 1 are possible after this and are
    // handled by the limiter below.
    double sum = 0.0;
    for (float v : u->mono) sum += static_cast<double>(v) * v;
    const auto rms = static_cast<float>(std::sqrt(sum / std::max<size_t>(1, u->mono.size())));
    if (rms > 1e-6f) {
        const float gain = kInputNominalRms / rms;
        for (float &v : u->mono) v *= gain;
    }

    // A soft limiter for the few samples far above the rest, so one grain
    // landing on them doesn't make a patch much louder. The knee is above the
    // take's 99.99th percentile, so the rest is untouched. The ceiling is full
    // scale, so the loudest a patch can get is its own `volume`.
    constexpr float kKnee = 0.7f, kCeiling = 1.0f;
    for (float &v : u->mono) {
        const float m = std::fabs(v);
        if (m <= kKnee) continue;
        v = (v < 0.0f ? -1.0f : 1.0f) *
            (kKnee + (kCeiling - kKnee) * std::tanh((m - kKnee) / (kCeiling - kKnee)));
    }
    return u;
}

/**
 * A small multisampled instrument: three key zones at two velocity layers.
 *
 * Built in code so the harness needs no SoundFont file. The top of the
 * keyboard is left uncovered on purpose, and `--phrase chromatic` plays off
 * the end of the map to show what happens.
 *
 * Each zone is a struck note rather than a steady tone, since a steady tone
 * sounds the same from any point and gives `start`, `reverse` and the loop
 * modes nothing to change. A zone has:
 *
 *   - a transient: 12 ms of low-passed noise, which `start` skips past and
 *     `reverse` puts at the end;
 *   - a body whose upper partials die faster, so the note darkens over time
 *     and grain position (`scan`, `gpos`) can be heard;
 *   - a steady sustain that loops cleanly over whole periods.
 */
/** The fade-in time of each zone. See where it's applied below. */
constexpr float kOnset = 0.004f;

inline std::unique_ptr<SampleMap> zoneMap() {
    auto map = std::make_unique<SampleMap>();
    map->name = "audition";
    // Three roots two octaves apart, each with a different harmonic mix. The
    // upper velocity layer is brighter, so velocity crossfades can be heard.
    struct Layer { int root; int lo; int hi; int loVel; int hiVel; int partials; float odd; };
    static const Layer kLayers[] = {
        // Partial counts keep the top partial under Nyquist even when a zone
        // is played 11 semitones above its root (1.89x speed).
        {36, 24, 47, 1, 79, 24, 1.0f},  {36, 24, 47, 80, 127, 40, 0.6f},
        {60, 48, 71, 1, 79, 16, 1.0f},  {60, 48, 71, 80, 127, 28, 0.6f},
        {84, 72, 95, 1, 79, 8, 1.0f},   {84, 72, 95, 80, 127, 12, 0.6f},
    };
    Rng rng(0x5a3c19u);
    // Each partial gets its own fixed random phase. If they all start at
    // zero they line up into a big impulse at the start of every note, which
    // sounds choppy. Random phases are still exact harmonics, so the loop
    // stays seamless.
    float phase[33];
    for (float &ph : phase) ph = (rng.next() + 1.0f) * static_cast<float>(M_PI);
    for (const Layer &l : kLayers) {
        SampleData s;
        s.name = "zone";
        s.rate = static_cast<int32_t>(kMatSr);
        const float hz = 440.0f * std::pow(2.0f, (static_cast<float>(l.root) - 69.0f) / 12.0f);
        // Three seconds, so `start` and grain clouds have room to move.
        const int32_t n = static_cast<int32_t>(kMatSr * 3.0f);
        const auto period = static_cast<int32_t>(kMatSr / hz);
        // The sustain, where the loop is, starts once the body has almost
        // stopped changing. Earlier than that the level still drops across
        // one loop, and the step back up each time round buzzes.
        const int32_t sustainAt = period * ((static_cast<int32_t>(kMatSr * 2.1f)) / period);
        s.left.assign(static_cast<size_t>(n), 0.0f);
        float lp = 0.0f;
        for (int32_t i = 0; i < n; ++i) {
            const float t = static_cast<float>(i) / kMatSr;
            float v = 0.0f;
            for (int h = 1; h <= l.partials; ++h) {
                const float amp = (h % 2 == 1 ? 1.0f : l.odd) / static_cast<float>(h);
                // Each partial decays faster the higher it is, down to a floor
                // it holds through the sustain. The floor keeps the loop
                // seamless. Decay time goes with the square root of the
                // partial number, which keeps enough top end for filters to
                // have something to work on.
                const float rootH = std::sqrt(static_cast<float>(h));
                const float tau = 0.9f / rootH;
                // The same floor for every partial, so the sustain keeps a
                // 1/h spectrum like a real sustained instrument. Anything
                // steeper makes the whole bank too dark.
                const float held = 0.5f;
                const float env = held + (1.0f - held) * std::exp(-t / tau);
                // Every partial must be an exact harmonic. The loop is a whole
                // number of periods, so any detune arrives at the loop point
                // with the wrong phase and clicks once per loop.
                v += amp * env * std::sin(2.0f * static_cast<float>(M_PI) * hz * static_cast<float>(h) * t +
                                          phase[h & 31]);
            }
            // The transient: a short low-passed noise burst, so it sounds like
            // a hammer rather than a click. It's gone before the loop starts.
            const float hit = std::exp(-t / 0.012f);
            // Filtered fairly dark. A brighter burst sounds like a tick of
            // noise on every note.
            lp += (rng.next() - lp) * 0.20f;
            // Kept moderate. A louder hammer gives a sustained sound a huge
            // crest factor and pushes patches near full scale.
            v += lp * hit * 1.0f;
            // A little noise through the body, like a real instrument, which
            // is most of what survives a high-pass. It dies away before the
            // loop starts, since noise isn't periodic and would click at the
            // loop seam.
            v += lp * 0.012f * std::exp(-t / 0.35f);
            // A 4 ms raised-cosine fade in. Without it every partial starts at
            // full level on the first frame and clicks, whatever the amp
            // attack is. 4 ms is still short enough for a lead to speak at
            // once.
            const float in = t < kOnset ? 0.5f - 0.5f * std::cos(static_cast<float>(M_PI) * t / kOnset) : 1.0f;
            s.left[static_cast<size_t>(i)] = v * 0.12f * in;
        }
        s.frames = n;
        s.stereo = false;
        // Loop over whole periods inside the sustain, so a held note neither
        // clicks nor fades.
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
