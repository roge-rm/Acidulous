// Plays a factory patch, writes a wav, and prints what it measures.
//
// It sets up a machine the same way the app does, plays it a phrase that
// suits it, writes a file to listen to and prints eight measurements. It's
// for tuning patches without a device. Nothing here passes or fails and
// tools/all_tests.sh doesn't run it. The bank checks are in bank_test.
//
//   audition params  <Machine|fx.Effect>
//   audition list    [<Machine>]
//   audition play    <Machine> <Patch> [options]
//   audition bank    <Machine> [options]
//
// See tools/patchbank.h for the bank format and tools/audition_material.h for
// the material loaded into machines that need input.

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <memory>
#include <sys/stat.h>
#include <set>
#include <string>
#include <vector>

#include <engine/core/Constants.h>
#include <engine/core/InputBus.h>
#include <engine/format/WavReader.h>
#include <engine/format/WavWriter.h>
#include <engine/effect/EffectRegistry.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/cumulus/Cloud.h>
#include <engine/machine/cumulus/Cumulus.h>
#include <engine/machine/formulate/Program.h>
#include <engine/machine/nexus/Graph.h>
#include <engine/machine/nexus/Modules.h>
#include "audition_settings.h"

#include "audition_kit.h"
#include "audition_material.h"
#include "audition_measure.h"
#include "patchbank.h"

using namespace acidulous;
using namespace acidulous::audition;

