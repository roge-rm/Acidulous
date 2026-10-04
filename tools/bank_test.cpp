// Checks every factory patch. The audition tool is for voicing and doesn't
// pass or fail. This runs in all_tests.sh and fails a patch that names a
// parameter the engine doesn't have, makes no sound, clips badly, or plays
// differently the second time.
//
// Unknown parameter names matter most: the app silently ignores them
// (ParamBinding.applyAll just uses the default), so a typo in a preset does
// nothing. Some machines build their parameter names with printf, so they
// can only be checked against the engine like this.
//
// Warnings are for things someone might have meant, failures for faults. A
// patch peaking at -0.5 dBFS is hot; one peaking at +34 is an error.

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <set>
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
#include "audition_settings.h"
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
 * Known outstanding faults, one line each, counted as "todo" instead of
 * failures so new faults stand out. An entry that's no longer needed is
 * reported, so the list doesn't go stale.
 */
struct Known {
    const char *unit;
    const char *patch; // empty: the whole unit
    const char *why;
};

const Known kKnown[] = {
    // Nothing outstanding right now.
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
/** "C4" for 60, so a warning about pitch says which one. */
std::string noteName(int midi) {
    static const char *kNames[12] = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    return std::string(kNames[((midi % 12) + 12) % 12]) + std::to_string(midi / 12 - 1);
}

void warn(const std::string &who, const std::string &what) {
    std::printf("  warn %-28s %s\n", who.c_str(), what.c_str());
    ++gWarnings;
}

// --- Rendering, shorter than the audition harness's -------------------------
//
// About two seconds per patch, since this runs with all the other harnesses.

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
    std::unique_ptr<::acidulous::machine::nexus::Graph> graph;
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
    // Put something on the input bus for machines that read it, so a patch
    // that needs input isn't reported as silent.
    if (machine == "Cipher" || machine == "Filament" || machine == "Molt" || machine == "Pollen" ||
        machine == "Nexus") {
        mat.input = voicePhrase();
    }
}


/**
 * The parts of a patch that aren't knobs: Nexus's graph and Formulate's
 * formula, which has to be compiled and mounted or it plays a plain
 * oscillator. This does what EngineHost does when the setting changes.
 */
