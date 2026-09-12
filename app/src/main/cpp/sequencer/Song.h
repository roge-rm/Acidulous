#pragma once
#include "Clip.h"
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
