#pragma once
#include <cstdint>
#include <string>
#include <vector>

// Which machines are played as a kit rather than a keyboard, and what their
// voices are called. A drum patch is 8 to 16 sounds and their balance, so the
// harness prints a row per voice.
//
// The lists are copied from MachineUi.voicesOf (model/MachineUi.kt). They're
// short and rarely change, so copying is simpler than bridging to Kotlin.

namespace acidulous::audition {

struct Kit {
    const char *machine;
    int baseNote;
    std::vector<std::string> voices;
};

inline const std::vector<Kit> &kits() {
    static const std::vector<Kit> k = {
        {"Hexbeat", 36,
         {"Kick", "Rim", "Snare", "Clap", "Low Tom", "Mid Tom", "Hi Tom", "Closed Hat", "Open Hat", "Crash",
          "Ride", "Cowbell", "Clave"}},
        {"Genesis", 36,
         {"Kick", "Snare", "Clap", "Rim", "Low Tom", "Mid Tom", "Hi Tom", "Closed Hat", "Open Hat", "Crash",
          "Ride", "Cowbell"}},
        // Each object's type is a parameter, so they're just numbered here.
        {"Resonance", 36,
         {"Object 1", "Object 2", "Object 3", "Object 4", "Object 5", "Object 6", "Object 7", "Object 8"}},
        {"Dice", 36,
         {"Slice 1", "Slice 2", "Slice 3", "Slice 4", "Slice 5", "Slice 6", "Slice 7", "Slice 8", "Slice 9",
          "Slice 10", "Slice 11", "Slice 12", "Slice 13", "Slice 14", "Slice 15", "Slice 16"}},
        // Forage's pads are named after what's loaded. Here that's the
        // synthetic kit the harness mounts.
        {"Forage", 36,
         {"Kick", "Rim", "Snare", "Clap", "Low Tom", "Mid Tom", "Hi Tom", "Closed Hat", "Open Hat", "Crash",
          "Ride", "Cowbell", "Clave"}},
    };
    return k;
}

inline const Kit *kitFor(const std::string &machine) {
    for (const Kit &k : kits()) {
        if (machine == k.machine) return &k;
    }
    return nullptr;
}

} // namespace acidulous::audition
