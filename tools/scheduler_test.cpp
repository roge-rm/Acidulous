// Drives the scene scheduler the way the engine does.
//
// Covers the awkward cases: what happens the moment clip is pressed, what
// "which pass is this" means when a clip doesn't divide its scene, and what
// the grid is told before anything has played.
//
// SceneScheduler needs live Racks, and a Rack needs a Machine, so this builds
// four from the registry, hands the scheduler a SongSnapshot built by hand,
// and turns the clock over a block at a time. It links the same host-engine
// archive as reset_test and bank_test.
//
// It watches the UI's view: `Transport::launchState` is the packed word the
// grid reads, and these faults show up there before they're audible.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

#include <engine/machine/MachineRegistry.h>
#include <engine/core/Frozen.h>
#include <engine/rack/Rack.h>
#include <sequencer/SceneScheduler.h>

using namespace acidulous;
using namespace acidulous::seq;

namespace {
int checks = 0;
int failures = 0;

/** Audio floats, so a hair either side of the number counts as the number. */
bool isAbout(float value, float want) { return std::fabs(value - want) < 1.0e-4f; }

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    if (cond) {
        printf("  ok   %-56s %s\n", what, detail.c_str());
    } else {
        ++failures;
        printf("  FAIL %-56s %s\n", what, detail.c_str());
    }
}

constexpr int32_t kRacks = 4;
constexpr int32_t kBar = 4 * kPPQN; // 960

/** What the grid is told about one rack, unpacked as the UI unpacks it. */
struct Seen {
    int32_t scene;
    int32_t pending;
    int64_t tickInCycle;
    bool playing() const { return scene != Transport::kNoScene; }
};

Seen seenOf(const Transport &t, int32_t rack) {
    const int64_t p = t.launchState(rack);
    return {static_cast<int32_t>((p >> 56) & 0xff), static_cast<int32_t>((p >> 48) & 0xff),
            p & 0xffffffffffffLL};
}

/** How many racks the grid believes are sounding something. */
int soundingCount(const Transport &t) {
    int n = 0;
    for (int32_t r = 0; r < kRacks; ++r) {
        if (seenOf(t, r).playing()) ++n;
    }
    return n;
}

/**
 * A song, four racks, and a scheduler bound to them.
 *
 * The racks carry a real machine because `Rack::isActive()` is
 * `machine != nullptr` and an inactive rack is skipped before any note fires,
 * so without one the scheduler would do nothing and the tests would pass.
 */
struct Fixture {
    Rack racks[kRacks];
    TickClock clock;
    Transport transport;
    SceneScheduler scheduler;
    std::shared_ptr<SongSnapshot> snap = std::make_shared<SongSnapshot>();
    std::vector<std::shared_ptr<const Clip>> keep;
    FrozenSet frozen;

    Fixture() {
        for (auto &rack : racks) {
            rack.swapMachine(MachineRegistry::create("Hexbeat"));
        }
        clock.setSampleRate(kSampleRate);
        clock.requestSongTempo(120.0f);
        snap->rackCount = kRacks;
        scheduler.bind(racks, kRacks, &clock, &transport);
    }

    ~Fixture() {
        for (auto &rack : racks) rack.swapFrozen(nullptr);
        for (auto &rack : racks) {
            delete rack.swapMachine(nullptr);
        }
    }

    void scene(int64_t id, int32_t bars, int32_t repeat = 1) {
        SceneInfo s;
        s.id = id;
        s.bars = bars;
        s.repeat = repeat;
        s.ticksPerBar = kBar;
        snap->scenes.push_back(s);
    }

    /** A clip of [hits] notes spread across its bars, on one pitch per rack. */
    void clip(int32_t rack, int32_t sceneIdx, int32_t bars, int32_t hits = 4,
              PlayMode mode = PlayMode::Loop) {
        auto c = std::make_shared<Clip>();
        c->rev = rack * 100 + sceneIdx + 1;
        c->bars = bars;
        c->ticksPerBar = kBar;
        c->playMode = mode;
        for (int32_t i = 0; i < hits; ++i) {
            ClipNote n{};
            n.tick = bars * kBar * i / hits;
            n.length = 60;
            n.pitch = static_cast<uint8_t>(36 + rack);
            n.velocity = 100;
            c->notes.push_back(n);
        }
        keep.push_back(c);
        snap->setClip(rack, sceneIdx, c);
    }

