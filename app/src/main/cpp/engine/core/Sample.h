#pragma once
#include <cstdint>
#include <string>
#include <vector>

// A decoded sample at the engine rate. Built on a normal thread, mounted into a
// machine's slot, never modified afterwards.
namespace acidulous {

struct SampleData {
    std::string name;
    std::vector<float> left;
    std::vector<float> right; // empty when mono
    int32_t frames = 0;
    bool stereo = false;
};

} // namespace acidulous
