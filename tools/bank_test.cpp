// Is every factory patch a patch?
//
// The audition harness next door is for voicing - it renders, it measures, it
// has no opinion. This is the floor underneath it, and it runs in
// all_tests.sh: a patch that names a parameter the engine does not have, or
// makes no sound, or clips fifty times over, or plays differently the second
// time cannot be committed.
//
// The first of those is the one that could not be checked before and matters
// most. A patch key that does not match any ParamDef does *nothing* in the
// app - ParamBinding.applyAll fills in the default and moves on - so a typo
// in a hand-written preset has always been completely silent. Eight machines
// build their parameter tables with printf format strings and cannot be
// checked from outside the engine at all, and they are the eight with the
// most parameters.
//
// Warnings are for taste and failures are for faults, and the line between
// them is whether a person could have meant it. A patch peaked at -0.5 dBFS
// is hot; a patch peaked at +34 is an error.

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include <engine/core/Constants.h>
#include <engine/core/InputBus.h>
#include <engine/effect/EffectRegistry.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/cumulus/Cloud.h>
#include <engine/machine/cumulus/Cumulus.h>
#include <engine/machine/formulate/Program.h>

#include "audition_kit.h"
#include "audition_material.h"
#include "audition_measure.h"
#include "patchbank.h"

using namespace acidulous;
using namespace acidulous::audition;

