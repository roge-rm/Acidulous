#pragma once
#include "Sample.h"
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

// A whole multisampled instrument, built on a worker thread and handed to the
// audio thread as one object mount. Everything a voice needs is inside it, so
// the audio thread never touches a file or an allocator.
namespace acidulous {

struct MapZone {
    int32_t sample = 0;        // index into SampleMap::samples
    uint8_t lowKey = 0, highKey = 127, rootKey = 60;
    uint8_t lowVel = 1, highVel = 127;
    float tuneCents = 0.0f;
    float gain = 1.0f;         // linear
    float pan = 0.0f;
    int32_t loopStart = -1, loopEnd = -1; // in source frames; -1 for none
    // Taken from the file when it carries one, used when the panel asks for
    // the file's envelope rather than its own.
    float attack = 0.001f, decay = 0.3f, sustain = 1.0f, release = 0.15f;
    bool hasEnvelope = false;
};

struct SampleMap {
    std::string name;
    std::vector<SampleData> samples;
    std::vector<MapZone> zones;

    int32_t zoneCount() const { return static_cast<int32_t>(zones.size()); }

    /**
     * Every zone that covers this key and velocity, with the gain the
     * crossfades give it. Returns how many were written, at most `max`.
     * Fade widths are in semitones and velocity steps; zero means a hard edge.
     */
    int32_t select(int key, int vel, float keyFade, float velFade, int32_t *outZones, float *outGains,
                   int32_t max) const {
        int32_t n = 0;
        for (int32_t z = 0; z < zoneCount() && n < max; ++z) {
            const MapZone &s = zones[static_cast<size_t>(z)];
            const float kg = edgeGain(static_cast<float>(key), s.lowKey, s.highKey, keyFade);
            if (kg <= 0.0f) continue;
            const float vg = edgeGain(static_cast<float>(vel), s.lowVel, s.highVel, velFade);
            if (vg <= 0.0f) continue;
            outZones[n] = z;
            outGains[n] = kg * vg;
            ++n;
        }
        return n;
    }

  private:
    // 1 inside, falling to 0 across `fade` either side of the edge.
    static float edgeGain(float v, float lo, float hi, float fade) {
        if (fade <= 0.0f) return (v >= lo && v <= hi) ? 1.0f : 0.0f;
        if (v < lo - fade || v > hi + fade) return 0.0f;
        float g = 1.0f;
        if (v < lo) g = (v - (lo - fade)) / fade;
        else if (v > hi) g = ((hi + fade) - v) / fade;
        return g < 0.0f ? 0.0f : (g > 1.0f ? 1.0f : g);
    }
};

} // namespace acidulous