void applySettings(Machine *m, const std::string &machine,
                   const std::vector<std::pair<std::string, std::string>> &settings, Material &mat,
                   const std::set<std::string> &named = {}) {
    if (machine == "Nexus") {
        for (const auto &kv : settings) {
            if (kv.first == "nexus") mountNexusGraph(m, kv.second, kSr, named, mat.graph);
        }
        return;
    }
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
                                 const std::vector<std::pair<std::string, std::string>> &settings, const std::set<std::string> &named = {}) {
    std::unique_ptr<Machine> m(MachineRegistry::create(machine.c_str()));
    if (!m) return {};
    m->prepare(static_cast<int32_t>(kSr));
    m->allNotesOff();
    m->reset();
    for (size_t i = 0; i < norm.size(); ++i) m->params().set(static_cast<int32_t>(i), norm[i]);
    m->params().jumpAll();

    Material mat;
    mountMaterial(m.get(), machine, mat);
    applySettings(m.get(), machine, settings, mat, named);

    const Kit *kit = kitFor(machine);
    const auto total = static_cast<int64_t>(kSr * (kHold + kTail));

    // Any single kit voice might be silent (an empty slice or pad), so
    // several are played across the kit. They're spread out in time, since a
    // slicer only plays the newest note and that could be an empty slice.
    struct Fire {
        int64_t at;
        int note;
    };
    std::vector<Fire> ons;
    if (kit != nullptr) {
        // Six voices spread across the whole kit, so kits that differ mostly
        // in their higher voices don't measure the same.
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
    // The source stops at two thirds and the rest is silence, so delay and
    // reverb tails can be measured.
    const size_t stop = n * 2 / 3;
    for (size_t i = 0; i < stop; ++i) {
        const float t = static_cast<float>(i) / kSr;
        const float env = std::exp(-std::fmod(t, 0.5f) / 0.12f);
        const float tone = (std::sin(2.0f * static_cast<float>(M_PI) * 220.0f * t) +
                            0.5f * std::sin(2.0f * static_cast<float>(M_PI) * 331.0f * t)) * 0.35f;
        // A noise floor at about -54 dB, like real gear. Louder noise holds
        // gates open, so different thresholds would all sound the same.
        const float v = tone * env + rng.next() * 0.002f;
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
        // Play the note the patch is meant for, since e.g. a piccolo trumpet
        // preset at C3 isn't representative.
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
            // A machine feeds a fader and a limiter, so a hot patch is a
            // choice, but twice full scale is a fault.
            char buf[80];
            std::snprintf(buf, sizeof(buf), "peaks at %+.1f dBFS", static_cast<double>(out.m.peakDb));
            if (unitKnown) todo(who, buf); else fail(who, buf);
        } else if (out.m.peakDb > -1.0f) {
            warn(who, "peaks within a decibel of full scale");
        }
        if (runaway(out.audio)) fail(who, "louder at the end of its tail than before it");

        // Reset and play again. Different output means some state survives a
        // reset. reset_test checks this at default settings, but a patch can
        // hide state behind a value.
        const std::vector<float> again = bank.isEffect() ? renderEffect(bank.typeName(), r.norm)
                                                         : renderMachine(bank.unit, r.norm, note, r.settings);
        if (again != out.audio) fail(who, "played differently the second time");

        if (out.m.dcDb > -40.0f) warn(who, "carries a DC offset");
        if (out.m.monoLossDb > 6.0f) warn(who, "loses more than 6 dB summed to mono");
        if (out.m.tailSeconds > 6.0f) warn(who, "rings for more than six seconds");
        // Checks on what a note does while it's held, which averages hide.
        char warnText[96];
        // Too much energy below 45 Hz, but only when the note played is well
        // above that (bass patches are meant to be down there). Skipped for
        // machines where the note picks a voice rather than a pitch, and for
        // effects.
        const bool notePicksVoice = kitFor(bank.typeName()) != nullptr ||
                                    (!bank.material.empty() && bank.material != "none");
        // Also skipped for unpitched sounds like Formulate's snare and hat,
        // which harmonicity tells apart.
        if (!notePicksVoice && !bank.isEffect() && note >= 48 && out.m.harmonicity > 0.1f &&
            out.m.subDb > -7.0f) {
            std::snprintf(warnText, sizeof(warnText), "%.0f%% of it is below 45 Hz, playing %s",
                          std::pow(10.0, static_cast<double>(out.m.subDb) / 10.0) * 100.0,
                          noteName(note).c_str());
            warn(who, warnText);
        }
        // Only fast level swings count, since a slow one is a swell. Skipped
        // for effects, where moving the level is often the point, and for
        // kits and the jaw harp's phrases, whose repeated plucks look like a
        // fast swing.
        const std::string &role = patch.role.empty() ? bank.role : patch.role;
        if (!bank.isEffect() && kitFor(bank.typeName()) == nullptr && role != "jaw" && role != "harmonics" &&
            out.m.swingDb > 8.0f && out.m.swingHz > 3.0f) {
            std::snprintf(warnText, sizeof(warnText), "wobbles %.0f dB at %.1f Hz inside one note",
                          static_cast<double>(out.m.swingDb), static_cast<double>(out.m.swingHz));
            warn(who, warnText);
        }
        // Same for stereo swing, since e.g. a ping-pong delay is meant to.
        if (!bank.isEffect() && out.m.panSwingDb > 9.0f) {
            std::snprintf(warnText, sizeof(warnText), "swings %.0f dB between the channels",
                          static_cast<double>(out.m.panSwingDb));
            warn(who, warnText);
        }
        // A click at the start of a note: a sharp corner in the waveform,
        // measured by clickRatio (a bright attack is fine and isn't counted).
        // Skipped for kits, where a click is the sound.
        if (kitFor(bank.typeName()) == nullptr && (out.m.clickRatio > 4.0f)) {
            char buf[80];
            std::snprintf(buf, sizeof(buf), "starts with a click, %.0fx the corner of its own tone",
                          static_cast<double>(out.m.clickRatio));
            warn(who, buf);
        }
        // Slower than 250 ms (an eighth note at 120) means a patch that's
        // fine held can go missing in a phrase. Skipped for kits, which never
        // settle to a level.
        if (kitFor(bank.typeName()) == nullptr && (out.m.speaksMs > 250.0f)) {
            char buf[80];
            std::snprintf(buf, sizeof(buf), "takes %.0f ms to speak", static_cast<double>(out.m.speaksMs));
            warn(who, buf);
        }
        rendered.push_back(std::move(out));
    }

    // Two bit-identical patches are a copy-paste mistake.
    for (size_t a = 0; a < rendered.size(); ++a) {
        for (size_t b = a + 1; b < rendered.size(); ++b) {
            if (rendered[a].audio == rendered[b].audio) {
                const std::string what = names[a] + " and " + names[b] + " render identically";
                if (known(bank.unit, names[a]) || known(bank.unit, names[b])) todo(label, what);
                else fail(label, what);
            }
        }
    }
    // Two that measure the same probably sound the same.
    for (size_t a = 0; a < rendered.size(); ++a) {
        for (size_t b = a + 1; b < rendered.size(); ++b) {
            const Measured &x = rendered[a].m, &y = rendered[b].m;
            // Also compares hollowness (a levelled bank has the same loudness
            // throughout, and centroid can't tell a clarinet from a sax) and
            // pitch (two patches an octave apart aren't the same sound).
            const bool samePitch = x.partialRatio > 0.0f && y.partialRatio > 0.0f &&
                                   std::fabs(x.partialRatio - y.partialRatio) < 0.25f * x.partialRatio;
            if (samePitch && std::fabs(x.loudnessDb - y.loudnessDb) < 1.0f && x.centroidHz > 0.0f &&
                std::fabs(x.centroidHz - y.centroidHz) < 0.05f * x.centroidHz &&
                std::fabs(x.evenOddDb - y.evenOddDb) < 2.0f &&
                std::fabs(x.tailSeconds - y.tailSeconds) < 0.1f * std::max(0.1f, x.tailSeconds)) {
                warn(label, names[a] + " and " + names[b] + " measure the same");
            }
        }
    }

    // For kits compare peaks, not loudness. `loud` is the loudest 400 ms, and
    // dry hits leave most of that window silent while bells fill it.
    const bool struck = kitFor(bank.typeName()) != nullptr;
    float lo = 200.0f, hi = -200.0f;
    for (const Rendered &r : rendered) {
        const float level = struck ? r.m.peakDb : r.m.loudnessDb;
        if (level < -190.0f) continue;
        lo = std::min(lo, level);
        hi = std::max(hi, level);
    }
    if (hi > lo && hi - lo > 12.0f) {
        char buf[80];
        std::snprintf(buf, sizeof(buf), "%.1f dB between its loudest and quietest patch%s",
                      static_cast<double>(hi - lo), struck ? ", by peak" : "");
        warn(label, buf);
    }
}

} // namespace

int main(int argc, char **argv) {
    const char *root = std::getenv("ACIDULOUS_ROOT");
    const std::string dir = std::string(root != nullptr ? root : ".") + "/tools/banks";
    const std::string only = argc > 1 ? argv[1] : "";

    // Every machine and effect in the registries, so new ones are covered
    // automatically.
    std::vector<std::string> units;
    for (int32_t i = 0; i < MachineRegistry::count(); ++i) {
        const std::string name = MachineRegistry::name(i);
        // Bias has no sound of its own. It plays what was recorded onto it
        // and its patches are recording media, so with nothing recorded some
        // are silent, correctly. `tools/audition.sh bank Bias` measures each
        // medium's noise floor and colour instead. Not on the known list,
        // since it's not a fault.
        if (name == "Bias") continue;
        units.emplace_back(name);
    }
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

    // Anything on the known list that didn't come up has been fixed and
    // should be removed.
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
