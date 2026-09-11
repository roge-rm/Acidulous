#pragma once
#include "Launcher.h"
#include "Song.h"
#include "TickClock.h"
#include "Transport.h"
#include <algorithm>
#include <cstdint>
#include <engine/rack/Rack.h>

// Walks the scene chain, or - in clip mode - lets every rack walk its own.
//
// The arranger owns the playback position as
//   (sceneIdx, repeatIdx, iterationOrigin)
// where iterationOrigin is the absolute clock tick at which the current pass
// through the current scene began. Everything else - which clip each rack
// plays, when OneShot clips re-arm, where the UI's bar.beat comes from - falls
// out of that triple.
//
// Clip mode replaces that triple with sixteen of them, held in the Launcher.
// Everything downstream already copes, because ClipPlayer was always given its
// origin as a parameter rather than reading a shared one: pointing each rack at
// a clip from a different scene and handing it its own origin is the whole
// change. The arranger path below is untouched and still selected whenever
// clip mode is off.
//
// Audio thread only.

namespace acidulous::seq {

class SceneScheduler {
  public:
    void bind(Rack *racks, int32_t rackCount, TickClock *clock, Transport *transport) {
        this->racks = racks;
        this->rackCount = rackCount;
        this->clock = clock;
        this->transport = transport;
    }

    // Returns the previous snapshot for the caller to queue for destruction.
    //
    // The scene we were playing is found again by *id*, not index, so inserting
    // or deleting scenes ahead of it while playing does not yank playback
    // somewhere else. If it was deleted, playback moves to the next scene that
    // survived (or the last one). If it merely got shorter than the playhead's
    // offset into it, the origin is re-anchored so the phase within the shorter
    // loop is kept, instead of stepping through phantom iterations to catch up.
    const SongSnapshot *swapSnapshot(const SongSnapshot *next) {
        const SongSnapshot *old = snap;
        snap = next;
        if (snap == nullptr || snap->scenes.empty()) {
            sceneIdx = 0;
            repeatIdx = 0;
            launcher.clearAll();
            launcher.takeChanged();
            for (int32_t r = 0; r < rackCount; ++r) {
                racks[r].clipPlayer.setClip(nullptr);
            }
            return old;
        }
        if (transport != nullptr && transport->launcherMode()) {
            // Clip mode holds what it is playing by scene *id*, so an edit
            // that inserts or deletes scenes ahead of a launched clip cannot
            // re-point a rack at somebody else's clip. A scene that was
            // deleted outright takes its rack silent with it.
            for (int32_t r = 0; r < rackCount; ++r) {
                const int64_t id = launcher.sceneId(r);
                if (id == Launcher::kNone) {
                    racks[r].clipPlayer.setClip(nullptr);
                    continue;
                }
                const int32_t idx = snap->indexOfScene(id);
                if (idx < 0) {
                    launcher.dropRack(r);
                    racks[r].clipPlayer.setClip(nullptr);
                    continue;
                }
                launcher.reshape(r, cycleTicks(r, idx));
                racks[r].clipPlayer.setClip(snap->clipFor(r, idx));
            }
            launcher.takeChanged();
            return old;
        }

        const auto count = static_cast<int32_t>(snap->scenes.size());
        const int64_t pos = clock->position();

        int32_t resolved = -1;
        if (old != nullptr && sceneIdx < static_cast<int32_t>(old->scenes.size())) {
            // Same scene, wherever it moved to.
            resolved = snap->indexOfScene(old->scenes[sceneIdx].id);
            if (resolved < 0) {
                // Deleted: the first later scene that still exists.
                for (size_t i = sceneIdx + 1; i < old->scenes.size() && resolved < 0; ++i) {
                    resolved = snap->indexOfScene(old->scenes[i].id);
                }
            }
        }
        if (resolved < 0) {
            resolved = std::min(sceneIdx, count - 1);
        }

        if (resolved != sceneIdx) {
            sceneIdx = resolved;
            repeatIdx = 0;
            iterationOrigin = pos;
        } else {
            const int64_t iterLen = std::max<int64_t>(1, snap->scenes[sceneIdx].iterationTicks());
            const int64_t elapsed = pos - iterationOrigin;
            if (elapsed >= iterLen) {
                iterationOrigin = pos - (elapsed % iterLen);
            }
        }
        repeatIdx = std::min(repeatIdx, snap->scenes[sceneIdx].repeat - 1);
        pointClipPlayers();
        return old;
    }
    const SongSnapshot *snapshot() const { return snap; }

