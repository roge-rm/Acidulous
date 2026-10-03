// Renders Hammer notes the way the reference recordings were made: one key,
// one velocity, held until it has rung out, each to its own file, with an
// instrument file (SFZ) saying which is which, so tools/hammer_reference/
// survey.py measures the renders exactly as it measured the recordings.
//
//   hammer_render <folder> [--keys anchors|21,33,60] [--velocities 30,60,90,120]
//                 [--seconds N] [--release S] [--adjust file] [name=value ...]
//
// name=value sets a parameter by its name, in its own units ("sustain=0.5").
// --seconds fixes every note's length; otherwise it follows the key, as the
// recordings' lengths do. --release lets the key go after S seconds.
// --adjust multiplies what the keys are (Keys.h) before rendering, for
// calibrate.py: each line a key and field=factor pairs (B, prompt1, after1,
// prompt3, after3, prompt7, after7, unison, uneven, contact, level, knock, hardening, bend), read between the keys
// given on a log scale.
#include <engine/format/WavWriter.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/hammer/Hammer.h>

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

using namespace acidulous;
namespace hammer = acidulous::machine::hammer;

namespace {

constexpr int32_t kSr = 48000, kBlock = 64;
const int kAnchors[] = {21, 24, 28, 33, 36, 40, 45, 48, 52, 57, 60, 64, 69, 72, 76, 81, 84, 88, 93, 96, 100, 105, 108};

std::vector<int> listOf(const char *text) {
    std::vector<int> out;
    for (const char *p = text; *p != '\0';) {
        out.push_back(std::atoi(p));
        while (*p != '\0' && *p != ',') ++p;
        if (*p == ',') ++p;
    }
    return out;
}

/** As long as the reference recordings at that key, near enough. */
float secondsFor(int key) {
    if (key < 36) return 25.0f;
    if (key < 60) return 20.0f;
    if (key < 72) return 15.0f;
    if (key < 84) return 12.0f;
    if (key < 96) return 6.0f;
    return 4.0f;
}

/** The factor for [field] at [key], read between the lines of an --adjust file. */
float factorAt(const std::map<int, std::map<std::string, float>> &adjust, const std::string &field, int key) {
    int lo = -1, hi = -1;
    float flo = 1.0f, fhi = 1.0f;
    for (const auto &row : adjust) {
        const auto f = row.second.find(field);
        if (f == row.second.end()) continue;
        if (row.first <= key) { lo = row.first; flo = f->second; }
        if (row.first >= key && hi < 0) { hi = row.first; fhi = f->second; }
    }
    if (lo < 0 && hi < 0) return 1.0f;
    if (lo < 0) return fhi;
    if (hi < 0 || hi == lo) return flo;
    const float t = static_cast<float>(key - lo) / static_cast<float>(hi - lo);
    return std::exp(std::log(flo) + (std::log(fhi) - std::log(flo)) * t);
}

} // namespace

