#pragma once
#include <cstddef>
#include <cmath>
#include <cstdint>

// Finding the voice that is playing a note.
//
// Thirteen machines keep an array of voices and every one of those voices
// carries the note it was started with, so per-note expression needs this
// exactly once rather than thirteen times. A voice counts only while it is
// still held: a note that has been released may still be ringing out, and a
// finger lifted from one key must not bend the tail of another.
namespace acidulous {

/** A voice's own bend as a frequency multiplier, for machines that scale. */
template <typename Voice>
inline float noteBendMul(const Voice &v) {
    return v.bend == 0.0f ? 1.0f : std::exp2(v.bend / 12.0f);
}

template <typename Voice, size_t N>
Voice *voiceForNote(Voice (&voices)[N], uint8_t note) {
    for (size_t i = 0; i < N; ++i) {
        if (voices[i].used && voices[i].gate && voices[i].note == note) return &voices[i];
    }
    return nullptr;
}


/**
 * A finger's pressure as a voice should use it: its own if it sent any, the
 * channel's otherwise, glided a block at a time.
 *
 * Pressure arrives in a hundred and twenty-eight steps and is applied once a
 * block, and a level stepped per block is the onset-click fault all over
 * again - so it is glided, about five milliseconds at a 64-frame block. No
 * pressure is exactly zero out, so a patch played without it renders
 * bit-identical to before.
 */
inline float glidePressure(float &state, float own, float channel) {
    const float target = own >= 0.0f ? own : channel;
    state += (target - state) * 0.3f;
    if (state < 1e-5f && target == 0.0f) state = 0.0f;
    return state;
}

} // namespace acidulous
