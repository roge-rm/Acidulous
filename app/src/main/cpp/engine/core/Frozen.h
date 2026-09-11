#pragma once
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

// A clip rendered to audio, and what a rack needs to play it back instead of
// running its machine.
//
// Freezing is the answer to sixteen tracks on a phone: a rack playing a
// frozen clip costs a memory read and the channel strip, where the same bar
// through Filament or Nexus costs a tenth of a core. The render is made
// off-thread, by the offline path in EngineHost, and handed over as an
// object like every other variable-length thing in this engine.
namespace acidulous {

struct FrozenClip {
    std::vector<float> left, right;
    /** Exactly the clip's length: playback loops on this, seamlessly. */
    int32_t frames = 0;
    /**
     * The tempo it was rendered at. A song played at another tempo cannot
     * use it - audio does not stretch - so the rack falls back to playing
     * the machine live rather than playing the wrong thing.
     */
    float bpm = 120.0f;
    /** The clip length in ticks, which is what the tempo above turns into frames. */
    int32_t ticks = 0;
};

/**
 * One rack's frozen clips, by scene. Keyed by the scene's stable id rather
 * than its index: scenes get inserted, moved and deleted, and a freeze must
 * not end up attached to a different scene because one was dragged.
 */
struct FrozenSet {
    struct Entry {
        int64_t sceneId = 0;
        std::shared_ptr<const FrozenClip> clip;
    };
    std::vector<Entry> entries;

    // Audio thread: a handful of entries, so a scan beats anything cleverer.
    const FrozenClip *find(int64_t sceneId) const {
        for (const Entry &e : entries) {
            if (e.sceneId == sceneId) return e.clip.get();
        }
        return nullptr;
    }
};

} // namespace acidulous
