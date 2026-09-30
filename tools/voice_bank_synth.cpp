// A second singer, for checking what's been tuned on one real voice: a whole
// voice bank sung by Diction's own voice, set up unlike the first singer's,
// laid out as the recorder takes it and cut by the cutter.
//
//   voice_bank_synth <folder> <note> <formant> [seed]
//
// Each take is 3.7 s: the count-in (silence and room noise), then the prompt
// sung a moment after "sing now". A carrier's consonant comes where the guide
// bar puts it; a diphthong holds and moves near the end. Writes a WAV per
// prompt, bank.json with the takes and their cuts (as the app keeps them),
// and voice.spec, the lines the app sends the engine.
#include <engine/format/WavWriter.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/diction/Cutter.h>
#include <engine/machine/diction/Diction.h>
#include <engine/machine/diction/Phones.h>

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

using namespace acidulous;
using machine::Diction;

namespace {

constexpr float kSr = 48000.0f;
constexpr int32_t kBlock = 64;
/** The count-in before the take, and the take, in seconds. */
constexpr float kCount = 1.2f, kTake = 2.5f;

uint32_t seed = 12345;
float rnd() { seed = seed * 1664525u + 1013904223u; return static_cast<float>(seed >> 8) / 16777216.0f; }

uint8_t code(const std::string &name) {
    return static_cast<uint8_t>(machine::diction::phoneCode(name.c_str(), static_cast<int32_t>(name.size())));
}

/** Sings [first] from [at] and [second] joined to it from [join] (if any), letting go at [off]; seconds. */
std::vector<float> sing(int note, float formant, const std::vector<std::string> &first, float at,
                        const std::vector<std::string> &second, float join, float off) {
    std::unique_ptr<Machine> m(MachineRegistry::create("Diction"));
    m->prepare(static_cast<int32_t>(kSr));
    m->params().set(Diction::Formant, m->params().def(Diction::Formant).unmap(formant));
    m->params().jumpAll();
    m->reset();
    std::vector<uint8_t> a, b;
    for (const auto &s : first) a.push_back(code(s));
    for (const auto &s : second) b.push_back(code(s));
    const float level = 0.3f + 0.5f * rnd();
    std::vector<float> out;
    float L[kBlock], R[kBlock];
    float rumble = 0.0f;
    const auto blocks = static_cast<int32_t>((kCount + kTake) * kSr / kBlock);
    for (int32_t blk = 0; blk < blocks; ++blk) {
        if (blk == static_cast<int32_t>(at * kSr / kBlock)) { m->lyric(a.data(), static_cast<int32_t>(a.size())); m->noteOn(static_cast<uint8_t>(note), 100); }
        if (!b.empty() && blk == static_cast<int32_t>(join * kSr / kBlock)) { m->lyric(b.data(), static_cast<int32_t>(b.size())); m->noteOn(static_cast<uint8_t>(note), 100); }
        if (blk == static_cast<int32_t>(off * kSr / kBlock)) m->noteOff(static_cast<uint8_t>(note));
        std::fill(L, L + kBlock, 0.0f);
        std::fill(R, R + kBlock, 0.0f);
        m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) {
            // A room at about -55 dB, and rumble from holding the phone.
            rumble = 0.999f * rumble + 0.001f * (rnd() - 0.5f);
            out.push_back(0.5f * (L[i] + R[i]) * level + (rnd() - 0.5f) * 0.004f + rumble * 0.6f);
        }
    }
    return out;
}

void write(const std::string &path, const std::vector<float> &mono) {
    std::vector<float> stereo;
    for (float v : mono) { stereo.push_back(v); stereo.push_back(v); }
    WavWriter w;
    std::string error;
    if (!w.open(path, static_cast<int32_t>(kSr), 24, error)) { std::fprintf(stderr, "cannot write %s: %s\n", path.c_str(), error.c_str()); std::exit(1); }
    w.write(stereo.data(), static_cast<int32_t>(mono.size()));
    w.close();
}

std::string lower(std::string s) { for (char &c : s) c = static_cast<char>(std::tolower(c)); return s; }

} // namespace

