// Plays phrases on a machine through a Mouth with its words up, through a
// rack as the app does, for listening: whether the words can be understood
// is an ear's call, not a harness's.
//
//   mouth_words <folder> [tongue]
//
// "tongue" plays Tongue saying the words itself instead, the Mouth's words
// off, to check a render against another tree's.
//
// Writes one numbered WAV a phrase. Notes are "pitch:beats:sounds" separated
// by "|"; a chord is "48+55+64", the first note has the words, and empty
// sounds play the note without any.
#include <engine/effect/EffectRegistry.h>
#include <engine/format/WavWriter.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/diction/Phones.h>
#include <engine/rack/Rack.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <iterator>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

using namespace acidulous;

namespace {

constexpr float kSr = 48000.0f;

struct Phrase {
    const char *name;
    const char *machine;
    float bpm;
    const char *notes;
    /** Mouth parameters, name=value in their own units. */
    const char *mouth;
};

const Phrase kPhrases[] = {
    {"hello-world-pad", "Trinity", 90.0f, "48+55+64:2:HH AH|48+55+64:2:L OW|45+52+60:2:W ER L D|45+52+60:2:", "words=1 vowel=0.3 depth=0"},
    {"ooh-ah-ee-chord", "Trinity", 80.0f, "50+57+65:2:UW|50+57+65:2:AA|50+57+65:2:IY|50+57+65:2:", "words=1 vowel=0.3 depth=0 size=0.7 mix=0.85"},
    {"twinkle-lead", "Trinity", 100.0f,
     "60:1:T W IH NG|60:1:K AH L|67:1:T W IH NG|67:1:K AH L|69:1:L IH|69:1:T AH L|67:2:S T AA R", "words=1 vowel=0.3 depth=0"},
    {"say-the-words-level", "Trinity", 110.0f,
     "57:1:S EY|57:0.5:DH AH|60:1.5:W ER D Z|64:1:S EY|62:0.5:DH AH|57:1.5:W ER D Z", "words=0.6 move=1 vowel=0.05 depth=0.6 size=0.55"},
};

const Phrase kTongue[] = {
    {"tongue-says", "Tongue", 100.0f, "48:1:HH AH|48:1:L OW|50:1:W ER L D|52:2:S T AA R Z|48:1:", "words=0"},
};

std::vector<std::string> split(const std::string &s, char by) {
    std::vector<std::string> out;
    std::string part;
    std::istringstream in(s);
    while (std::getline(in, part, by)) out.push_back(part);
    return out;
}

void setByName(ParamSet &p, const std::string &name, float value) {
    const int32_t i = p.indexOf(name.c_str());
    if (i < 0) {
        std::fprintf(stderr, "no parameter %s\n", name.c_str());
        return;
    }
    p.set(i, p.def(i).unmap(value));
}

} // namespace

int main(int argc, char **argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: mouth_words <folder>\n");
        return 2;
    }
    const std::string folder = argv[1];
    const bool tongue = argc > 2 && std::string(argv[2]) == "tongue";
    std::vector<Phrase> phrases;
    if (tongue) phrases.assign(std::begin(kTongue), std::end(kTongue));
    else phrases.assign(std::begin(kPhrases), std::end(kPhrases));
    int number = 0;
    for (const Phrase &phrase : phrases) {
        std::unique_ptr<Machine> m(MachineRegistry::create(phrase.machine));
        std::unique_ptr<Effect> fx(EffectRegistry::create("Mouth"));
        m->prepare(static_cast<int32_t>(kSr));
        m->reset();
        fx->prepare(static_cast<int32_t>(kSr));
        for (const std::string &kv : split(phrase.mouth, ' ')) {
            const auto eq = kv.find('=');
            setByName(fx->params(), kv.substr(0, eq), std::stof(kv.substr(eq + 1)));
        }
        fx->params().jumpAll();
        Rack rack;
        rack.swapMachine(m.get());
        rack.swapEffect(0, fx.get());

        std::vector<float> stereo;
        const double ticksPerFrame = kPPQN * phrase.bpm / 60.0 / kSr;
        double tick = 0.0;
        const auto render = [&](double beats) {
            const auto frames = static_cast<int64_t>(beats * 60.0 / phrase.bpm * kSr);
            for (int64_t done = 0; done < frames; done += kBlockFrames) {
                const auto t0 = static_cast<int64_t>(tick);
                tick += kBlockFrames * ticksPerFrame;
                rack.onBlock(t0, static_cast<int64_t>(tick), phrase.bpm);
                rack.render(kBlockFrames);
                for (int32_t i = 0; i < kBlockFrames; ++i) {
                    stereo.push_back(rack.bufL[i]);
                    stereo.push_back(rack.bufR[i]);
                }
            }
        };
        render(0.25);
        for (const std::string &note : split(phrase.notes, '|')) {
            const auto parts = split(note, ':');
            std::vector<int> pitches;
            for (const std::string &p : split(parts[0], '+')) pitches.push_back(std::stoi(p));
            const double beats = std::stod(parts[1]);
            if (parts.size() > 2 && !parts[2].empty()) {
                uint8_t phones[32];
                const int32_t n = machine::diction::parsePhones(parts[2].c_str(), phones, 32);
                rack.lyric(phones, n);
            }
            for (int p : pitches) rack.playSequenced(0x90, static_cast<uint8_t>(p), 100);
            render(beats * 0.92);
            for (int p : pitches) rack.playSequenced(0x80, static_cast<uint8_t>(p), 0);
            render(beats * 0.08);
        }
        render(4.0);
        rack.swapEffect(0, nullptr);
        rack.swapMachine(nullptr);

        float peak = 0.0f;
        for (float s : stereo) peak = std::max(peak, std::fabs(s));
        char name[256];
        std::snprintf(name, sizeof name, "%s/%02d-%s.wav", folder.c_str(), ++number, phrase.name);
        WavWriter w;
        std::string error;
        if (!w.open(name, static_cast<int32_t>(kSr), 32, error)) {
            std::fprintf(stderr, "cannot write %s: %s\n", name, error.c_str());
            return 1;
        }
        w.write(stereo.data(), static_cast<int32_t>(stereo.size() / 2));
        w.close();
        std::printf("  %-28s %5.1f s  peak %.2f\n", name + folder.size() + 1, static_cast<float>(stereo.size() / 2) / kSr, peak);
    }
    return 0;
}
