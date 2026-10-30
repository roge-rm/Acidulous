#pragma once
// A room's impulse made up for the harnesses: a click, then noise decaying
// to -60 dB over the length, darker as it goes. Normalised as loadImpulse
// does, so its level is the effect's working level.
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

#include <engine/core/Constants.h>
#include <engine/effect/Venue.h>

namespace venue_harness {

inline void synthRoom(double seconds, bool stereo, uint32_t seed, std::vector<float> &l, std::vector<float> &r) {
    const auto n = static_cast<int32_t>(seconds * acidulous::kSampleRate);
    l.assign(static_cast<size_t>(n), 0.0f);
    r.assign(stereo ? static_cast<size_t>(n) : 0u, 0.0f);
    uint32_t s = seed;
    auto rnd = [&s] {
        s = s * 1664525u + 1013904223u;
        return static_cast<float>(s >> 9) * (2.0f / 8388608.0f) - 1.0f;
    };
    float lp[2] = {};
    for (int32_t i = 0; i < n; ++i) {
        const double t = static_cast<double>(i) / n;
        const auto env = static_cast<float>(std::exp(-6.9078 * t));
        // A one-pole closing from bright to dark over the tail.
        const auto a = static_cast<float>(0.9 - 0.85 * t);
        lp[0] += (rnd() - lp[0]) * a;
        lp[1] += (rnd() - lp[1]) * a;
        l[static_cast<size_t>(i)] = lp[0] * env;
        if (stereo) r[static_cast<size_t>(i)] = lp[1] * env;
    }
    l[0] = 1.0f;
    if (stereo) r[0] = 1.0f;
    acidulous::effect::normaliseImpulse(l, r);
}

/** The made-up room, built as the Venue mounts it. */
inline std::unique_ptr<acidulous::effect::VenueImpulse> room(double seconds, bool stereo = true, uint32_t seed = 1) {
    std::vector<float> l, r;
    synthRoom(seconds, stereo, seed, l, r);
    return acidulous::effect::buildVenueImpulse(l.data(), stereo ? r.data() : nullptr, static_cast<int32_t>(l.size()),
                                                acidulous::kSampleRate, acidulous::effect::VenueShape{});
}

} // namespace venue_harness