namespace {

constexpr int32_t kBlock = kBlockFrames;

int gFailures = 0;
int gWarnings = 0;
int gPatches = 0;
int gTodo = 0;

/**
 * What is known to be outstanding, so that everything else can fail loudly.
 *
 * A suite that is red for a whole milestone is a suite nobody reads, and the
 * point of this harness is that a new fault is visible the day it arrives.
 * So the faults that were already here are named, one line each, and counted
 * separately - and a name that no longer needs to be on the list is itself
 * reported, because a list like this rots the moment it stops being checked.
 */
struct Known {
    const char *unit;
    const char *patch; // empty: the whole unit
    const char *why;
};

const Known kKnown[] = {
    // No bank written yet. These are the milestone's own acceptance test:
    // when the list is empty, M45 is done.
    {"Nexus", "", "no bank yet - graphs have to be built on the device and exported"},
    {"Forage", "", "no bank yet - never had one"},
    {"Molt", "", "no bank yet - never had one"},
    {"fx.Delay", "", "no bank yet - effects have never had presets"},
    {"fx.Reverb", "", "no bank yet - effects have never had presets"},
    {"fx.Eq", "", "no bank yet - effects have never had presets"},
    {"fx.Distortion", "", "no bank yet - effects have never had presets"},
    {"fx.Compressor", "", "no bank yet - effects have never had presets"},
    {"fx.Filter", "", "no bank yet - effects have never had presets"},
    {"fx.Bitcrusher", "", "no bank yet - effects have never had presets"},
    {"fx.Phaser", "", "no bank yet - effects have never had presets"},
    {"fx.Flanger", "", "no bank yet - effects have never had presets"},
    // A patch whose every value is the default: it is Init under another
    // name. Goes when Dice's bank is written.
    {"Dice", "Straight", "sets nothing at all - identical to Init"},
};

constexpr size_t kKnownCount = sizeof(kKnown) / sizeof(kKnown[0]);
bool gKnownUsed[kKnownCount] = {};

bool known(const std::string &unit, const std::string &patch = "") {
    bool any = false;
    for (size_t i = 0; i < kKnownCount; ++i) {
        if (unit != kKnown[i].unit) continue;
        if (kKnown[i].patch[0] == '\0' || patch == kKnown[i].patch) {
            gKnownUsed[i] = true;
            any = true;
        }
    }
    return any;
}

void fail(const std::string &who, const std::string &what) {
    std::printf("  FAIL %-28s %s\n", who.c_str(), what.c_str());
    ++gFailures;
}
void todo(const std::string &who, const std::string &what) {
    std::printf("  todo %-28s %s\n", who.c_str(), what.c_str());
    ++gTodo;
}
void warn(const std::string &who, const std::string &what) {
    std::printf("  warn %-28s %s\n", who.c_str(), what.c_str());
    ++gWarnings;
}

// --- Rendering, shorter than the audition harness's -------------------------
//
// A second and a bit rather than four seconds. This runs on every commit
// beside nine other harnesses; the audition tool is where a patch is listened
// to at length.

constexpr float kHold = 1.2f;
constexpr float kTail = 0.8f;

struct Material {
    std::vector<std::unique_ptr<SampleData>> samples;
    std::unique_ptr<audio::Take> take;
    std::unique_ptr<audio::Utterance> utterance;
    std::unique_ptr<SampleMap> map;
    std::unique_ptr<machine::cumulus::CloudSet> cloud;
    std::unique_ptr<machine::formulate::Program> program;
    std::vector<float> input;
};

void mountMaterial(Machine *m, const std::string &machine, Material &mat) {
    if (machine == "Cumulus") {
        auto *cum = static_cast<machine::Cumulus *>(m);
        mat.cloud = machine::cumulus::buildCloud(cum->spec(), static_cast<int32_t>(kSr));
        m->swapObject(0, mat.cloud.get());
    } else if (machine == "Forage") {
        for (int i = 0; i < static_cast<int>(Piece::Count); ++i) {
            mat.samples.push_back(pieceSample(static_cast<Piece>(i)));
            m->swapObject(i, mat.samples.back().get());
        }
    } else if (machine == "Dice" || machine == "Pollen") {
        mat.take = breakLoop();
        m->swapObject(0, mat.take.get());
    } else if (machine == "Mosaic") {
        mat.map = zoneMap();
        m->swapObject(0, mat.map.get());
    } else if (machine == "Molt") {
        mat.utterance = voiceUtterance();
        m->swapObject(0, mat.utterance.get());
    }
    // Something on the input bus for everything that reads it, so a patch
    // built around an external exciter is not called silent for wanting one.
    if (machine == "Cipher" || machine == "Filament" || machine == "Molt" || machine == "Pollen" ||
        machine == "Nexus") {
        mat.input = voicePhrase();
    }
}


/**
 * The half of a patch that is not knobs.
 *
 * Formulate's sound is a string, not a number: without its formula compiled
 * and mounted it plays its plain oscillator, which is why "Formula Buzz"
 * measured as silence. Cumulus's table is the same shape of problem and is
 * built from the machine's own parameters above. Both are what EngineHost
 * does when the setting changes, done here for the same reason.
 */
void applySettings(Machine *m, const std::string &machine,
                   const std::vector<std::pair<std::string, std::string>> &settings, Material &mat) {
    if (machine != "Formulate") return;
    std::string formula, arp, duty, vol;
    for (const auto &kv : settings) {
        if (kv.first == "formula") formula = kv.second;
        else if (kv.first == "arp") arp = kv.second;
        else if (kv.first == "duty") duty = kv.second;
        else if (kv.first == "vol") vol = kv.second;
    }
    if (formula.empty() && arp.empty() && duty.empty() && vol.empty()) return;
    std::string error;
    mat.program = machine::formulate::compile(formula, arp, duty, vol, error);
    if (!mat.program) {
        std::fprintf(stderr, "  the formula did not compile: %s\n", error.c_str());
        return;
    }
    m->swapObject(0, mat.program.get());
}

std::vector<float> renderMachine(const std::string &machine, const std::vector<float> &norm, int note,
                                 const std::vector<std::pair<std::string, std::string>> &settings) {
    std::unique_ptr<Machine> m(MachineRegistry::create(machine.c_str()));
    if (!m) return {};
    m->prepare(static_cast<int32_t>(kSr));
    m->allNotesOff();
    m->reset();
    for (size_t i = 0; i < norm.size(); ++i) m->params().set(static_cast<int32_t>(i), norm[i]);
    m->params().jumpAll();

    Material mat;
    mountMaterial(m.get(), machine, mat);
    applySettings(m.get(), machine, settings, mat);

    const Kit *kit = kitFor(machine);
    const auto total = static_cast<int64_t>(kSr * (kHold + kTail));

    // A kit is silent on any one note if that voice happens to be - a Dice
    // slice with no onset behind it, an empty Forage pad - so four are played
    // across whatever it has. Spread in time, not together: struck together
    // they were all silent, because a slicer takes the newest note and the
    // newest happened to be an empty slice. Four notes at once is not how a
    // kit is played anyway.
    struct Fire {
        int64_t at;
        int note;
    };
    std::vector<Fire> ons;
    if (kit != nullptr) {
        // Six, spread across the whole kit rather than four across the front
        // of it. Four reached the kick, the clap, a tom and a cymbal, and
        // three kits that differ mostly in their rim, cowbell and clave
        // measured as the same sound - which they are not.
        const int n = 6;
        for (int i = 0; i < n; ++i) {
            ons.push_back({static_cast<int64_t>(kSr * 0.18f) * i,
                           kit->baseNote + static_cast<int>(i * (kit->voices.size() - 1) / (n - 1))});
        }
    } else {
        ons.push_back({0, note});
    }
    const auto onFrames = static_cast<int64_t>(kSr * kHold);

    std::vector<float> out;
    out.reserve(static_cast<size_t>(total) * 2);
    float L[kBlock], R[kBlock];
    std::vector<float> inBlock(static_cast<size_t>(kBlock) * 2, 0.0f);
    const double ticksPerFrame = 120.0 * kPPQN / (60.0 * static_cast<double>(kSr));
    size_t nextOn = 0;
    bool released = false;

    for (int64_t at = 0; at < total; at += kBlock) {
        while (nextOn < ons.size() && ons[nextOn].at < at + kBlock) {
            m->noteOn(static_cast<uint8_t>(ons[nextOn].note), 100);
            ++nextOn;
        }
        if (!released && at >= onFrames) {
            for (const Fire &f : ons) m->noteOff(static_cast<uint8_t>(f.note));
            released = true;
        }
        if (!mat.input.empty()) {
            for (int32_t i = 0; i < kBlock; ++i) {
                const size_t src = static_cast<size_t>(at) + static_cast<size_t>(i);
                const float v = src < mat.input.size() ? mat.input[src] : 0.0f;
                inBlock[static_cast<size_t>(i) * 2] = v;
                inBlock[static_cast<size_t>(i) * 2 + 1] = v;
            }
            InputBus::get().publish(inBlock.data(), kBlock);
        }
        const auto t0 = static_cast<int64_t>(static_cast<double>(at) * ticksPerFrame);
        const auto t1 = static_cast<int64_t>(static_cast<double>(at + kBlock) * ticksPerFrame);
        m->onBlock(t0, t1, 120.0f);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        const bool stereo = m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) {
            out.push_back(L[i]);
            out.push_back(stereo ? R[i] : L[i]);
        }
    }
    InputBus::get().publish(nullptr, 0);
    return out;
}