    // On play. Call after the clock has been reset. kCurrentScene restarts
    // whichever scene we are on from its top.
    void start(int32_t requestedScene) {
        if (transport != nullptr && transport->launcherMode()) {
            // Nothing plays until a clip is tapped, so the clock simply runs
            // from zero and the grid stays silent - which is what a launcher
            // does when you press play with nothing armed.
            launcher.reset();
            lastTickInIteration = 0;
            for (int32_t r = 0; r < rackCount; ++r) {
                racks[r].clipPlayer.setClip(nullptr);
            }
            return;
        }
        if (snap == nullptr || snap->scenes.empty()) {
            sceneIdx = 0;
            return;
        }
        const auto count = static_cast<int32_t>(snap->scenes.size());
        int32_t idx = (requestedScene == Transport::kCurrentScene) ? sceneIdx : requestedScene;
        idx = std::clamp(idx, 0, count - 1);
        iterationOrigin = clock->position();
        lastTickInIteration = 0;
        enterScene(idx, /*allowSmooth=*/false);
    }

    /** Stopped: nothing is launched, nothing is queued, the grid goes dark. */
    void stopLauncher() {
        launcher.clearAll();
        launcher.takeChanged();
        launcherNow = 0;
        if (transport != nullptr) {
            for (int32_t r = 0; r < rackCount; ++r) {
                transport->publishLaunch(r, Transport::packLaunch(Transport::kNoScene, Transport::kNoScene, 0));
            }
        }
    }

    void allNotesOff() {
        for (int32_t r = 0; r < rackCount; ++r) {
            if (racks[r].isActive()) {
                Rack &rack = racks[r];
                rack.clipPlayer.allNotesOff([&rack](uint8_t c, uint8_t a, uint8_t b) { rack.handleMidi(c, a, b); });
            }
        }
    }

    // While stopped the song tempo from the UI applies directly.
    void applyIdleTempo() {
        const float want = clock->songTempoRequested();
        if (!clock->isRamping() && want != clock->bpm()) {
            clock->setTempo(want);
        }
    }

    // Fire everything in [blockStart, blockEnd). Returns false when the song
    // has run out and loopSong is off: the caller stops the transport.
    bool process(int64_t blockStart, int64_t blockEnd) {
        if (snap == nullptr || snap->scenes.empty()) {
            return true;
        }
        if (transport->launcherMode()) {
            return processLauncher(blockStart, blockEnd);
        }
        followTempo();

        int64_t cur = blockStart;
        while (cur < blockEnd) {
            const SceneInfo &sc = snap->scenes[sceneIdx];
            const int64_t iterLen = std::max<int64_t>(1, sc.iterationTicks());
            const int64_t iterEnd = iterationOrigin + iterLen;
            const int64_t segEnd = std::min(blockEnd, iterEnd);
            if (segEnd > cur) {
                fire(cur, segEnd);
                cur = segEnd;
            }
            if (cur >= iterEnd) {
                // Iteration boundary. The origin moves, which is what re-arms
                // OneShot clips and keeps loop arithmetic honest.
                iterationOrigin = iterEnd;
                if (++repeatIdx >= sc.repeat) {
                    repeatIdx = 0;
                    // This boundary is where everything queued from the UI
                    // lands: a scene waiting its turn, or an armed finish.
                    const int32_t queued = transport->takeQueuedScene();
                    if (queued >= 0 && queued < static_cast<int32_t>(snap->scenes.size())) {
                        enterScene(queued, /*allowSmooth=*/true);
                        continue;
                    }
                    if (transport->takeStopAtEnd()) {
                        lastTickInIteration = 0;
                        return false;
                    }
                    if (!transport->loopScene()) {
                        int32_t next = sceneIdx + 1;
                        if (next >= static_cast<int32_t>(snap->scenes.size())) {
                            if (!transport->loopSong()) {
                                sceneIdx = 0; // next Play starts from the top
                                lastTickInIteration = 0;
                                return false;
                            }
                            next = 0;
                        }
                        if (next != sceneIdx) {
                            enterScene(next, /*allowSmooth=*/true);
                        }
                    }
                }
            }
        }
        lastTickInIteration = blockEnd - iterationOrigin;
        return true;
    }

    int64_t packedPosition() const { return Transport::pack(sceneIdx, repeatIdx, lastTickInIteration); }

