// Where a recording's boundaries fall, which is the whole of splitting a take.
//
// The highest-value harness in M54, because a wrong mark does not crash or
// crackle: it silently puts somebody's second verse underneath their first,
// and they find out by listening. Everything it checks is exact arithmetic, so
// there is no reason to find any of it on a phone.
//
// It drives a real SceneScheduler block by block, exactly as `Engine` does,
// and stamps a CaptureMark after each block with a frame count that advances
// by the block - which is what the capture's own `pushed()` does when nothing
// is dropped.
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

#include <engine/machine/MachineRegistry.h>
#include <engine/rack/Rack.h>
#include <sequencer/CaptureMarks.h>
#include <sequencer/SceneScheduler.h>

using namespace acidulous;
using namespace acidulous::seq;

namespace {
int checks = 0;
int failures = 0;

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    if (cond) {
        printf("  ok   %-54s %s\n", what, detail.c_str());
    } else {
        ++failures;
        printf("  FAIL %-54s %s\n", what, detail.c_str());
    }
}

constexpr int32_t kRacks = 2;
constexpr int32_t kBar = 4 * kPPQN; // 960 ticks
/** A bar at 120bpm is two seconds, which is 1500 blocks of 64 frames. */
constexpr int64_t kBarFrames = 2 * kSampleRate;

struct Rig {
    Rack racks[kRacks];
    TickClock clock;
    Transport transport;
    SceneScheduler scheduler;
    CaptureMarks marks;
    std::shared_ptr<SongSnapshot> snap = std::make_shared<SongSnapshot>();
    std::vector<std::shared_ptr<const Clip>> keep;
    int64_t frames = 0;
    int32_t armed = 0;

    Rig() {
        for (auto &rack : racks) rack.swapMachine(MachineRegistry::create("Hexbeat"));
        clock.setSampleRate(kSampleRate);
        clock.requestSongTempo(120.0f);
        snap->rackCount = kRacks;
        scheduler.bind(racks, kRacks, &clock, &transport);
    }
    ~Rig() {
        for (auto &rack : racks) delete rack.swapMachine(nullptr);
    }

    void scene(int64_t id, int32_t bars, int32_t repeat = 1, float bpm = 0.0f) {
        SceneInfo s;
        s.id = id;
        s.bars = bars;
        s.repeat = repeat;
        s.ticksPerBar = kBar;
        s.bpmOverride = bpm;
        snap->scenes.push_back(s);
    }

    void clip(int32_t rack, int32_t sceneIdx, int32_t bars) {
        auto c = std::make_shared<Clip>();
        c->rev = rack * 100 + sceneIdx + 1;
        c->bars = bars;
        c->ticksPerBar = kBar;
        keep.push_back(c);
        snap->setClip(rack, sceneIdx, c);
    }

    void commit() { scheduler.swapSnapshot(snap.get()); }

    void play(int32_t sceneIdx = 0) {
        transport.requestPlay(sceneIdx);
        clock.reset();
        scheduler.start(sceneIdx);
        marks.reset();
        frames = 0;
    }

    /** One block, exactly as Engine::renderBlock takes it. */
    void block() {
        clock.advance(kBlockFrames);
        scheduler.process(clock.blockStart(), clock.blockEnd());
        // The count *before* this block's frames go in - see the note in
        // Engine.cpp. A mark names the frame its cell begins at.
        marks.observe(frames, scheduler.rackSceneId(armed), scheduler.rackCycleTick(armed),
                      scheduler.rackCycleTicks(armed), clock.bpm());
        frames += kBlockFrames;
    }

    void run(double seconds) {
        const int n = static_cast<int>(seconds * kSampleRate / kBlockFrames);
        for (int i = 0; i < n; ++i) block();
    }

    std::string report() const {
        std::string out;
        for (int32_t i = 0; i < marks.count(); ++i) {
            const CaptureMark &m = marks.at(i);
            out += std::to_string(m.frame) + "@s" + std::to_string(m.sceneId) + "+" +
                   std::to_string(m.tick) + " ";
        }
        return out;
    }
};

/** Within a block of where it should be: a boundary lands to the block. */
bool near(int64_t got, int64_t want) { return std::llabs(got - want) <= kBlockFrames; }

// --- The cases ------------------------------------------------------------------

