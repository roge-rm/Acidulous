// Sings phrases from written-out sounds, for listening: whether the words
// can be understood is an ear's call, not a harness's.
//
//   diction_words <folder> [formant] [voice spec] [octave]
//
// Writes one WAV a phrase. A voice spec sings in a recorded voice, as the app
// sends it: "V|PHONE|path|holdFrom|holdTo" a held vowel, "C|PHONE|VOWEL|path|
// from|to" a consonant. The sounds are what the dictionary and the accent
// would give: "|" between notes, "~" at the end of a note joins it to the
// next. A chord is "60+64+67": the first note has the words. MORPH_SPEC is
// a second voice's spec, for the morph knob.
#include <engine/format/WavWriter.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/diction/Diction.h>
#include <engine/machine/diction/Phones.h>
#include <engine/machine/diction/RecordedVoice.h>

#include <fstream>
#include <sstream>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

using namespace acidulous;
using machine::Diction;

namespace {

constexpr float kSr = 48000.0f;
constexpr int32_t kBlock = 64;

struct Phrase {
    const char *name;
    float bpm;
    /** Notes as "pitch:beats:sounds", separated by "|". */
    const char *notes;
};

const Phrase kPhrases[] = {
    {"row-row-row", 100,
     "60:1:R OWP|60:1:R OWP|60:0.75:R OWP|62:0.25:Y OR R|64:2:B OWP T|"
     "64:0.75:JH EHC N|62:0.25:T L IY|64:0.75:D AWP N|65:0.25:DH AX|67:2:S T R IY M"},
    // The same in 6/8, as it's mostly sung: boat as long as each row. In
    // eighths at 200, a dotted quarter is 0.9 s.
    {"row-row-row-6-8", 200,
     "60:3:R OWP|60:3:R OWP|60:2:R OWP|62:1:Y OR R|64:3:B OWP T|"
     "64:2:JH EHC N|62:1:T L IY|64:2:D AWP N|65:1:DH AX|67:6:S T R IY M"},
    {"twinkle", 90,
     "60:1:T W IHC NG|60:1:K AX L|67:1:T W IHC NG|67:1:K AX L|69:1:L IHC|69:1:DX AX L|67:2:S T AA R|"
     "65:1:HH AW|65:1:AY|64:1:W AH N|64:1:DX ER|62:1:W AH T|62:1:Y UW|60:2:AA R"},
    {"happy-birthday", 100,
     "55:0.75:HH AEC~|55:0.25:P IY|57:1:B ER TH|55:1:D EYP|60:1:T UW|59:2:Y UW"},
    {"hello", 90, "64:1:HH AX~|67:2:L OWP"},
    {"vowels", 80, "57:2:IY|59:2:EH|60:2:AA|62:2:OW|64:2:UW|62:1:AE|60:1:AH|59:2:ER|57:3:AY"},
    {"ah-scale", 120, "57:1:AA~|59:1:AA~|61:1:AA~|62:1:AA~|64:1:AA~|66:1:AA~|68:1:AA~|69:3:AA"},
    {"words-one-note", 80,
     "57:1:S IY|57:1:S OC|57:1:SH UW|57:1:M UW N|57:1:N AY N|57:1:T EHC N|57:1:G OWP|57:1:D EYP|"
     "57:1:Y EHC S|57:1:W AH T|57:1:F AY V|57:1:TH IHC NG K|57:1:K AEC T|57:1:B AEC D|57:1:L AY T"},
    {"thin-fin", 80, "57:1:TH IHC N|57:1:F IHC N|57:1:TH IHC NG K|57:1:F IHC NG K|57:1:B ER TH|57:1:S ER F"},
    {"prairie-house", 100, "57:0.5:AX|60:1:B AWP T|59:0.5:DH AX|57:2:HH AWP S"},
    {"central-house", 100, "57:0.5:AX|60:1:B AWC T|59:0.5:DH AX|57:2:HH AWC S"},
    {"american-house", 100, "57:0.5:AX|60:1:B AW T|59:0.5:DH AX|57:2:HH AW S"},
    {"prairie-bag", 100, "57:1:B EG G|59:0.5:AX V|60:2:R AYC S"},
    {"american-bag", 100, "57:1:B AE G|59:0.5:AX V|60:2:R AY S"},
};

struct Note {
    int32_t pitch;
    /** A chord's other notes, "60+64+67": keys with no words, for harmony. */
    std::vector<int32_t> chord;
    float beats;
    std::vector<uint8_t> phones;
    bool tied;
};

std::vector<Note> parse(const char *text) {
    std::vector<Note> notes;
    std::string all(text);
    size_t at = 0;
    while (at <= all.size()) {
        size_t bar = all.find('|', at);
        if (bar == std::string::npos) bar = all.size();
        std::string one = all.substr(at, bar - at);
        at = bar + 1;
        if (one.empty()) continue;
        Note n{};
        const size_t c1 = one.find(':'), c2 = one.find(':', c1 + 1);
        const std::string pitches = one.substr(0, c1);
        n.pitch = std::atoi(pitches.c_str());
        for (size_t plus = pitches.find('+'); plus != std::string::npos; plus = pitches.find('+', plus + 1))
            n.chord.push_back(std::atoi(pitches.c_str() + plus + 1));
        n.beats = std::strtof(one.substr(c1 + 1, c2 - c1 - 1).c_str(), nullptr);
        std::string sounds = one.substr(c2 + 1);
        if (!sounds.empty() && sounds.back() == '~') {
            n.tied = true;
            sounds.pop_back();
        }
        uint8_t codes[Diction::kMaxPhones];
        const int32_t count = machine::diction::parsePhones(sounds.c_str(), codes, Diction::kMaxPhones);
        n.phones.assign(codes, codes + count);
        notes.push_back(n);
    }
    return notes;
}

} // namespace

