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
 */
inline std::unique_ptr<SampleMap> zoneMap() {
    auto map = std::make_unique<SampleMap>();
    map->name = "audition";
    // Three roots two octaves apart, each a different harmonic mix, and each
    // velocity layer brighter than the one below it - which is what a
    // velocity crossfade has to have to be worth testing.
    struct Layer { int root; int lo; int hi; int loVel; int hiVel; int partials; float odd; };
    static const Layer kLayers[] = {
        {36, 24, 47, 1, 79, 12, 1.0f},  {36, 24, 47, 80, 127, 20, 0.6f},
        {60, 48, 71, 1, 79, 8, 1.0f},   {60, 48, 71, 80, 127, 16, 0.6f},
        {84, 72, 95, 1, 79, 5, 1.0f},   {84, 72, 95, 80, 127, 9, 0.6f},
    };
    for (const Layer &l : kLayers) {
        SampleData s;
        s.name = "zone";
        s.rate = static_cast<int32_t>(kMatSr);
        const float hz = 440.0f * std::pow(2.0f, (static_cast<float>(l.root) - 69.0f) / 12.0f);
        const int32_t n = static_cast<int32_t>(kMatSr * 1.5f);
        s.left.assign(static_cast<size_t>(n), 0.0f);
        for (int32_t i = 0; i < n; ++i) {
            const float t = static_cast<float>(i) / kMatSr;
            float v = 0.0f;
            for (int h = 1; h <= l.partials; ++h) {
                const float amp = (h % 2 == 1 ? 1.0f : l.odd) / static_cast<float>(h);
                v += amp * std::sin(2.0f * static_cast<float>(M_PI) * hz * static_cast<float>(h) * t);
            }
            s.left[static_cast<size_t>(i)] = v * 0.12f;
        }
        s.frames = n;
        s.stereo = false;
        // A loop over whole periods, so a held note does not click.
        const auto period = static_cast<int32_t>(kMatSr / hz);
        s.loopStart = period * 8;
        s.loopEnd = s.loopStart + period * 16;
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
