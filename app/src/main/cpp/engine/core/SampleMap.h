#pragma once
#include "Sample.h"
#include <cmath>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

// A whole multisampled instrument, built on a worker thread and handed to the
// audio thread as one object mount. Everything a voice needs is inside it, so
// the audio thread never touches a file or an allocator.
namespace acidulous {

/**
 * A SoundFont modulator, resolved at build time into something the audio
 * thread can evaluate without knowing anything about the file format.
 *
 * The file describes a source (a controller or the note itself), a curve, and
 * an amount in the destination's own units. Only the destinations this engine
 * can actually honour survive the reader; the rest are counted and dropped.
 */
struct ZoneMod {
    enum Source : uint8_t { SrcNone, SrcVelocity, SrcKeyNumber, SrcChannelPressure, SrcPolyPressure, SrcPitchWheel, SrcCc };
    enum Dest : uint8_t { DstAttenuation, DstFilterCutoff, DstPan, DstTuning };
    enum Curve : uint8_t { Linear, Concave, Convex, Switch };

    uint8_t source = SrcNone;
    uint8_t cc = 0;          // when source is SrcCc
    uint8_t dest = DstAttenuation;
    uint8_t curve = Linear;
    bool decreasing = false; // the source runs max to min
    bool bipolar = false;
    float amount = 0.0f;     // centibels, cents, or tenths of a percent for pan

    /**
     * The curve's output for a source already normalised to 0..1.
     *
     * The spec gives each curve a closed form per direction rather than
     * mirroring the input, and the difference is not academic: the default
     * velocity modulator is concave *descending*, which must read zero
     * attenuation at full velocity and about twelve decibels down at half.
     * Mirroring first inverts that and the instrument goes silent.
     */
    float apply(float x) const {
        if (x < 0.0f) x = 0.0f;
        if (x > 1.0f) x = 1.0f;
        float y;
        switch (curve) {
        case Concave: y = decreasing ? conc(x) : conc(1.0f - x); break;
        case Convex: y = decreasing ? 1.0f - conc(1.0f - x) : 1.0f - conc(x); break;
        case Switch: y = (x < 0.5f) == decreasing ? 1.0f : 0.0f; break;
        default: y = decreasing ? 1.0f - x : x; break;
        }
        if (y < 0.0f) y = 0.0f;
        if (y > 1.0f) y = 1.0f;
        return bipolar ? 2.0f * y - 1.0f : y;
    }

  private:
    /** One at zero, falling steeply and then flattening to zero at one. */
    static float conc(float t) {
        if (t <= 0.0001f) return 1.0f;
        const float v = -(40.0f / 96.0f) * std::log10(t);
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }
};

constexpr int kMaxZoneMods = 8;

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

    ZoneMod mods[kMaxZoneMods];
    int32_t modCount = 0;
    /** Whether anything in `mods` drives the amplitude from velocity. */
    bool velocityToLevel = false;
};

struct SampleMap {
    std::string name;
    std::vector<SampleData> samples;
    std::vector<MapZone> zones;
    /** Modulators the file asked for that this engine cannot honour. */
    int32_t droppedMods = 0;

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