namespace {

constexpr int32_t kBlock = kBlockFrames;
std::string gBankDir;
// The repo root, for material kept in files rather than headers.
std::string gRootDir = ".";
std::string gOutDir = "build/audition";

// --- Phrases -----------------------------------------------------------------

struct NoteEvent {
    int64_t frame;
    uint8_t note;
    uint8_t velocity; // 0 = note off
};

struct Phrase {
    std::vector<NoteEvent> events;
    int64_t frames = 0;   // how long to render, including the tail
    int64_t lastOff = 0;  // where the tail starts, for measuring it
    int measuredNote = 0; // what the pitch reading should be compared against
    /**
     * Keep rendering past `frames` until the sound has actually stopped. A
     * phrase knows when the playing stops but not how long the patch rings,
     * and a fixed tail cut long sounds off.
     *
     * Off for the per-voice probe, which gives each voice the same window so
     * they can be compared.
     */
    bool ringOut = true;
};

int64_t secondsToFrames(float s) { return static_cast<int64_t>(kSr * s); }

/** The longest a ring-out may add. 12 seconds covers the longest setting of
 *  any machine and still stops a patch that never goes quiet. */
constexpr float kMaxRingOut = 12.0f;

/** How long each voice of a kit gets to itself, in the `voices` phrase. */
constexpr float kVoiceWindow = 1.0f;

/** Adds a note and its release; returns the release frame. */
int64_t hit(Phrase &p, float atSeconds, float forSeconds, int note, int vel) {
    const int64_t on = secondsToFrames(atSeconds);
    const int64_t off = secondsToFrames(atSeconds + forSeconds);
    p.events.push_back({on, static_cast<uint8_t>(note), static_cast<uint8_t>(vel)});
    p.events.push_back({off, static_cast<uint8_t>(note), 0});
    return off;
}

/**
 * The phrases. A pad and a bass need different things to judge them: a pad
 * wants a long held note, a bass wants eighths with a few overlapping so glide
 * and voice stealing show. The bank file says which phrase each patch wants.
 *
 * `note` is always rendered as well, because the measurements are taken from
 * it so patches can be compared.
 */
/** The notes a patch is played in, from the bank's `range=`; -1 for none. */
struct Range {
    int low = -1, high = -1;
    bool set() const { return low >= 0 && high > low; }
};

Phrase buildPhrase(const std::string &kind, int note, int velocity, float bpm, const Kit *kit,
                   Range range = Range()) {
    Phrase p;
    p.measuredNote = note;
    const float beat = 60.0f / bpm;

    if (kind == "vocode") {
        // For the vocoder. It's only heard while the carrier plays, so the
        // chords are held for the whole of the speech. Four chords so you can
        // hear the same words over different harmony.
        //
        // Cm, Ab, Eb, Bb, three seconds each, voiced root-fifth-octave so
        // every band has something to shape. 12 seconds of carrier under an
        // 11 second recording.
        static const int kChords[][3] = {{0, 7, 12}, {-4, 3, 8}, {-9, -2, 3}, {-2, 5, 10}};
        for (int c = 0; c < 4; ++c) {
            const float at = static_cast<float>(c) * 3.0f;
            for (const int semi : kChords[c]) {
                p.lastOff = hit(p, at, 3.05f, note + semi, velocity);
            }
        }
        p.frames = p.lastOff + secondsToFrames(2.0f);
    } else if (kind == "hold") {
        p.lastOff = hit(p, 0.0f, 8.0f, note, velocity);
        p.frames = p.lastOff + secondsToFrames(4.0f);
    } else if (kind == "bass") {
        // Two bars of eighths. Two overlap to show glide and mono voice
        // stealing, and a few are accented.
        static const int kSteps[] = {0, 0, 12, 0, 7, 0, 3, 5, 0, 0, 12, 10, 7, 0, 3, 0};
        float t = 0.0f;
        for (int i = 0; i < 16; ++i) {
            const bool tie = i == 4 || i == 11;                 // overlaps the next
            const bool accent = i == 0 || i == 6 || i == 8;
            p.lastOff = hit(p, t, beat * 0.5f * (tie ? 1.4f : 0.85f), note + kSteps[i], accent ? 120 : 90);
            t += beat * 0.5f;
        }
        p.frames = p.lastOff + secondsToFrames(2.0f);
    } else if (kind == "acid") {
        // Two bars of sixteenths with rests, slides and accents. An acid
        // line's sound comes from what the filter does between notes, so a
        // plain bass line doesn't show it.
        //
        // -1 is a rest. A sliding step lasts 1.6 sixteenths so it's still
        // sounding when the next one starts.
        static const int kNote[32] = {0,  -1, 0,  12, -1, 0,  -1, 3,
                                      -1, 0,  0,  -1, 7,  -1, 10, 12,
                                      0,  -1, 0,  12, -1, 0,  -1, 3,
                                      -1, 5,  -1, 3,  0,  -1, 0,  -1};
        static const bool kSlide[32] = {false, false, false, true,  false, false, false, true,
                                        false, false, false, false, true,  false, false, false,
                                        false, false, false, true,  false, false, false, true,
                                        false, true,  false, false, false, false, false, false};
        static const bool kAccent[32] = {true,  false, false, false, false, true,  false, false,
                                         false, false, true,  false, false, false, false, true,
                                         true,  false, false, false, false, true,  false, false,
                                         false, false, false, false, true,  false, false, false};
        const float step = beat * 0.25f;
        for (int i = 0; i < 32; ++i) {
            if (kNote[i] < 0) continue;
            p.lastOff = hit(p, static_cast<float>(i) * step, step * (kSlide[i] ? 1.6f : 0.8f),
                            note + kNote[i], kAccent[i] ? 122 : 88);
        }
        p.frames = p.lastOff + secondsToFrames(2.0f);
    } else if (kind == "chord") {
        static const int kMaj7[] = {0, 4, 7, 11};
        static const int kMin7[] = {-3, 0, 4, 7};
        for (int i = 0; i < 4; ++i) {
            // Staggered slightly, like a real hand.
            p.lastOff = hit(p, 0.0f + 0.02f * static_cast<float>(i), 3.0f, note + 12 + kMaj7[i], velocity);
        }
        for (int i = 0; i < 4; ++i) {
            p.lastOff = hit(p, 3.4f + 0.02f * static_cast<float>(i), 3.0f, note + 12 + kMin7[i], velocity);
        }
        p.frames = p.lastOff + secondsToFrames(3.0f);
    } else if (kind == "tune") {
        // One melody, an octave and a fifth wide, kept inside the
        // instrument's range. Most problems show at one end of a range
        // (slow attack, weak fundamental, tuning drift), so the tune covers
        // both ends, but it never goes outside the range where the model
        // isn't meant to play.
        //
        // A phrase low, an answer high, in Dorian. The bottom, top and last
        // notes are held, two repeated notes show the tonguing, and one
        // overlapping pair shows slurs and mono voices. It ends where it
        // started.
        struct Step { float at; float len; int step; int vel; };
        static const Step kTune[] = {
            { 0.0f, 0.90f,  0,  96}, { 1.0f, 0.45f,  3,  90}, { 1.5f, 0.45f,  5,  92},
            { 2.0f, 0.90f,  7, 100}, { 3.0f, 0.90f,  5,  94}, { 4.0f, 1.90f,  3,  98},
            { 6.0f, 0.45f,  7,  96}, { 6.5f, 0.45f, 10, 100}, { 7.0f, 0.90f, 12, 104},
            { 8.0f, 0.45f, 15, 100}, { 8.5f, 0.45f, 17, 104}, { 9.0f, 1.40f, 19, 110},
            {10.5f, 0.45f, 17, 100}, {11.0f, 0.90f, 15,  98}, {12.0f, 0.40f, 12,  92},
            {12.5f, 0.40f, 12,  92}, {13.0f, 1.20f, 10,  96}, {14.0f, 0.90f,  7,  94},
            {15.0f, 2.50f,  0, 100},
        };
        constexpr int kSpan = 19;
        // Where the tune sits. With a range it stays inside it, centred on
        // the note when there's room so --note still moves it. Without a
        // range it's centred on the note.
        int bottom = note - 10;
        if (range.set()) {
            bottom = range.high - range.low >= kSpan ? std::clamp(note - 10, range.low, range.high - kSpan)
                                                     : range.low;
        }
        for (const Step &st : kTune) {
            int n = bottom + st.step;
            // If the range is narrower than the tune, high notes drop an
            // octave to stay inside it.
            if (range.set()) while (n > range.high) n -= 12;
            p.lastOff = std::max(p.lastOff, hit(p, beat * st.at, beat * st.len, n, st.vel));
        }
        p.frames = p.lastOff + secondsToFrames(2.5f);
    } else if (kind == "arp") {
        // A broken chord in sixteenths, up and down over a progression, so a
        // pluck's attack is heard many times and its tail runs into the next
        // note.
        static const int kRoot[4] = {0, -4, -7, -5};
        static const int kShape[8] = {0, 4, 7, 12, 16, 12, 7, 4};
        float t = 0.0f;
        for (int bar = 0; bar < 4; ++bar) {
            for (int i = 0; i < 8; ++i) {
                p.lastOff = hit(p, t, beat * 0.25f * 0.9f, note + kRoot[bar] + kShape[i], velocity);
                t += beat * 0.25f;
            }
        }
        p.frames = p.lastOff + secondsToFrames(2.0f);
    } else if (kind == "pad") {
        // Four chords, each held four seconds and overlapping the next by
        // half a second. A pad is judged on how it moves while held and how
        // one chord's release sits under the next one's attack.
        static const int kChord[4][4] = {
            {0, 7, 12, 15},   // i
            {-4, 5, 8, 12},   // VI
            {-7, 4, 7, 12},   // III
            {-5, 2, 7, 11},   // VII
        };
        for (int c = 0; c < 4; ++c) {
            const float at = static_cast<float>(c) * 3.5f;
            for (int n = 0; n < 4; ++n) {
                // Staggered slightly, like a real hand.
                p.lastOff = hit(p, at + 0.025f * static_cast<float>(n), 4.0f,
                                note + kChord[c][n], velocity);
            }
        }
        p.frames = p.lastOff + secondsToFrames(4.0f);
    } else if (kind == "lead") {
        // A lead line: two long notes to hear the tone settle, a fast run,
        // and a leap at the end that a mono voice has to glide across.
        struct Step { float at; float len; int step; int vel; };
        static const Step kLine[] = {
            {0.00f, 1.10f,  0, 100}, {1.20f, 0.45f,  3,  92}, {1.70f, 0.45f,  5,  96},
            {2.20f, 1.40f,  7, 108}, {3.80f, 0.22f, 12, 100}, {4.05f, 0.22f, 10,  96},
            {4.30f, 0.22f,  8,  98}, {4.55f, 0.22f,  7, 100}, {4.80f, 0.22f,  5,  94},
            {5.05f, 0.22f,  3,  96}, {5.30f, 0.90f,  2, 104}, {6.40f, 0.45f,  7,  98},
            {6.90f, 0.45f, 10, 102}, {7.40f, 2.20f, 14, 112}, {9.80f, 1.80f,  0,  96},
        };
        for (const Step &st : kLine) p.lastOff = hit(p, st.at, st.len, note + st.step, st.vel);
        p.frames = p.lastOff + secondsToFrames(3.0f);
    } else if (kind == "keys") {
        // A root in the left hand and chords on the off beats above it.
        // Shows whether the machine has enough voices for both and how
        // note-offs behave when they overlap.
        static const int kRoot[4] = {0, -4, -7, -5};
        static const int kStab[3] = {12, 16, 19};
        for (int bar = 0; bar < 4; ++bar) {
            const float at = static_cast<float>(bar) * beat * 2.0f;
            p.lastOff = hit(p, at, beat * 1.8f, note + kRoot[bar] - 12, 104);
            for (int s = 0; s < 3; ++s) {
                const float when = at + beat * (0.5f + 0.5f * static_cast<float>(s));
                for (int n = 0; n < 3; ++n) {
                    p.lastOff = hit(p, when + 0.02f * static_cast<float>(n), beat * 0.4f,
                                    note + kRoot[bar] + kStab[n], s == 0 ? 100 : 84);
                }
            }
        }
        p.frames = p.lastOff + secondsToFrames(3.0f);
    } else if (kind == "bell") {
        // A bell is mostly its tail, so this leaves room between strikes:
        // about a second and a half apart, one pair together to hear two
        // tails beat, and the last one left to ring out.
        struct Hit { float at; int step; int vel; };
        static const Hit kHits[] = {
            {0.0f, 0, 110}, {1.6f, 7, 96}, {3.2f, 12, 104}, {4.8f, 4, 92},
            {6.4f, 0, 100}, {6.55f, 7, 88}, {8.4f, 16, 112},
        };
        for (const Hit &h : kHits) p.lastOff = hit(p, h.at, 0.25f, note + h.step, h.vel);
        p.frames = p.lastOff + secondsToFrames(5.0f);
    } else if (kind == "mallets" && kit != nullptr) {
        // Plays every pad like a tuned instrument. `beat` only uses some
        // voices, and Resonance kits are often tuned (marimba, bells, steel
        // pan), so this plays up and down, with a roll and with pairs struck
        // together to hear them ring into each other.
        struct Step { float at; int voice; int vel; };
        static const Step kPattern[] = {
            // up the kit, one at a time
            {0.00f, 0, 112}, {0.25f, 1, 96}, {0.50f, 2, 104}, {0.75f, 3, 92},
            {1.00f, 4, 108}, {1.25f, 5, 96}, {1.50f, 6, 104}, {1.75f, 7, 100},
            // and back down, quieter, so the decay of each is heard under it
            {2.00f, 7,  88}, {2.25f, 5, 84}, {2.50f, 3,  88}, {2.75f, 1, 84},
            // a roll on one pad, struck again before it has finished
            {3.00f, 2, 104}, {3.12f, 2, 84}, {3.25f, 2, 96}, {3.37f, 2, 80},
            {3.50f, 2, 108}, {3.62f, 2, 84}, {3.75f, 2, 92}, {3.87f, 2, 78},
            // two together, twice, where the coupling is loudest
            {4.25f, 0, 110}, {4.25f, 6, 100},
            {4.75f, 1, 106}, {4.75f, 7,  96},
            // and one struck hard and left to ring
            {5.50f, 4, 120},
        };
        const int n = static_cast<int>(kit->voices.size());
        for (const Step &st : kPattern) {
            if (st.voice >= n) continue;
            p.lastOff = std::max(p.lastOff, hit(p, beat * st.at, 0.08f,
                                                kit->baseNote + st.voice, st.vel));
        }
        p.frames = p.lastOff + secondsToFrames(5.0f);
    } else if (kind == "chip") {
        // Fast, short and jumpy, the way chip music is written. The tables
        // run at 50 steps a second, so long or slurred notes hide them. One
        // voice carries both harmony and tune, hence the leaps.
        //
        // Mostly staccato sixteenths, with two held notes at the end so a
        // duty sweep or looping volume table can be heard.
        struct Step { float at; float len; int step; int vel; };
        static const Step kLine[] = {
            { 0.00f, 0.20f,  0, 116}, { 0.25f, 0.20f, 12, 100}, { 0.50f, 0.20f,  7, 104},
            { 0.75f, 0.20f, 12,  96}, { 1.00f, 0.20f,  3, 110}, { 1.25f, 0.20f, 15, 100},
            { 1.50f, 0.20f, 10, 104}, { 1.75f, 0.20f, 15,  96}, { 2.00f, 0.20f,  5, 112},
            { 2.25f, 0.20f, 17, 100}, { 2.50f, 0.20f, 12, 104}, { 2.75f, 0.20f, 17,  96},
            { 3.00f, 0.45f,  7, 114}, { 3.50f, 0.20f, 19, 104}, { 3.75f, 0.20f, 12,  98},
            // the same shape a fourth down, for tables that depend on the note
            { 4.00f, 0.20f, -5, 116}, { 4.25f, 0.20f,  7, 100}, { 4.50f, 0.20f,  2, 104},
            { 4.75f, 0.20f,  7,  96}, { 5.00f, 0.20f, -2, 110}, { 5.25f, 0.20f, 10, 100},
            { 5.50f, 0.20f,  5, 104}, { 5.75f, 0.20f, 10,  96}, { 6.00f, 0.45f,  0, 112},
            { 6.50f, 0.20f, 12, 104}, { 6.75f, 0.20f,  7,  98},
            // two long notes for duty sweeps and looping volume tables
            { 7.00f, 1.40f, 12, 118}, { 8.50f, 1.90f,  0, 110},
        };
        for (const Step &st : kLine) {
            p.lastOff = std::max(p.lastOff, hit(p, beat * st.at, beat * st.len, note + st.step, st.vel));
        }
        p.frames = p.lastOff + secondsToFrames(2.0f);
    } else if (kind == "drone") {
        // One chord held much longer than any other phrase. A spectral pad
        // is about slow movement (drift, morph) that's slower than the pad
        // phrase's chord changes. Notes join and leave a chord that never
        // restarts, and nothing is shorter than a whole note.
        struct Voice { float at; float len; int step; int vel; };
        static const Voice kVoices[] = {
            { 0.0f, 21.0f,  0,  92}, // the root, the whole way
            { 0.0f, 21.0f,  7,  88}, // the fifth
            { 4.0f, 17.0f,  4,  84}, // the third joins
            { 9.0f, 12.0f, 14,  80}, // the ninth, an octave up
            {13.0f,  4.0f, 11,  76}, // a seventh, which leaves again
            {17.0f,  4.0f, 12,  80}, // an octave, held to the end
        };
        for (const Voice &v : kVoices) {
            p.lastOff = std::max(p.lastOff, hit(p, v.at, v.len, note + v.step, v.vel));
        }
        p.frames = p.lastOff + secondsToFrames(6.0f);
    } else if (kind == "gospel" || kind == "chorale" || kind == "combo" || kind == "swell") {
        // The organ phrases, written in absolute notes. The organ's splits
        // decide which manual or the pedals a note goes to, so these are
        // written for the default splits (pedals below 48, lower manual below
        // 60, upper above) and `--note` only moves them by whole octaves.
        // There's one phrase for each kind of organ the machine does.
        struct Step { float at; float len; int note; int vel; };
        // Whole octaves, truncated rather than rounded, so a patch centred on
        // G4 isn't moved up an octave.
        const int shift = 12 * ((note - 60) / 12);

        // Gospel: right hand above the split, left hand comping below it,
        // pedals on the roots. Grace notes and a run of sixteenths show off
        // the percussion and key clicks. Times are in beats.
        static const Step kGospel[] = {
            // feet
            { 0.0f, 3.6f, 29, 100}, { 4.0f, 3.6f, 34, 100}, { 8.0f, 1.8f, 31,  98},
            {10.0f, 1.8f, 36,  98}, {12.0f, 3.6f, 29, 100}, {16.0f, 1.8f, 38,  98},
            {18.0f, 1.8f, 31,  98}, {20.0f, 1.8f, 36,  98}, {22.0f, 4.0f, 29, 104},
            // left hand, on the off beats
            { 1.5f, 0.40f, 53, 88}, { 1.5f, 0.40f, 57, 88},
            { 3.0f, 0.40f, 53, 84}, { 3.0f, 0.40f, 57, 84},
            { 5.5f, 0.40f, 53, 88}, { 5.5f, 0.40f, 58, 88},
            { 7.0f, 0.40f, 53, 84}, { 7.0f, 0.40f, 58, 84},
            { 9.5f, 0.40f, 55, 88}, { 9.5f, 0.40f, 59, 88},
            {11.0f, 0.40f, 52, 84}, {11.0f, 0.40f, 55, 84},
            {13.5f, 0.40f, 53, 88}, {13.5f, 0.40f, 57, 88},
            {15.0f, 0.40f, 53, 84}, {15.0f, 0.40f, 57, 84},
            {17.5f, 0.40f, 54, 88}, {17.5f, 0.40f, 57, 88},
            {19.0f, 0.40f, 55, 84}, {19.0f, 0.40f, 59, 84},
            {21.5f, 0.40f, 52, 84}, {21.5f, 0.40f, 55, 84},
            {23.0f, 1.50f, 53, 88}, {23.0f, 1.50f, 57, 88},
            // right hand
            { 0.00f, 0.12f, 68,  72},
            { 0.12f, 1.60f, 69, 104}, { 0.12f, 1.60f, 72, 104}, { 0.12f, 1.60f, 77, 104},
            { 2.00f, 1.60f, 72, 100}, { 2.00f, 1.60f, 77, 100}, { 2.00f, 1.60f, 81, 100},
            { 4.00f, 0.12f, 73,  72},
            { 4.12f, 1.70f, 74, 104}, { 4.12f, 1.70f, 77, 104}, { 4.12f, 1.70f, 82, 104},
            { 6.00f, 1.60f, 70,  96}, { 6.00f, 1.60f, 74,  96}, { 6.00f, 1.60f, 77,  96},
            { 8.00f, 1.60f, 71,  98}, { 8.00f, 1.60f, 74,  98}, { 8.00f, 1.60f, 79,  98},
            {10.00f, 1.60f, 72, 100}, {10.00f, 1.60f, 76, 100}, {10.00f, 1.60f, 79, 100},
            // the run: eight sixteenths down, each a new key
            {12.00f, 0.22f, 84, 104}, {12.25f, 0.22f, 82,  98}, {12.50f, 0.22f, 81, 100},
            {12.75f, 0.22f, 79,  96}, {13.00f, 0.22f, 77,  98}, {13.25f, 0.22f, 76,  94},
            {13.50f, 0.22f, 74,  96}, {13.75f, 0.22f, 72,  92},
            {14.00f, 1.80f, 69, 104}, {14.00f, 1.80f, 72, 104}, {14.00f, 1.80f, 77, 104},
            {16.00f, 1.60f, 74, 100}, {16.00f, 1.60f, 78, 100}, {16.00f, 1.60f, 81, 100},
            {18.00f, 1.60f, 71,  98}, {18.00f, 1.60f, 74,  98}, {18.00f, 1.60f, 79,  98},
            {20.00f, 1.50f, 72, 100}, {20.00f, 1.50f, 76, 100}, {20.00f, 1.50f, 79, 100},
            {22.00f, 2.50f, 69, 108}, {22.00f, 2.50f, 72, 108}, {22.00f, 2.50f, 77, 108},
            {22.00f, 2.50f, 81, 108},
        };

        // The hymn: four held parts, doubled in the pedals an octave down,
        // with a breath in the middle. Long notes only, to hear the wind sag,
        // the chiff and each release under the next chord. Times are in
        // seconds, not beats.
        static const Step kChorale[] = {
            { 0.00f, 1.70f, 72, 96}, { 0.00f, 1.70f, 67, 92}, { 0.00f, 1.70f, 64, 92}, { 0.00f, 1.70f, 48, 96}, { 0.00f, 1.70f, 36, 100},
            { 1.80f, 1.70f, 71, 96}, { 1.80f, 1.70f, 67, 92}, { 1.80f, 1.70f, 62, 92}, { 1.80f, 1.70f, 47, 96}, { 1.80f, 1.70f, 35, 100},
            { 3.60f, 1.70f, 72, 98}, { 3.60f, 1.70f, 69, 92}, { 3.60f, 1.70f, 64, 92}, { 3.60f, 1.70f, 45, 96}, { 3.60f, 1.70f, 33, 100},
            { 5.40f, 2.20f, 77,104}, { 5.40f, 2.20f, 72, 96}, { 5.40f, 2.20f, 65, 94}, { 5.40f, 2.20f, 53, 98}, { 5.40f, 2.20f, 41, 102},
            // the breath
            { 8.00f, 1.70f, 76,100}, { 8.00f, 1.70f, 72, 94}, { 8.00f, 1.70f, 67, 92}, { 8.00f, 1.70f, 52, 96}, { 8.00f, 1.70f, 40, 100},
            { 9.80f, 1.70f, 74, 98}, { 9.80f, 1.70f, 69, 92}, { 9.80f, 1.70f, 65, 92}, { 9.80f, 1.70f, 50, 96}, { 9.80f, 1.70f, 38, 100},
            {11.60f, 1.70f, 74,100}, {11.60f, 1.70f, 71, 94}, {11.60f, 1.70f, 67, 92}, {11.60f, 1.70f, 55, 96}, {11.60f, 1.70f, 43, 100},
            {13.40f, 3.60f, 72,104}, {13.40f, 3.60f, 67, 96}, {13.40f, 3.60f, 64, 94}, {13.40f, 3.60f, 48,100}, {13.40f, 3.60f, 36, 104},
        };

        // The combo organ: a riff played hard and short, to hear the attack
        // and release. Eighths at the demo tempo over a two-note vamp, with
        // only the last chord held.
        static const Step kCombo[] = {
            { 0.0f, 0.42f, 69,110}, { 0.5f, 0.42f, 72, 96}, { 1.0f, 0.42f, 76,104}, { 1.5f, 0.42f, 72, 94},
            { 2.0f, 0.42f, 74,102}, { 2.5f, 0.42f, 72, 94}, { 3.0f, 0.42f, 69,100}, { 3.5f, 0.42f, 67, 92},
            { 4.0f, 0.42f, 69,110}, { 4.5f, 0.42f, 72, 96}, { 5.0f, 0.42f, 76,104}, { 5.5f, 0.42f, 72, 94},
            { 6.0f, 0.42f, 77,106}, { 6.5f, 0.42f, 76, 96}, { 7.0f, 0.42f, 74,100}, { 7.5f, 0.42f, 72, 92},
            { 8.0f, 0.42f, 67,108}, { 8.5f, 0.42f, 71, 96}, { 9.0f, 0.42f, 74,104}, { 9.5f, 0.42f, 71, 94},
            {10.0f, 0.42f, 72,102}, {10.5f, 0.42f, 71, 94}, {11.0f, 0.42f, 67,100}, {11.5f, 0.42f, 65, 92},
            {12.0f, 0.42f, 69,110}, {12.5f, 0.42f, 72, 96}, {13.0f, 0.42f, 76,104}, {13.5f, 0.42f, 79,108},
            {14.0f, 0.42f, 76,100}, {14.5f, 0.42f, 72, 96},
            {15.0f, 2.20f, 69,112}, {15.0f, 2.20f, 76,104}, {15.0f, 2.20f, 81,100},
            // the vamp, under the split
            { 0.0f, 0.90f, 57, 92}, { 0.0f, 0.90f, 60, 88}, { 2.0f, 0.90f, 57, 84}, { 2.0f, 0.90f, 60, 80},
            { 4.0f, 0.90f, 57, 92}, { 4.0f, 0.90f, 60, 88}, { 6.0f, 0.90f, 58, 84}, { 6.0f, 0.90f, 62, 80},
            { 8.0f, 0.90f, 55, 92}, { 8.0f, 0.90f, 59, 88}, {10.0f, 0.90f, 55, 84}, {10.0f, 0.90f, 59, 80},
            {12.0f, 0.90f, 57, 92}, {12.0f, 0.90f, 60, 88}, {14.0f, 0.90f, 56, 88}, {14.0f, 0.90f, 59, 84},
            {15.0f, 2.20f, 57, 96}, {15.0f, 2.20f, 64, 92},
        };

        // The reed organ: slow and held, with dynamics coming from the
        // bellows rather than the notes. Chords swell up and fall away with
        // one inner voice moving each time, over a drone that keeps the wind
        // supply under load. Times are in seconds.
        static const Step kSwell[] = {
            { 0.0f, 16.5f, 38, 96},  // the drone, under the feet the whole way
            { 0.0f, 2.60f, 50, 72}, { 0.0f, 2.60f, 57, 70}, { 0.0f, 2.60f, 62, 70}, { 0.0f, 2.60f, 65, 74},
            { 2.5f, 2.60f, 50, 84}, { 2.5f, 2.60f, 57, 80}, { 2.5f, 2.60f, 62, 80}, { 2.5f, 2.60f, 66, 86},
            { 5.0f, 2.60f, 50, 96}, { 5.0f, 2.60f, 55, 92}, { 5.0f, 2.60f, 60, 92}, { 5.0f, 2.60f, 67, 98},
            { 7.5f, 2.60f, 53, 106}, { 7.5f, 2.60f, 58, 102}, { 7.5f, 2.60f, 62, 102}, { 7.5f, 2.60f, 65, 108},
            {10.0f, 2.60f, 52, 118}, {10.0f, 2.60f, 57, 112}, {10.0f, 2.60f, 60, 112}, {10.0f, 2.60f, 69, 120},
            {12.5f, 4.00f, 50, 92}, {12.5f, 4.00f, 57, 88}, {12.5f, 4.00f, 62, 88}, {12.5f, 4.00f, 74, 96},
        };

        const Step *steps = kGospel;
        size_t count = sizeof(kGospel) / sizeof(kGospel[0]);
        float unit = beat; // gospel and combo are in beats
        if (kind == "chorale") { steps = kChorale; count = sizeof(kChorale) / sizeof(kChorale[0]); unit = 1.0f; }
        else if (kind == "combo") { steps = kCombo; count = sizeof(kCombo) / sizeof(kCombo[0]); }
        else if (kind == "swell") { steps = kSwell; count = sizeof(kSwell) / sizeof(kSwell[0]); unit = 1.0f; }

        // Notes that start together are staggered by a few milliseconds, like
        // real hands and feet. Key clicks all on one sample sound mechanical.
        float lastAt = -1.0f;
        int together = 0;
        for (size_t i = 0; i < count; ++i) {
            const Step &st = steps[i];
            together = st.at == lastAt ? together + 1 : 0;
            lastAt = st.at;
            const int n = std::clamp(st.note + shift, 0, 127);
            p.lastOff = std::max(p.lastOff, hit(p, st.at * unit + 0.013f * static_cast<float>(together),
                                                st.len * unit, n, st.vel));
        }
        p.frames = p.lastOff + secondsToFrames(3.0f);
    } else if (kind == "chromatic") {
        for (int i = 0; i < 13; ++i) {
            p.lastOff = hit(p, 0.5f * static_cast<float>(i), 0.4f, 24 + i * 6, velocity);
        }
        p.frames = p.lastOff + secondsToFrames(2.0f);
    } else if (kind == "velocity") {
        static const int kVels[] = {20, 50, 80, 110, 127};
        for (int i = 0; i < 5; ++i) {
            p.lastOff = hit(p, 0.7f * static_cast<float>(i), 0.55f, note, kVels[i]);
        }
        p.frames = p.lastOff + secondsToFrames(2.0f);
    } else if (kind == "voices" && kit != nullptr) {
        // A second apart, so a crash or open hat can decay inside its own
        // window. Otherwise the tail column measures the window, not the
        // sound.
        for (size_t i = 0; i < kit->voices.size(); ++i) {
            p.lastOff = hit(p, kVoiceWindow * static_cast<float>(i), 0.1f, kit->baseNote + static_cast<int>(i),
                            velocity);
        }
        p.frames = secondsToFrames(kVoiceWindow * static_cast<float>(kit->voices.size()));
    } else if (kind == "beat" && kit != nullptr) {
        // Two bars. Voices the kit doesn't have are skipped.
        const int n = static_cast<int>(kit->voices.size());
        struct Step { int voice; int sixteenth; int vel; };
        // The crash is on the downbeat so it rings under the two bars. Near
        // the end, a long crash would ring on alone after the kit stopped.
        static const Step kPattern[] = {
            {0, 0, 120}, {9, 0, 65},  {7, 2, 70},  {2, 4, 110}, {7, 6, 70},  {0, 8, 100},
            {0, 10, 80}, {2, 12, 110}, {8, 14, 80}, {0, 16, 120}, {7, 18, 70}, {2, 20, 110},
            {3, 20, 70}, {7, 22, 70}, {0, 24, 100}, {5, 26, 85}, {2, 28, 110},
        };
        for (const Step &s : kPattern) {
            if (s.voice >= n) continue;
            p.lastOff = std::max(p.lastOff, hit(p, beat * static_cast<float>(s.sixteenth) / 4.0f, 0.08f,
                                                kit->baseNote + s.voice, s.vel));
        }
        p.frames = secondsToFrames(beat * 8.0f) + secondsToFrames(2.0f);
    } else { // "note", and anything unrecognised
        p.lastOff = hit(p, 0.0f, 2.0f, note, velocity);
        p.frames = p.lastOff + secondsToFrames(2.0f);
    }
    std::stable_sort(p.events.begin(), p.events.end(),
                     [](const NoteEvent &a, const NoteEvent &b) { return a.frame < b.frame; });
    return p;
}

// --- What gets mounted -------------------------------------------------------

/**
 * The material a machine needs before it makes any sound, and the audio for
 * the input bus. Kept alive for the whole render, because swapObject borrows
 * the pointer and the machine keeps reading it.
 */
struct Material {
    std::vector<std::unique_ptr<SampleData>> samples;
    std::unique_ptr<audio::Take> take;
    std::unique_ptr<audio::Utterance> utterance;
    std::unique_ptr<SampleMap> map;
    std::unique_ptr<::acidulous::machine::cumulus::CloudSet> cloud;
    std::unique_ptr<::acidulous::machine::formulate::Program> program;
    std::unique_ptr<::acidulous::machine::nexus::Graph> graph;
    std::vector<float> input; // mono, published a block at a time
};

/**
 * Cumulus is silent until it's given a table, built off the audio thread from
 * its spectrum parameters. This makes the same two calls EngineHost::buildCloud
 * makes when a knob moves.
 */
void buildCumulusCloud(Machine *m, Material &mat) {
    auto *cum = static_cast<::acidulous::machine::Cumulus *>(m);
    mat.cloud = ::acidulous::machine::cumulus::buildCloud(cum->spec(), static_cast<int32_t>(kSr));
    m->swapObject(0, mat.cloud.get());
}

void mountMaterial(Machine *m, const std::string &machine, const std::string &kind, Material &mat) {
    if (machine == "Cumulus") {
        buildCumulusCloud(m, mat);
        return;
    }
    if (kind == "kit" || (kind.empty() && machine == "Forage")) {
        for (int i = 0; i < static_cast<int>(Piece::Count); ++i) {
            mat.samples.push_back(pieceSample(static_cast<Piece>(i)));
            m->swapObject(i, mat.samples.back().get());
        }
    } else if (kind == "break") {
        mat.take = breakLoop();
        m->swapObject(0, mat.take.get());
    } else if (kind == "seed") {
        mat.take = musicSeed();
        m->swapObject(0, mat.take.get());
    } else if (kind == "voicetake") {
        mat.take = voiceTake();
        m->swapObject(0, mat.take.get());
    } else if (kind == "voice") {
        mat.utterance = voiceUtterance();
        m->swapObject(0, mat.utterance.get());
    } else if (kind == "map") {
        mat.map = zoneMap();
        m->swapObject(0, mat.map.get());
    }
}

/**
 * The parts of a patch that aren't knobs. Formulate's formula is a string
 * that has to be compiled and mounted, or it plays a plain oscillator. This
 * does what EngineHost does when the setting changes.
 */
void applySettings(Machine *m, const std::string &machine,
                   const std::vector<std::pair<std::string, std::string>> &settings, Material &mat,
                   const std::set<std::string> &named = {}) {
    // A Nexus patch is its graph, a line per module and per cable. The app
    // parses it on a worker and hands it over with swapObject.
    if (machine == "Nexus") {
        for (const auto &kv : settings) {
            if (kv.first == "nexus") mountNexusGraph(m, kv.second, kSr, named, mat.graph);
        }
        return;
    }
    if (machine != "Formulate") return;
    std::string formula, arp, duty, vol;
    for (const auto &kv : settings) {
        if (kv.first == "formula") formula = kv.second;
        else if (kv.first == "arp") arp = kv.second;
        else if (kv.first == "duty") duty = kv.second;
        else if (kv.first == "vol") vol = kv.second;
    }
    if (formula.empty() && arp.empty() && duty.empty() && vol.empty()) return;
    std::string error;
    mat.program = ::acidulous::machine::formulate::compile(formula, arp, duty, vol, error);
    if (!mat.program) {
        std::fprintf(stderr, "  the formula did not compile: %s\n", error.c_str());
        return;
    }
    m->swapObject(0, mat.program.get());
}

/**
 * A real recording on the input bus, looped to fill the render. Synthetic
 * speech is fine for checking a vocoder's wiring but lacks the detail needed
 * to judge how a patch sounds.
 *
 * Looped with a short crossfade so there's no click where it wraps.
 */
std::vector<float> fileInput(const std::string &path, float seconds) {
    // Reading, mono fold, rumble filter and level are in audition_material.h,
    // shared with Molt.
    std::string error;
    std::vector<float> src = fileMono(path.c_str(), error);
    if (src.empty()) {
        std::fprintf(stderr, "input file %s: %s\n", path.c_str(), error.c_str());
        return {};
    }
    const auto fade = static_cast<size_t>(kSr * 0.01f);
    const auto want = static_cast<size_t>(kSr * seconds);
    std::vector<float> out;
    out.reserve(want);
    while (out.size() < want) {
        const size_t base = out.size();
        for (size_t i = 0; i < src.size() && out.size() < want; ++i) {
            float v = src[i];
            if (base > 0 && i < fade) v *= static_cast<float>(i) / static_cast<float>(fade);
            if (i + fade >= src.size()) {
                v *= static_cast<float>(src.size() - i) / static_cast<float>(fade);
            }
            out.push_back(v);
        }
    }
    return out;
}

void loadInput(const std::string &kind, Material &mat) {
    if (kind == "voice") {
        mat.input = voicePhrase();
    } else if (kind == "noise") {
        mat.input = noise(4.0f, 0.4f);
    } else if (kind == "break") {
        const std::unique_ptr<audio::Take> t = breakLoop();
        mat.input = t->left;
    } else if (kind == "speech") {
        mat.input = speechPhrase();
    } else if (kind.rfind("file:", 0) == 0) {
        mat.input = fileInput(kind.substr(5), 14.0f);
    }
}

/** What a machine wants mounted when its bank does not say. */
std::string defaultMaterial(const std::string &machine) {
    if (machine == "Forage") return "kit";
    if (machine == "Dice") return "break";
    // Pollen granulates music by default. A break only tests onset snap,
    // not the pitch, bloom and cloud patches that make up most of the bank.
    // The rhythm patches ask for the break themselves.
    if (machine == "Pollen") return "seed";
    if (machine == "Mosaic") return "map";
    if (machine == "Molt") return "voice";
    return "none";
}

std::string defaultInput(const std::string &machine) {
    // A vocoder needs moving input. Use the real recording named in
    // tools/local.env if there is one (the shipped levels came from a real
    // voice), otherwise synthetic speech. Synthetic consonants are long
    // noise bursts that sound like static.
    if (machine == "Cipher") {
        const char *file = std::getenv("ACIDULOUS_INPUT_FILE");
        if (file != nullptr && *file != '\0') return std::string("file:") + file;
        return "speech";
    }
    return "none";
}

// --- Applying a patch --------------------------------------------------------

/**
 * Does what the app does, in the same order. Every parameter is written, not
 * just the ones the patch names, like ParamBinding.applyAll which sets
 * defaults for anything a patch leaves out. Otherwise a patch could inherit
 * values from the one before it.
 */
void applyTo(ParamSet &params, const std::vector<float> &norm) {
    for (size_t i = 0; i < norm.size(); ++i) params.set(static_cast<int32_t>(i), norm[i]);
    params.jumpAll();
}

// --- Rendering ---------------------------------------------------------------

struct Take {
    std::vector<float> stereo;
    int64_t offAt = 0;
};

/**
 * The single held note every patch also plays. Measurements come from this
 * rather than the phrase, so different kinds of patch can be compared and the
 * harmonic readings aren't taken mid note change.
 */
Take gMeasureTake;

/**
 * Plays a phrase on a machine and collects the output.
 *
 * Ticks are worked out in double from the frame count (0.64 ticks a block at
 * 120 bpm) so tempo-synced LFOs, Manual's rotary and Nexus's clock run at the
 * right speed. A fixed ticks-per-block step like reset_test's would run them
 * about six times too fast.
 */
Take render(Machine *m, const Phrase &phrase, float bpm, const Material &mat) {
    Take out;
    out.stereo.reserve(static_cast<size_t>(phrase.frames) * 2);
    out.offAt = phrase.lastOff;

    float L[kBlock], R[kBlock];
    std::vector<float> inBlock(static_cast<size_t>(kBlock) * 2, 0.0f);
    size_t next = 0;
    const double ticksPerFrame = static_cast<double>(bpm) * kPPQN / (60.0 * static_cast<double>(kSr));

    for (int64_t at = 0; at < phrase.frames; at += kBlock) {
        while (next < phrase.events.size() && phrase.events[next].frame < at + kBlock) {
            const NoteEvent &e = phrase.events[next];
            if (e.velocity > 0) m->noteOn(e.note, e.velocity);
            else m->noteOff(e.note);
            ++next;
        }
        if (!mat.input.empty()) {
            for (int32_t i = 0; i < kBlock; ++i) {
                const size_t src = static_cast<size_t>(at) + static_cast<size_t>(i);
                const float v = src < mat.input.size() ? mat.input[src] : 0.0f;
                inBlock[static_cast<size_t>(i) * 2] = v;
                inBlock[static_cast<size_t>(i) * 2 + 1] = v;
            }
            InputBus::get().publish(inBlock.data(), kBlock);
        }
        const auto t0 = static_cast<int64_t>(static_cast<double>(at) * ticksPerFrame);
        const auto t1 = static_cast<int64_t>(static_cast<double>(at + kBlock) * ticksPerFrame);
        m->onBlock(t0, t1, bpm);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        const bool stereo = m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) {
            out.stereo.push_back(L[i]);
            out.stereo.push_back(stereo ? R[i] : L[i]);
        }
    }