    /** The same clip again, muted or not, as the UI's mute chip does it. */
    void mute(int32_t rack, int32_t sceneIdx, bool on) {
        const auto &was = snap->clips[static_cast<size_t>(rack) * snap->scenes.size() +
                                      static_cast<size_t>(sceneIdx)];
        auto c = std::make_shared<Clip>(*was);
        c->mute = on;
        c->rev = was->rev + 1000;
        keep.push_back(c);
        snap->setClip(rack, sceneIdx, c);
        commit();
    }

    /**
     * A freeze of [sceneId] on [rack], rendered at the tempo it will play at.
     *
     * The body is a flat 0.5 and the ring-out a flat 0.25, so any sample shows
     * which region of the file it came from, and 0.75 means both are sounding,
     * as at a loop point.
     */
    void freeze(int32_t rack, int64_t sceneId, int32_t ticks, int32_t tail = 0,
                int32_t frames = kSampleRate) {
        auto fc = std::make_shared<FrozenClip>();
        fc->frames = frames;
        fc->tail = tail;
        fc->ticks = ticks;
        fc->bpm = 120.0f;
        fc->left.assign(static_cast<size_t>(frames), 0.5f);
        fc->left.resize(static_cast<size_t>(frames + tail), 0.25f);
        fc->right = fc->left;
        frozen.entries.push_back({sceneId, fc});
        racks[rack].swapFrozen(&frozen);
    }

    /**
     * What `Engine::renderBlock` does before the scheduler fires, done by hand.
     *
     * `run()` can't do it: the engine asks every rack whether it's playing
     * audio it made earlier, outside the scheduler, so a harness that only
     * runs the scheduler would never see the frozen path.
     */
    void updateFrozen() {
        for (int32_t r = 0; r < kRacks; ++r) {
            racks[r].updateFrozen(scheduler.rackSceneId(r), clock.bpm(), true);
        }
    }

    void commit() { scheduler.swapSnapshot(snap.get()); }

    /** Turns the clock over, as Engine::renderBlock does. */
    void run(int blocks) {
        for (int i = 0; i < blocks; ++i) {
            clock.advance(kBlockFrames);
            scheduler.process(clock.blockStart(), clock.blockEnd());
        }
    }

    /** Blocks are 64 frames; a bar at 120bpm is two seconds. */
    int blocksFor(double seconds) const {
        return static_cast<int>(seconds * kSampleRate / kBlockFrames);
    }

    /**
     * What `Engine::renderBlock` does with a play request, done by hand.
     *
     * The engine resets the clock and calls `scheduler.start(...)`. Nothing
     * here renders audio, so the two steps are done directly. The order
     * matters: `start` reads `clock.position()` for the iteration origin.
     */
    void play(int32_t sceneIdx = 0) {
        transport.requestPlay(sceneIdx);
        clock.reset();
        scheduler.start(sceneIdx);
    }

    void stop() {
        scheduler.allNotesOff();
        scheduler.stopLauncher();
        transport.clearLaunchRequests();
    }

    uint32_t notes(int32_t rack) const { return racks[rack].clipPlayer.notesOn(); }
    uint32_t totalNotes() const {
        uint32_t n = 0;
        for (int32_t r = 0; r < kRacks; ++r) n += racks[r].clipPlayer.notesOn();
        return n;
    }
};

/** A song of two scenes: three racks play the first, two the second. */
std::unique_ptr<Fixture> twoScenes() {
    auto f = std::make_unique<Fixture>();
    f->scene(11, 1);    // Intro, one bar
    f->scene(22, 2);    // Verse, two bars
    f->clip(0, 0, 1);
    f->clip(1, 0, 1);
    f->clip(2, 0, 1);
    f->clip(0, 1, 2);
    f->clip(1, 1, 2);
    f->commit();
    return f;
}

// --- the checks -------------------------------------------------------------

void theGridIsToldNothingBeforeAnythingRuns() {
    printf("- a transport that has never played\n");
    Fixture f;
    // `launchForUi` must not be zeroed: a zeroed slot unpacks to scene zero
    // with scene zero queued, and pressing clip on a cold start would light
    // the whole first column without a note sounding.
    ok("no rack claims to be playing", soundingCount(f.transport) == 0,
       std::to_string(soundingCount(f.transport)) + " of 4");
    ok("and none claims anything queued",
       seenOf(f.transport, 0).pending == Transport::kNoScene);
    ok("a rack past the end reads idle too",
       !seenOf(f.transport, kRacks + 40).playing());
}