    // For stamping recorded events. Valid between blocks, i.e. from pollMidiIn().
    int64_t currentSceneId() const {
        return (snap != nullptr && sceneIdx < static_cast<int32_t>(snap->scenes.size())) ? snap->scenes[sceneIdx].id : 0;
    }
    int64_t currentTickInIteration() const { return lastTickInIteration; }
    const SceneInfo *currentSceneInfo() const {
        return (snap != nullptr && sceneIdx < static_cast<int32_t>(snap->scenes.size())) ? &snap->scenes[sceneIdx] : nullptr;
    }
    int32_t currentRepeat() const { return repeatIdx; }
    int32_t currentScene() const { return sceneIdx; }

    /** True while clip mode is driving playback. */
    bool launcherActive() const { return transport != nullptr && transport->launcherMode(); }

    /**
     * The scene a *rack* is playing, and how far through its own cycle it is.
     * In the arranger these are the same for all sixteen; in clip mode they
     * are not, and everything that stamps or syncs per rack - recording,
     * frozen audio - has to ask this rather than the scheduler's own
     * position, or it lands in the wrong clip.
     */
    int64_t rackSceneId(int32_t rack) const {
        if (!launcherActive()) {
            return currentSceneId();
        }
        const int64_t id = launcher.sceneId(rack);
        return id == Launcher::kNone ? 0 : id;
    }
    int64_t rackTick(int32_t rack) const {
        if (!launcherActive()) {
            return lastTickInIteration;
        }
        return launcher.playing(rack) ? launcherNow - launcher.origin(rack) : 0;
    }

    /** Clip mode has no one scene to take a signature from, so the song's. */
    int32_t songTicksPerBar() const {
        return (snap != nullptr && !snap->scenes.empty()) ? snap->scenes[0].ticksPerBar : 4 * kPPQN;
    }

    /** Clip mode: what each rack is doing, for the grid. */
    void publishLaunchStates(int64_t now) const {
        for (int32_t r = 0; r < rackCount; ++r) {
            const int64_t id = launcher.sceneId(r);
            const int32_t cur = id == Launcher::kNone ? Transport::kNoScene : snap->indexOfScene(id);
            const int64_t pend = launcher.pendingId(r);
            int32_t pendIdx = Transport::kNoScene;
            if (pend == Launcher::kStopId) {
                pendIdx = Transport::kStopQueued;
            } else if (pend != Launcher::kNone) {
                pendIdx = snap->indexOfScene(pend);
            }
            const int64_t tick = launcher.playing(r) ? now - launcher.origin(r) : 0;
            transport->publishLaunch(r, Transport::packLaunch(cur < 0 ? Transport::kNoScene : cur,
                                                              pendIdx < 0 ? Transport::kNoScene : pendIdx,
                                                              tick < 0 ? 0 : tick));
        }
    }

    const Launcher &launchState() const { return launcher; }

  private:
    /**
     * Clip mode. The block is cut at every tick where something changes - a
     * cycle ending, a launch landing - so a swap is as sample-accurate as the
     * arranger's, and each rack is fired against its own origin.
     */
    bool processLauncher(int64_t blockStart, int64_t blockEnd) {
        // No scene owns the tempo when clips come from four of them, so the
        // song tempo rules and scene overrides, ramps and fades sit this out.
        if (!clock->isRamping()) {
            const float want = clock->songTempoRequested();
            if (want != clock->bpm()) {
                clock->setTempo(want);
            }
        }
        launcher.setQuantise(transport->launchQuantise());

        if (transport->takeStopAll()) {
            launcher.requestStopAll(blockStart);
        }
        for (int32_t r = 0; r < rackCount; ++r) {
            const int64_t id = transport->takeQueuedClip(r);
            if (id == 0) {
                continue;
            }
            const int32_t idx = snap->indexOfScene(id);
            if (idx < 0) {
                continue;
            }
            launcher.request(r, id, cycleTicks(r, idx), blockStart);
        }

        int64_t cur = blockStart;
        while (cur < blockEnd) {
            launcher.applyDue(cur);
            const uint32_t changed = launcher.takeChanged();
            if (changed != 0) {
                pointLaunched(changed);
            }
            const int64_t segEnd = std::min(blockEnd, launcher.nextEvent(cur));
            fireLauncher(cur, segEnd);
            cur = segEnd;
        }
        lastTickInIteration = 0;
        launcherNow = blockEnd;
        publishLaunchStates(blockEnd);
        return true;
    }

