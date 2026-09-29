// The cutter: does it find the singing, a vowel's steady part and a
// consonant, in takes like a phone makes?
//
// Until real voices are recorded, the takes are Diction's own voice singing
// every first-stage prompt, with what a phone adds: silence before and
// after, room noise and rumble, an uneven level, and a note sung a little
// off. Clean synthetic takes would hide exactly the faults a real one shows.
//
// A carrier is sung as a held "ah", then the consonant and "ah" joined to it,
// so the consonant starts at a known moment. A diphthong is held on its first
// vowel and moves to the second when the note is let go, as Diction sings it
// and as a singer does.
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/diction/Cutter.h>
#include <engine/machine/diction/Diction.h>
#include <engine/machine/diction/Phones.h>

#include <cmath>
#include <cstdio>
#include <memory>
#include <string>
#include <vector>

using namespace acidulous;
using machine::Diction;

namespace {

constexpr float kSr = 48000.0f;
constexpr int32_t kBlock = 64;
constexpr int kNote = 57; // the bank's default note, 220 Hz

uint32_t seed = 12345;
float rnd() { seed = seed * 1664525u + 1013904223u; return static_cast<float>(seed >> 8) / 16777216.0f; }

struct Take {
    std::vector<float> mono;
    float sungFrom = 0.0f;              // seconds
    float consonantFrom = 0.0f, consonantTo = 0.0f; // seconds, carriers only
    float letGo = 0.0f;                 // seconds, when the note ends
    float cents = 0.0f;                 // how far off the note it's sung
};

/** How long a consonant lasts in Diction's voice, near enough: closure, burst and breath for a stop. */
float lengthOf(const char *name) {
    int32_t count = 0;
    const machine::diction::Phone *table = machine::diction::phoneTable(count);
    const int32_t code = machine::diction::phoneCode(name, static_cast<int32_t>(std::string(name).size()));
    const machine::diction::Phone &p = table[code];
    switch (p.kind) {
    case machine::diction::Kind::Stop: return p.length + 0.006f + (p.voiced ? 0.0f : 0.035f);
    default: return p.length;
    }
}

Take sing(const std::vector<std::string> &sounds, float holdFor = 1.8f) {
    Take take;
    std::unique_ptr<Machine> m(MachineRegistry::create("Diction"));
    m->prepare(static_cast<int32_t>(kSr));
    m->params().jumpAll();
    m->reset();
    // Sung a little off the note, as a person would.
    take.cents = (rnd() - 0.5f) * 50.0f;
    m->pitchBend(static_cast<int16_t>(take.cents / 100.0f / 2.0f * 8192.0f));
    const float lead = 0.2f + 0.5f * rnd();
    take.sungFrom = lead;
    const bool held = sounds.size() == 1;
    const float joinAt = lead + 0.9f;
    const float offAt = held ? lead + holdFor : joinAt + 1.0f;
    take.letGo = offAt;
    const float total = offAt + 0.6f;
    uint8_t first[4], second[4];
    const int32_t n1 = machine::diction::parsePhones(sounds[0].c_str(), first, 4);
    int32_t n2 = 0;
    if (!held) {
        const std::string rest = sounds[1] + " " + sounds[2];
        n2 = machine::diction::parsePhones(rest.c_str(), second, 4);
        take.consonantFrom = joinAt;
        take.consonantTo = joinAt + lengthOf(sounds[1].c_str());
    }
    const float level = 0.3f + 0.7f * rnd();
    float L[kBlock], R[kBlock];
    float rumble = 0.0f;
    for (int32_t b = 0; b < static_cast<int32_t>(total * kSr / kBlock); ++b) {
        const float t = static_cast<float>(b * kBlock) / kSr;
        if (b == static_cast<int32_t>(lead * kSr / kBlock)) { m->lyric(first, n1); m->noteOn(kNote, 100); }
        if (!held && b == static_cast<int32_t>(joinAt * kSr / kBlock)) { m->lyric(second, n2); m->noteOn(kNote, 100); }
        if (b == static_cast<int32_t>(offAt * kSr / kBlock)) m->noteOff(kNote);
        std::fill(L, L + kBlock, 0.0f);
        std::fill(R, R + kBlock, 0.0f);
        m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) {
            // A room at about -55 dB, and rumble from holding the phone.
            rumble = 0.999f * rumble + 0.001f * (rnd() - 0.5f);
            const float room = (rnd() - 0.5f) * 0.004f + rumble * 0.6f;
            take.mono.push_back(0.5f * (L[i] + R[i]) * level + room);
        }
        (void)t;
    }
    return take;
}

int failures = 0, checks = 0;
void check(bool ok, const std::string &what, const std::string &detail) {
    ++checks;
    if (!ok) ++failures;
    if (!ok) std::printf("  FAIL %-22s %s\n", what.c_str(), detail.c_str());
}

} // namespace

