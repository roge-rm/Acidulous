// The scheduler, driven the way the engine drives it.
//
// Everything below this line had no harness until now, and it is where the
// awkward questions live: what happens at the moment somebody presses clip,
// what "which pass is this" means when a clip does not divide its scene, and
// what the grid is told before anything has ever played. Three separate faults
// shipped out of that gap in one day - a cold start claiming to play the first
// scene, a mode latch taken at the wrong moment, and a launch from stopped
// that sounded eight notes and gave up - and each of them was found by hand,
// on a phone, by Dan.
//
// The reason it had none is real rather than an oversight: SceneScheduler
// wants live Racks, and a Rack wants a Machine. So this builds four of them
// out of the registry, hands the scheduler a SongSnapshot assembled by hand,
// and turns the clock over a block at a time. It costs the host-engine archive
// that reset_test and bank_test already pay for.
//
// What it watches is deliberately the *UI's* view: `Transport::launchState`
// is the packed word the grid reads, and every fault above was visible in it
// before it was audible.
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
 * `machine != nullptr` and an inactive rack is skipped before a note is
 * fired - so a harness without one would watch a scheduler doing nothing and
 * report that everything was fine.
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

    /** A freeze of [sceneId] on [rack], rendered at the tempo it will play at. */
    void freeze(int32_t rack, int64_t sceneId, int32_t ticks) {
        auto fc = std::make_shared<FrozenClip>();
        fc->frames = kSampleRate;
        fc->ticks = ticks;
        fc->bpm = 120.0f;
        fc->left.assign(static_cast<size_t>(fc->frames), 0.5f);
        fc->right = fc->left;
        frozen.entries.push_back({sceneId, fc});
        racks[rack].swapFrozen(&frozen);
    }

    /**
     * What `Engine::renderBlock` does before the scheduler fires, by hand.
     *
     * `run()` cannot do it: the engine asks every rack whether it is playing
     * audio it made earlier *outside* the scheduler, and a harness that only
     * turns the scheduler would never see the frozen path at all.
     */
    void updateFrozen() {
        for (int32_t r = 0; r < kRacks; ++r) {
            racks[r].updateFrozen(scheduler.rackSceneId(r), clock.bpm(), true);
        }
    }

    void commit() { scheduler.swapSnapshot(snap.get()); }

    /** Turn the clock over, as Engine::renderBlock does. */
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
     * What `Engine::renderBlock` does with a play request, by hand.
     *
     * The engine resets the clock and calls `scheduler.start(...)`; nothing
     * here renders audio, so the two steps are taken directly. Keeping them
     * in this order matters - `start` reads `clock.position()` for the
     * iteration origin.
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
    // The fault Dan found: `launchForUi` was zeroed, and a zeroed slot
    // unpacks to scene nought with scene nought queued - "playing the first
    // scene, and queued to play it again". Press clip on a cold start and the
    // whole first column lit up without a note being sounded.
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
    // Exactly Dan's sequence: the app has just started, clip is pressed, and
    // then the transport is started.
    f->transport.setLauncher(true);
    f->play(0);
    f->run(f->blocksFor(4.0));
    ok("nothing sounds", f->totalNotes() == 0, std::to_string(f->totalNotes()) + " notes");
    ok("and the grid says so", soundingCount(f->transport) == 0);
    // `start()` says it in words - "nothing plays until a clip is tapped" -
    // and `process` used to undo it on the very next block by reading the
    // launcher flag as a mode change and adopting the whole scene.
    ok("the clock ran anyway", f->clock.position() > kBar, std::to_string(f->clock.position()));
}

void launchingAColumnFromStopped() {
    printf("- launch a column, then start\n");
    auto f = twoScenes();
    f->transport.setLauncher(true);
    // The UI queues the clips and *then* asks for play, which is the order
    // that lets the first clip sound at tick zero.
    // Every rack, including the one with nothing in that scene - which the
    // grid would never do, and which the scheduler should decline.
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
    // The latch used to be taken here, at the stop - which is before the mode
    // is toggled, so it was always one step behind.
    f->transport.setLauncher(true);
    f->play(0);
    f->run(f->blocksFor(4.0));
    const uint32_t after = f->totalNotes();
    ok("a launcher started from stopped is silent", soundingCount(f->transport) == 0,
       std::to_string(soundingCount(f->transport)) + " of 4");
    // The notes from the first playing are still counted; what matters is
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
    // One hit per scene iteration: the origin moves each repeat, which is
    // what re-arms it without any extra state.
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
    // The invariant ClipPlayer states about itself: equal after a stop means
    // nothing is left hanging.
    ok("every note-on has its note-off", balanced,
       std::to_string(f->racks[0].clipPlayer.notesOn()) + " on, " +
           std::to_string(f->racks[0].clipPlayer.notesOff()) + " off");
    ok("and the grid is dark", soundingCount(f->transport) == 0);
}

/**
 * Muting a clip mutes its frozen audio too.
 *
 * `ClipPlayer` has always skipped a muted clip's notes. `Rack::updateFrozen`
 * never looked at a clip at all - so muting a frozen clip silenced notes that
 * nobody was playing and left the audio running, on the one kind of clip whose
 * whole point is that the machine is not running. The mute chip did nothing.
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
 * A cell's cycle counts its repeats; a note's tick does not.
 *
 * `rackTick` goes back to nought at every repeat, which is what makes a
 * one-bar clip come round four times in a four-bar scene. Audio arranged on
 * the song cannot work that way: a take sung across a scene played twice is
 * one performance eight bars long, and restarting it on the second pass would
 * play the first four bars again. `rackCycleTick` is the same question asked
 * so that the answer runs on - and in clip mode it is already what the
 * launcher's own origin means, which is what lets one take serve both modes.
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
 * The claim that one recording serves both modes, put where it can fail.
 *
 * A cell shorter than its scene is the case the two modes used to disagree
 * about: the launcher gives a clip `lengthTicks() * repeat` and the arranger
 * used to give it the whole scene, so the same take would have read different
 * frames depending on which button was pressed. Four bars of scene, one bar of
 * clip, played twice - so the clip's cycle is two bars and the scene's
 * iteration is four.
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
    aCellsCycleCountsItsRepeats();
    aShortCellsCycleAgreesInBothModes();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