void songModePlaysTheSong() {
    printf("- the arranger, as a baseline\n");
    auto f = twoScenes();
    f->play(0);
    f->run(f->blocksFor(1.5)); // most of the one-bar first scene
    ok("three racks sounded", f->notes(0) > 0 && f->notes(1) > 0 && f->notes(2) > 0,
       std::to_string(f->totalNotes()) + " notes");
    ok("and the fourth, which has no clip, did not", f->notes(3) == 0);
    ok("still on the scene asked for", f->scheduler.currentScene() == 0);
    // A one-bar scene with one repeat lasts a bar, so the chain moves on.
    f->run(f->blocksFor(1.0));
    ok("and walks on to the next", f->scheduler.currentScene() == 1,
       "scene " + std::to_string(f->scheduler.currentScene()));
}

void clipModeFromColdStaysSilent() {
    printf("- press clip with nothing playing, then play\n");
    auto f = twoScenes();
    // The app has just started, clip is pressed, and then the transport is
    // started.
    f->transport.setLauncher(true);
    f->play(0);
    f->run(f->blocksFor(4.0));
    ok("nothing sounds", f->totalNotes() == 0, std::to_string(f->totalNotes()) + " notes");
    ok("and the grid says so", soundingCount(f->transport) == 0);
    // `start()` says "nothing plays until a clip is tapped", and `process`
    // mustn't undo that on the next block by reading the launcher flag as a
    // mode change and adopting the whole scene.
    ok("the clock ran anyway", f->clock.position() > kBar, std::to_string(f->clock.position()));
}

void launchingAColumnFromStopped() {
    printf("- launch a column, then start\n");
    auto f = twoScenes();
    f->transport.setLauncher(true);
    // The UI queues the clips and then asks for play, so the first clip
    // sounds at tick zero.
    // Every rack, including the one with nothing in that scene, which the
    // grid would never do and the scheduler should decline.
    for (int32_t r = 0; r < kRacks; ++r) f->transport.launchClip(r, 11);
    f->play(0);
    f->run(f->blocksFor(2.0));
    ok("the three racks with a clip are sounding", soundingCount(f->transport) == 3,
       std::to_string(soundingCount(f->transport)) + " of 4");
    ok("and they keep sounding", f->totalNotes() > 0, std::to_string(f->totalNotes()) + " notes");
    const uint32_t before = f->totalNotes();
    f->run(f->blocksFor(4.0));
    ok("and go on doing so", f->totalNotes() > before,
       std::to_string(before) + " then " + std::to_string(f->totalNotes()));
    ok("on the scene that was launched", seenOf(f->transport, 0).scene == 0);
}

void songToClipHandsOver() {
    printf("- press clip while the song plays\n");
    auto f = twoScenes();
    f->play(0);
    f->run(f->blocksFor(1.0));
    const uint32_t before = f->totalNotes();
    ok("the song is playing", before > 0, std::to_string(before) + " notes");

    f->transport.setLauncher(true);
    f->run(f->blocksFor(2.0));
    ok("every rack that was playing still is", soundingCount(f->transport) == 3,
       std::to_string(soundingCount(f->transport)) + " of 4");
    ok("and notes never stopped", f->totalNotes() > before,
       std::to_string(before) + " then " + std::to_string(f->totalNotes()));
    ok("on the scene that was sounding", seenOf(f->transport, 0).scene == 0);
}

void clipToSongWaitsForTheBar() {
    printf("- press clip again while clips play\n");
    auto f = twoScenes();
    f->play(0);
    f->run(f->blocksFor(1.0));
    f->transport.setLauncher(true);
    f->run(f->blocksFor(2.0));
    const uint32_t before = f->totalNotes();

    f->transport.setLauncher(false);
    f->run(f->blocksFor(4.0));
    ok("the grid goes dark", soundingCount(f->transport) == 0,
       std::to_string(soundingCount(f->transport)) + " of 4");
    ok("and the song plays on", f->totalNotes() > before,
       std::to_string(before) + " then " + std::to_string(f->totalNotes()));
}

