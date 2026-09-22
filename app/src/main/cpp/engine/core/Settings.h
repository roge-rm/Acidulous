#pragma once
#include <atomic>
#include <cstdint>

// The handful of engine-wide choices that belong to the device rather than
// to the song: how hard the engine is allowed to work, and how much it is
// allowed to sound like it.
//
// Read on the audio thread, written from the UI, so each one is an atomic
// and each is a plain value with a sane default - a setting that has never
// been written must behave exactly as the app did before it existed.
namespace acidulous {

struct EngineSettings {
    /**
     * Notes a rack may hold at once, 0 for no limit. Counted as held notes
     * rather than as voices: a voice that is releasing costs a fraction of
     * one that is being played, and a machine knows how to steal its own.
     */
    std::atomic<int32_t> voiceLimit{0};

    /**
     * 1 is everything the engine can do; 0 trades some of it for headroom on
     * a phone that has not got any. It reaches the master reverb's density
     * and the distortion's oversampling, and nothing that would change a
     * song's parameters.
     *
     * **It never reaches a file.** See [offlineRender].
     */
    std::atomic<int32_t> quality{1};

    /** Bits per sample in a recorded or exported WAV: 24 or 16. */
    std::atomic<int32_t> recordBits{24};

    /**
     * Set while the engine is rendering to a file rather than to a speaker.
     *
     * **A render has no deadline.** An export and a freeze both stop the audio
     * stream and drive `renderBlock` in a loop as fast as the machine allows,
     * so there is no callback to miss and nothing to be gained by working less
     * hard - it simply takes as long as it takes. Lean exists to buy headroom
     * that a render does not need.
     *
     * So it is not a *setting* the render saves and restores: the automatic
     * quality watcher moves on the interface's poll while a render runs on a
     * worker, and a saved-and-restored value is a race that would change
     * quality part way through a file. The flag is read where the answer is
     * given instead, so nothing can reach past it.
     */
    std::atomic<bool> offlineRender{false};

    static EngineSettings &get() {
        static EngineSettings instance;
        return instance;
    }
};

/**
 * Everything the engine can do - always, while rendering to a file.
 *
 * Every lean branch in the engine goes through here, so writing the answer in
 * one place is what makes "an export is always full" true of all of them at
 * once, including the ones added after this was written.
 */
inline bool fullQuality() {
    const EngineSettings &s = EngineSettings::get();
    return s.offlineRender.load(std::memory_order_relaxed) ||
           s.quality.load(std::memory_order_relaxed) != 0;
}

} // namespace acidulous
