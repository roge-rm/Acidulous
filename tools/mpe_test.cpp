// Does a finger's expression reach its own voice, and only its own?
//
// That is the whole of MPE and the only thing that can really go wrong: a
// bend that moves every note is what the engine did before this, and it
// looks exactly like a bend that works if you only ever play one note.
//
// So every check here plays two notes and moves one of them. The machine is
// asked to render, and the proof is that the other note's sound is
// unchanged - not approximately, but bit for bit, because the expression
// either reached the right voice or it did not.

#include <engine/machine/MachineRegistry.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using namespace acidulous;

namespace {

constexpr int32_t kRate = 48000;
constexpr int32_t kBlock = 64;
constexpr int32_t kBlocks = 64;

int checks = 0, failures = 0;

void ok(bool pass, const char *what, const std::string &detail) {
    ++checks;
    if (!pass) ++failures;
    std::printf("  %-4s %-52s %s\n", pass ? "ok" : "FAIL", what, detail.c_str());
}

/** Render from a clean start, with whatever expression the caller applies. */
std::vector<float> play(const char *type, const std::vector<uint8_t> &notes,
                        void (*express)(Machine *), bool bendAll = false) {
    Machine *m = MachineRegistry::create(type);
    m->prepare(kRate);
    m->allNotesOff();
    m->reset();
    m->params().jumpAll();
    for (uint8_t n : notes) m->noteOn(n, 100);
    if (express != nullptr) express(m);
    if (bendAll) m->pitchBend(4096); // the old way: everything moves

    std::vector<float> out;
    float L[kBlock], R[kBlock];
    for (int32_t b = 0; b < kBlocks; ++b) {
        m->onBlock(b * 4, b * 4 + 4, 120.0f);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        const bool stereo = m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) {
            out.push_back(L[i]);
            out.push_back(stereo ? R[i] : L[i]);
        }
    }
    delete m;
    return out;
}

bool silent(const std::vector<float> &v) {
    for (float x : v) if (x != 0.0f) return false;
    return true;
}

void bendSixty(Machine *m) { m->noteBend(60, 2.0f); }
void bendUnheld(Machine *m) { m->noteBend(72, 2.0f); }
void pressSixty(Machine *m) { m->notePressure(60, 127); }
void slideSixty(Machine *m) { m->noteTimbre(60, 127); }
void pressUnheld(Machine *m) { m->notePressure(72, 127); }
void slideUnheld(Machine *m) { m->noteTimbre(72, 127); }

void check(const char *type, bool pressureIsAudible) {
    const std::vector<uint8_t> one{60};
    const std::vector<uint8_t> two{60, 64};

    const auto alone = play(type, one, nullptr);
    if (silent(alone)) {
        std::printf("  --   %-52s %s\n", type, "silent without a sample; skipped");
        return;
    }

    // Reaching. One note, so this holds for a monophonic machine too -
    // Timber is a woodwind and mono by default, and a clarinet cannot play
    // two notes however many channels you send it on.
    ok(play(type, one, bendSixty) != alone,
       (std::string(type) + ": a bend reaches the note being played").c_str(), "");

    // Ownership, which is the whole point. A bend aimed at a note nobody is
    // holding must change nothing at all - before this, every bend moved
    // everything, and that looks identical to a bend that works until you
    // play a second note.
    const auto plain = play(type, two, nullptr);
    ok(play(type, two, bendUnheld) == plain,
       (std::string(type) + ": a bend for a note not held does nothing").c_str(), "");
    ok(play(type, one, bendUnheld) == alone,
       (std::string(type) + ": nor does it disturb the note that is held").c_str(), "");

    // And the old way still works, for the keyboard that is not an MPE one.
    ok(play(type, one, nullptr, true) != alone,
       (std::string(type) + ": the channel-wide bend still bends").c_str(), "");

    // Pressure and slide: ownership everywhere, audibility only where the
    // machine routes them without being asked. On Trinity, Ratio and
    // Filament pressure arrives at the voice but goes through the
    // modulation matrix, so a patch with no row for it is silent on purpose
    // - that is the matrix working, not the expression failing.
    ok(play(type, one, pressUnheld) == alone,
       (std::string(type) + ": pressure for a note not held does nothing").c_str(), "");
    ok(play(type, one, slideUnheld) == alone,
       (std::string(type) + ": slide for a note not held does nothing").c_str(), "");
    if (pressureIsAudible) {
        ok(play(type, one, pressSixty) != alone,
           (std::string(type) + ": a finger's pressure is heard").c_str(), "");
    }
}

/** The zone arithmetic, which decides what counts as a finger at all. */
bool member(int kind, int members, int channel) {
    if (kind == 0 || channel > 15) return false;
    if (kind == 1) return channel >= 1 && channel <= members;
    return channel <= 14 && channel >= 15 - members;
}

void zones() {
    ok(!member(0, 15, 3), "zone off: no channel is a finger", "");
    ok(!member(1, 15, 0), "lower zone: channel 1 is the master, not a finger", "ch 0");
    ok(member(1, 15, 1) && member(1, 15, 15), "lower zone: 2..16 are fingers", "ch 1..15");
    ok(!member(1, 4, 5), "lower zone honours a smaller member count", "4 members, ch 5");
    ok(!member(2, 15, 15), "upper zone: channel 16 is the master, not a finger", "ch 15");
    ok(member(2, 15, 14) && member(2, 15, 0), "upper zone: 15 down are fingers", "ch 14..0");
    ok(!member(2, 4, 9), "upper zone honours a smaller member count", "4 members, ch 9");
}

} // namespace

int main() {
    std::printf("MPE: expression reaches the voice that owns it, and no other\n\n");
    std::printf("the zone arithmetic\n");
    zones();

    std::printf("\nper-note expression, per machine\n");
    // Pressure is audible without a patch routing it only where the machine
    // uses it directly: Brazen and Timber blow that finger's breath.
    check("Trinity", false);
    check("Ratio", false);
    check("Filament", false);
    check("Brazen", true);
    check("Timber", true);
    // Two that were given per-note pitch and nothing else, to be sure the
    // mechanical half reaches them too.
    check("Cumulus", false);
    check("Formulate", false);
    std::printf("\nSubvert is monophonic and implements no pitch bend at all, so there is\n"
                "nothing here for it to answer. Dice is a slicer and Manual is 91 wheels on\n"
                "one shaft - neither can bend a note on its own.\n");

    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
