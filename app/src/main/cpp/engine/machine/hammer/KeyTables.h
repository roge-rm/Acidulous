#pragma once
// What each key of each instrument is, measured.
//
// Generated anchors: tools/hammer_reference/tables.py --emit <name> <set>
// prints a table to paste here. Numbers only, under anonymous names; what the
// recordings were is in the untracked docs/lineage.md. Keys.h reads between
// the anchors.
namespace acidulous::machine::hammer {

/** A value the recordings couldn't give. */
constexpr float kGap = -1e30f;

/** One measured key; kGap where there's nothing. */
struct Anchor {
    int key;
    /** Inharmonicity: partial n at n·f0·sqrt(1 + B·n²). */
    float B;
    /** The tuning against equal temperament, cents: the instrument's stretch. */
    float cents;
    /**
     * T60s in seconds of the prompt sound (the first slope) and the
     * aftersound (the second), for the fundamental, partials 2-4 and 5-10.
     */
    float prompt1, after1, prompt3, after3, prompt7, after7;
    /** How far the prompt sound falls before the aftersound takes over, dB. */
    float kneeDb;
    /** How far the partials' levels wobble around their decay: the beating, dB. */
    float wobbleDb;
    /** How far up the strike reaches at a middle velocity, Hz. */
    float reachHz;
    /** Brightness over the first 150 ms, as a multiple of f0. */
    float brightF0;
};

/**
 * Factors on a key's design values: what the model needs, on top of what the
 * recordings measured, for its renders to measure the same
 * (tools/hammer_reference/calibrate.py).
 */
struct Adjust {
    int key;
    float B, prompt1, after1, prompt3, after3, prompt7, after7, unison, contact, level, knock;
};

/** How the strike changes per 10 velocity steps in a register: brightness and reach, percent. */
struct VelocityShape {
    float brightPct, reachPct;
};

// The measured B levels off near 6e-3 at the top: that's where partials 2
// and 3 are, and they're what's heard.
// GrandA: measured from a reference recording by tools/hammer_reference/tables.py --emit.
// Columns: key, B, cents, t60 1 prompt, t60 1 after, t60 2-4 prompt, t60 2-4 after, t60 5-10 prompt, t60 5-10 after, knee dB, wobble dB, reach Hz, bright x f0.
constexpr Anchor kGrandA[] = {
    {21, 2.716e-04f, -24.65f, 66.02f, 71.96f, 21.22f, 36.42f, 14.86f, 29.91f, 4.535f, 3.321f, 621.8f, 5.566f},
    {24, 1.859e-04f, -20.37f, 56.67f, 71.63f, 20.58f, 37.92f, 15.48f, 32.73f, 8.756f, 3.501f, 644.3f, 4.898f},
    {28, 1.273e-04f, -15.55f, 46.24f, 70.23f, 19.49f, 39.24f, 15.9f, 35.69f, 14.0f, 3.722f, 677.5f, 4.159f},
    {33, 9.540e-05f, -10.77f, 35.86f, 67.01f, 17.83f, 39.68f, 15.72f, 37.7f, 19.95f, 3.97f, 725.0f, 3.428f},
    {36, 8.778e-05f, -8.5f, 30.79f, 64.39f, 16.71f, 39.27f, 15.25f, 37.86f, 23.19f, 4.104f, 757.0f, 3.071f},
    {40, 8.627e-05f, -6.065f, 25.12f, 60.23f, 15.13f, 37.98f, 14.23f, 36.83f, 27.12f, 4.264f, 804.4f, 2.671f},
    {45, 9.643e-05f, -3.83f, 19.49f, 54.19f, 13.08f, 35.28f, 12.48f, 33.73f, 31.43f, 4.436f, 871.9f, 2.268f},
    {48, 1.096e-04f, -2.841f, 16.74f, 50.26f, 11.85f, 33.19f, 11.27f, 31.09f, 33.69f, 4.524f, 917.4f, 2.069f},
    {52, 1.382e-04f, -1.84f, 13.66f, 44.85f, 10.25f, 30.0f, 9.554f, 26.98f, 36.32f, 4.623f, 984.9f, 1.843f},
    {57, 2.001e-04f, -0.951f, 10.6f, 38.04f, 8.375f, 25.61f, 7.434f, 21.42f, 38.99f, 4.719f, 1081.0f, 1.612f},
    {60, 2.583e-04f, -0.5308f, 9.108f, 34.06f, 7.335f, 22.9f, 6.244f, 18.13f, 40.26f, 4.761f, 1147.0f, 1.497f},
    {64, 3.735e-04f, -0.009937f, 7.437f, 28.99f, 6.066f, 19.34f, 4.811f, 14.04f, 41.58f, 4.799f, 1244.0f, 1.366f},
    {69, 6.085e-04f, 0.7272f, 5.773f, 23.19f, 4.684f, 15.17f, 3.321f, 9.662f, 42.62f, 4.819f, 1383.0f, 1.231f},
    {72, 8.198e-04f, 1.295f, 4.96f, 20.04f, 3.966f, 12.89f, 2.596f, 7.505f, 42.91f, 4.815f, 1478.0f, 1.164f},
    {76, 1.214e-03f, 2.291f, 4.051f, 16.27f, 3.135f, 10.17f, 1.818f, 5.183f, 42.92f, 4.793f, 1620.0f, 1.087f},
    {81, 1.934e-03f, 4.07f, 3.146f, 12.27f, 2.287f, 7.332f, 1.113f, 3.094f, 42.32f, 4.736f, 1825.0f, 1.01f},
    {84, 2.499e-03f, 5.502f, 2.703f, 10.24f, 1.872f, 5.922f, 0.8096f, 2.206f, 41.63f, 4.687f, 1966.0f, 0.9722f},
    {88, 3.389e-03f, 7.929f, 2.209f, 7.931f, 1.414f, 4.368f, 0.5151f, 1.359f, 40.33f, 4.603f, 2176.0f, 0.9302f},
    {93, 4.585e-03f, 11.94f, 1.716f, 5.638f, 0.9753f, 2.893f, 0.2798f, 0.7032f, 38.09f, 4.47f, 2484.0f, 0.8902f},
    {96, 5.224e-03f, 14.96f, 1.475f, 4.541f, 0.7716f, 2.221f, 0.1894f, 0.4603f, 36.42f, 4.375f, 2696.0f, 0.8722f},
    {100, 5.793e-03f, 19.77f, 1.205f, 3.356f, 0.5572f, 1.531f, kGap, kGap, 33.81f, 4.23f, 3016.0f, 0.8548f},
    {105, 5.785e-03f, 27.21f, 0.9365f, 2.249f, 0.3632f, 0.9314f, kGap, kGap, 29.93f, 4.021f, 3486.0f, 0.8428f},
    {108, 5.340e-03f, 32.52f, 0.805f, 1.749f, 0.2777f, 0.6797f, kGap, kGap, 27.28f, 3.88f, 3813.0f, 0.8408f},
};
// Per 10 velocity steps, keys 21-47 / 48-71 / 72-108: brightness % and reach %.
constexpr VelocityShape kGrandAVelocity[3] = {
    {4.01f, 7.99f},
    {2.68f, 7.59f},
    {1.86f, 1.81f},
};

// GrandAAdjust: tools/hammer_reference/calibrate.py, --fields B,contact then --fields
// level; contact then x1.3 up to C4 (down to x1 at C6) for the band spectrum; level again
// with the knock in: what the model needs, on top of the measurements, to measure like the
// reference.
// prompt1: how far the fundamental has fallen at 0.5 and 1 s, against the
// recordings (the two-slope fit missed its first fast fall in the middle).
// knock: the board's thump below each note's fundamental in its first 40 ms (the
// first 10 ms in the bass), against the recordings.
// Columns: key, B, prompt1, after1, prompt3, after3, prompt7, after7, unison, contact, level, knock.
constexpr Adjust kGrandAAdjust[] = {
    {21, 0.9151f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.5849f, 2.859f, 0.008536f},
    {24, 0.9266f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.5684f, 2.957f, 0.008489f},
    {28, 0.9413f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.5502f, 2.758f, 0.03334f},
    {33, 0.9583f, 1.321f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.5338f, 2.416f, 1.228f},
    {36, 0.9676f, 1.745f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.5369f, 2.193f, 1.924f},
    {40, 0.9789f, 0.5599f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.5442f, 1.903f, 1.095f},
    {45, 0.9909f, 0.3714f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.5584f, 1.578f, 0.06387f},
    {48, 0.9969f, 0.2451f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.5698f, 1.413f, 0.172f},
    {52, 1.003f, 0.2815f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.5888f, 1.233f, 0.3565f},
    {57, 1.008f, 0.3865f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.6189f, 1.068f, 0.2859f},
    {60, 1.01f, 0.3708f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.6409f, 1.0f, 0.1016f},
    {64, 1.01f, 0.3752f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.6491f, 0.9466f, 0.02092f},
    {69, 1.007f, 0.4598f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.6642f, 0.9433f, 0.01315f},
    {72, 1.003f, 0.543f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.6756f, 0.9798f, 0.02093f},
    {76, 0.9961f, 0.6481f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.6941f, 1.089f, 0.007926f},
    {81, 0.9835f, 0.7354f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.7223f, 1.367f, 0.0189f},
    {84, 0.9739f, 0.809f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.7421f, 1.448f, 0.07119f},
    {88, 0.9588f, 1.06f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.8125f, 1.832f, 0.1497f},
    {93, 0.9363f, 1.296f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.9184f, 2.144f, 0.05433f},
    {96, 0.9208f, 0.9864f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.9932f, 2.102f, 0.01749f},
    {100, 0.898f, 0.9924f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.109f, 2.153f, 0.05319f},
    {105, 0.8661f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.284f, 1.863f, 0.02197f},
    {108, 0.8453f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.409f, 1.738f, 0.04042f},
};

} // namespace acidulous::machine::hammer