    // The ring-out. It stops 60 dB under the peak, the same floor the tail
    // measurement uses. It has to stay quiet for a tenth of a second, since a
    // decaying wave crosses zero and one quiet block isn't silence. Capped,
    // because a noisy or self-oscillating patch never gets that quiet. Hitting
    // the cap shows as a `+` in the tail column.
    if (phrase.ringOut) {
        float peak = 0.0f;
        for (float v : out.stereo) peak = std::max(peak, std::fabs(v));
        const float floorAt = peak * 0.001f;
        const int64_t cap = phrase.frames + secondsToFrames(kMaxRingOut);
        const int32_t quietBlocksWanted = static_cast<int32_t>(kSr * 0.1f) / kBlock + 1;
        int32_t quiet = 0;
        for (int64_t at = phrase.frames; at < cap && quiet < quietBlocksWanted; at += kBlock) {
            const auto t0 = static_cast<int64_t>(static_cast<double>(at) * ticksPerFrame);
            const auto t1 = static_cast<int64_t>(static_cast<double>(at + kBlock) * ticksPerFrame);
            m->onBlock(t0, t1, bpm);
            std::memset(L, 0, sizeof(L));
            std::memset(R, 0, sizeof(R));
            const bool stereo = m->render(L, R, kBlock);
            float loudest = 0.0f;
            for (int32_t i = 0; i < kBlock; ++i) {
                out.stereo.push_back(L[i]);
                out.stereo.push_back(stereo ? R[i] : L[i]);
                loudest = std::max(loudest, std::max(std::fabs(L[i]), std::fabs(stereo ? R[i] : L[i])));
            }
            quiet = loudest < floorAt ? quiet + 1 : 0;
        }
    }
    InputBus::get().publish(nullptr, 0);
    return out;
}

