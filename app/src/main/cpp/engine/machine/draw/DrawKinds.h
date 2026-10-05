#pragma once
#include <cstdint>
#include <engine/machine/draw/FreeReed.h>

// What makes each kind of free reed instrument itself: its reeds, how the
// player's breath or the bellows feed them, and what the sound passes
// through on its way out. Shared by Draw and by tools/draw_tune, which works
// out DrawTuning.h from the reeds here.
namespace acidulous::machine::draw {

enum Kind : int32_t {
    Diatonic = 0, Chromatic, TremoloHarp, OctaveHarp,
    Accordion, Bandoneon, Concertina, Melodica, Harmonium,
    Sheng, Sho, Khaen, PitchPipe,
    kKinds
};

/** The reed makes there are; a kind uses one. DrawTuning.h has a table for each. */
enum Make : int32_t { HarpReed = 0, AccordionReed, MelodicaReed, kMakes };

struct KindVoice {
    int32_t make;
    /** How freely the breath or bellows feed a reed's cell, m^3/s a Pa. */
    float supply;
    /** The pressure a reed is blown at by default, Pa, and the most. */
    float pressure, most;
    /** The body: a low-pass it radiates through (Hz) and a resonance on top: Hz, width Hz, lift. */
    float lowPass, bodyHz, bodyWidth, bodyLift;
    /** Level, so every kind sits at the same loudness. */
    float level;
    /** How unsteady the breath or bellows are: the pressure's slow wander, as a share of it. */
    float wander;
    /** Reeds a note sounds when the reeds knob is on auto: an accordion's are two, tuned apart. */
    int32_t reeds;
};

// A harmonica's reed is Millot & Baumann's (hole 4 of a G harp). Accordion
// reeds are longer and sit in a block; a melodica's are in a closed body
// blown through a tube. Kinds not modelled yet borrow the nearest.
inline constexpr ReedMake kMakes_[kMakes] = {
    // harmonica
    {12.95e-3f, 2.1e-3f, 2.0000e-03f, 2.0000e-05f, 5.5738e+01f, 2.1120e+00f, 700.0f, 95.0f, 2.3438e+00f, 8.0000e-01f, 1.6000e-02f, 30e-6f, 1.0000e+00f, 7.0400e-01f, 0.022f},
    // accordion: longer, a little stiffer, set for bellows pressure, a bigger cell
    {18.0e-3f, 2.6e-3f, 2.0000e-03f, 5.5000e-05f, 55.0f, 2.4640e+00f, 500.0f, 1.7600e+02f, 3.0000e+00f, 3.1818e-01f, 14e-3f, 40e-6f, 1.0000e+00f, 5.0000e-01f, 0.008f},
    // melodica: harmonica-sized reeds in a body
    {13.5e-3f, 2.2e-3f, 1.9800e-03f, 3.1250e-05f, 50.0f, 1.32f, 650.0f, 90.0f, 1.8750e+00f, 0.45f, 1.9200e-02f, 30e-6f, 9.6000e-01f, 2.5000e-01f, 0.03f},
};

inline constexpr KindVoice kKindVoices[kKinds] = {// make         supply  pressure most    lowPass   bodyHz    bodyWidth lift  level wander reeds
    {HarpReed, 5e-6f, 600.0f, 1100.0f, 16000.0f, 5000.0f, 1800.0f, 1.2000e+00f, 0.85f, 0.12f, 1},  // diatonic
    {HarpReed,      5e-6f,  600.0f, 1100.0f, 16000.0f, 5000.0f, 1800.0f, 0.6f, 0.97f, 0.06f, 1},  // chromatic
    {HarpReed,      5e-6f,  600.0f, 1100.0f, 16000.0f, 5000.0f, 1800.0f, 0.6f, 0.97f, 0.06f, 1},  // tremolo harp
    {HarpReed,      5e-6f,  600.0f, 1100.0f, 16000.0f, 5000.0f, 1800.0f, 0.6f, 0.97f, 0.06f, 1},  // octave harp
    {AccordionReed, 2e-5f, 450.0f, 900.0f, 2.8000e+03f, 2.1120e+03f, 900.0f, 2.5000e-01f, 0.7f, 0.05f, 2},  // accordion
    {AccordionReed, 2e-5f, 450.0f, 900.0f, 2.8000e+03f, 2.1120e+03f, 900.0f, 4.0000e-01f, 0.7f, 0.05f, 2},  // bandoneon
    {AccordionReed, 2e-5f, 450.0f, 900.0f, 2.8000e+03f, 2.1120e+03f, 900.0f, 4.0000e-01f, 1.0f, 0.05f, 1},  // concertina
    {MelodicaReed,  4e-6f,  550.0f, 1000.0f, 2600.0f,  1000.0f, 1200.0f, 0.8f, 0.82f, 0.06f, 1},  // melodica
    {AccordionReed, 2e-5f, 450.0f, 900.0f, 2.8000e+03f, 2.1120e+03f, 900.0f, 4.0000e-01f, 1.0f, 0.05f, 1},  // harmonium
    {HarpReed,      5e-6f,  600.0f, 1100.0f, 16000.0f, 5000.0f, 1800.0f, 0.6f, 0.6f, 0.06f, 1},  // sheng
    {HarpReed,      5e-6f,  600.0f, 1100.0f, 16000.0f, 5000.0f, 1800.0f, 0.6f, 0.6f, 0.06f, 1},  // sho
    {HarpReed,      5e-6f,  600.0f, 1100.0f, 16000.0f, 5000.0f, 1800.0f, 0.6f, 0.6f, 0.06f, 1},  // khaen
    {HarpReed,      5e-6f,  600.0f, 1100.0f, 16000.0f, 5000.0f, 1800.0f, 0.6f, 0.6f, 0.06f, 1},  // pitch pipe
};

} // namespace acidulous::machine::draw
