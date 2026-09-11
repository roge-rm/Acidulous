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
     */
    std::atomic<int32_t> quality{1};

    /** Bits per sample in a recorded or exported WAV: 24 or 16. */
    std::atomic<int32_t> recordBits{24};

    static EngineSettings &get() {
        static EngineSettings instance;
        return instance;
    }
};

inline bool fullQuality() { return EngineSettings::get().quality.load(std::memory_order_relaxed) != 0; }

} // namespace acidulous