/** An effect gets a source rather than notes: a tone, a transient and noise. */
std::vector<float> effectSource(float seconds) {
    const auto n = static_cast<size_t>(kSr * seconds);
    std::vector<float> out(n * 2, 0.0f);
    Rng rng(0xeffec7u);
    // The source stops at two thirds and the rest is silence, so delay and
    // reverb tails can be heard.
    const size_t stop = n * 2 / 3;
    for (size_t i = 0; i < stop; ++i) {
        const float t = static_cast<float>(i) / kSr;
        // A note every half second gives delays, gates and compressors an
        // edge to work on, over some noise for the filters.
        const float phase = std::fmod(t, 0.5f);
        const float env = std::exp(-phase / 0.12f);
        const float tone = (std::sin(2.0f * static_cast<float>(M_PI) * 220.0f * t) +
                            0.5f * std::sin(2.0f * static_cast<float>(M_PI) * 331.0f * t)) * 0.35f;
        const float v = tone * env + rng.next() * 0.04f;
        out[i * 2] = v;
        out[i * 2 + 1] = v * 0.97f + rng.next() * 0.01f;
    }
    return out;
}

Take renderEffect(Effect *fx, float bpm, float seconds) {
    Take out;
    std::vector<float> src = effectSource(seconds);
    const auto frames = static_cast<int64_t>(src.size() / 2);
    out.offAt = frames * 2 / 3; // where effectSource falls silent
    out.stereo.reserve(src.size());
    float L[kBlock], R[kBlock];
    const double ticksPerFrame = static_cast<double>(bpm) * kPPQN / (60.0 * static_cast<double>(kSr));
    for (int64_t at = 0; at < frames; at += kBlock) {
        const int32_t n = static_cast<int32_t>(std::min<int64_t>(kBlock, frames - at));
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        for (int32_t i = 0; i < n; ++i) {
            L[i] = src[static_cast<size_t>(at + i) * 2];
            R[i] = src[static_cast<size_t>(at + i) * 2 + 1];
        }
        const auto t0 = static_cast<int64_t>(static_cast<double>(at) * ticksPerFrame);
        const auto t1 = static_cast<int64_t>(static_cast<double>(at + kBlock) * ticksPerFrame);
        fx->onBlock(t0, t1, bpm);
        fx->run(L, R, n, true);
        for (int32_t i = 0; i < n; ++i) {
            out.stereo.push_back(L[i]);
            out.stereo.push_back(R[i]);
        }
    }
    return out;
}

