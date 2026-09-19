#pragma once
#include <cstdint>
#include <engine/core/Sample.h>
#include <string>

// What a recording needs doing to it before it is usable.
//
// **Not a machine, and not on the audio thread.** Everything here works on a
// decoded file: it is read with WavReader, changed here, written with
// WavWriter, and the result is a sample like any other. That is what lets a
// take be cropped and levelled once rather than by every machine that plays
// it, and it is why these are plain functions over a `SampleData` rather than
// anything that knows about racks - the harness in tools/ drives them on the
// desk with no engine at all.
//
// The order the operations run in is fixed and is not the order they are
// listed: crop first because everything after it is cheaper on less audio,
// then reverse, then the filters, then the compressor, then level, and the
// fades last so that whatever the level did the ends still reach nought.
namespace acidulous::audio {

/**
 * One edit, as a screen would describe it.
 *
 * Every field is inert at its default, so an empty `SampleOps` is a copy.
 * That matters more than it looks: it is what lets the screen hand the whole
 * struct over every time rather than working out which parts changed.
 */
struct SampleOps {
    /** Keep [from] until [to], in frames. `to <= from` means to the end. */
    int32_t from = 0;
    int32_t to = 0;
    float fadeInMs = 0.0f;
    float fadeOutMs = 0.0f;
    /** Level, in decibels, applied before the peak is normalised. */
    float gainDb = 0.0f;
    /** Bring the loudest sample to this, 0..1. Nought leaves the level alone. */
    float normaliseTo = 0.0f;
    bool reverse = false;
    /** A high pass, for the room under a voice. Nought is off. */
    float lowCutHz = 0.0f;
    /** The tone filter: nought is off; the type is a dsp::MultiFilter::Type. */
    float cutoffHz = 0.0f;
    float resonance = 0.0f;
    int32_t filterType = 0;
    /**
     * How hard the compressor squeezes, 0..1, and how fast.
     *
     * One knob rather than a threshold and a ratio, because the two move
     * together in every use this has: nought is off, and at one the threshold
     * is 24 dB down and the ratio is eight to one, with the makeup that keeps
     * the peak where it was.
     */
    float squash = 0.0f;
    float squashAttackMs = 10.0f;
    float squashReleaseMs = 120.0f;
};

/** Apply the lot, in the fixed order. False with [error] set if it cannot. */
bool applyEdit(SampleData &data, const SampleOps &ops, std::string &error);

// --- and each on its own, which is how they are tested -----------------------

/** Keep [from] until [to]. Clamped; a range that inverts is left alone. */
void cropTo(SampleData &data, int32_t from, int32_t to);
/** Linear fades over this many frames at each end. */
void fadeEnds(SampleData &data, int32_t inFrames, int32_t outFrames);
/** Multiply. */
void applyGain(SampleData &data, float linear);
/** Scale so the loudest sample is [peak]. Silence is left silent. */
void normalisePeak(SampleData &data, float peak);
void reverseInPlace(SampleData &data);
/** One pass of `dsp::MultiFilter`, per channel, at the data's own rate. */
void filterInPlace(SampleData &data, float cutoffHz, float resonance, int32_t type);
/** See `SampleOps::squash`. */
void compressInPlace(SampleData &data, float amount, float attackMs, float releaseMs);

} // namespace acidulous::audio
