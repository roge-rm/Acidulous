// The lyric chord simulator's engine: Diction and the chord decoder, built as
// a standalone WebAssembly module (fm1/sim/build.sh) that the page's audio
// worklet runs. No Emscripten runtime: the page calls these exports directly.
#include <engine/core/Constants.h>
#include <engine/machine/diction/Diction.h>
#include <engine/machine/diction/Phones.h>

#include <cstdint>
#include <cstring>

extern "C" {
#include "../chords.h"
}

using acidulous::machine::Diction;

namespace {

constexpr int32_t kFrames = 128; // a Web Audio render quantum
Diction *diction = nullptr;
float outL[kFrames], outR[kFrames];
// Text both ways: a chord's sounds out, a syllable's sounds in.
char text[256];

} // namespace

extern "C" {

__attribute__((export_name("sim_init"))) void sim_init(int32_t sampleRate) {
    diction = new Diction();
    diction->prepare(sampleRate);
    diction->params().jumpAll();
}

__attribute__((export_name("sim_left"))) float *sim_left() { return outL; }
__attribute__((export_name("sim_right"))) float *sim_right() { return outR; }
__attribute__((export_name("sim_text"))) char *sim_text() { return text; }

__attribute__((export_name("sim_render"))) void sim_render(int32_t frames) {
    std::memset(outL, 0, sizeof outL);
    std::memset(outR, 0, sizeof outR);
    if (!diction->render(outL, outR, frames > kFrames ? kFrames : frames))
        std::memcpy(outR, outL, sizeof outL);
}

/** The sounds of a chord into sim_text(), as phone names. Returns 0 for a chord the tables don't have. */
__attribute__((export_name("sim_chord"))) int32_t sim_chord(uint32_t chord) {
    return chord_read(chord, text, sizeof text);
}

/** The sounds in sim_text() (phone names, [length] bytes) are the next note's words. */
__attribute__((export_name("sim_lyric"))) void sim_lyric(int32_t length) {
    text[length < static_cast<int32_t>(sizeof text) ? length : sizeof text - 1] = 0;
    uint8_t phones[Diction::kMaxPhones];
    const int32_t n = acidulous::machine::diction::parsePhones(text, phones, Diction::kMaxPhones);
    diction->lyric(phones, n);
}

__attribute__((export_name("sim_note_on"))) void sim_note_on(int32_t note, int32_t velocity) {
    diction->noteOn(static_cast<uint8_t>(note), static_cast<uint8_t>(velocity));
}

__attribute__((export_name("sim_note_off"))) void sim_note_off(int32_t note) {
    diction->noteOff(static_cast<uint8_t>(note));
}

__attribute__((export_name("sim_all_off"))) void sim_all_off() { diction->allNotesOff(); }

/** Diction's parameter [index] (Diction::P) to [value], in its own units (semitones, seconds...). */
__attribute__((export_name("sim_param"))) void sim_param(int32_t index, float value) {
    auto &p = diction->params();
    p.set(index, p.def(index).unmap(value));
}

} // extern "C"
