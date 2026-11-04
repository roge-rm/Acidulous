#pragma once
#include <cstdint>
#include <vector>

#include <engine/core/Reel.h>

// Reading a stack of takes the way a producer listens to them: which take
// sings each phrase in tune, in time, unclipped and clean, and how far a
// double sits behind its lead.
//
// Offline only, on a worker. Nothing here runs on the audio thread.
namespace acidulous::audio::takescore {

/** The onset strength's step: 1 ms at 48 kHz. */
constexpr int32_t kHop = 48;

/** One take of a stack, as region frames: frame [at] is source frame `offset + at`. */
struct TakeAudio {
    const Reel::Source *source = nullptr;
    int32_t offset = 0;
    int32_t start = 0; // region frames it has audio for
    int32_t end = 0;
};

/**
 * What one take scored over one phrase. [score] is 0-100, or NaN when the
 * take has no audio over most of the phrase. The rest say why: the mean
 * pitch error in cents (NaN when the phrase isn't pitched), the mean timing
 * error in ms (NaN with no hits), the clipped peaks and the signal over the
 * take's own floor in dB.
 */
struct Score {
    float score = 0.0f;
    float pitchCents = 0.0f;
    float timingMs = 0.0f;
    float clips = 0.0f;
    float snrDb = 0.0f;
};

/** The weights of the four parts of a score. A part that can't be measured drops out. */
constexpr float kPitchWeight = 0.30f;
constexpr float kTimingWeight = 0.25f;
constexpr float kClipWeight = 0.30f;
constexpr float kNoiseWeight = 0.15f;

/** What a part costs at its worst: errors at or past these score nothing for that part. */
constexpr float kWorstCents = 40.0f;
constexpr float kWorstMs = 40.0f;
constexpr float kWorstClips = 2.0f;
/** Signal over the floor: this much or more is clean, 25 dB less is as noisy as it gets. */
constexpr float kCleanDb = 40.0f;

struct Stack {
    std::vector<TakeAudio> takes;
    std::vector<int32_t> cuts; // phrase i is [cuts[i], cuts[i + 1])
    /** The song's sixteenths in region frames, step <= 0 for no grid. */
    double gridStep = 0.0;
    double gridPhase = 0.0;
    float sampleRate = 48000.0f;
};

/** Every take over every phrase, take-major: [take * phrases + phrase]. */
std::vector<Score> scoreStack(const Stack &stack);

/**
 * The rise in loudness every [kHop] frames of [x], in dB, above a high-pass
 * so rumble doesn't count. One value per hop, the first at frame 0.
 */
std::vector<float> onsetStrength(const float *x, int32_t frames, float sampleRate);

/** The hits in an onset strength, in frames of the audio it came from. */
std::vector<int32_t> hitsOf(const std::vector<float> &strength);

/**
 * How many frames [dbl] sounds after [lead], from their hits over the same
 * stretch, up to [maxLag] either way. NaN when fewer than half the hits
 * agree on one gap.
 */
float lagOf(const float *lead, const float *dbl, int32_t frames, int32_t maxLag, float sampleRate);

/**
 * For lining up a double: the shift that puts each phrase of [dbl] on
 * [lead], in frames (negative is earlier), NaN where it can't tell. Both
 * are read as they sound, so a comped lead is followed as it plays.
 */
std::vector<float> lineUp(const Reel::Region &lead, const Reel::Region &dbl, const std::vector<int32_t> &cuts,
                          int32_t maxShift, float sampleRate);

} // namespace acidulous::audio::takescore