// --- Output ------------------------------------------------------------------

/**
 * The shortest decimal that reads back as exactly this float. Tries 7
 * significant figures first and falls back to 9, so renders stay bit exact
 * but most values still come out short like `0.62f`.
 */
std::string floatLiteral(float v) {
    char buf[48];
    for (int digits : {7, 9}) {
        std::snprintf(buf, sizeof(buf), "%.*g", digits, static_cast<double>(v));
        if (static_cast<float>(std::atof(buf)) == v) return buf;
    }
    return buf;
}

/** A Kotlin string literal's insides. `$` opens a template, so it escapes too. */
std::string kotlinString(const std::string &in) {
    std::string out;
    for (char c : in) {
        // Newlines are escaped. A Nexus patch has one line per module and
        // cable, and a raw line break in a Kotlin string won't compile.
        if (c == '\n') { out += "\\n"; continue; }
        if (c == '\r') continue;
        if (c == '\\' || c == '"' || c == '$') out += '\\';
        out += c;
    }
    return out;
}

/**
 * Each unit's wavs go in their own folder, so they're easy to find and copy
 * to another machine.
 */
std::string folderFor(const std::string &unit);

std::string safeName(const std::string &s) {
    std::string out;
    for (char c : s) out += (std::isalnum(static_cast<unsigned char>(c)) != 0 || c == '-') ? c : '_';
    return out.empty() ? "patch" : out;
}

std::string folderFor(const std::string &unit) {
    const std::string dir = gOutDir + "/" + safeName(unit);
    ::mkdir(gOutDir.c_str(), 0777);
    ::mkdir(dir.c_str(), 0777);
    return dir;
}

/**
 * Always writes float wavs. WavWriter clamps PCM to +/-1, which would hide the
 * patches that clip.
 */
bool writeWav(const std::string &path, const std::vector<float> &stereo) {
    WavWriter w;
    std::string error;
    if (!w.open(path, static_cast<int32_t>(kSr), 32, error)) {
        std::fprintf(stderr, "  cannot write %s: %s\n", path.c_str(), error.c_str());
        return false;
    }
    w.write(stereo.data(), static_cast<int32_t>(stereo.size() / 2));
    return w.close();
}

/**
 * One row per kit voice. A kit is judged on the balance between its pieces,
 * which is the `rel` column: each voice against the loudest in the patch.
 */
struct VoiceRow {
    std::string name;
    Measured m;
};

/**
 * Renders each voice on its own with a fresh machine. Slicing one render
 * into windows would measure each hit on top of the previous one's tail.
 */
std::vector<VoiceRow> measureVoices(const Kit &kit, const std::vector<float> &norm, const std::string &machine,
                                    const std::string &material, float bpm, int velocity) {
    std::vector<VoiceRow> rows;
    for (size_t v = 0; v < kit.voices.size(); ++v) {
        std::unique_ptr<Machine> m(MachineRegistry::create(machine.c_str()));
        if (!m) break;
        m->prepare(static_cast<int32_t>(kSr));
        m->allNotesOff();
        m->reset();
        applyTo(m->params(), norm);
        Material mat;
        mountMaterial(m.get(), machine, material, mat);

        Phrase one;
        one.lastOff = hit(one, 0.0f, 0.1f, kit.baseNote + static_cast<int>(v), velocity);
        one.frames = secondsToFrames(kVoiceWindow);
        one.ringOut = false; // every voice gets the same window
        const Take take = render(m.get(), one, bpm, mat);
        rows.push_back({kit.voices[v], measure(take.stereo, 0, 0)});
    }
    return rows;
}

void printVoices(const std::vector<VoiceRow> &rows) {
    float loudest = -200.0f;
    for (const VoiceRow &r : rows) loudest = std::max(loudest, r.m.peakDb);
    float quietest = 200.0f;
    std::printf("    %-13s %7s %8s %7s %9s\n", "voice", "peak", "rel", "tail", "centroid");
    for (const VoiceRow &r : rows) {
        const bool silent = r.m.peakDb < -100.0f;
        if (!silent) quietest = std::min(quietest, r.m.peakDb);
        // A tail that fills its window was cut off, not measured.
        const bool clipped = r.m.tailSeconds >= kVoiceWindow - 0.02f;
        std::printf("    %-13s %+6.1f %+7.1f %5.2f%ss %8.0fHz%s\n", r.name.c_str(),
                    static_cast<double>(r.m.peakDb), static_cast<double>(r.m.peakDb - loudest),
                    static_cast<double>(r.m.tailSeconds), clipped ? "+" : " ",
                    static_cast<double>(r.m.centroidHz), silent ? "   silent" : "");
    }
    if (quietest < 200.0f) {
        std::printf("    %-13s %s%.1f dB between the loudest and the quietest\n", "",
                    loudest - quietest > 24.0f ? "wide: " : "", static_cast<double>(loudest - quietest));
    }
}

/**
 * The first twelve harmonics, in dB against the strongest. For some machines
 * (Brazen, for example) the centroid barely moves because the fundamental
 * grows with everything else. The harmonic ladder shows the difference, e.g.
 * a tuba has a few partials while a trumpet has a long, even rolloff.
 */
void printLadder(const std::vector<float> &stereo, float f0) {
    if (f0 <= 0.0f) {
        std::printf("    no pitch found, so no ladder\n");
        return;
    }
    const size_t frames = stereo.size() / 2;
    std::vector<float> mono(frames, 0.0f);
    for (size_t i = 0; i < frames; ++i) mono[i] = 0.5f * (stereo[i * 2] + stereo[i * 2 + 1]);
    // Half a second in, past the attack and into the steady part.
    const auto at = static_cast<int32_t>(std::min<size_t>(static_cast<size_t>(kSr * 0.5f), frames));

    float mags[12];
    float loudest = 1e-9f;
    for (int h = 0; h < 12; ++h) {
        mags[h] = magnitudeAt(mono, at, f0 * static_cast<float>(h + 1));
        loudest = std::max(loudest, mags[h]);
    }
    std::printf("    harmonics at %.0f Hz, dB against the strongest\n     ", static_cast<double>(f0));
    for (int h = 0; h < 12; ++h) std::printf(" %5.0f", static_cast<double>(dB(mags[h] / loudest)));
    std::printf("\n      ");
    for (int h = 0; h < 12; ++h) std::printf(" %5d", h + 1);
    // The highest harmonic still above -30 dB.
    int reach = 1;
    for (int h = 0; h < 12; ++h) {
        if (dB(mags[h] / loudest) > -30.0f) reach = h + 1;
    }
    std::printf("\n    reaches harmonic %d above -30 dB\n", reach);
}

/**
 * Pitch over time at the start of the measured note. Readings are every 5 ms
 * for the first 100 ms, where glides and unstable attacks show, then sparser.
 * `?` marks a reading whose three periods disagreed by more than a tenth.
 */