/** The same deterministic source reset_test feeds an effect. */
std::vector<float> effectSource() {
    const auto n = static_cast<size_t>(kSr * (kHold + kTail));
    std::vector<float> out(n * 2, 0.0f);
    Rng rng(0xeffec7u);
    // The source stops at two thirds, and the rest is silence: a delay's
    // tail and a reverb's are most of what there is to judge about them, and
    // fed a signal to the last sample there is nowhere for either to show.
    const size_t stop = n * 2 / 3;
    for (size_t i = 0; i < stop; ++i) {
        const float t = static_cast<float>(i) / kSr;
        const float env = std::exp(-std::fmod(t, 0.5f) / 0.12f);
        const float tone = (std::sin(2.0f * static_cast<float>(M_PI) * 220.0f * t) +
                            0.5f * std::sin(2.0f * static_cast<float>(M_PI) * 331.0f * t)) * 0.35f;
        const float v = tone * env + rng.next() * 0.04f;
        out[i * 2] = v;
        out[i * 2 + 1] = v * 0.97f;
    }
    return out;
}

std::vector<float> renderEffect(const std::string &type, const std::vector<float> &norm) {
    std::unique_ptr<Effect> fx(EffectRegistry::create(type.c_str()));
    if (!fx) return {};
    fx->prepare(static_cast<int32_t>(kSr));
    fx->reset();
    for (size_t i = 0; i < norm.size(); ++i) fx->params().set(static_cast<int32_t>(i), norm[i]);
    fx->params().jumpAll();

    std::vector<float> src = effectSource();
    const auto frames = static_cast<int64_t>(src.size() / 2);
    std::vector<float> out;
    out.reserve(src.size());
    float L[kBlock], R[kBlock];
    const double ticksPerFrame = 120.0 * kPPQN / (60.0 * static_cast<double>(kSr));
    for (int64_t at = 0; at < frames; at += kBlock) {
        const int32_t n = static_cast<int32_t>(std::min<int64_t>(kBlock, frames - at));
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        for (int32_t i = 0; i < n; ++i) {
            L[i] = src[static_cast<size_t>(at + i) * 2];
            R[i] = src[static_cast<size_t>(at + i) * 2 + 1];
        }
        const auto t0 = static_cast<int64_t>(static_cast<double>(at) * ticksPerFrame);
        const auto t1 = static_cast<int64_t>(static_cast<double>(at + kBlock) * ticksPerFrame);
        fx->onBlock(t0, t1, 120.0f);
        fx->run(L, R, n, true);
        for (int32_t i = 0; i < n; ++i) {
            out.push_back(L[i]);
            out.push_back(R[i]);
        }
    }
    return out;
}

