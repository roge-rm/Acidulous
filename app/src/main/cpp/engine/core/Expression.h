#pragma once
#include <cmath>
#include <cstdint>

// The domain per-note expression is stored in, shared by everything that
// records it, writes it to a file, or plays it back.
//
// Three curves belong to a note: bend, pressure and slide. All three are
// kept as 0..1, the same normalised domain an automation lane uses, so the
// document has one convention rather than three and the editor can draw them
// with the code it already has.
//
// **Bend is stored in semitones, scaled**, and not as the fourteen bits that
// arrived. A controller's bend range is a property of the controller: one set
// to 24 semitones and one set to the specification's 48 send the same bytes
// for different music, so keeping the bytes would mean a take recorded on one
// desk played back wrong on another. Scaling to the
// specification's own maximum fixes the meaning of what is written down, and
// costs nothing - a float over +/-48 semitones resolves to well under a cent.
namespace acidulous {

/** Full scale, either way. MPE's default bend range, and its widest. */
constexpr float kExprBendSemis = 48.0f;

/** Which of a note's three curves. Ordinals reach the document as an index. */
enum class Expr : int32_t { Bend = 0, Pressure = 1, Timbre = 2, Count = 3 };

/** Signed semitones to 0..1, with the centre at a half. */
inline float exprBendTo01(float semitones) {
    const float v = 0.5f + semitones / (2.0f * kExprBendSemis);
    return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
}

/** And back. */
inline float exprBendFrom01(float v01) { return (v01 - 0.5f) * 2.0f * kExprBendSemis; }

/** A seven-bit controller value to 0..1, and back. */
inline float expr7To01(uint8_t v) { return static_cast<float>(v) / 127.0f; }
inline uint8_t expr7From01(float v01) {
    const float v = v01 * 127.0f + 0.5f;
    return static_cast<uint8_t>(v < 0.0f ? 0.0f : (v > 127.0f ? 127.0f : v));
}

/** The value a curve says nothing at: centred bend, no pressure, no slide. */
inline float exprNeutral(Expr kind) { return kind == Expr::Bend ? 0.5f : 0.0f; }

} // namespace acidulous
