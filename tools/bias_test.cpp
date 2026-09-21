// Bias, asked where it is and made to say what it read.
//
// Two things here are worth a harness rather than an ear. The first is that a
// region is a *window*: a take sung across four scenes is one file and four
// offsets, and an offset that is wrong by a bar is a vocal that comes in on
// the wrong word - inaudible as a bug, obvious as a disaster. The second is
// that a cell covers bars x repeat, so the second pass of a scene played twice
// must carry on through the take rather than start it again.
//
// Both are answerable exactly, because a reel can be built out of a ramp: put
// the frame's own index in the sample and whatever comes out says where it was
// read from. Everything outside a region carries a sentinel, so a read that
// wanders off the end of one announces itself instead of sounding plausible.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

#include <engine/core/Reel.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/bias/Bias.h>

using namespace acidulous;
using namespace acidulous::audio;

namespace {
int checks = 0;
int failures = 0;

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    if (cond) {
        printf("  ok   %-56s %s\n", what, detail.c_str());
    } else {
        ++failures;
        printf("  FAIL %-56s %s\n", what, detail.c_str());
    }
}

constexpr int32_t kRate = 48000;
constexpr int64_t kBarTicks = 4 * kPPQN;   // 960
constexpr int32_t kFramesPerTick = 100;    // at 48 kHz and 120 bpm
constexpr int32_t kBarFrames = static_cast<int32_t>(kBarTicks) * kFramesPerTick; // 96000

/**
 * What a frame at index [i] holds, so a reading says where it came from.
 *
 * **Offset by one, because frame nought must not be silence.** Ramped from
 * nought, four of the assertions below compared a reading against 0.0 and
 * would have passed just as well on a machine that rendered nothing at all -
 * which is the failure they exist to catch.
 */
int16_t ramp(int32_t i) { return static_cast<int16_t>(i % 30000 + 1); }

/** Anything a region does not cover. Loud, and nothing a ramp can produce. */
constexpr int16_t kPoison = -30001;

float expectRamp(int32_t frame) { return static_cast<float>(ramp(frame)) / 32768.0f; }

/** A source of [frames], ramped inside [from, to) and poisoned everywhere else. */
std::shared_ptr<Reel::Source> ramped(int32_t frames, int32_t from, int32_t to) {
    auto s = std::make_shared<Reel::Source>();
    s->frames = frames;
    s->stereo = false;
    s->left.assign(static_cast<size_t>(frames), kPoison);
    for (int32_t i = from; i < to && i < frames; ++i) s->left[static_cast<size_t>(i)] = ramp(i);
    return s;
}

/** A source that is one value all the way through, for the summing tests. */
std::shared_ptr<Reel::Source> flat(int32_t frames, int16_t value) {
    auto s = std::make_shared<Reel::Source>();
    s->frames = frames;
    s->stereo = false;
    s->left.assign(static_cast<size_t>(frames), value);
    return s;
}

/** The machine, built the way the rack builds it. */
struct Rig {
    std::unique_ptr<machine::Bias> bias{
        static_cast<machine::Bias *>(MachineRegistry::create("Bias"))};
    Reel reel;
    float L[256]{}, R[256]{};

    Rig() { bias->prepare(kRate); }

    void mount() { bias->swapObject(0, &reel); }

    /** One block at a place in the cell, and what the left channel held. */
    float readAt(int64_t sceneId, int64_t cycleTick, bool muted = false) {
        bias->onScene(sceneId, cycleTick, true, muted);
        bias->render(L, R, 1);
        return L[0];
    }

    /** The same, having first jumped elsewhere so the cursor cannot coast. */
    float probe(int64_t sceneId, int64_t cycleTick, bool muted = false) {
        bias->onScene(0, 0, true, false); // no cell: invalidates every cursor
        bias->render(L, R, 1);
        return readAt(sceneId, cycleTick, muted);
    }
};