int main(int argc, char **argv) {
    const std::string folder = argc > 1 ? argv[1] : ".";
    const float formant = argc > 2 ? std::strtof(argv[2], nullptr) : 0.0f;
    const std::string specPath = argc > 3 ? argv[3] : "";
    const float octave = argc > 4 ? std::strtof(argv[4], nullptr) : 0.0f;
    std::unique_ptr<machine::diction::RecordedVoice> voice;
    if (!specPath.empty()) {
        std::ifstream in(specPath);
        std::stringstream all;
        all << in.rdbuf();
        std::string error;
        // Morphing, both voices want their throats' line spectral frequencies.
        voice = machine::diction::RecordedVoice::fromSpec(all.str() + (std::getenv("MORPH_SPEC") ? "\nLSF" : ""), kSr, 4, error);
        if (!error.empty()) std::fprintf(stderr, "  %s\n", error.c_str());
        std::printf("  voice: %zu vowels, %zu diphthongs, %zu consonants\n", voice->vowels.size(), voice->diphthongs.size(),
                    voice->joins.size());
    }
    // A second voice to morph to, from MORPH_SPEC.
    std::unique_ptr<machine::diction::RecordedVoice> voiceB;
    if (const char *morphSpec = std::getenv("MORPH_SPEC")) {
        std::ifstream in(morphSpec);
        std::stringstream all;
        all << in.rdbuf();
        std::string error;
        voiceB = machine::diction::RecordedVoice::fromSpec(all.str() + "\nLSF", kSr, 4, error);
    }
    // Phrases from a file instead, one a line, "name|bpm|notes", with the
    // notes as above but separated by ";": for checking every sound.
    std::vector<std::string> owned;
    std::vector<Phrase> phrases(std::begin(kPhrases), std::end(kPhrases));
    if (const char *file = std::getenv("DICTION_PHRASES")) {
        phrases.clear();
        std::ifstream in(file);
        for (std::string line; std::getline(in, line);) {
            const size_t a = line.find('|'), b = line.find('|', a + 1);
            if (a == std::string::npos || b == std::string::npos) continue;
            owned.push_back(line.substr(0, a));
            std::string notes = line.substr(b + 1);
            for (char &c : notes) if (c == ';') c = '|';
            owned.push_back(notes);
        }
        std::ifstream again(file);
        size_t k = 0;
        for (std::string line; std::getline(again, line);) {
            const size_t a = line.find('|'), b = line.find('|', a + 1);
            if (a == std::string::npos || b == std::string::npos) continue;
            phrases.push_back(Phrase{owned[k].c_str(), std::strtof(line.substr(a + 1, b - a - 1).c_str(), nullptr), owned[k + 1].c_str()});
            k += 2;
        }
    }
    for (const Phrase &phrase : phrases) {
        std::unique_ptr<Machine> m(MachineRegistry::create("Diction"));
        m->prepare(static_cast<int32_t>(kSr));
        m->params().set(Diction::Formant, m->params().def(Diction::Formant).unmap(formant));
        m->params().set(Diction::Octave, m->params().def(Diction::Octave).unmap(octave));
        if (const char *c = std::getenv("CONSONANTS")) m->params().set(Diction::Consonants, m->params().def(Diction::Consonants).unmap(std::strtof(c, nullptr)));
        if (const char *b = std::getenv("BREATH")) m->params().set(Diction::Breath, m->params().def(Diction::Breath).unmap(std::strtof(b, nullptr)));
        if (const char *c = std::getenv("CLEAN")) m->params().set(Diction::Clean, m->params().def(Diction::Clean).unmap(std::strtof(c, nullptr)));
        // Any others by name: PARAMS="vibrato=0,drift=0".
        if (const char *list = std::getenv("PARAMS")) {
            std::stringstream in(list);
            for (std::string item; std::getline(in, item, ',');) {
                const auto eq = item.find('=');
                const int32_t i = eq == std::string::npos ? -1 : m->params().indexOf(item.substr(0, eq).c_str());
                if (i >= 0) m->params().set(i, m->params().def(i).unmap(std::strtof(item.c_str() + eq + 1, nullptr)));
                else std::fprintf(stderr, "  no param %s\n", item.c_str());
            }
        }
        m->params().jumpAll();
        m->reset();
        if (voice) m->swapObject(0, voice.get());
        if (voiceB) m->swapObject(1, voiceB.get());

        const std::vector<Note> notes = parse(phrase.notes);
        const float beat = 60.0f / phrase.bpm * kSr;
        // Each note's start and end in blocks. A note tied to the next ends
        // as the next begins; the others leave a small gap, as a singer
        // breathes between words.
        std::vector<int64_t> on, off;
        // From a file, room before the first note for its consonants to start early.
        float t = kSr * (std::getenv("DICTION_PHRASES") ? 0.6f : 0.2f);
        for (const Note &n : notes) {
            on.push_back(static_cast<int64_t>(t / kBlock));
            const float length = n.beats * beat;
            off.push_back(static_cast<int64_t>((t + length * (n.tied ? 1.0f : 0.92f)) / kBlock));
            t += length;
        }
        const auto blocks = static_cast<int64_t>((t + kSr * 1.5f) / kBlock);

        std::vector<float> stereo;
        float L[kBlock], R[kBlock];
        // Words half a second ahead of their notes, as the scheduler sends them.
        const auto aheadBlocks = static_cast<int64_t>(0.5f * kSr / kBlock);
        for (int64_t b = 0; b < blocks; ++b) {
            for (size_t i = 0; i < notes.size(); ++i) {
                if (on[i] - aheadBlocks == b || (b == 0 && on[i] - aheadBlocks < 0)) {
                    m->wordsAhead(notes[i].phones.data(), static_cast<int32_t>(notes[i].phones.size()),
                                  static_cast<uint8_t>(notes[i].pitch), 100, static_cast<int32_t>((on[i] - b) * kBlock));
                }
            }
            for (size_t i = 0; i < notes.size(); ++i) {
                // A tied note's next begins in the same block its note ends:
                // the new note first, so it's legato.
                if (on[i] == b) {
                    m->lyric(notes[i].phones.data(), static_cast<int32_t>(notes[i].phones.size()));
                    m->noteOn(static_cast<uint8_t>(notes[i].pitch), 100);
                    for (int32_t p : notes[i].chord) m->noteOn(static_cast<uint8_t>(p), 100);
                }
            }
            for (size_t i = 0; i < notes.size(); ++i) {
                if (off[i] == b) {
                    m->noteOff(static_cast<uint8_t>(notes[i].pitch));
                    for (int32_t p : notes[i].chord) m->noteOff(static_cast<uint8_t>(p));
                }
            }
            std::fill(L, L + kBlock, 0.0f);
            std::fill(R, R + kBlock, 0.0f);
            m->render(L, R, kBlock);
            for (int32_t i = 0; i < kBlock; ++i) {
                stereo.push_back(L[i]);
                stereo.push_back(R[i]);
            }
        }
        float peak = 0.0f;
        for (float s : stereo) peak = std::max(peak, std::fabs(s));

        const std::string path = folder + "/" + phrase.name + ".wav";
        WavWriter w;
        std::string error;
        if (!w.open(path, static_cast<int32_t>(kSr), 32, error)) {
            std::fprintf(stderr, "cannot write %s: %s\n", path.c_str(), error.c_str());
            return 1;
        }
        w.write(stereo.data(), static_cast<int32_t>(stereo.size() / 2));
        w.close();
        std::printf("  %-18s %5.1f s  peak %.2f\n", phrase.name, static_cast<float>(stereo.size() / 2) / kSr, peak);
        if (voice) m->swapObject(0, nullptr);
        if (voiceB) m->swapObject(1, nullptr);
    }
    return 0;
}