// --- The checks --------------------------------------------------------------

struct Rendered {
    std::vector<float> audio;
    Measured m;
};

/** Rising energy long after the note is gone is a feedback bug, not a taste. */
bool runaway(const std::vector<float> &stereo) {
    const size_t quarter = static_cast<size_t>(kSr * 0.25f) * 2;
    if (stereo.size() < quarter * 2) return false;
    auto rms = [&](size_t from, size_t to) {
        double sum = 0.0;
        for (size_t i = from; i < to; ++i) sum += static_cast<double>(stereo[i]) * stereo[i];
        return std::sqrt(sum / static_cast<double>(to - from));
    };
    const double last = rms(stereo.size() - quarter, stereo.size());
    const double before = rms(stereo.size() - quarter * 2, stereo.size() - quarter);
    return last > before * 2.0 && last > 1e-4;
}

void checkBank(const Bank &bank) {
    const std::string label = bank.unit;
    int32_t count = 0;
    const ParamDef *defs = bank.isEffect() ? EffectRegistry::paramDefs(bank.typeName().c_str(), count)
                                           : MachineRegistry::paramDefs(bank.unit.c_str(), count);
    if (defs == nullptr || count == 0) {
        fail(label, "the engine has no unit by that name");
        return;
    }
    if (bank.patches.empty()) {
        fail(label, "the bank is empty");
        return;
    }
    const bool unitKnown = known(bank.unit);
    if (bank.patches.front().name != "Init" || !bank.patches.front().values.empty()) {
        fail(label, "the first patch must be Init, and must set nothing");
    }

    std::vector<Rendered> rendered;
    std::vector<std::string> names;
    for (const BankPatch &patch : bank.patches) {
        ++gPatches;
        const std::string who = label + " / " + patch.name;
        if (std::find(names.begin(), names.end(), patch.name) != names.end()) {
            fail(who, "two patches with the same name");
        }
        names.push_back(patch.name);

        const Resolved r = resolve(patch, defs, count);
        for (const std::string &p : r.problems) fail(who, p);
        for (size_t i = 0; i < r.norm.size(); ++i) {
            if (!(r.norm[i] >= 0.0f && r.norm[i] <= 1.0f)) {
                fail(who, std::string(defs[i].name) + " resolved outside 0..1");
            }
        }

        Rendered out;
        // The note the patch says it is for. A piccolo trumpet's preset
        // played at C3 is not the preset, and comparing it with a tuba's at
        // the same pitch says nothing about either.
        const int note = patch.note > 0 ? patch.note : 48;
        out.audio = bank.isEffect() ? renderEffect(bank.typeName(), r.norm)
                                    : renderMachine(bank.unit, r.norm, note, r.settings);
        if (out.audio.empty()) {
            fail(who, "nothing rendered at all");
            continue;
        }
        // An effect's tail starts where its source stops, a machine's where
        // the note is released.
        const int64_t offAt = bank.isEffect() ? static_cast<int64_t>(out.audio.size() / 2) * 2 / 3
                                              : static_cast<int64_t>(kSr * kHold);
        out.m = measure(out.audio, offAt, 0,
                        bank.isEffect() ? offAt + static_cast<int64_t>(kSr * 0.05f) : -1);

        if (!out.m.finite) fail(who, "the output is not a number");
        if (out.m.peakDb < -60.0f) fail(who, "silent, with its material mounted");
        if (out.m.peakDb > 6.0f) {
            // A machine feeds a fader and a limiter, so hot is a matter of
            // taste. Twice full scale is not taste, it is arithmetic.
            char buf[80];
            std::snprintf(buf, sizeof(buf), "peaks at %+.1f dBFS", static_cast<double>(out.m.peakDb));
            if (unitKnown) todo(who, buf); else fail(who, buf);
        } else if (out.m.peakDb > -1.0f) {
            warn(who, "peaks within a decibel of full scale");
        }
        if (runaway(out.audio)) fail(who, "louder at the end of its tail than before it");

        // Panic and play it again: the same numbers or something is carrying
        // state that a reset does not reach. reset_test asks this of every
        // machine at its *defaults*; a patch can hide state behind a value.
        const std::vector<float> again = bank.isEffect() ? renderEffect(bank.typeName(), r.norm)
                                                         : renderMachine(bank.unit, r.norm, note, r.settings);
        if (again != out.audio) fail(who, "played differently the second time");

        if (out.m.dcDb > -40.0f) warn(who, "carries a DC offset");
        if (out.m.monoLossDb > 6.0f) warn(who, "loses more than 6 dB summed to mono");
        if (out.m.tailSeconds > 6.0f) warn(who, "rings for more than six seconds");
        // A quarter of a second is an eighth note at 120. A patch slower than
        // that is fine held and produces almost nothing in a phrase - which
        // is how Brazen's low brass shipped: excitation with no tube behind
        // it, heard as a click and a missing note.
        // A click on the front of a note. Not an overshoot - these measure
        // under the tone they settle into - but a burst of high frequency the
        // body of the sound never has.
        if (out.m.onsetEdge > 4.0f) {
            char buf[80];
            std::snprintf(buf, sizeof(buf), "the attack is %.0fx the edge of its own tone",
                          static_cast<double>(out.m.onsetEdge));
            warn(who, buf);
        }
        if (out.m.speaksMs > 250.0f) {
            char buf[80];
            std::snprintf(buf, sizeof(buf), "takes %.0f ms to speak", static_cast<double>(out.m.speaksMs));
            warn(who, buf);
        }
        rendered.push_back(std::move(out));
    }

    // Two patches that are bit-identical are a copy-paste, not a variation.
    for (size_t a = 0; a < rendered.size(); ++a) {
        for (size_t b = a + 1; b < rendered.size(); ++b) {
            if (rendered[a].audio == rendered[b].audio) {
                const std::string what = names[a] + " and " + names[b] + " render identically";
                if (known(bank.unit, names[a]) || known(bank.unit, names[b])) todo(label, what);
                else fail(label, what);
            }
        }
    }
    // And two that measure the same probably sound the same.
    for (size_t a = 0; a < rendered.size(); ++a) {
        for (size_t b = a + 1; b < rendered.size(); ++b) {
            const Measured &x = rendered[a].m, &y = rendered[b].m;
            // ...including how hollow each one is, because a bank that has
            // been levelled on purpose has the same rms all the way down,
            // and a centroid cannot tell a clarinet from a saxophone: the
            // two read within a percent of each other with thirteen
            // decibels between their second harmonics.
            if (std::fabs(x.rmsDb - y.rmsDb) < 1.0f && x.centroidHz > 0.0f &&
                std::fabs(x.centroidHz - y.centroidHz) < 0.05f * x.centroidHz &&
                std::fabs(x.evenOddDb - y.evenOddDb) < 2.0f &&
                std::fabs(x.tailSeconds - y.tailSeconds) < 0.1f * std::max(0.1f, x.tailSeconds)) {
                warn(label, names[a] + " and " + names[b] + " measure the same");
            }
        }
    }

    float lo = 200.0f, hi = -200.0f;
    for (const Rendered &r : rendered) {
        if (r.m.rmsDb < -190.0f) continue;
        lo = std::min(lo, r.m.rmsDb);
        hi = std::max(hi, r.m.rmsDb);
    }
    if (hi > lo && hi - lo > 12.0f) {
        char buf[80];
        std::snprintf(buf, sizeof(buf), "%.1f dB between its loudest and quietest patch",
                      static_cast<double>(hi - lo));
        warn(label, buf);
    }
}

} // namespace