int main(int argc, char **argv) {
    if (argc < 2) {
        std::fprintf(stderr, "hammer_render <folder> [--keys anchors|a,b,c] [--velocities a,b] [--seconds N] "
                             "[--release S] [name=value ...]\n");
        return 2;
    }
    const std::string folder = argv[1];
    std::vector<int> keys(std::begin(kAnchors), std::end(kAnchors));
    std::vector<int> velocities = {30, 60, 90, 120};
    float fixedSeconds = 0.0f, release = -1.0f;
    std::vector<std::pair<std::string, float>> knobs;
    std::map<int, std::map<std::string, float>> adjust;
    for (int i = 2; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "--keys" && i + 1 < argc) {
            const std::string v = argv[++i];
            if (v != "anchors") keys = listOf(v.c_str());
        } else if (a == "--velocities" && i + 1 < argc) {
            velocities = listOf(argv[++i]);
        } else if (a == "--seconds" && i + 1 < argc) {
            fixedSeconds = static_cast<float>(std::atof(argv[++i]));
        } else if (a == "--adjust" && i + 1 < argc) {
            std::ifstream in(argv[++i]);
            std::string line;
            while (std::getline(in, line)) {
                std::istringstream words(line);
                int key;
                if (!(words >> key)) continue;
                std::string pair;
                while (words >> pair) {
                    const auto eq = pair.find('=');
                    if (eq != std::string::npos) adjust[key][pair.substr(0, eq)] = std::stof(pair.substr(eq + 1));
                }
            }
        } else if (a == "--release" && i + 1 < argc) {
            release = static_cast<float>(std::atof(argv[++i]));
        } else if (a.find('=') != std::string::npos) {
            knobs.emplace_back(a.substr(0, a.find('=')), static_cast<float>(std::atof(a.c_str() + a.find('=') + 1)));
        } else {
            std::fprintf(stderr, "what is %s?\n", a.c_str());
            return 2;
        }
    }

    std::unique_ptr<Machine> m(MachineRegistry::create("Hammer"));
    m->prepare(kSr);
    int32_t count = 0;
    const ParamDef *defs = m->paramDefs(count);
    for (const auto &k : knobs) {
        int32_t at = -1;
        for (int32_t p = 0; p < count; ++p) if (k.first == defs[p].name) at = p;
        if (at < 0) {
            std::fprintf(stderr, "no parameter called %s\n", k.first.c_str());
            return 2;
        }
        m->params().set(at, defs[at].unmap(k.second));
    }
    m->params().jumpAll();
    if (!adjust.empty()) {
        auto *hammer = static_cast<machine::Hammer *>(m.get());
        for (int k = 0; k < 128; ++k) {
            hammer::KeySpec &s = hammer->keySpec(k);
            const int at = k < 21 ? 21 : (k > 108 ? 108 : k);
            s.B *= factorAt(adjust, "B", at);
            s.prompt1 *= factorAt(adjust, "prompt1", at);
            s.after1 *= factorAt(adjust, "after1", at);
            s.prompt3 *= factorAt(adjust, "prompt3", at);
            s.after3 *= factorAt(adjust, "after3", at);
            s.prompt7 *= factorAt(adjust, "prompt7", at);
            s.after7 *= factorAt(adjust, "after7", at);
            s.unison *= factorAt(adjust, "unison", at);
            s.uneven *= factorAt(adjust, "uneven", at);
            s.contact *= factorAt(adjust, "contact", at);
            s.level *= factorAt(adjust, "level", at);
            s.knock *= factorAt(adjust, "knock", at);
            s.hardening *= factorAt(adjust, "hardening", at);
            s.bend *= factorAt(adjust, "bend", at);
        }
        hammer->refreshKeys();
    }

    FILE *sfz = std::fopen((folder + "/renders.sfz").c_str(), "w");
    if (sfz == nullptr) {
        std::fprintf(stderr, "can't write in %s\n", folder.c_str());
        return 1;
    }
    std::fprintf(sfz, "// Hammer renders, by tools/hammer_render.\n");
    float L[kBlock], R[kBlock], both[kBlock * 2];
    for (int key : keys) {
        for (int velocity : velocities) {
            m->reset();
            char name[64];
            std::snprintf(name, sizeof(name), "k%03d_v%03d.wav", key, velocity);
            WavWriter out;
            std::string error;
            if (!out.open(folder + "/" + name, kSr, 32, error)) {
                std::fprintf(stderr, "%s\n", error.c_str());
                return 1;
            }
            const float seconds = fixedSeconds > 0.0f ? fixedSeconds : secondsFor(key);
            const int32_t blocks = static_cast<int32_t>(seconds * kSr / kBlock);
            const int32_t letGo = release >= 0.0f ? static_cast<int32_t>(release * kSr / kBlock) : -1;
            // A short lead-in, as a recording has before the note.
            const int32_t lead = kSr / 20 / kBlock;
            float peak = 0.0f;
            for (int32_t b = 0; b < blocks + lead; ++b) {
                if (b == lead) m->noteOn(static_cast<uint8_t>(key), static_cast<uint8_t>(velocity));
                if (b == lead + letGo) m->noteOff(static_cast<uint8_t>(key));
                std::fill(L, L + kBlock, 0.0f);
                std::fill(R, R + kBlock, 0.0f);
                if (!m->render(L, R, kBlock)) std::copy(L, L + kBlock, R);
                for (int32_t i = 0; i < kBlock; ++i) {
                    both[2 * i] = L[i];
                    both[2 * i + 1] = R[i];
                    peak = std::fmax(peak, std::fmax(std::fabs(L[i]), std::fabs(R[i])));
                }
                out.write(both, kBlock);
            }
            out.close();
            std::fprintf(sfz, "<region> sample=%s key=%d lovel=%d hivel=%d\n", name, key, velocity, velocity);
            std::printf("  %s peak %.3f\n", name, peak);
        }
    }
    std::fclose(sfz);
    return 0;
}
