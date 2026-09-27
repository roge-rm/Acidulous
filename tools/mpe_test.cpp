// Checks a finger's expression reaches its own voice, and only its own.
//
// A bend that moves every note looks like one that works if you only play one
// note. So every check plays two notes and moves one, then checks the other
// note's sound is unchanged bit for bit.

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
 * The sample machines are silent with nothing mounted and would have to be
 * skipped. A second of a saw-like tone at the root is enough, since the test
 * is whether one note's expression reaches one voice.
 *
 * Static because `swapObject` takes a borrowed pointer and hands the old one
 * back, so the map has to outlive the machine pointing at it.
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
 * Mosaic routes pressure nowhere by default, so without this the harness
 * could only check that pressure on an unheld note changes nothing, which
 * also passes if pressure does nothing at all. Row 0 sends it to amplitude at
 * full depth, so pressure on the held note is heard and on an unheld one
 * isn't.
 *
 * The indices come from Mosaic's own enums. Counting them by hand is easy to
 * get wrong: the destination list is sixteen long.
 */
void mountToneAndRoute(Machine *m) {
    mountTone(m);
    ParamSet &p = m->params();
    // `m01`, not `m00`: slot names count from one though they're indexed
    // from zero, `put(..., "m%02d_src", m + 1)`.
    const int32_t src = p.indexOf("m01_src");
    const int32_t dest = p.indexOf("m01_dest");
    const int32_t depth = p.indexOf("m01_depth");
    // Fail loudly if a name doesn't resolve, or the pressure check fails with
    // no hint that the harness asked for a parameter that doesn't exist.
    if (src < 0 || dest < 0 || depth < 0) {
        std::printf("  FAIL %-52s %s\n", "Mosaic: matrix parameters not found",
                    "m01_src / m01_dest / m01_depth");
        ++failures;
        return;
    }
    using M = acidulous::machine::Mosaic;
    p.set(src, static_cast<float>(M::SrcPressure) / (M::SourceCount - 1.0f));
    p.set(dest, static_cast<float>(M::DstAmp) / (M::DestCount - 1.0f));
    // The bottom of -1..1, not the top. Amplitude is already at full, so
    // adding saturates and changes nothing audible, while taking away is
    // clearly heard.
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
    if (bendAll) m->pitchBend(4096); // channel-wide: everything moves

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
// Poly aftertouch, as a controller outside MPE mode sends it. The message
// names its note and must land exactly where a finger's pressure would.
void polySixty(Machine *m) { m->handleMidi(0xa0, 60, 127); }
void slideUnheld(Machine *m) { m->noteTimbre(72, 127); }

void check(const char *type, bool pressureIsAudible, bool slideIsAudible, void (*mount)(Machine *) = nullptr) {
    const std::vector<uint8_t> one{60};
    const std::vector<uint8_t> two{60, 64};

    const auto alone = play(type, one, nullptr, false, mount);
    if (silent(alone)) {
        std::printf("  --   %-52s %s\n", type, "silent without a sample; skipped");
        return;
    }

    // Reaching. One note, so this works for monophonic machines too (Timber
    // is a woodwind and mono by default).
    ok(play(type, one, bendSixty, false, mount) != alone,
       (std::string(type) + ": a bend reaches the note being played").c_str(), "");

    // Ownership. A bend aimed at a note nobody is holding must change nothing
    // at all. A bend that moves every note looks fine until you play two.
    const auto plain = play(type, two, nullptr, false, mount);
    ok(play(type, two, bendUnheld, false, mount) == plain,
       (std::string(type) + ": a bend for a note not held does nothing").c_str(), "");
    ok(play(type, one, bendUnheld, false, mount) == alone,
       (std::string(type) + ": nor does it disturb the note that is held").c_str(), "");

    // The channel-wide bend still works, for keyboards that aren't MPE.
    ok(play(type, one, nullptr, true, mount) != alone,
       (std::string(type) + ": the channel-wide bend still bends").c_str(), "");

    // Pressure and slide: always owned by their note, and audible wherever
    // the machine uses them by default. That's nearly everywhere: a patch with
    // no matrix row for pressure still opens up and raises the level, and
    // slide defaults to half depth.
    ok(play(type, one, pressUnheld, false, mount) == alone,
       (std::string(type) + ": pressure for a note not held does nothing").c_str(), "");
    ok(play(type, one, slideUnheld, false, mount) == alone,
       (std::string(type) + ": slide for a note not held does nothing").c_str(), "");
    if (pressureIsAudible) {
        const auto pressed = play(type, one, pressSixty, false, mount);
        ok(pressed != alone, (std::string(type) + ": a finger's pressure is heard").c_str(), "");
        ok(play(type, one, polySixty, false, mount) == pressed,
           (std::string(type) + ": poly aftertouch is that finger's pressure").c_str(), "");
    }
    if (slideIsAudible) {
        ok(play(type, one, slideSixty, false, mount) != alone,
           (std::string(type) + ": a finger's slide is heard").c_str(), "");
    }
}

// The organ bends as one, so a finger's bend falls back to the channel's. A
// 48 semitone slide must not wrap the 16-bit bend value.
void bendFortyEight(Machine *m) { m->noteBend(60, 48.0f); }
void bendTop(Machine *m) { m->pitchBend(8191); }
void organ() {
    const std::vector<uint8_t> one{60};
    ok(play("Manual", one, bendFortyEight) == play("Manual", one, bendTop),
       "Manual: a finger's 48-semitone bend is the channel's full bend", "not wrapped round");
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
    // uses it directly: Brazen and Timber use it as that finger's breath.
    check("Trinity", true, true);
    check("Ratio", true, true);
    check("Filament", true, true);
    check("Brazen", true, true);
    check("Timber", true, true);
    // Mosaic reads each voice's own pressure, with -1 meaning "never set", so
    // an ordinary keyboard's channel aftertouch still moves every note.
    check("Mosaic", true, true, mountToneAndRoute);
    // Two machines with per-note pitch only, to check the basic path reaches
    // them too.
    // Cumulus: pressure opens the filter. Slide moves a morph the default
    // patch leaves the same at both ends, so it isn't checked.
    check("Cumulus", true, false);
    check("Formulate", false, false);
    organ();
    std::printf("\nReflux is monophonic and implements no pitch bend at all, so there is\n"
                "nothing here for it to answer. Dice is a slicer and Manual is 91 wheels on\n"
                "one shaft - neither can bend a note on its own.\n");

    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
