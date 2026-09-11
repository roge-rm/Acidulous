#pragma once
#include "Clip.h"
#include <algorithm>
#include <engine/core/Constants.h>
#include <cstdint>
#include <memory>
#include <vector>

// The engine's view of a song: what the SceneScheduler plays.
//
// The document of record lives on the Kotlin side (it is what the editor edits,
// what undo/redo operates on, and what is serialised). What the engine gets is
// this immutable snapshot, built on the UI thread and handed over through the
// constructor queue as a single "song.snapshot" record - one pointer swap, so
// scenes and clips can never disagree mid-swap, and no burst of records that
// could overflow the queue when a scene is inserted across sixteen racks.
//
// Live editing while playing is safe because nothing that gives playback its
// continuity lives here: position belongs to the SceneScheduler, sounding notes
// to each ClipPlayer. A swap is a pointer exchange and sixteen setClip() calls.

namespace acidulous::seq {

struct SceneInfo {
    int64_t id = 0;                  // stable across edits; how the scheduler finds "the scene I was playing" after a swap
    int32_t ticksPerBar = 4 * kPPQN; // from the scene's signature
    int32_t bars = 1;                // DERIVED: longest clip in the scene, never less than 1
    int32_t repeat = 1;
    float bpmOverride = 0.0f; // 0 = follow the song tempo
    bool smooth = false;      // glide into bpmOverride over the first bar
    bool fadeIn = false;      // honoured once the mixer exists (M5)
    bool fadeOut = false;

    int64_t iterationTicks() const { return static_cast<int64_t>(bars) * ticksPerBar; }
};

struct SongSnapshot {
    std::vector<SceneInfo> scenes;
    // rack-major: clips[rack * scenes.size() + scene]; nullptr = no clip there.
    // Shared, not owned: unchanged clips are the same object in consecutive
    // snapshots. The audio thread only ever reads the raw pointer.
    std::vector<std::shared_ptr<const Clip>> clips;
    int32_t rackCount = kRackCount;

    int32_t indexOfScene(int64_t id) const {
        for (size_t i = 0; i < scenes.size(); ++i) {
            if (scenes[i].id == id) {
                return static_cast<int32_t>(i);
            }
        }
        return -1;
    }

    // Audio thread.
    const Clip *clipFor(int32_t rack, int32_t scene) const {
        if (rack < 0 || rack >= rackCount || scene < 0 || scene >= static_cast<int32_t>(scenes.size())) {
            return nullptr;
        }
        const size_t idx = static_cast<size_t>(rack) * scenes.size() + static_cast<size_t>(scene);
        return idx < clips.size() ? clips[idx].get() : nullptr;
    }

    /**
     * Where a position sits on the whole arrangement's timeline, in ticks.
     *
     * The scheduler's position is scene-relative and resets at every repeat,
     * which is all playback ever needed. A Song Position Pointer is absolute,
     * so it needs this - in both directions, which is why the inverse is here
     * too. Neither existed before; a prefix sum over the scenes is all they
     * are, but nothing was keeping one.
     */
    int64_t songTickAt(int32_t scene, int32_t repeat, int64_t tickIn) const {
        int64_t t = 0;
        const int32_t count = static_cast<int32_t>(scenes.size());
        for (int32_t i = 0; i < scene && i < count; ++i) {
            t += scenes[i].iterationTicks() * std::max(1, scenes[i].repeat);
        }
        if (scene >= 0 && scene < count) {
            t += static_cast<int64_t>(repeat) * scenes[scene].iterationTicks() + tickIn;
        }
        return t;
    }

    void locate(int64_t songTick, int32_t &scene, int32_t &repeat, int64_t &tickIn) const {
        scene = 0;
        repeat = 0;
        tickIn = 0;
        if (scenes.empty()) {
            return;
        }
        int64_t left = songTick > 0 ? songTick : 0;
        for (size_t i = 0; i < scenes.size(); ++i) {
            const int64_t iter = std::max<int64_t>(1, scenes[i].iterationTicks());
            const int32_t reps = std::max(1, scenes[i].repeat);
            const int64_t whole = iter * reps;
            if (left < whole || i + 1 == scenes.size()) {
                scene = static_cast<int32_t>(i);
                repeat = static_cast<int32_t>(std::min<int64_t>(left / iter, reps - 1));
                tickIn = left - static_cast<int64_t>(repeat) * iter;
                return;
            }
            left -= whole;
        }
    }

    // --- Builder side (never the audio thread) --------------------------------
    bool setClip(int32_t rack, int32_t scene, std::shared_ptr<const Clip> clip) {
        if (rack < 0 || rack >= rackCount || scene < 0 || scene >= static_cast<int32_t>(scenes.size())) {
            return false;
        }
        const size_t needed = static_cast<size_t>(rackCount) * scenes.size();
        if (clips.size() != needed) {
            clips.resize(needed);
        }
        clips[static_cast<size_t>(rack) * scenes.size() + static_cast<size_t>(scene)] = std::move(clip);
        return true;
    }

    // Scene length is derived from its clips: the longest one wins and shorter
    // ones loop (or one-shot) against it. An empty scene still lasts one bar.
    void finalize() {
        const size_t needed = static_cast<size_t>(rackCount) * scenes.size();
        if (clips.size() != needed) {
            clips.resize(needed);
        }
        for (size_t s = 0; s < scenes.size(); ++s) {
            int32_t bars = 1;
            for (int32_t r = 0; r < rackCount; ++r) {
                const Clip *c = clips[static_cast<size_t>(r) * scenes.size() + s].get();
                if (c != nullptr && c->bars > bars) {
                    bars = c->bars;
                }
            }
            scenes[s].bars = bars;
        }
    }
};

} // namespace acidulous::seq
