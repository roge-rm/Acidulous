#pragma once
#include <cstdint>
#include <engine/core/Utterance.h>
#include <memory>
#include <vector>

// The sounds Diction sings from.
//
// A bank is units of a voice, each with its pitch marks, so the singer can be
// moved to any pitch and any formant without the two dragging each other
// along (the same way Molt moves a take). For now it holds the held vowels.
// Consonants and the joins between sounds come later, and so do banks
// recorded in the app. They'll be mounted in place of the built-in one.
namespace acidulous::machine::diction {

/** The vowels, in the order the vowel knob sweeps them: back of the mouth to the front. */
enum Vowel : int32_t { Oo = 0, Oh, Ah, Eh, Ee, kVowels };

struct Unit {
    /** The sound and its pitch marks. [sound.mono] is the whole voice. */
    audio::Utterance sound;
    /**
     * The voice in two bands that add back to [sound.mono], split at about
     * 1.5 kHz. The formant control moves the low band fully and the high band
     * only partly: a big voice still has the ring near 3 kHz that carries it,
     * and without it a lowered voice goes hollow instead of big.
     */
    std::vector<float> low, high;
    /**
     * Breath: air through the same throat, so it carries the vowel and follows
     * the formant as the voice does, instead of hissing beside it. As loud as
     * the voice, so the breath control is a mix. It's read straight through,
     * never a pulse at a time: a pulse reused to raise the pitch is harmless
     * for a voice, but air summed with a copy of itself pulses in level.
     */
    std::vector<float> air;
    /** The part that holds steady, which a held note loops through. In frames. */
    int32_t holdFrom = 0, holdTo = 0;
};

/**
 * Splits [unit]'s voice into its two bands, for a bank built from recordings
 * as well as the generated one. The bands always add back to the voice.
 */
void splitBands(Unit &unit, int32_t sampleRate);

struct VoiceBank {
    Unit vowels[kVowels];
    bool usable() const {
        for (const auto &u : vowels) if (!u.sound.usable() || u.holdTo <= u.holdFrom) return false;
        return true;
    }
};

/**
 * The bank that ships with the machine, made rather than recorded: a modelled
 * glottal pulse through five formant resonances for each vowel, with the
 * small unsteadiness of a real voice. Its pitch marks are exact, because the
 * generator knows where every pulse closes.
 *
 * Pitched between a man's and a woman's voice, so the formant control has
 * room to go either way. Built once and shared.
 */
const VoiceBank &builtInBank(int32_t sampleRate);

/** The generator on its own, for the harness. [rootHz] is the pitch it's sung at. */
std::unique_ptr<VoiceBank> makeBank(int32_t sampleRate, float rootHz);

/** A vowel's first five formants in hertz, as the generator uses them. */
void formantsOf(Vowel v, float hz[5], float bandwidth[5]);

} // namespace acidulous::machine::diction