Reel::Region region(std::shared_ptr<const Reel::Source> src, int32_t offset, int32_t frames,
                    int32_t ticks, bool loop = false, int32_t startTick = 0) {
    Reel::Region r;
    r.source = std::move(src);
    r.offset = offset;
    r.frames = frames;
    r.ticks = ticks;
    r.bpm = 120.0f;
    r.loop = loop;
    r.startTick = startTick;
    return r;
}

/**
 * Tight enough to tell one frame from the next.
 *
 * A single frame of the ramp is 1/32768, about 3e-5, so the 1e-4 this started
 * at was looser than the thing being measured: it could not have told frame
 * 500 from frame 502, nor either of them from silence.
 */
bool near(float a, float b) { return std::fabs(a - b) < 1e-6f; }

// --- the tests ------------------------------------------------------------

/**
 * A take sung across three scenes is one file at three offsets.
 *
 * The arithmetic this asserts is the whole of "split at the scene lines": get
 * the offset wrong and the Verse cell sings the Intro's words.
 */
void aRegionIsAWindowIntoItsFile() {
    Rig rig;
    const int32_t total = kBarFrames * 24;
    auto src = ramped(total, 0, total);

    // Intro 4 bars x2 -> an 8-bar cell at 0; Verse 8 bars at 8 bars in;
    // Chorus 4 bars x2 at 16 bars in.
    Reel::Cell intro{1, {}};
    intro.lanes[0] = region(src, 0, kBarFrames * 8, kBarTicks * 8);
    Reel::Cell verse{2, {}};
    verse.lanes[0] = region(src, kBarFrames * 8, kBarFrames * 8, kBarTicks * 8);
    Reel::Cell chorus{3, {}};
    chorus.lanes[0] = region(src, kBarFrames * 16, kBarFrames * 8, kBarTicks * 8);
    rig.reel.cells = {intro, verse, chorus};
    rig.mount();

    ok("the Intro's cell starts at the head of the file", near(rig.probe(1, 0), expectRamp(0)));
    ok("the Verse's starts eight bars in", near(rig.probe(2, 0), expectRamp(kBarFrames * 8)),
       "wanted frame " + std::to_string(kBarFrames * 8));
    ok("the Chorus's starts sixteen", near(rig.probe(3, 0), expectRamp(kBarFrames * 16)));
}

/**
 * The second pass of a repeated scene carries on through the take.
 *
 * This is the machine's half of `rackCycleTick`: the scheduler counts the
 * repeats, and Bias has to read that far into the region rather than
 * treating the bar as the whole of it.
 */
void aRepeatedSceneCarriesOn() {
    Rig rig;
    const int32_t total = kBarFrames * 8;
    auto src = ramped(total, 0, total);
    Reel::Cell cell{1, {}};
    cell.lanes[0] = region(src, 0, kBarFrames * 8, kBarTicks * 8); // 4 bars x2
    rig.reel.cells = {cell};
    rig.mount();

    ok("the first pass reads the first bar", near(rig.probe(1, 0), expectRamp(0)));
    ok("and the fifth bar is five bars in",
       near(rig.probe(1, kBarTicks * 4), expectRamp(kBarFrames * 4)),
       "wanted frame " + std::to_string(kBarFrames * 4));
    // The one that catches the mistake rather than the arithmetic: anything
    // that treats an iteration as the whole cycle reads the head of the take
    // again on the second pass, and that is a vocal singing verse one twice.
    ok("and it has not started the take again",
       !near(rig.probe(1, kBarTicks * 4), expectRamp(0)));
}

