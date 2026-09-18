#pragma once
#include <engine/core/SampleMap.h>
#include <memory>
#include <string>
#include <vector>

// SoundFont 2 reader. Ours, no dependency, and deliberately partial: it reads
// what a multisample player needs - presets, their instruments, key and
// velocity zones, root keys, tuning, loops, attenuation, pan and the volume
// envelope - and ignores modulators, chorus and reverb sends, and anything
// the engine has its own answer for.
//
// Only the chosen preset's samples are decoded, so opening one instrument out
// of a large bank costs what that instrument costs.
namespace acidulous {

class Sf2Reader {
  public:
    struct PresetInfo {
        int32_t bank = 0;
        int32_t preset = 0;
        std::string name;
    };

    // Reads only the headers. Cheap enough to call to fill a picker.
    static bool listPresets(const std::string &path, std::vector<PresetInfo> &out, std::string &error);

    // Builds one preset into a playable map. `presetIndex` indexes the list
    // above, in file order.
    static std::unique_ptr<SampleMap> load(const std::string &path, int32_t presetIndex, std::string &error);

    static constexpr int64_t kMaxFileBytes = 320ll * 1024 * 1024;
    static constexpr int64_t kMaxDecodedFrames = 48000ll * 60 * 12; // twelve minutes of audio
};

} // namespace acidulous