void printTrack(const std::vector<float> &stereo, float f0, int note) {
    if (f0 <= 0.0f) {
        std::printf("    no pitch found, so no track\n");
        return;
    }
    const size_t frames = stereo.size() / 2;
    std::vector<float> mono(frames, 0.0f);
    for (size_t i = 0; i < frames; ++i) mono[i] = 0.5f * (stereo[i * 2] + stereo[i * 2 + 1]);
    std::vector<float> at;
    for (float ms = 5.0f; ms <= 100.0f; ms += 5.0f) at.push_back(ms);
    for (float ms = 120.0f; ms <= 200.0f; ms += 20.0f) at.push_back(ms);
    for (float ms : {250.0f, 300.0f, 400.0f, 500.0f}) at.push_back(ms);
    const float want = note > 0 ? midiToHz(note) : f0;
    std::printf("    pitch against time, cents against %s %.1f Hz\n", note > 0 ? "note" : "settled",
                static_cast<double>(want));
    std::printf("    %6s %8s %7s %7s\n", "ms", "Hz", "cents", "dB");
    for (const TrackPoint &p : pitchTrack(mono, f0, at)) {
        if (p.hz > 0.0f) {
            std::printf("    %6.0f %8.1f %+7.0f %7.1f%s\n", static_cast<double>(p.ms), static_cast<double>(p.hz),
                        static_cast<double>(cents(p.hz, want)), static_cast<double>(p.db), p.sure ? "" : "  ?");
        } else {
            std::printf("    %6.0f %8s %7s %7.1f\n", static_cast<double>(p.ms), "-", "-", static_cast<double>(p.db));
        }
    }
}

void printHeader() {
    std::printf("  %-22s %6s %6s %8s %6s %7s %5s %5s %6s %7s %6s %6s %6s %5s %6s\n",
                "patch", "loud", "peak", "centroid", "hollow", "tune", "part", "harm", "ring",
                "speaks", "click", "chiff", "tail", "mono", "low");
}

void printRow(const std::string &name, const Measured &m, int note) {
    char tune[16] = "-", part[16] = "-", ring[16] = "-";
    if (m.tuned) std::snprintf(tune, sizeof(tune), "%+.0fc", static_cast<double>(m.tuneCents));
    if (m.partialRatio > 0.0f) std::snprintf(part, sizeof(part), "%.2f", static_cast<double>(m.partialRatio));
    if (m.ringSeconds > 0.0f) std::snprintf(ring, sizeof(ring), "%.2fs", static_cast<double>(m.ringSeconds));
    std::printf("  %-22s %+6.1f %+6.1f %6.0fHz %+6.0f %7s %5s %5.2f %6s %5.0fms %5.1fx %5.1fx %4.2f%s %+5.1f %+6.0f%s\n",
                name.c_str(),
                static_cast<double>(m.loudnessDb), static_cast<double>(m.peakDb),
                static_cast<double>(m.centroidHz), static_cast<double>(m.evenOddDb),
                tune, part, static_cast<double>(m.harmonicity), ring,
                static_cast<double>(m.speaksMs),
                static_cast<double>(m.clickRatio), static_cast<double>(m.onsetEdge),
                static_cast<double>(m.tailSeconds), m.tailRanOut ? "+s" : "s ",
                static_cast<double>(m.monoLossDb), static_cast<double>(m.lowDb),
                m.finite ? "" : "  NOT FINITE");
}

// --- Banks -------------------------------------------------------------------

std::string bankPath(const std::string &unit) {
    std::string name = unit;
    if (name.rfind("fx.", 0) == 0) name = "fx." + name.substr(3);
    return gBankDir + "/" + name + ".bank";
}

bool loadBank(const std::string &unit, Bank &bank) {
    std::string error;
    if (!readBank(bankPath(unit), bank, error)) {
        std::fprintf(stderr, "%s\n", error.c_str());
        return false;
    }
    return true;
}

const ParamDef *defsFor(const std::string &unit, int32_t &count) {
    if (unit.rfind("fx.", 0) == 0) return EffectRegistry::paramDefs(unit.substr(3).c_str(), count);
    return MachineRegistry::paramDefs(unit.c_str(), count);
}

// --- Commands ----------------------------------------------------------------

int cmdParams(const std::string &unit) {
    int32_t count = 0;
    const ParamDef *defs = defsFor(unit, count);
    if (defs == nullptr || count == 0) {
        std::fprintf(stderr, "no unit called '%s'\n", unit.c_str());
        return 1;
    }
    std::printf("%s - %d parameters\n\n", unit.c_str(), count);
    std::printf("  %3s %-18s %12s %12s %12s  %s\n", "idx", "name", "min", "max", "default", "curve");
    for (int32_t i = 0; i < count; ++i) {
        const ParamDef &d = defs[i];
        const char *curve = d.curve == Curve::Exponential ? "exponential"
                          : d.curve == Curve::Stepped     ? "stepped"
                                                          : "linear";
        char steps[24] = "";
        if (d.curve == Curve::Stepped) std::snprintf(steps, sizeof(steps), " (%d steps)", d.steps);
        std::printf("  %3d %-18s %12g %12g %12g  %s%s%s%s\n", i, d.name, static_cast<double>(d.min),
                    static_cast<double>(d.max), static_cast<double>(d.def), curve, steps,
                    d.unit != nullptr && d.unit[0] != '\0' ? "  " : "", d.unit != nullptr ? d.unit : "");
    }
    return 0;
}

struct Options {
    std::string phrase;
    std::string material;
    std::string input;
    int note = -1; // -1: whatever the patch or phrase wants
    int velocity = 100;
    float bpm = 120.0f;
    bool ladder = false;
    bool track = false;
    bool quiet = false; // sweep: no row, no wav, just return the numbers
    std::vector<std::pair<std::string, double>> sets;
};

/** Renders one patch, writes its wav, and returns what it measured. */
bool auditionOne(const Bank &bank, const BankPatch &patch, const Options &opt, Measured &measured) {
    int32_t count = 0;
    const ParamDef *defs = defsFor(bank.unit, count);
    if (defs == nullptr || count == 0) {
        std::fprintf(stderr, "no unit called '%s'\n", bank.unit.c_str());
        return false;
    }
    Resolved r = resolve(patch, defs, count);
    for (const std::string &p : r.problems) {
        std::fprintf(stderr, "  %s: %s\n", patch.name.c_str(), p.c_str());
    }
    for (const auto &s : opt.sets) {
        for (int32_t i = 0; i < count; ++i) {
            if (s.first == defs[i].name) r.norm[static_cast<size_t>(i)] = defs[i].unmap(static_cast<float>(s.second));
        }
    }

    Take take;
    int measuredNote = 0;
    bool measuredAlready = false;
    std::vector<VoiceRow> voices;

    if (bank.isEffect()) {
        std::unique_ptr<Effect> fx(EffectRegistry::create(bank.typeName().c_str()));
        if (!fx) return false;
        fx->prepare(static_cast<int32_t>(kSr));
        fx->reset();
        applyTo(fx->params(), r.norm);
        take = renderEffect(fx.get(), opt.bpm, 4.0f);
    } else {
        std::unique_ptr<Machine> m(MachineRegistry::create(bank.unit.c_str()));
        if (!m) {
            std::fprintf(stderr, "no machine called '%s'\n", bank.unit.c_str());
            return false;
        }
        m->prepare(static_cast<int32_t>(kSr));
        m->allNotesOff();
        m->reset();
        applyTo(m->params(), r.norm);

        Material mat;
        std::string material = !patch.material.empty() ? patch.material
                             : bank.material != "none" ? bank.material
                                                       : defaultMaterial(bank.unit);
        std::string input = !patch.input.empty() ? patch.input
                          : bank.input != "none" ? bank.input
                                                 : defaultInput(bank.unit);
        if (!opt.material.empty()) material = opt.material;
        if (!opt.input.empty()) input = opt.input;
        // Everything a patch needs before it can sound. It runs twice because
        // the listen render below resets the machine, which undoes it.
        std::set<std::string> named;
        for (const BankValue &v : patch.values) named.insert(v.name);
        const auto dress = [&] {
            mountMaterial(m.get(), bank.unit, material, mat);
            applySettings(m.get(), bank.unit, r.settings, mat, named);
        };
        dress();
        loadInput(input, mat);

        const Kit *kit = kitFor(bank.unit);
        // --note wins over the patch's note, then the middle of its range,
        // then the default.
        const Range range{patch.low, patch.high};
        const int note = opt.note > 0    ? opt.note
                         : patch.note > 0 ? patch.note
                         : range.set()    ? (range.low + range.high) / 2
                                          : 48;

        // The measured phrase and the listened-to phrase are different on
        // purpose. Every patch is measured on one plain note so patches of
        // different kinds can be compared. A kit is measured one voice at a
        // time instead.
        const std::string measureKind = kit != nullptr ? "voices" : "note";
        const Phrase measurePhrase = buildPhrase(measureKind, note, opt.velocity, opt.bpm, kit);
        const Take measureTake = render(m.get(), measurePhrase, opt.bpm, mat);
        gMeasureTake = measureTake;
        measured = measure(measureTake.stereo, measureTake.offAt, kit != nullptr ? 0 : note);
        measuredNote = kit != nullptr ? 0 : note;
        measuredAlready = true;
        if (kit != nullptr) {
            voices = measureVoices(*kit, r.norm, bank.unit, material, opt.bpm, opt.velocity);
            // A kit's summary row can't come from the render, since the
            // measurement window would only see the first voice. Use the
            // loudness-weighted mean of the voices' brightness and the
            // longest tail in the kit.
            double num = 0.0, den = 0.0;
            float longest = 0.0f;
            for (const VoiceRow &v : voices) {
                if (v.m.peakDb < -100.0f) continue;
                const double w = std::pow(10.0, static_cast<double>(v.m.peakDb) / 20.0);
                num += w * v.m.centroidHz;
                den += w;
                longest = std::max(longest, v.m.tailSeconds);
                if (v.m.tailRanOut) measured.tailRanOut = true;
            }
            if (den > 0.0) measured.centroidHz = static_cast<float>(num / den);
            measured.tailSeconds = longest;
        }

        std::string listenKind = !opt.phrase.empty() ? opt.phrase
                               : !patch.role.empty() ? patch.role
                                                     : bank.role;
        if (kit != nullptr && (listenKind == "note" || listenKind.empty())) listenKind = "beat";
        if (listenKind == measureKind) {
            take = measureTake;
        } else {
            m->allNotesOff();
            m->reset();
            applyTo(m->params(), r.norm);
            // Mount the patch again too. Nexus keeps its graph in a setting,
            // and mounting it sets every knob the patch didn't name to the
            // module's default. Without this those knobs go back to zero and
            // the render can be silent.
            dress();
            take = render(m.get(), buildPhrase(listenKind, note, opt.velocity, opt.bpm, kit, range), opt.bpm, mat);
        }
    }

    // An effect has no note to hold, so it's measured on its own render.
    // Presets differ mostly in their tails, so brightness and pitch are read
    // just after the source stops. A machine only gets here without a
    // measure take, and then the default window is used.
    if (!measuredAlready) {
        measured = bank.isEffect()
                       ? measure(take.stereo, take.offAt, 0, take.offAt + static_cast<int64_t>(kSr * 0.05f))
                       : measure(take.stereo, take.offAt, 0);
    }
    if (opt.quiet) return true;
    // Named "<family>-<patch>.wav" so a folder sorts by family, with all the
    // bells together and so on.
    const std::string family = !patch.family.empty() ? patch.family
                             : !patch.role.empty()   ? patch.role
                                                     : bank.role;
    writeWav(folderFor(bank.unit) + "/" + safeName(family) + "-" + safeName(patch.name) + ".wav",
             take.stereo);
    printRow(patch.name, measured, measuredNote);
    if (!voices.empty()) printVoices(voices);
    if (opt.ladder) printLadder(bank.isEffect() ? take.stereo : gMeasureTake.stereo, measured.f0Hz);
    if (opt.track && !bank.isEffect()) printTrack(gMeasureTake.stereo, measured.f0Hz, measuredNote);
    return true;
}