int main() {
    const char *vowels[] = {"IY", "IH", "EH", "AE", "AA", "AO", "AH", "UH", "UW", "ER"};
    const char *diphthongs[] = {"EY", "AY", "AW", "OY", "OW"};
    const char *consonants[] = {"P", "B", "T", "D", "K", "G", "CH", "JH", "F", "V", "TH", "DH",
                                "S", "Z", "SH", "ZH", "HH", "M", "N", "NG", "L", "R", "W", "Y"};
    const float noteHz = 440.0f * std::exp2((kNote - 69) / 12.0f);
    float worstStart = 0.0f, worstEdge = 0.0f;

    std::printf("\nheld vowels\n");
    for (const char *v : vowels) {
        const Take take = sing({v});
        const auto cut = machine::diction::cutTake(take.mono, kSr, machine::diction::TakeKind::Held, noteHz);
        const float start = static_cast<float>(cut.start) / kSr;
        worstStart = std::max(worstStart, std::fabs(start - take.sungFrom));
        check(cut.problem.empty(), v, "said: " + cut.problem);
        check(std::fabs(start - take.sungFrom) < 0.06f, std::string(v) + " start", std::to_string(start) + " s, sung at " + std::to_string(take.sungFrom));
        check(cut.holdTo - cut.holdFrom > static_cast<int32_t>(0.3f * kSr), std::string(v) + " hold", std::to_string((cut.holdTo - cut.holdFrom) / kSr) + " s");
        check(std::fabs(cut.centsOff - take.cents) < 12.0f, std::string(v) + " pitch", std::to_string(cut.centsOff) + " ct, sung " + std::to_string(take.cents));
    }

    std::printf("diphthongs\n");
    float worstGlide = 0.0f;
    for (const char *v : diphthongs) {
        const Take take = sing({v});
        const auto cut = machine::diction::cutTake(take.mono, kSr, machine::diction::TakeKind::Glide, noteHz);
        const float glide = static_cast<float>(cut.glideFrom) / kSr;
        if (cut.problem.empty()) worstGlide = std::max(worstGlide, std::fabs(glide - take.letGo));
        char detail[160];
        std::snprintf(detail, sizeof(detail), "glide %.3f-%.3f s, let go at %.3f s, hold %.3f-%.3f s %s", glide,
                      static_cast<float>(cut.glideTo) / kSr, take.letGo, cut.holdFrom / kSr, cut.holdTo / kSr, cut.problem.c_str());
        check(cut.problem.empty(), v, detail);
        // The glide begins as the note is let go and takes about a tenth of a second.
        check(cut.problem.empty() && std::fabs(glide - take.letGo) < 0.06f, std::string(v) + " glide", detail);
        check(cut.glideTo > cut.glideFrom && cut.glideTo - cut.glideFrom < static_cast<int32_t>(0.25f * kSr), std::string(v) + " glide length", detail);
        check(cut.holdTo - cut.holdFrom > static_cast<int32_t>(0.3f * kSr) && cut.holdTo <= cut.glideFrom, std::string(v) + " hold", detail);
        check(std::fabs(cut.centsOff - take.cents) < 12.0f, std::string(v) + " pitch", std::to_string(cut.centsOff) + " ct, sung " + std::to_string(take.cents));
    }

    std::printf("ah-C-ah\n");
    for (const char *c : consonants) {
        const Take take = sing({"AA", c, "AA"});
        const auto cut = machine::diction::cutTake(take.mono, kSr, machine::diction::TakeKind::Between, noteHz);
        const float from = static_cast<float>(cut.consonantFrom) / kSr, to = static_cast<float>(cut.consonantTo) / kSr;
        const float edge = std::max(std::fabs(from - take.consonantFrom), std::fabs(to - take.consonantTo));
        if (cut.problem.empty()) worstEdge = std::max(worstEdge, edge);
        char detail[160];
        std::snprintf(detail, sizeof(detail), "found %.3f-%.3f s, sung %.3f-%.3f s %s", from, to, take.consonantFrom,
                      take.consonantTo, cut.problem.c_str());
        check(cut.problem.empty(), std::string(c), detail);
        check(cut.problem.empty() && edge < 0.07f, std::string(c) + " edges", detail);
    }

    std::printf("takes that should be sung again\n");
    {
        std::vector<float> silence(static_cast<size_t>(kSr * 2.0f));
        for (auto &s : silence) s = (rnd() - 0.5f) * 0.004f;
        const auto cut = machine::diction::cutTake(silence, kSr, machine::diction::TakeKind::Held, noteHz);
        check(cut.problem == "too quiet", "silence", "said: " + cut.problem);
        const Take vowel = sing({"AA"});
        const auto none = machine::diction::cutTake(vowel.mono, kSr, machine::diction::TakeKind::Between, noteHz);
        check(none.problem == "no consonant found", "no consonant", "said: " + none.problem);
        // A vowel with no glide in it is still usable: held, with its glide
        // put where it ends.
        const auto still = machine::diction::cutTake(vowel.mono, kSr, machine::diction::TakeKind::Glide, noteHz);
        char detail[160];
        std::snprintf(detail, sizeof(detail), "glide %.3f s, let go at %.3f s, hold %.3f s %s", still.glideFrom / kSr,
                      vowel.letGo, (still.holdTo - still.holdFrom) / kSr, still.problem.c_str());
        check(still.problem.empty() && std::fabs(still.glideFrom / kSr - vowel.letGo) < 0.15f &&
                  still.holdTo - still.holdFrom > static_cast<int32_t>(0.3f * kSr) && still.holdTo <= still.glideFrom,
              "no glide", detail);
        const Take early = sing({"AY"}, 0.25f);
        const auto soon = machine::diction::cutTake(early.mono, kSr, machine::diction::TakeKind::Glide, noteHz);
        check(soon.problem == "glide too soon", "glide too soon", "said: " + soon.problem);
    }

    std::printf("\nworst start %.0f ms, worst consonant edge %.0f ms, worst glide %.0f ms\n", worstStart * 1000.0f,
                worstEdge * 1000.0f, worstGlide * 1000.0f);
    std::printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