    /** bars x repeat: what a swap waits for, and what re-arms a OneShot. */
    int64_t cycleTicks(int32_t rack, int32_t sceneIdx_) const {
        const Clip *c = snap->clipFor(rack, sceneIdx_);
        if (c == nullptr) {
            return 0;
        }
        const int32_t repeat = std::max(1, snap->scenes[sceneIdx_].repeat);
        return c->lengthTicks() * repeat;
    }

    void pointLaunched(uint32_t mask) {
        for (int32_t r = 0; r < rackCount; ++r) {
            if ((mask & (1u << r)) == 0) {
                continue;
            }
            const int64_t id = launcher.sceneId(r);
            const int32_t idx = (id == Launcher::kNone) ? -1 : snap->indexOfScene(id);
            racks[r].clipPlayer.setClip(idx < 0 ? nullptr : snap->clipFor(r, idx));
        }
    }

    void fireLauncher(int64_t from, int64_t to) {
        if (to <= from) {
            return;
        }
        for (int32_t r = 0; r < rackCount; ++r) {
            if (!racks[r].isActive() || racks[r].frozenActive()) {
                continue;
            }
            Rack &rack = racks[r];
            // A silenced rack is still walked, because process() flushes the
            // note-offs it still owes before it looks at the clip at all.
            const int64_t origin = launcher.playing(r) ? launcher.origin(r) : from;
            if (rack.clipPlayer.originChanged(origin)) {
                rack.clearTouched();
            }
            rack.clipPlayer.process(from, to, origin,
                                    [&rack](uint8_t c, uint8_t a, uint8_t b) { rack.handleMidi(c, a, b); });
            if (launcher.playing(r)) {
                rack.clipPlayer.processLanes(
                    to, origin,
                    [&rack](Unit u, int32_t i, float v) { rack.setParam(u, i, v); },
                    [&rack](Unit u, int32_t i) { return rack.isTouched(u, i); });
            }
        }
    }

    void enterScene(int32_t idx, bool allowSmooth) {
        sceneIdx = idx;
        repeatIdx = 0;
        pointClipPlayers();
        const SceneInfo &sc = snap->scenes[idx];
        const float want = sc.bpmOverride > 0.0f ? sc.bpmOverride : clock->songTempoRequested();
        if (allowSmooth && sc.smooth && want != clock->bpm()) {
            clock->rampTempo(want, sc.ticksPerBar); // over the first bar of the new scene
        } else {
            clock->setTempo(want);
        }
    }

    // A scene without a tempo of its own follows the UI's song tempo live.
    void followTempo() {
        if (clock->isRamping()) {
            return;
        }
        const SceneInfo &sc = snap->scenes[sceneIdx];
        const float want = sc.bpmOverride > 0.0f ? sc.bpmOverride : clock->songTempoRequested();
        if (want != clock->bpm()) {
            clock->setTempo(want);
        }
    }

    void pointClipPlayers() {
        for (int32_t r = 0; r < rackCount; ++r) {
            racks[r].clipPlayer.setClip(snap->clipFor(r, sceneIdx));
        }
    }

    void fire(int64_t from, int64_t to) {
        for (int32_t r = 0; r < rackCount; ++r) {
            // A frozen rack is sent nothing: its notes and its automation are
            // both already in the audio, and running them again would cost
            // the CPU that freezing was meant to give back.
            if (racks[r].isActive() && !racks[r].frozenActive()) {
                Rack &rack = racks[r];
                if (rack.clipPlayer.originChanged(iterationOrigin)) rack.clearTouched();
                rack.clipPlayer.process(from, to, iterationOrigin,
                                        [&rack](uint8_t c, uint8_t a, uint8_t b) { rack.handleMidi(c, a, b); });
                rack.clipPlayer.processLanes(
                    to, iterationOrigin,
                    [&rack](Unit u, int32_t i, float v) { rack.setParam(u, i, v); },
                    [&rack](Unit u, int32_t i) { return rack.isTouched(u, i); });
            }
        }
    }

    Rack *racks = nullptr;
    int32_t rackCount = 0;
    TickClock *clock = nullptr;
    Transport *transport = nullptr;
    const SongSnapshot *snap = nullptr;

    Launcher launcher;
    int64_t launcherNow = 0;
    int32_t sceneIdx = 0;
    int32_t repeatIdx = 0;
    int64_t iterationOrigin = 0;
    int64_t lastTickInIteration = 0;
};

} // namespace acidulous::seq