void theModeSurvivesAStopAndAnotherStart() {
    printf("- stop, toggle the mode, start again\n");
    auto f = twoScenes();
    f->play(0);
    f->run(f->blocksFor(2.0));
    f->stop();
    // The latch mustn't be taken here at the stop, which is before the mode
    // is toggled, or it's always one step behind.
    f->transport.setLauncher(true);
    f->play(0);
    f->run(f->blocksFor(4.0));
    const uint32_t after = f->totalNotes();
    ok("a launcher started from stopped is silent", soundingCount(f->transport) == 0,
       std::to_string(soundingCount(f->transport)) + " of 4");
    // The notes from the first playing are still counted. What matters is
    // that the second playing added none.
    f->run(f->blocksFor(2.0));
    ok("and stays silent", f->totalNotes() == after);
}

void aOneShotReArmsEveryIteration() {
    printf("- a one-shot clip in a repeating scene\n");
    auto f = std::make_unique<Fixture>();
    f->scene(11, 1, /*repeat=*/4);
    f->clip(0, 0, 1, /*hits=*/1, PlayMode::OneShot);
    f->commit();
    f->play(0);
    f->run(f->blocksFor(8.0)); // four bars, four repeats
    // One hit per scene iteration: the origin moves each repeat, which
    // re-arms it without any extra state.
    ok("fired once per repeat", f->notes(0) == 4, std::to_string(f->notes(0)));
}

void sceneRepeatsAdvanceAndWrap() {
    printf("- the repeat counter\n");
    auto f = std::make_unique<Fixture>();
    f->scene(11, 1, /*repeat=*/2);
    f->scene(22, 1, /*repeat=*/1);
    f->clip(0, 0, 1);
    f->clip(0, 1, 1);
    f->commit();
    f->play(0);
    f->run(f->blocksFor(1.0));
    ok("first repeat of the first scene",
       f->scheduler.currentScene() == 0 && f->scheduler.currentRepeat() == 0,
       "scene " + std::to_string(f->scheduler.currentScene()) + " repeat " +
           std::to_string(f->scheduler.currentRepeat()));
    f->run(f->blocksFor(2.0));
    ok("second repeat", f->scheduler.currentScene() == 0 && f->scheduler.currentRepeat() == 1,
       "scene " + std::to_string(f->scheduler.currentScene()) + " repeat " +
           std::to_string(f->scheduler.currentRepeat()));
    f->run(f->blocksFor(2.0));
    ok("then the second scene", f->scheduler.currentScene() == 1,
       "scene " + std::to_string(f->scheduler.currentScene()));
}

void nothingIsLeftSounding() {
    printf("- stopping\n");
    auto f = twoScenes();
    f->play(0);
    f->run(f->blocksFor(3.0));
    f->stop();
    bool balanced = true;
    for (int32_t r = 0; r < kRacks; ++r) {
        if (f->racks[r].clipPlayer.notesOn() != f->racks[r].clipPlayer.notesOff()) balanced = false;
    }
    // ClipPlayer's own invariant: equal after a stop means no note is left
    // hanging.
    ok("every note-on has its note-off", balanced,
       std::to_string(f->racks[0].clipPlayer.notesOn()) + " on, " +
           std::to_string(f->racks[0].clipPlayer.notesOff()) + " off");
    ok("and the grid is dark", soundingCount(f->transport) == 0);
}

/**
 * Muting a clip mutes its frozen audio too.
 *
 * `ClipPlayer` skips a muted clip's notes, but `Rack::updateFrozen` has to
 * check the mute as well, or a frozen clip keeps playing when muted.
 */
void aMutedClipIsMutedWhenFrozen() {
    auto f = std::make_unique<Fixture>();
    f->scene(1, 1);
    f->clip(0, 0, 1);
    f->commit();
    f->play();
    f->run(2);

    f->freeze(0, 1, kBar);
    f->updateFrozen();
    ok("frozen audio plays", f->racks[0].frozenActive());

    f->mute(0, 0, true);
    f->run(1);
    f->updateFrozen();
    ok("and stops the moment the clip is muted", !f->racks[0].frozenActive());

    f->mute(0, 0, false);
    f->run(1);
    f->updateFrozen();
    ok("and comes back when it is not", f->racks[0].frozenActive());
}

/**
 * A frozen clip rings out past its own end, and over its own next pass.
 *
 * The tail is a region after the clip, read by a second cursor. That way the
 * last pass before a scene change rings on past the bar line, the first pass
 * has no ring from a pass that hasn't played, and a clip shorter than its
 * tail doesn't wrap it twice.
 *
 * 0.5 is the body, 0.25 the ring-out, so 0.75 is both at once.
 */
