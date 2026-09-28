// Sings phrases from written-out sounds, for listening: whether the words
// can be understood is an ear's call, not a harness's.
//
//   diction_words <folder> [formant]
//
// Writes one WAV a phrase. The sounds are what the dictionary and the accent
// would give: "|" between notes, "~" at the end of a note joins it to the
// next.
#include <engine/format/WavWriter.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/diction/Diction.h>
#include <engine/machine/diction/Phones.h>

#include <cstdio>
#include <cstdlib>
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
    {"twinkle", 90,
     "60:1:T W IHC NG|60:1:K AX L|67:1:T W IHC NG|67:1:K AX L|69:1:L IHC|69:1:DX AX L|67:2:S T AA R|"
     "65:1:HH AW|65:1:AY|64:1:W AH N|64:1:DX ER|62:1:W AH T|62:1:Y UW|60:2:AA R"},
    {"happy-birthday", 100,
     "55:0.75:HH AEC~|55:0.25:P IY|57:1:B ER TH|55:1:D EYP|60:1:T UW|59:2:Y UW"},
    {"hello", 90, "64:1:HH AX~|67:2:L OWP"},
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
        n.pitch = std::atoi(one.substr(0, c1).c_str());
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
    for (const Phrase &phrase : kPhrases) {
        std::unique_ptr<Machine> m(MachineRegistry::create("Diction"));
        m->prepare(static_cast<int32_t>(kSr));
        m->params().set(Diction::Formant, m->params().def(Diction::Formant).unmap(formant));
        m->params().jumpAll();
        m->reset();

        const std::vector<Note> notes = parse(phrase.notes);
        const float beat = 60.0f / phrase.bpm * kSr;
        // Each note's start and end in blocks. A note tied to the next ends
        // as the next begins; the others leave a small gap, as a singer
        // breathes between words.
        std::vector<int64_t> on, off;
        float t = kSr * 0.2f;
        for (const Note &n : notes) {
            on.push_back(static_cast<int64_t>(t / kBlock));
            const float length = n.beats * beat;
            off.push_back(static_cast<int64_t>((t + length * (n.tied ? 1.0f : 0.92f)) / kBlock));
            t += length;
        }
        const auto blocks = static_cast<int64_t>((t + kSr * 1.5f) / kBlock);

        std::vector<float> stereo;
        float L[kBlock], R[kBlock];
        for (int64_t b = 0; b < blocks; ++b) {
            for (size_t i = 0; i < notes.size(); ++i) {
                // A tied note's next begins in the same block its note ends:
                // the new note first, so it's legato.
                if (on[i] == b) {
                    m->lyric(notes[i].phones.data(), static_cast<int32_t>(notes[i].phones.size()));
                    m->noteOn(static_cast<uint8_t>(notes[i].pitch), 100);
                }
            }
            for (size_t i = 0; i < notes.size(); ++i) {
                if (off[i] == b) m->noteOff(static_cast<uint8_t>(notes[i].pitch));
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
    }
    return 0;
}