/** Four lanes sum, at their levels, and a muted one is not in the sum. */
void fourLanesSum() {
    Rig rig;
    Reel::Cell cell{1, {}};
    for (int32_t lane = 0; lane < kReelLanes; ++lane) {
        cell.lanes[lane] = region(flat(kBarFrames, static_cast<int16_t>(1000 * (lane + 1))), 0,
                                  kBarFrames, kBarTicks);
    }
    rig.reel.cells = {cell};
    rig.mount();

    const float one = 1000.0f / 32768.0f;
    ok("four lanes are the sum of four lanes", near(rig.probe(1, 0), one * (1 + 2 + 3 + 4)),
       std::to_string(rig.probe(1, 0)));

    rig.bias->params().set(machine::Bias::Mute2, 1.0f);
    rig.bias->params().jumpAll();
    ok("a muted lane is not in it", near(rig.probe(1, 0), one * (1 + 3 + 4)));

    rig.bias->params().set(machine::Bias::Mute2, 0.0f);
    rig.bias->params().set(machine::Bias::Lane3, 0.5f);
    rig.bias->params().jumpAll();
    ok("and a lane at half is in it by half", near(rig.probe(1, 0), one * (1 + 2 + 1.5f + 4)));
}

/** A muted clip is silent however many lanes have something on them. */
void aMutedClipIsSilent() {
    Rig rig;
    Reel::Cell cell{1, {}};
    cell.lanes[0] = region(flat(kBarFrames, 8000), 0, kBarFrames, kBarTicks);
    rig.reel.cells = {cell};
    rig.mount();
    ok("unmuted, it sounds", !near(rig.probe(1, 0), 0.0f));
    ok("muted, it does not", near(rig.probe(1, 0, true), 0.0f));
}

/**
 * Past its end a lane goes silent rather than wrapping, and a looping one
 * wraps within its own region rather than at the end of the file.
 */
void pastTheEnd() {
    Rig rig;
    const int32_t total = kBarFrames * 4;
    // The region is one bar of a four-bar file: everything past it is poison.
    auto src = ramped(total, 0, kBarFrames);
    Reel::Cell once{1, {}};
    once.lanes[0] = region(src, 0, kBarFrames, kBarTicks * 4);
    Reel::Cell looped{2, {}};
    looped.lanes[0] = region(src, 0, kBarFrames, kBarTicks * 4, /*loop=*/true);
    rig.reel.cells = {once, looped};
    rig.mount();

    const float past = rig.probe(1, kBarTicks * 2);
    ok("past its end a region is silent", near(past, 0.0f), std::to_string(past));
    ok("and never reads the poison beyond it", !near(past, static_cast<float>(kPoison) / 32768.0f));

    const float wrapped = rig.probe(2, kBarTicks * 2);
    ok("a looping region wraps at its own length, not the file's",
       near(wrapped, expectRamp(0)), std::to_string(wrapped));
}

/** A punch-in is silent until the song reaches it. */
void aPunchInWaits() {
    Rig rig;
    auto src = ramped(kBarFrames * 4, 0, kBarFrames * 4);
    Reel::Cell cell{1, {}};
    cell.lanes[0] = region(src, 0, kBarFrames * 2, kBarTicks * 4, false, static_cast<int32_t>(kBarTicks * 2));
    rig.reel.cells = {cell};
    rig.mount();
    ok("before the punch-in there is nothing", near(rig.probe(1, kBarTicks), 0.0f));
    ok("and at it the take begins", near(rig.probe(1, kBarTicks * 2), expectRamp(0)));
}

/** A cell with nothing on it, and a scene the reel has never heard of. */
void nothingToPlay() {
    Rig rig;
    Reel::Cell cell{1, {}};
    rig.reel.cells = {cell};
    rig.mount();
    ok("a cell with no lanes is silent", near(rig.probe(1, 0), 0.0f));
    ok("and a scene that is not on the reel is too", near(rig.probe(99, 0), 0.0f));
}

} // namespace

int main() {
    printf("- a region is a window\n");
    aRegionIsAWindowIntoItsFile();
    printf("- repeats\n");
    aRepeatedSceneCarriesOn();
    printf("- four lanes\n");
    fourLanesSum();
    printf("- mute\n");
    aMutedClipIsSilent();
    printf("- ends\n");
    pastTheEnd();
    printf("- punch-in\n");
    aPunchInWaits();
    printf("- nothing\n");
    nothingToPlay();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
