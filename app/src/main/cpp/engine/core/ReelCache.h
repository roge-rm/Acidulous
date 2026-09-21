#pragma once
#include <cstdint>
#include <string>

// Turning a take too long to hold into a file the engine can map.
//
// Its own translation unit rather than a corner of EngineHost, because it is
// the one piece of the long-take path that is pure arithmetic over a file and
// so the one piece a harness can drive: `tools/bias_test.sh` converts a wav,
// maps it, and asks whether it reads what the ordinary reader read.
namespace acidulous::audio {

struct ReelCache {
    /**
     * [path] converted once into [dest] as planar int16 at the engine rate.
     *
     * Returns the frame count written, or 0 and why not in [error]. Chunked
     * throughout: nothing here is proportional to the length of the file.
     */
    static int64_t convert(const std::string &path, const std::string &dest, bool &stereoOut,
                           std::string &error);

    /**
     * What a converted file is called: a hash of the source, its size and when
     * it changed.
     *
     * A re-import of the same file reuses the conversion; a file edited in
     * place does not, which is the whole of the cache's correctness.
     */
    static std::string nameFor(const std::string &path);
};

} // namespace acidulous::audio