void aFrozenClipRingsOutPastItsOwnEnd() {
    auto f = std::make_unique<Fixture>();
    f->scene(1, 1);
    f->clip(0, 0, 1);
    f->commit();
    f->play();
    f->run(2);

    // Rack 1 has no clip in this scene and so no notes, so the only sound it
    // makes is the frozen audio. Rack 0 is playing, so its machine would be
    // in every number.
    constexpr int32_t kBody = 256; // four blocks, so the loop point is close
    constexpr int32_t kTail = 64;  // one block of ring-out
    f->freeze(1, 1, kBar, kTail, kBody);
    Rack &r = f->racks[1];
    r.tapDry = true;
    f->updateFrozen();
    ok("frozen audio plays", r.frozenActive());

    r.syncFrozen(0, 120.0f);
    for (int i = 0; i < 4; ++i) r.render(kBlockFrames); // the whole body
    ok("the first pass is the clip alone", isAbout(r.dryL[0], 0.5f), std::to_string(r.dryL[0]));

    r.render(kBlockFrames); // round again, with the last pass ringing over it
    ok("the loop point has both", isAbout(r.dryL[0], 0.75f), std::to_string(r.dryL[0]));
    r.render(kBlockFrames);
    ok("and the ring stops when it runs out", isAbout(r.dryL[0], 0.5f), std::to_string(r.dryL[0]));

    // A scene this rack has no clip in: the audio stops, the ring doesn't.
    r.updateFrozen(999, 120.0f, true);
    ok("the clip has stopped", !r.frozenActive());
    r.render(kBlockFrames);
    ok("but it is still ringing", isAbout(r.dryL[0], 0.25f), std::to_string(r.dryL[0]));
    r.render(kBlockFrames);
    ok("for exactly as long as it was given", isAbout(r.dryL[0], 0.0f), std::to_string(r.dryL[0]));

    // And panic cuts it mid-ring.
    r.updateFrozen(1, 120.0f, true);
    r.updateFrozen(999, 120.0f, true);
    r.allNotesOff();
    r.render(kBlockFrames);
    ok("panic cuts the ring-out", isAbout(r.dryL[0], 0.0f), std::to_string(r.dryL[0]));
    r.tapDry = false;
}

/**
 * A frozen clip follows a tempo it wasn't rendered at while the clock ramps.
 *
 * A scene with a smooth tempo change spends its first bar between two tempos,
 * so it matches no clip's rendered tempo. Dropping the freeze for that bar
 * would run every frozen machine live at once (Trinity costs 87 us a rack
 * against a stretch's 9), so the mismatch becomes a stretch rate instead.
 * Checks it engages only while ramping, the rate is the tempo ratio, and
 * audio comes out (a stretcher fed a bad range returns silence).
 */
void aFrozenClipFollowsARamp() {
    auto f = std::make_unique<Fixture>();
    f->scene(1, 1);
    f->clip(0, 0, 1);
    f->commit();
    f->play();
    f->run(2);

    // Rack 1 again: no clip, so no notes, so the only sound is the audio.
    // Long enough that the stretcher has a window and a search to work with.
    constexpr int32_t kBody = kSampleRate * 2;
    f->freeze(1, 1, kBar, kSampleRate, kBody);
    Rack &r = f->racks[1];
    r.tapDry = true;

    // At the tempo it was rendered at, nothing changes.
    r.updateFrozen(1, 120.0f, true, false);
    ok("frozen at its own tempo", r.frozenActive());
    r.syncFrozen(0, 120.0f);
    r.render(kBlockFrames);
    ok("and read plainly", r.dryL[0] == 0.5f, std::to_string(r.dryL[0]));

    // A different tempo with no ramp: the freeze steps aside, because audio
    // at the wrong tempo drifts off the beat.
    r.updateFrozen(1, 132.0f, true, false);
    ok("a wrong tempo drops the freeze", !r.frozenActive());

    // The same mismatch while the clock ramps into it: kept, and stretched.
    r.updateFrozen(1, 132.0f, true, true);
    ok("but a ramp keeps it", r.frozenActive());
    r.syncFrozen(0, 132.0f);
    r.render(kBlockFrames);
    float peak = 0.0f;
    for (int32_t i = 0; i < kBlockFrames; ++i) peak = std::max(peak, std::fabs(r.dryL[i]));
    ok("and it still makes a sound", peak > 0.1f, std::to_string(peak));
    // Both channels match, because the source is the same in both and the
    // search is shared, which keeps the stereo image together.
    bool same = true;
    for (int32_t i = 0; i < kBlockFrames; ++i) same = same && r.dryL[i] == r.dryR[i];
    ok("in both channels alike", same);

    // Nothing jumps on the way in or out.
    //
    // `sourcePosition()` is where the next hop will read, which runs up to a
    // whole hop ahead of the audio already output. Switching straight to the
    // plain read there would skip up to fifteen milliseconds, so both edges
    // are crossfaded. The test is that no sample step is bigger than the
    // material's own.
    float worstStep = 0.0f;
    float last = r.dryL[kBlockFrames - 1];
    for (int pass = 0; pass < 24; ++pass) {
        // Out of the ramp, half way through: the hardest edge there is.
        if (pass == 12) r.updateFrozen(1, 120.0f, true, false);
        r.syncFrozen(static_cast<int64_t>(pass) * 8, pass < 12 ? 132.0f : 120.0f);
        r.render(kBlockFrames);
        for (int32_t i = 0; i < kBlockFrames; ++i) {
            worstStep = std::max(worstStep, std::fabs(r.dryL[i] - last));
            last = r.dryL[i];
        }
    }
    // The body is a flat 0.5 and the ring 0.25, so any real signal step is 0.
    // A jump between two read positions would show up as 0.25 or more.
    ok("no jump leaving the stretch", worstStep < 0.2f, std::to_string(worstStep));

    r.tapDry = false;
}