int cmdPlay(const std::string &unit, const std::string &patchName, const Options &opt) {
    Bank bank;
    if (!loadBank(unit, bank)) return 1;
    for (const BankPatch &p : bank.patches) {
        if (p.name != patchName) continue;
        printHeader();
        Measured m;
        return auditionOne(bank, p, opt, m) ? 0 : 1;
    }
    std::fprintf(stderr, "no patch called '%s' in %s\n", patchName.c_str(), bank.path.c_str());
    return 1;
}

int cmdBank(const std::string &unit, const Options &opt) {
    Bank bank;
    if (!loadBank(unit, bank)) return 1;
    std::printf("%s - %zu patches\n\n", bank.unit.c_str(), bank.patches.size());
    printHeader();
    std::vector<float> rms;
    for (const BankPatch &p : bank.patches) {
        Measured m;
        if (!auditionOne(bank, p, opt, m)) continue;
        if (m.loudnessDb > -190.0f) rms.push_back(m.loudnessDb);
    }
    if (rms.size() > 1) {
        const float lo = *std::min_element(rms.begin(), rms.end());
        const float hi = *std::max_element(rms.begin(), rms.end());
        // Patches far apart in loudness are a common bank fault and hard to
        // hear one patch at a time.
        std::printf("\n  loudness spread %.1f dB%s\n", static_cast<double>(hi - lo),
                    hi - lo > 12.0f ? "   <- wide; level these against each other" : "");
    }
    return 0;
}

/**
 * Every note of a patch's range, one line each. Problems usually show at the
 * ends of a range, not the middle, so the whole range is checked.
 *
 * `dead` is a note 20 dB below the patch's loudest. `mode` (wrong partial)
 * means another partial is loudest and the fundamental is missing. `atonal`
 * means no pitch was found at all, which is a different fault from `mode`.
 */
int cmdSweep(const std::string &unit, const std::string &patchName, const Options &opt) {
    Bank bank;
    if (!loadBank(unit, bank)) return 1;
    Options one = opt;
    one.quiet = true;
    int worst = 0;
    for (const BankPatch &p : bank.patches) {
        if (!patchName.empty() && p.name != patchName) continue;
        if (p.low < 0 || p.high <= p.low) continue;
        std::printf("\n%s  %s  notes %d..%d\n", bank.unit.c_str(), p.name.c_str(), p.low, p.high);
        std::printf("   note   cents     loud  harm   part   root   speaks\n");
        std::vector<float> rmsAt;
        std::vector<float> centsAt;
        std::vector<float> speakAt;
        std::vector<float> partAt;
        std::vector<float> harmAt;
        std::vector<float> rootAt;
        for (int n = p.low; n <= p.high; ++n) {
            one.note = n;
            Measured m;
            if (!auditionOne(bank, p, one, m)) break;
            // The note is known, so tuning and partial are measured against it.
            rmsAt.push_back(m.loudnessDb);
            // Counts as pitched if the note's harmonics or its fundamental are
            // there. A sub-octave patch puts most of its energy an octave down
            // and scores low on harmonics while being in tune.
            centsAt.push_back(m.tuned && (m.harmonicity > 0.15f || m.fundamentalDb > -12.0f)
                                  ? m.tuneCents
                                  : -9999.0f);
            speakAt.push_back(m.speaksMs);
            partAt.push_back(m.partialRatio);
            harmAt.push_back(m.harmonicity);
            rootAt.push_back(m.fundamentalDb);
        }
        if (rmsAt.empty()) continue;
        const float loudest = *std::max_element(rmsAt.begin(), rmsAt.end());
        int bad = 0;
        int atonal = 0;
        for (size_t i = 0; i < rmsAt.size(); ++i) {
            const int n = p.low + static_cast<int>(i);
            const bool none = centsAt[i] < -9000.0f;
            const bool dead = rmsAt[i] < loudest - 20.0f;
            // Another partial is loudest and the fundamental is missing, like
            // a sax jumping to its octave. A string with a loud fourth
            // harmonic but a present fundamental is fine.
            const bool mode = partAt[i] > 1.5f && rootAt[i] < -30.0f;
            if (none) ++atonal;
            if (dead || mode || none) ++bad;
            char cents[16];
            if (none) std::snprintf(cents, sizeof(cents), "    -");
            else std::snprintf(cents, sizeof(cents), "%+7.0f", static_cast<double>(centsAt[i]));
            std::printf("   %4d %s  %7.1f  %4.2f  %5.2fx  %+5.0f  %5.0fms%s%s%s\n", n, cents,
                        static_cast<double>(rmsAt[i]), static_cast<double>(harmAt[i]),
                        static_cast<double>(partAt[i]), static_cast<double>(rootAt[i]),
                        static_cast<double>(speakAt[i]),
                        dead ? "  <- dead" : "", mode ? "  <- wrong partial" : "",
                        none && !dead ? "  <- atonal" : "");
        }
        // The level spread across the range and the worst tuning.
        const float quietest = *std::min_element(rmsAt.begin(), rmsAt.end());
        float worstCents = 0.0f;
        for (float c : centsAt) if (c > -9000.0f && std::fabs(c) > std::fabs(worstCents)) worstCents = c;
        std::printf("   %d of %zu notes wrong (%d with no pitch at all), %.0f dB across the "
                    "range, worst tuning %+.0f cents\n",
                    bad, rmsAt.size(), atonal, static_cast<double>(loudest - quietest),
                    static_cast<double>(worstCents));
        worst += bad;
    }
    return worst > 0 ? 1 : 0;
}

int cmdList(const std::string &unit) {
    Bank bank;
    if (!loadBank(unit, bank)) return 1;
    std::printf("%s (%s, role %s)\n", bank.unit.c_str(), bank.path.c_str(), bank.role.c_str());
    for (const BankPatch &p : bank.patches) {
        std::printf("  %-26s %2zu values%s\n", p.name.c_str(), p.values.size(),
                    p.settings.empty() ? "" : ", with settings");
    }
    return 0;
}

/**
 * Turns a JVM dump of the shipped patches into bank files.
 *
 * The patches are normalised floats in Kotlin and the ranges are in the
 * ParamDef tables here, so only this side can write readable units. The dump
 * comes from a unit test on the Kotlin side.
 *
 * Every value is round-tripped as it's written and any that doesn't come back
 * is reported, since a wrong value in a preset just sounds like a bad patch.
 */
int cmdSeed(const std::string &dumpPath) {
    std::ifstream in(dumpPath);
    if (!in) {
        std::fprintf(stderr, "cannot open %s\n"
                             "  ./gradlew :app:testDebugUnitTest --tests '*DumpFactoryPatches*'\n",
                     dumpPath.c_str());
        return 1;
    }
    struct Out {
        std::string machine;
        std::string text;
        int patches = 0;
    };
    std::vector<Out> banks;
    Out *bank = nullptr;
    const ParamDef *defs = nullptr;
    int32_t count = 0;
    int drifted = 0, dropped = 0, values = 0;

    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        const size_t t1 = line.find('\t');
        if (t1 == std::string::npos) continue;
        const std::string kind = line.substr(0, t1);
        const size_t t2 = line.find('\t', t1 + 1);

        if (kind == "patch") {
            const std::string machine = line.substr(t1 + 1, t2 - t1 - 1);
            const std::string name = line.substr(t2 + 1);
            if (bank == nullptr || bank->machine != machine) {
                banks.push_back({machine, "", 0});
                bank = &banks.back();
                defs = MachineRegistry::paramDefs(machine.c_str(), count);
                bank->text = "machine " + machine + "\n";
            }
            ++bank->patches;
            const bool quote = name.find(' ') != std::string::npos;
            bank->text += "\npatch " + (quote ? "\"" + name + "\"" : name) + "\n";
            continue;
        }
        if (bank == nullptr || defs == nullptr) continue;

        if (kind == "set") {
            bank->text += "  set " + line.substr(t1 + 1, t2 - t1 - 1) + " \"" + line.substr(t2 + 1) + "\"\n";
            continue;
        }
        if (kind != "param") continue;
        const std::string name = line.substr(t1 + 1, t2 - t1 - 1);
        const auto v01 = static_cast<float>(std::atof(line.c_str() + t2 + 1));
        int32_t index = -1;
        for (int32_t i = 0; i < count; ++i) {
            if (name == defs[i].name) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            // A name the engine doesn't have has never done anything in the
            // app either.
            std::printf("  %s: no parameter named '%s' - dropped\n", bank->machine.c_str(), name.c_str());
            ++dropped;
            continue;
        }
        const ParamDef &d = defs[index];
        // A patch lists only what it changes, so defaults are left out.
        //
        // Stepped parameters are compared as steps, because the Kotlin
        // literals are rounded to four places (Dice's `"slices" to 0.4286f`
        // is the default step 6). Continuous ones are compared at the
        // precision of those literals.
        bool isDefault;
        if (d.curve == Curve::Stepped) {
            const auto step = [&](float v) { return static_cast<int>(v * static_cast<float>(d.steps - 1) + 0.5f); };
            isDefault = step(v01) == step(d.unmap(d.def));
        } else {
            isDefault = std::fabs(v01 - d.unmap(d.def)) < 5e-5f;
        }
        if (isDefault) continue;
        ++values;

        // Names with a space (Cipher's "wave a" and "wave b") are quoted so
        // they parse. The engine's names can't change because saved patches
        // use them as keys.
        const std::string key = name.find(' ') != std::string::npos ? "\"" + name + "\"" : name;
        char buf[192];
        if (d.curve == Curve::Stepped) {
            const int step = static_cast<int>(v01 * static_cast<float>(d.steps - 1) + 0.5f);
            std::snprintf(buf, sizeof(buf), "  %-14s #%d\n", key.c_str(), step);
        } else {
            const float value = d.map(v01);
            // Checks the exact digits that go in the file.
            char digits[48];
            std::snprintf(digits, sizeof(digits), "%.6g", static_cast<double>(value));
            const float back = d.unmap(static_cast<float>(std::atof(digits)));
            if (std::fabs(back - v01) > 1e-4f) {
                std::printf("  %s %s: %s does not come back (%g -> %g)\n", bank->machine.c_str(), name.c_str(),
                            digits, static_cast<double>(v01), static_cast<double>(back));
                ++drifted;
            }
            std::snprintf(buf, sizeof(buf), "  %-14s %s%s%s\n", key.c_str(), digits,
                          d.unit != nullptr && d.unit[0] != '\0' ? " " : "", d.unit != nullptr ? d.unit : "");
        }
        bank->text += buf;
    }

    for (const Out &b : banks) {
        const std::string path = gBankDir + "/" + b.machine + ".bank";
        std::ofstream out(path);
        if (!out) {
            std::fprintf(stderr, "cannot write %s\n", path.c_str());
            return 1;
        }
        out << "# " << b.machine << " - seeded from what shipped, and not yet voiced.\n"
            << "#\n"
            << "# Values are in each parameter's own units; `audition params " << b.machine << "` lists\n"
            << "# them. A patch names only what it changes.\n\n"
            << b.text;
        std::printf("  %-12s %2d patches -> %s\n", b.machine.c_str(), b.patches, path.c_str());
    }
    std::printf("\n%zu banks, %d values, %d dropped, %d that did not round-trip\n", banks.size(), values,
                dropped, drifted);
    return 0;
}