int main(int argc, char **argv) {
    if (argc < 4) { std::fprintf(stderr, "voice_bank_synth <folder> <note> <formant> [seed]\n"); return 1; }
    const std::string folder = argv[1];
    const int note = std::atoi(argv[2]);
    const float formant = std::strtof(argv[3], nullptr);
    if (argc > 4) seed = static_cast<uint32_t>(std::atoi(argv[4]));
    const float noteHz = 440.0f * std::exp2((note - 69) / 12.0f);
    const char *vowels[] = {"IY", "IH", "EH", "AE", "AA", "AO", "AH", "UH", "UW", "ER", "EY", "AY", "AW", "OY", "OW"};
    const char *glides[] = {"EY", "AY", "AW", "OY", "OW"};
    const char *consonants[] = {"P", "B", "T", "D", "K", "G", "CH", "JH", "F", "V", "TH", "DH",
                                "S", "Z", "SH", "ZH", "HH", "M", "N", "NG", "L", "R", "W", "Y"};
    const char *carriers[] = {"AA", "IY", "UW"};
    std::ofstream spec(folder + "/voice.spec");
    std::string takes, cuts;
    int usable = 0, total = 0;
    auto record = [&](const std::string &id, const std::vector<float> &mono, machine::diction::TakeKind kind, const std::string &line) {
        write(folder + "/" + id + ".wav", mono);
        // Where the guide put the consonant, from the end, as the app works it out.
        const auto near = kind == machine::diction::TakeKind::Between
                              ? static_cast<int32_t>(static_cast<float>(mono.size()) - kTake * (1.0f - 0.44f) * kSr) : -1;
        const auto c = machine::diction::cutTake(mono, kSr, kind, noteHz, near);
        ++total;
        char buf[512];
        std::snprintf(buf, sizeof(buf), "%s\"%s\": \"%s.wav\"", takes.empty() ? "" : ", ", id.c_str(), id.c_str());
        takes += buf;
        std::snprintf(buf, sizeof(buf),
                      "%s\"%s\": {\"problem\": \"%s\", \"start\": %d, \"end\": %d, \"holdFrom\": %d, \"holdTo\": %d, "
                      "\"glideFrom\": %d, \"glideTo\": %d, \"consonantFrom\": %d, \"consonantTo\": %d, \"rootHz\": %.2f, \"by\": 2}",
                      cuts.empty() ? "" : ", ", id.c_str(), c.problem.c_str(), c.start, c.end, c.holdFrom, c.holdTo, c.glideFrom,
                      c.glideTo, c.consonantFrom, c.consonantTo, static_cast<double>(c.rootHz));
        cuts += buf;
        if (!c.problem.empty()) { std::printf("  %-6s %s\n", id.c_str(), c.problem.c_str()); return; }
        ++usable;
        char out[512];
        if (kind == machine::diction::TakeKind::Held) std::snprintf(out, sizeof(out), line.c_str(), c.holdFrom, c.holdTo);
        else if (kind == machine::diction::TakeKind::Glide) std::snprintf(out, sizeof(out), line.c_str(), c.holdFrom, c.holdTo, c.glideFrom, c.glideTo);
        else std::snprintf(out, sizeof(out), line.c_str(), c.consonantFrom, c.consonantTo);
        spec << out << "\n";
    };
    // Sung a moment after "sing now", as a person reacts.
    const float onset = kCount + 0.15f;
    for (const char *v : vowels) {
        bool glide = false;
        for (const char *g : glides) glide |= std::string(v) == g;
        const std::string id = "v-" + lower(v);
        const std::string path = folder + "/" + id + ".wav";
        const auto mono = sing(note, formant, {v}, onset, {}, 0.0f, kCount + kTake * (glide ? 0.85f : 0.9f));
        if (glide) record(id, mono, machine::diction::TakeKind::Glide, "D|" + std::string(v) + "|" + path + "|%d|%d|%d|%d");
        else record(id, mono, machine::diction::TakeKind::Held, "V|" + std::string(v) + "|" + path + "|%d|%d");
    }
    for (const char *carrier : carriers) {
        for (const char *c : consonants) {
            const std::string id = lower(carrier) + "-" + lower(c);
            const std::string path = folder + "/" + id + ".wav";
            // The consonant where the guide bar puts it: after the first vowel's third.
            const float join = kCount + kTake * 0.36f;
            // An NG can't start a syllable, so it closes the first vowel.
            const bool ng = std::string(c) == "NG";
            const auto mono = ng ? sing(note, formant, {carrier, c}, onset, {carrier}, join + 0.1f, kCount + kTake * 0.9f)
                                 : sing(note, formant, {carrier}, onset, {c, carrier}, join, kCount + kTake * 0.9f);
            record(id, mono, machine::diction::TakeKind::Between, "C|" + std::string(c) + "|" + std::string(carrier) + "|" + path + "|%d|%d");
        }
    }
    std::ofstream bank(folder + "/bank.json");
    bank << "{\"name\": \"synth\", \"note\": " << note << ", \"takes\": {" << takes << "}, \"cuts\": {" << cuts << "}}\n";
    std::printf("%d of %d takes usable\n", usable, total);
    return 0;
}
