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

#include <engine/core/SampleMap.h>
#include <engine/machine/mosaic/Mosaic.h>
#include <engine/machine/MachineRegistry.h>
#include <cmath>
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
/**
 * One looping tone, mapped across the whole keyboard.
 *
 * The sample machines render silence with nothing mounted, so without this
 * they can only be skipped - which is what Mosaic was, while the very thing
 * being changed was its per-note pressure. A second of a sawish tone at the
 * root is enough: the test is whether one note's expression reaches one
 * voice, not what the voice sounds like.
 *
 * Static because `swapObject` takes a borrowed pointer and hands the old one
 * back - the map has to outlive the machine that is pointing at it.
 */
const SampleMap &toneMap() {
    static SampleMap map = [] {
        SampleMap m;
        m.name = "tone";
        SampleData s;
        s.name = "tone";
        s.frames = kRate;
        s.rate = kRate;
        s.left.resize(static_cast<size_t>(s.frames));
        for (int32_t i = 0; i < s.frames; ++i) {
            const float t = static_cast<float>(i) / static_cast<float>(kRate);
            // 261.63 Hz, the root the zone below declares, so a note plays at
            // its own pitch rather than at a transposition of somebody else's.
            const float ph = t * 261.63f;
            s.left[static_cast<size_t>(i)] = 2.0f * (ph - std::floor(ph)) - 1.0f;
        }
        m.samples.push_back(std::move(s));
        MapZone z;
        z.sample = 0;
        z.lowKey = 0;
        z.highKey = 127;
        z.rootKey = 60;
        z.loopStart = 0;
        z.loopEnd = kRate - 1;
        m.zones.push_back(z);
        return m;
    }();
    return map;
}

void mountTone(Machine *m) { m->swapObject(0, const_cast<SampleMap *>(&toneMap())); }

/**
 * The tone, and a matrix row that makes pressure audible.
 *
 * Without this, Mosaic's pressure is routed nowhere by default, so the only
 * thing the harness could say about it was negative - that pressure for a
 * note *not* held changes nothing. That passes just as well when pressure
 * does nothing at all, which is the one thing a test of pressure must not
 * accept. Row 0 sends it to the amplitude at full depth, so a finger leaning
 * on the held note is heard and a finger on an unheld one still is not.
 *
 * The indices come from Mosaic's own enums rather than being counted by
 * hand. Counting them by hand is how this was first written and it put the
 * destination at DstAmp's position in a list six long, when the list is
 * sixteen - so the row drove a grain rate and the test failed while the code
 * under it was right.
 */
void mountToneAndRoute(Machine *m) {
    mountTone(m);
    ParamSet &p = m->params();
    // `m01`, not `m00`: the slots are numbered from one in their names even
    // though they are indexed from zero - `put(..., "m%02d_src", m + 1)`.
    const int32_t src = p.indexOf("m01_src");
    const int32_t dest = p.indexOf("m01_dest");
    const int32_t depth = p.indexOf("m01_depth");
    // **Loudly.** This returned quietly when a name did not resolve, so the
    // row was never written and the check that pressure is heard failed with
    // nothing to say why - which read as the machine being broken rather
    // than the harness asking for a parameter that does not exist.
    if (src < 0 || dest < 0 || depth < 0) {
        std::printf("  FAIL %-52s %s\n", "Mosaic: matrix parameters not found",
                    "m01_src / m01_dest / m01_depth");
        ++failures;
        return;
    }
    using M = acidulous::machine::Mosaic;
    p.set(src, static_cast<float>(M::SrcPressure) / (M::SourceCount - 1.0f));
    p.set(dest, static_cast<float>(M::DstAmp) / (M::DestCount - 1.0f));
    // The *bottom* of -1..1, not the top. Amplitude sits at full already, so
    // adding to it saturates and a leaning finger changes nothing audible;
    // taking away from it is plainly heard. The test is that the voice hears
    // its own pressure, and quieter proves that as well as louder.
    p.set(depth, 0.0f);
    p.jumpAll();
}

std::vector<float> play(const char *type, const std::vector<uint8_t> &notes,
                        void (*express)(Machine *), bool bendAll = false,
                        void (*mount)(Machine *) = nullptr) {
    Machine *m = MachineRegistry::create(type);
    m->prepare(kRate);
    if (mount != nullptr) mount(m);
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

void check(const char *type, bool pressureIsAudible, void (*mount)(Machine *) = nullptr) {
    const std::vector<uint8_t> one{60};
    const std::vector<uint8_t> two{60, 64};

    const auto alone = play(type, one, nullptr, false, mount);
    if (silent(alone)) {
        std::printf("  --   %-52s %s\n", type, "silent without a sample; skipped");
        return;
    }

    // Reaching. One note, so this holds for a monophonic machine too -
    // Timber is a woodwind and mono by default, and a clarinet cannot play
    // two notes however many channels you send it on.
    ok(play(type, one, bendSixty, false, mount) != alone,
       (std::string(type) + ": a bend reaches the note being played").c_str(), "");

    // Ownership, which is the whole point. A bend aimed at a note nobody is
    // holding must change nothing at all - before this, every bend moved
    // everything, and that looks identical to a bend that works until you
    // play a second note.
    const auto plain = play(type, two, nullptr, false, mount);
    ok(play(type, two, bendUnheld, false, mount) == plain,
       (std::string(type) + ": a bend for a note not held does nothing").c_str(), "");
    ok(play(type, one, bendUnheld, false, mount) == alone,
       (std::string(type) + ": nor does it disturb the note that is held").c_str(), "");

    // And the old way still works, for the keyboard that is not an MPE one.
    ok(play(type, one, nullptr, true, mount) != alone,
       (std::string(type) + ": the channel-wide bend still bends").c_str(), "");

    // Pressure and slide: ownership everywhere, audibility only where the
    // machine routes them without being asked. On Trinity, Ratio and
    // Filament pressure arrives at the voice but goes through the
    // modulation matrix, so a patch with no row for it is silent on purpose
    // - that is the matrix working, not the expression failing.
    ok(play(type, one, pressUnheld, false, mount) == alone,
       (std::string(type) + ": pressure for a note not held does nothing").c_str(), "");
    ok(play(type, one, slideUnheld, false, mount) == alone,
       (std::string(type) + ": slide for a note not held does nothing").c_str(), "");
    if (pressureIsAudible) {
        ok(play(type, one, pressSixty, false, mount) != alone,
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
    // Mosaic's matrix was already per-voice and its pressure source was not:
    // it read the channel's value inside a function handed the voice. Now it
    // reads the voice's own, with -1 meaning "never told" so an ordinary
    // keyboard's single aftertouch still moves every note.
    check("Mosaic", true, mountToneAndRoute);
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