/**
 * Intro(4 x2), Verse(8), Chorus(4 x2): three scenes, three cycles of sixteen
 * seconds each, and then round again.
 *
 * The repeats are the point. A scene played twice is **one** cell of audio, so
 * it makes one mark, not two - which is the same claim `rackCycleTick` makes
 * and the reason a take does not restart half way through.
 */
void aSongWalkedThrough() {
    printf("- Intro(4 x2) / Verse(8) / Chorus(4 x2), recorded straight through\n");
    Rig r;
    r.scene(11, 4, 2);
    r.scene(22, 8);
    r.scene(33, 4, 2);
    for (int32_t s = 0; s < 3; ++s) r.clip(0, s, s == 1 ? 8 : 4);
    r.commit();
    r.play();
    r.run(50.0); // three sixteen-second cells and a little of the fourth

    ok("four cells crossed", r.marks.count() == 4, r.report());
    if (r.marks.count() < 4) return;
    ok("the first is the Intro at frame nought",
       r.marks.at(0).sceneId == 11 && r.marks.at(0).frame == 0, r.report());
    ok("the Verse begins eight bars in", r.marks.at(1).sceneId == 22 &&
       near(r.marks.at(1).frame, 8 * kBarFrames), std::to_string(r.marks.at(1).frame));
    ok("the Chorus eight bars after that", r.marks.at(2).sceneId == 33 &&
       near(r.marks.at(2).frame, 16 * kBarFrames), std::to_string(r.marks.at(2).frame));
    ok("and the song comes round to the Intro", r.marks.at(3).sceneId == 11 &&
       near(r.marks.at(3).frame, 24 * kBarFrames), std::to_string(r.marks.at(3).frame));
    ok("every cell starts at the top of its cycle",
       r.marks.at(1).tick == 0 && r.marks.at(2).tick == 0 && r.marks.at(3).tick == 0,
       r.report());
    ok("and each says the cycle it was made against",
       r.marks.at(0).cycleTicks == 8 * kBar && r.marks.at(1).cycleTicks == 8 * kBar,
       std::to_string(r.marks.at(0).cycleTicks));
}

/**
 * Recording started part way through a cell - a punch-in.
 *
 * The first mark is not at tick nought, and that is exactly what `startTick`
 * on a take is for. Getting this wrong puts the whole recording a bar early.
 */
void aPunchIn() {
    printf("- the recording starts half way through the Verse\n");
    Rig r;
    r.scene(11, 4, 2);
    r.scene(22, 8);
    r.clip(0, 0, 4);
    r.clip(0, 1, 8);
    r.commit();
    r.play();
    // Run into the Verse *before* arming, then start the marks from there.
    r.run(20.0);
    r.marks.reset();
    r.frames = 0;
    r.run(4.0); // four seconds of the Verse, then keep going

    ok("the first mark is the Verse, not the top of it",
       r.marks.count() >= 1 && r.marks.at(0).sceneId == 22 && r.marks.at(0).tick > 0,
       r.report());
    ok("and it is two bars in", r.marks.count() >= 1 &&
       std::llabs(r.marks.at(0).tick - 2 * kBar) <= kPPQN / 4,
       r.marks.count() >= 1 ? std::to_string(r.marks.at(0).tick) : "none");
    ok("at frame nought of the file", r.marks.count() >= 1 && r.marks.at(0).frame == 0,
       r.report());
}

/**
 * A scene with a tempo of its own.
 *
 * The mark carries the bpm, so a take recorded in a 90bpm middle eight is
 * stamped with ninety and plays at ninety. Nothing in Kotlin has to know that
 * scene tempos exist.
 */
void aSceneWithItsOwnTempo() {
    printf("- a scene that plays at another tempo stamps that tempo\n");
    Rig r;
    r.scene(11, 4);
    r.scene(22, 4, 1, 90.0f);
    r.clip(0, 0, 4);
    r.clip(0, 1, 4);
    r.commit();
    r.play();
    r.run(12.0);

    ok("two cells", r.marks.count() >= 2, r.report());
    if (r.marks.count() < 2) return;
    ok("the first was recorded at the song's tempo",
       std::fabs(r.marks.at(0).bpm - 120.0f) < 0.5f, std::to_string(r.marks.at(0).bpm));
    ok("and the second at the scene's", std::fabs(r.marks.at(1).bpm - 90.0f) < 0.5f,
       std::to_string(r.marks.at(1).bpm));
}