/**
 * A cell's cycle counts its repeats; a note's tick doesn't.
 *
 * `rackTick` goes back to zero at every repeat, which is how a one-bar clip
 * comes round four times in a four-bar scene. Recorded audio can't work that
 * way: a take sung across a scene played twice is one eight-bar performance,
 * and restarting it would replay the first four bars. `rackCycleTick` keeps
 * counting instead, and in clip mode it's the same as the launcher's origin,
 * so one take serves both modes.
 */
void aCellsCycleCountsItsRepeats() {
    auto f = std::make_unique<Fixture>();
    f->scene(1, 1, 2); // one bar, played twice: a two-bar cell
    f->clip(0, 0, 1);
    f->commit();
    f->play();

    f->run(f->blocksFor(1.0)); // half way through the first pass
    const int64_t tick1 = f->scheduler.rackTick(0);
    const int64_t cycle1 = f->scheduler.rackCycleTick(0);
    ok("first pass: a tick and a cycle are the same thing", tick1 == cycle1,
       std::to_string(tick1) + " and " + std::to_string(cycle1));

    f->run(f->blocksFor(2.0)); // the same place in the second pass
    const int64_t tick2 = f->scheduler.rackTick(0);
    const int64_t cycle2 = f->scheduler.rackCycleTick(0);
    ok("second pass: the tick has gone back to where it was",
       std::llabs(tick2 - tick1) < 8, std::to_string(tick2) + " against " + std::to_string(tick1));
    ok("and the cycle has carried on", std::llabs(cycle2 - (cycle1 + kBar)) < 8,
       std::to_string(cycle2) + ", wanted about " + std::to_string(cycle1 + kBar));
}

/**
 * Checks one recording serves both modes.
 *
 * A cell shorter than its scene is where the modes could disagree: the
 * launcher gives a clip `lengthTicks() * repeat`, so the arranger must too,
 * or the same take would read different frames in each mode. Four bars of
 * scene, one bar of clip played twice, so the clip's cycle is two bars and
 * the scene's iteration is four.
 */
void aShortCellsCycleAgreesInBothModes() {
    printf("- a cell shorter than its scene reads the same in both modes\n");
    const int64_t at = kBar + kBar / 2; // a bar and a half in: past one cycle

    auto song = std::make_unique<Fixture>();
    song->scene(1, 4, 2);
    song->clip(0, 0, 1);
    song->commit();
    song->play();
    song->run(song->blocksFor(3.0)); // three seconds: a bar and a half
    const int64_t arranger = song->scheduler.rackCycleTick(0);

    auto clips = std::make_unique<Fixture>();
    clips->scene(1, 4, 2);
    clips->clip(0, 0, 1);
    clips->commit();
    clips->transport.setLauncher(true);
    clips->transport.launchClip(0, 1);
    clips->play(0);
    clips->run(clips->blocksFor(3.0));
    const int64_t launcher = clips->scheduler.rackCycleTick(0);

    ok("the arranger wraps onto the clip's cycle", std::llabs(arranger - at) < 16,
       std::to_string(arranger) + ", wanted about " + std::to_string(at));
    ok("and clip mode says the same", std::llabs(launcher - arranger) < 16,
       std::to_string(launcher) + " against " + std::to_string(arranger));
}

} // namespace