/**
 * Writes the banks out as the Kotlin the app ships.
 *
 * The app reads the generated file. The bank files are what you edit and
 * what the harness auditions, so what you listened to is exactly what plays.
 * Values are converted once here using the engine's own ParamDef tables.
 *
 * One small function per patch and a lazy list, not one big `listOf(...)`. A
 * patch is about 1.3 KB of bytecode and a method is capped at 64 KB, so one
 * list would hit `Method too large` after about 48 Resonance patches.
 */
int cmdEmit(const std::string &outPath) {
    std::vector<std::string> units;
    for (int32_t i = 0; i < MachineRegistry::count(); ++i) units.emplace_back(MachineRegistry::name(i));
    for (int32_t i = 0; i < EffectRegistry::count(); ++i) {
        units.emplace_back(std::string("fx.") + EffectRegistry::name(i));
    }

    std::string body, dispatch;
    int emitted = 0, patches = 0;
    for (const std::string &unit : units) {
        Bank bank;
        std::string error;
        if (!readBank(bankPath(unit), bank, error)) continue; // no bank for this unit yet
        int32_t count = 0;
        const ParamDef *defs = defsFor(unit, count);
        if (defs == nullptr || count == 0) {
            std::fprintf(stderr, "%s: the engine has no unit by that name\n", unit.c_str());
            return 1;
        }
        // A unique Kotlin identifier, since "fx.Delay" isn't valid as is.
        std::string tag;
        for (char c : unit) tag += (c == '.' ? '_' : static_cast<char>(std::tolower(c)));

        std::string list;
        int n = 0;
        for (const BankPatch &patch : bank.patches) {
            const Resolved r = resolve(patch, defs, count);
            for (const std::string &p : r.problems) {
                std::fprintf(stderr, "%s / %s: %s\n", unit.c_str(), patch.name.c_str(), p.c_str());
                return 1;
            }
            char fn[64];
            std::snprintf(fn, sizeof(fn), "%s%d", tag.c_str(), n);
            list += (n > 0 ? ", " : "") + std::string(fn) + "()";

            body += "\n    private fun " + std::string(fn) + "() = Patch(\"" + unit + "\", \"" +
                    kotlinString(patch.name) + "\",";
            // Only values that differ from the default, in table order. The
            // app fills in the rest, and it keeps diffs readable.
            std::string params;
            int written = 0;
            for (int32_t i = 0; i < count; ++i) {
                if (std::fabs(r.norm[static_cast<size_t>(i)] - defs[i].unmap(defs[i].def)) < 1e-7f) continue;
                char kv[160];
                std::snprintf(kv, sizeof(kv), "%s\"%s\" to %sf", written == 0 ? "" : ", ", defs[i].name,
                              floatLiteral(r.norm[static_cast<size_t>(i)]).c_str());
                params += kv;
                ++written;
            }
            body += written == 0 ? " emptyMap()" : "\n        mapOf(" + params + ")";
            if (!r.settings.empty()) {
                std::string sets;
                for (size_t k = 0; k < r.settings.size(); ++k) {
                    sets += (k > 0 ? ", " : "") + std::string("\"") + r.settings[k].first + "\" to \"" +
                            kotlinString(r.settings[k].second) + "\"";
                }
                body += ",\n        mapOf(" + sets + ")";
            }
            // Named arguments, so they work whether or not settings came
            // before. The app uses the range to place the keyboard.
            if (!patch.family.empty()) {
                body += ",\n        family = \"" + kotlinString(patch.family) + "\"";
            }
            if (patch.low >= 0) {
                char range[64];
                std::snprintf(range, sizeof(range), ",\n        low = %d, high = %d", patch.low, patch.high);
                body += range;
            }
            body += ")\n";
            ++n;
            ++patches;
        }
        body += "\n    private val " + tag + ": List<Patch> by lazy { listOf(" + list + ") }\n";
        dispatch += "        \"" + unit + "\" -> " + tag + "\n";
        ++emitted;
    }

    std::ofstream out(outPath);
    if (!out) {
        std::fprintf(stderr, "cannot write %s\n", outPath.c_str());
        return 1;
    }
    out << "// GENERATED by tools/gen_patches.sh from tools/banks/*.bank - do not edit.\n"
        << "//\n"
        << "// Edit the bank file and run the script. A value there is written in the\n"
        << "// parameter's own units and converted here by the engine's own ParamDef, so\n"
        << "// what the audition harness played is what the app plays.\n"
        << "package com.rm.acidulous.model\n"
        << "\n"
        << "internal object FactoryBanks {\n"
        << "    fun of(unit: String): List<Patch> = when (unit) {\n"
        << dispatch
        << "        else -> emptyList()\n"
        << "    }\n"
        << body
        << "}\n";
    std::printf("%d units, %d patches -> %s\n", emitted, patches, outPath.c_str());
    return 0;
}

void usage() {
    std::printf(
        "audition - play a factory patch on a desk and measure it\n\n"
        "  audition params <Machine|fx.Effect>       the parameter table\n"
        "  audition list   <Machine>                 what is in the bank\n"
        "  audition play   <Machine> <Patch>         one patch: a wav and a row\n"
        "  audition bank   <Machine>                 every patch, and the spread\n"
        "  audition seed   [dump.txt]                what ships today, as bank files\n"
        "  audition emit   [out.kt]                  the banks, as the Kotlin that ships\n"
        "  audition selftest                         the pitch tracker against known tones\n\n"
        "  --phrase note|tune|bass|acid|chord|arp|pad|lead|keys|bell|hold\n"
        "          |chip|drone|mallets|gospel|chorale|combo|swell|chromatic\n"
        "          |velocity|beat|voices\n"
        "  --note N  --vel N  --bpm N  --set name=value\n"
        "  sweep <Unit> [patch]   every note of the range, one line each\n"
        "  --material kit|break|voice|voicetake|map|none   --input voice|noise|break|none\n"
        "  --out DIR one folder per unit under it; the default is build/audition\n"
        "  --ladder  the first twelve harmonics, for machines a centroid cannot describe\n"
        "\n"
        "  The columns, in the order they are printed:\n"
        "    loud      the loudest four hundred milliseconds - the level a player would\n"
        "              call this note, and the one to flatten across a bank. Peak and rms\n"
        "              each level half a bank that holds both plucks and held notes\n"
        "    centroid  brightness; hollow  even harmonics over odd, in dB, so a cylinder\n"
        "              reads about -20 and a cone about -5\n"
        "    tune      how far out, from the note's own low partials. Anchored: the\n"
        "              harness knows which note it asked for and never has to guess one\n"
        "    part      which partial is loudest, over the note. 2.00 is an octave up\n"
        "    harm      how much of the energy stands on the note's harmonic series\n"
        "    ring      the fundamental's own fall to -60 dB, or - if it holds\n"
        "    click     the corner at the note-on against the sound's own, just after it.\n"
        "              A discontinuity, which is what a click is; over about 4 is a fault\n"
        "    chiff     how much brighter the attack is than the tone. A flute has one on\n"
        "              purpose and so does a pluck; this is not the click column\n"
        "    low       energy under half the note, against all of it\n"
        "  --track   pitch and level against time over the front of the note\n"
        "  --banks DIR  --out DIR\n");
}

} // namespace

int main(int argc, char **argv) {
    const char *root = std::getenv("ACIDULOUS_ROOT");
    gRootDir = root != nullptr ? root : ".";
    gBankDir = gRootDir + "/tools/banks";
    if (root != nullptr) gOutDir = std::string(root) + "/build/audition";

    std::vector<std::string> positional;
    Options opt;
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : ""; };
        if (a == "--phrase") opt.phrase = next();
        else if (a == "--material") opt.material = next();
        else if (a == "--input") opt.input = next();
        else if (a == "--note") opt.note = std::atoi(next().c_str());
        else if (a == "--vel") opt.velocity = std::atoi(next().c_str());
        else if (a == "--bpm") opt.bpm = static_cast<float>(std::atof(next().c_str()));
        else if (a == "--join") { /* ignored: `bank` always does this. Kept so old command lines work */ }
        else if (a == "--ladder") opt.ladder = true;
        else if (a == "--track") opt.track = true;
        else if (a == "--banks") gBankDir = next();
        else if (a == "--out") gOutDir = next();
        else if (a == "--set") {
            const std::string kv = next();
            const size_t eq = kv.find('=');
            if (eq != std::string::npos) opt.sets.emplace_back(kv.substr(0, eq), std::atof(kv.c_str() + eq + 1));
        } else if (a == "-h" || a == "--help") {
            usage();
            return 0;
        } else {
            positional.push_back(a);
        }
    }
    if (positional.empty()) {
        usage();
        return 1;
    }
    const std::string &cmd = positional[0];
    if (cmd == "params" && positional.size() >= 2) return cmdParams(positional[1]);
    if (cmd == "list" && positional.size() >= 2) return cmdList(positional[1]);
    if (cmd == "play" && positional.size() >= 3) return cmdPlay(positional[1], positional[2], opt);
    if (cmd == "bank" && positional.size() >= 2) return cmdBank(positional[1], opt);
    if (cmd == "sweep" && positional.size() >= 2)
        return cmdSweep(positional[1], positional.size() >= 3 ? positional[2] : std::string(), opt);
    if (cmd == "selftest") {
        // Checks the pitch tracker against tones of known pitch. The limit
        // is 2 cents, well under the 10+ cents of a real tuning fault.
        const float worst = trackSelfTest(true);
        std::printf("  worst error %.2f cents: %s\n", static_cast<double>(worst), worst < 2.0f ? "ok" : "FAILED");
        return worst < 2.0f ? 0 : 1;
    }
    if (cmd == "emit") {
        return cmdEmit(positional.size() >= 2
                           ? positional[1]
                           : std::string(std::getenv("ACIDULOUS_ROOT") != nullptr ? std::getenv("ACIDULOUS_ROOT")
                                                                                  : ".") +
                                 "/shared/src/commonMain/kotlin/com/rm/acidulous/model/FactoryBanks.kt");
    }
    if (cmd == "seed") {
        return cmdSeed(positional.size() >= 2 ? positional[1]
                                              : std::string(std::getenv("ACIDULOUS_ROOT") != nullptr
                                                                ? std::getenv("ACIDULOUS_ROOT")
                                                                : ".") +
                                                    "/app/build/factory-dump.txt");
    }
    usage();
    return 1;
}