int main(int argc, char **argv) {
    const char *root = std::getenv("ACIDULOUS_ROOT");
    const std::string dir = std::string(root != nullptr ? root : ".") + "/tools/banks";
    const std::string only = argc > 1 ? argv[1] : "";

    // Every machine and every effect the registries know, so a new one is
    // covered the day it is written rather than the day somebody remembers.
    std::vector<std::string> units;
    for (int32_t i = 0; i < MachineRegistry::count(); ++i) units.emplace_back(MachineRegistry::name(i));
    for (int32_t i = 0; i < EffectRegistry::count(); ++i) units.emplace_back(std::string("fx.") + EffectRegistry::name(i));

    for (const std::string &unit : units) {
        if (!only.empty() && unit != only) continue;
        Bank bank;
        std::string error;
        if (!readBank(dir + "/" + unit + ".bank", bank, error)) {
            if (known(unit)) todo(unit, "no bank file yet");
            else fail(unit, "no bank file");
            continue;
        }
        if (bank.unit != unit) {
            fail(unit, "the bank says it is " + bank.unit);
            continue;
        }
        checkBank(bank);
    }

    // A list of known faults that is never re-checked is a list that lies.
    // Anything on it that did not come up has been fixed, and saying so is
    // the only thing that keeps the list honest as the milestone shortens it.
    if (only.empty()) {
        for (size_t i = 0; i < kKnownCount; ++i) {
            if (gKnownUsed[i]) continue;
            const std::string who = std::string(kKnown[i].unit) +
                                    (kKnown[i].patch[0] != '\0' ? std::string(" / ") + kKnown[i].patch : "");
            fail(who, std::string("is on the known list and did not come up - take it off: ") + kKnown[i].why);
        }
    }
    std::printf("\n%d patches, %d failures, %d warnings, %d still to do\n", gPatches, gFailures, gWarnings,
                gTodo);
    return gFailures == 0 ? 0 : 1;
}