/** Runs [blocks] and says whether the song was still going after all of them. */
bool runOn(Fixture &f, int blocks) {
    for (int i = 0; i < blocks; ++i) {
        f.clock.advance(kBlockFrames);
        if (!f.scheduler.process(f.clock.blockStart(), f.clock.blockEnd())) return false;
    }
    return true;
}

void deletingThePlayingLastScene() {
    printf("- the scene playing is the last, and is deleted\n");
    auto f = twoScenes();
    f->scene(33, 1);
    f->clip(0, 2, 1);
    f->commit();
    f->play(2);
    f->run(f->blocksFor(0.5));
    ok("playing the third scene", f->scheduler.currentScene() == 2);
    // The edit arrives as a new snapshot without it.
    auto next = std::make_shared<SongSnapshot>(*f->snap);
    next->scenes.pop_back();
    next->clips.clear();
    next->setClip(0, 0, f->keep[0]);
    f->scheduler.swapSnapshot(next.get());
    ok("the scene index is one that exists", f->scheduler.currentScene() >= 0 && f->scheduler.currentScene() < 2,
       "scene " + std::to_string(f->scheduler.currentScene()));
    f->run(f->blocksFor(3.0)); // and it keeps running without reading past the list
    ok("and it keeps running on a scene that exists", f->scheduler.currentScene() >= 0 && f->scheduler.currentScene() < 2);
    f->scheduler.swapSnapshot(f->snap.get()); // before `next` goes
}

void aQueuedSceneJumpsTheOrder() {
    printf("- a scene queued from the first skips the second\n");
    auto f = twoScenes();
    f->scene(33, 1);
    f->commit();
    f->play(0);
    f->transport.queueScene(2);
    f->run(f->blocksFor(2.5)); // the one-bar first scene ends at 2 s
    ok("the queued scene came next", f->scheduler.currentScene() == 2,
       "scene " + std::to_string(f->scheduler.currentScene()));
}

void stopAtTheEndOfALoopedScene() {
    printf("- the scene loops, and stop-at-end is asked for\n");
    auto f = twoScenes();
    f->transport.setLoopScene(true);
    f->play(0);
    ok("a looped scene goes round", runOn(*f, f->blocksFor(5.0)) && f->scheduler.currentScene() == 0);
    f->transport.setStopAtEnd(true);
    ok("and stops at the end of the pass it's in", !runOn(*f, f->blocksFor(2.5)));
}

void aSongEmptiedWhilePlaying() {
    printf("- every scene deleted while it plays, then one added\n");
    auto f = twoScenes();
    f->play(1);
    f->run(f->blocksFor(1.0));
    auto none = std::make_shared<SongSnapshot>();
    none->rackCount = kRacks;
    f->scheduler.swapSnapshot(none.get());
    ok("a song with no scenes stops rather than reading past its list", !runOn(*f, f->blocksFor(1.0)) || f->scheduler.currentScene() == 0);
    f->commit(); // the two scenes back
    f->run(f->blocksFor(1.0));
    ok("and it plays a scene that exists afterwards", f->scheduler.currentScene() >= 0 && f->scheduler.currentScene() < 2,
       "scene " + std::to_string(f->scheduler.currentScene()));
}

int main() {
    theGridIsToldNothingBeforeAnythingRuns();
    songModePlaysTheSong();
    clipModeFromColdStaysSilent();
    launchingAColumnFromStopped();
    songToClipHandsOver();
    clipToSongWaitsForTheBar();
    theModeSurvivesAStopAndAnotherStart();
    aOneShotReArmsEveryIteration();
    sceneRepeatsAdvanceAndWrap();
    nothingIsLeftSounding();
    aMutedClipIsMutedWhenFrozen();
    aFrozenClipRingsOutPastItsOwnEnd();
    aFrozenClipFollowsARamp();
    aCellsCycleCountsItsRepeats();
    aShortCellsCycleAgreesInBothModes();
    deletingThePlayingLastScene();
    aQueuedSceneJumpsTheOrder();
    stopAtTheEndOfALoopedScene();
    aSongEmptiedWhilePlaying();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