/**
 * Clip mode: the same rule, which is the point of there being one rule.
 *
 * Nothing is launched at first, so nothing is recorded against a cell; once a
 * clip is launched, every cycle of it is a mark.
 */
void aLauncherSequence() {
    printf("- in clip mode the cycles of the launched clip are the marks\n");
    Rig r;
    r.scene(11, 2);
    r.clip(0, 0, 2);
    r.commit();
    r.transport.setLauncher(true);
    r.transport.launchClip(0, 11);
    r.play(0);
    r.run(13.0); // four-second cycles: three of them and a bit

    ok("nothing is stamped before the clip is launched",
       r.marks.count() >= 1 && r.marks.at(0).frame <= kBlockFrames, r.report());
    ok("four cycles have been crossed", r.marks.count() == 4, r.report());
    if (r.marks.count() < 4) return;
    ok("each one two bars after the last",
       near(r.marks.at(1).frame - r.marks.at(0).frame, 2 * kBarFrames) &&
           near(r.marks.at(2).frame - r.marks.at(1).frame, 2 * kBarFrames),
       r.report());
    // Within a block of the top, not exactly on it, and for the same reason
    // the frame is: the launcher's own clock is read once a block, so a cycle
    // that turns over inside one is seen a tick or two late. A tick at 120bpm
    // is 2ms and a block is 1.3ms, so "one or two" is the whole error.
    ok("and each at the top of its cycle",
       r.marks.at(1).tick <= 2 && r.marks.at(2).tick <= 2, r.report());
}

/**
 * Recording across scenes the track has nothing in yet.
 *
 * Which is the ordinary case, not an edge one: you add an audio track, you
 * sing over the whole song, and the cells are made *by* the recording. The
 * scheduler's `cycleTicks` answers nought for a rack with no clip - right for
 * the launcher, which must not launch a clip that is not there - and taking
 * that at face value here made a take sung over a whole song land entirely in
 * whichever scene the track happened to have a clip in.
 */
void recordingOntoScenesWithNoClipYet() {
    printf("- a take sung across scenes the track has nothing in yet\n");
    Rig r;
    r.scene(11, 4);
    r.scene(22, 4);
    r.scene(33, 4);
    r.clip(0, 0, 4); // the armed rack has a clip in the first scene only
    r.commit();
    r.play();
    r.run(26.0); // eight-second scenes: all three, and round again

    ok("every scene is marked, not just the one with a clip", r.marks.count() == 4, r.report());
    if (r.marks.count() < 4) return;
    ok("in the order they played",
       r.marks.at(0).sceneId == 11 && r.marks.at(1).sceneId == 22 &&
           r.marks.at(2).sceneId == 33 && r.marks.at(3).sceneId == 11,
       r.report());
    ok("and each says the cycle it would be made against",
       r.marks.at(1).cycleTicks == 4 * kBar && r.marks.at(2).cycleTicks == 4 * kBar,
       std::to_string(r.marks.at(1).cycleTicks));
}

/** A dropped ring poisons the lot: every later frame names the wrong moment. */
void anOverflowPoisonsTheSplit() {
    printf("- a capture that dropped frames cannot be split\n");
    Rig r;
    r.scene(11, 4);
    r.clip(0, 0, 4);
    r.commit();
    r.play();
    r.run(4.0);
    ok("clean so far", !r.marks.poisoned());
    r.marks.poison();
    r.run(4.0);
    ok("and poisoned for good once the ring drops anything", r.marks.poisoned());
}

/** More boundaries than there is room for is refused, not truncated silently. */
void tooManyBoundaries() {
    printf("- a song with more cells than the mark table holds says so\n");
    CaptureMarks m;
    m.reset();
    for (int32_t i = 0; i < CaptureMarks::kMax + 4; ++i) {
        m.observe(i * 64, 100 + i, 0, 960, 120.0f); // a new scene every block
    }
    ok("it fills up", m.count() == CaptureMarks::kMax, std::to_string(m.count()));
    ok("and says the split cannot be trusted", m.poisoned());
}

} // namespace

int main() {
    printf("where a recording's boundaries fall\n");
    aSongWalkedThrough();
    aPunchIn();
    aSceneWithItsOwnTempo();
    aLauncherSequence();
    recordingOntoScenesWithNoClipYet();
    anOverflowPoisonsTheSplit();
    tooManyBoundaries();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
