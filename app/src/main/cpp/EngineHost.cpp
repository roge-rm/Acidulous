#include "EngineHost.h"

#include <engine/dsp/Denormals.h>
#include <engine/machine/molt/Molt.h>
#include <engine/core/Settings.h>
#include <engine/machine/nexus/Nexus.h>

#include <algorithm>
#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <chrono>
#include <drivers/AudioDriver.h>
#include <link/LinkTimebase.h>
#include <engine/core/Constants.h>
#include <engine/core/Frozen.h>
#include <engine/format/WavReader.h>
#include <engine/core/ReelCache.h>
#include <engine/machine/bias/Bias.h>
#include <engine/format/WavStream.h>
#include <sys/stat.h>
#include <engine/dsp/Wavetable.h>
#include <engine/format/WavWriter.h>
#include <engine/core/Reel.h>
#include <engine/effect/EffectRegistry.h>
#include <engine/inputmod/InputModRegistry.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/format/Sf2Reader.h>
#include <engine/core/Slices.h>
#include <engine/format/AudioDecoder.h>
#include <engine/core/Take.h>
#include <engine/machine/forage/Forage.h>
#include <engine/machine/cumulus/Cumulus.h>
#include <engine/machine/formulate/Formulate.h>
#include <engine/machine/dice/Dice.h>
#include <engine/machine/pollen/Pollen.h>
#include <engine/machine/mosaic/Mosaic.h>
#include <map>
#include <sstream>
#include <engine/rack/Engine.h>
#include <sequencer/Song.h>
#include <thread>

#define LOG_TAG "Acidulous.Engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace acidulous {

namespace {
Engine sEngine;
AudioDriver sAudio;
/** Link, made once and kept: the audio thread holds a pointer to it. */
LinkTimebase sLink;

Unit unitFromName(const std::string &u) {
    if (u == "effect1") return Unit::Effect1;
    if (u == "effect2") return Unit::Effect2;
    if (u == "mod1") return Unit::Mod1;
    if (u == "mod2") return Unit::Mod2;
    if (u == "mod3") return Unit::Mod3;
    if (u == "channel") return Unit::Channel;
    if (u == "master") return Unit::Master;
    // group1fx1 .. group4fx2
    if (u.size() == 9 && u.compare(0, 5, "group") == 0 && u.compare(6, 2, "fx") == 0) {
        const int g = u[5] - '1', s = u[8] - '1';
        if (g >= 0 && g < kGroupSlots && s >= 0 && s < kGroupInsertSlots) {
            return static_cast<Unit>(static_cast<int>(Unit::Group1Fx1) + g * kGroupInsertSlots + s);
        }
    }
    if (u == "master1") return Unit::MasterFx1;
    if (u == "master2") return Unit::MasterFx2;
    if (u == "send1") return Unit::Send1;
    if (u == "send2") return Unit::Send2;
    if (u == "input1") return Unit::Input1;
    if (u == "input2") return Unit::Input2;
    if (u == "performance") return Unit::Performance;
    if (u == "perform") return Unit::Perform;
    return Unit::Machine;
}

seq::SongSnapshot *fromHandle(int64_t handle) {
    return reinterpret_cast<seq::SongSnapshot *>(static_cast<intptr_t>(handle));
}
} // namespace

EngineHost &EngineHost::instance() {
    static EngineHost host;
    return host;
}

EngineHost::~EngineHost() { stop(); }

bool EngineHost::start() {
    if (running) return true;
    sEngine.start();
    sAudio.registerCallback([](float *in, float *out, unsigned long) { sEngine.renderBlock(in, out); });
    if (!sAudio.start()) {
        LOGE("audio failed to start");
        sEngine.stop();
        return false;
    }
    running = true;
    LOGI("engine started: %d racks, %d Hz, block %d", kRackCount, sAudio.getSampleRate(), kBlockFrames);
    return true;
}

void EngineHost::stop() {
    if (!running) return;
    running = false;
    sAudio.stop(); // once the callback is gone nothing else touches the racks
    sEngine.stop();
    for (auto &t : mountedType) t.clear();
    for (auto &r : mountedEffectType) for (auto &t : r) t.clear();
    for (auto &r : mountedModifierType) for (auto &t : r) t.clear();
    LOGI("engine stopped");
}

bool EngineHost::mountObjectWithRetry(Mount &m) { return mountWithRetry(m, m.deleter); }

bool EngineHost::mountWithRetry(Mount &m, void (*deleter)(void *)) {
    // The audio thread applies a bounded burst of mounts per block (1.33 ms); a full queue is a
    // burst, not a fault.
    for (int attempt = 0; attempt < 50; ++attempt) {
        if (sEngine.mount(m)) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    LOGE("mount queue stayed full; dropping");
    deleter(m.object);
    return false;
}

bool EngineHost::mountMachine(int rack, const std::string &typeName) {
    if (rack < 0 || rack >= kRackCount) return false;
    Machine *machine = MachineRegistry::create(typeName.c_str());
    if (machine == nullptr) {
        LOGE("unknown machine '%s'", typeName.c_str());
        return false;
    }
    machine->prepare(kSampleRate);
    Mount m;
    m.kind = Mount::Kind::Machine;
    m.rack = rack;
    m.object = machine;
    if (!mountWithRetry(m, deleteAs<Machine>)) return false;
    mountedType[rack] = typeName;
    LOGI("queued %s for rack %d", typeName.c_str(), rack);
    return true;
}

void EngineHost::unmountMachine(int rack) {
    if (rack < 0 || rack >= kRackCount) return;
    Mount m;
    m.kind = Mount::Kind::Machine;
    m.rack = rack;
    m.object = nullptr; // swap in nothing; the old machine is retired
    if (mountWithRetry(m, [](void *) {})) mountedType[rack].clear();
}

/**
 * Put an effect on one of the two send buses, or empty it.
 *
 * `mountEffect` for the master, and deliberately the same shape: built here,
 * prepared here, handed over on the audio thread, the old one retired off it.
 *
 * **The mix is pinned open.** Every effect carries a wet/dry, and on a send a
 * dry path is the track arriving in the mix twice - once through the channel
 * and once through the return. So if this effect has a `mix`, it is set to
 * fully wet as it is built, and the editor does not offer it.
 */
bool EngineHost::mountInputEffect(int slot, const std::string &typeName) {
    if (slot < 0 || slot >= kInputSlots) return false;
    Effect *fx = nullptr;
    if (!typeName.empty()) {
        fx = EffectRegistry::create(typeName.c_str());
        if (fx == nullptr) {
            LOGE("unknown input effect '%s'", typeName.c_str());
            return false;
        }
        fx->prepare(kSampleRate);
    }
    Mount m;
    m.kind = Mount::Kind::Input;
    m.slot = slot;
    m.object = fx;
    if (!mountWithRetry(m, deleteAs<Effect>)) return false;
    mountedInputType[slot] = typeName;
    return true;
}

bool EngineHost::mountSend(int slot, const std::string &typeName) {
    if (slot < 0 || slot >= kSendSlots) return false;
    Effect *fx = nullptr;
    if (!typeName.empty()) {
        fx = EffectRegistry::create(typeName.c_str());
        if (fx == nullptr) {
            LOGE("unknown send effect '%s'", typeName.c_str());
            return false;
        }
        fx->prepare(kSampleRate);
        const int32_t mix = fx->params().indexOf("mix");
        if (mix >= 0) {
            fx->params().set(mix, 1.0f);
            fx->params().jumpAll(); // wet from the first block, not smoothed up to it
        }
    }
    Mount m;
    m.kind = Mount::Kind::Send;
    m.slot = slot;
    m.object = fx;
    if (!mountWithRetry(m, deleteAs<Effect>)) return false;
    mountedSendType[slot] = typeName;
    return true;
}

bool EngineHost::mountGroupInsert(int group, int slot, const std::string &typeName) {
    if (group < 0 || group >= kGroupSlots || slot < 0 || slot >= kGroupInsertSlots) return false;
    Effect *fx = nullptr;
    if (!typeName.empty()) {
        fx = EffectRegistry::create(typeName.c_str());
        if (fx == nullptr) {
            LOGE("unknown group effect '%s'", typeName.c_str());
            return false;
        }
        fx->prepare(kSampleRate);
    }
    Mount m;
    m.kind = Mount::Kind::GroupInsert;
    m.rack = group;
    m.slot = slot;
    m.object = fx;
    if (!mountWithRetry(m, deleteAs<Effect>)) return false;
    mountedGroupInsertType[group][slot] = typeName;
    return true;
}

bool EngineHost::mountMasterInsert(int slot, const std::string &typeName) {
    if (slot < 0 || slot >= kMasterInsertSlots) return false;
    Effect *fx = nullptr;
    if (!typeName.empty()) {
        fx = EffectRegistry::create(typeName.c_str());
        if (fx == nullptr) {
            LOGE("unknown master effect '%s'", typeName.c_str());
            return false;
        }
        fx->prepare(kSampleRate);
    }
    Mount m;
    m.kind = Mount::Kind::MasterInsert;
    m.slot = slot;
    m.object = fx;
    if (!mountWithRetry(m, deleteAs<Effect>)) return false;
    mountedMasterInsertType[slot] = typeName;
    return true;
}

bool EngineHost::mountEffect(int rack, int slot, const std::string &typeName) {
    if (rack < 0 || rack >= kRackCount || slot < 0 || slot >= kEffectSlots) return false;
    Effect *fx = nullptr;
    if (!typeName.empty()) {
        fx = EffectRegistry::create(typeName.c_str());
        if (fx == nullptr) {
            LOGE("unknown effect '%s'", typeName.c_str());
            return false;
        }
        fx->prepare(kSampleRate);
    }
    Mount m;
    m.kind = Mount::Kind::Effect;
    m.rack = rack;
    m.slot = slot;
    m.object = fx;
    if (!mountWithRetry(m, deleteAs<Effect>)) return false;
    mountedEffectType[rack][slot] = typeName;
    LOGI("queued effect '%s' for rack %d slot %d", typeName.c_str(), rack, slot);
    return true;
}

bool EngineHost::mountInputMod(int rack, int slot, const std::string &typeName) {
    if (rack < 0 || rack >= kRackCount || slot < 0 || slot >= kInputModSlots) return false;
    InputMod *ev = nullptr;
    if (!typeName.empty()) {
        ev = InputModRegistry::create(typeName.c_str());
        if (ev == nullptr) {
            LOGE("unknown modifier '%s'", typeName.c_str());
            return false;
        }
        ev->reset();
    }
    Mount m;
    m.kind = Mount::Kind::InputMod;
    m.rack = rack;
    m.slot = slot;
    m.object = ev;
    if (!mountWithRetry(m, deleteAs<InputMod>)) return false;
    mountedModifierType[rack][slot] = typeName;
    LOGI("queued modifier '%s' for rack %d slot %d", typeName.c_str(), rack, slot);
    return true;
}

void EngineHost::prewarm() {
    const auto t0 = std::chrono::steady_clock::now();
    (void)dsp::WavetableBank::instance();
    LOGI("prewarm: wavetables ready in %lld ms",
         static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::steady_clock::now() - t0).count()));
}

const char *EngineHost::mountedEffect(int rack, int slot) const {
    return (rack >= 0 && rack < kRackCount && slot >= 0 && slot < kEffectSlots) ? mountedEffectType[rack][slot].c_str() : "";
}

bool EngineHost::loadSample(int rack, int slot, const std::string &path, std::string &error, int maxSeconds) {
    if (rack < 0 || rack >= kRackCount) { error = "bad rack"; return false; }
    SampleData *sample = nullptr;
    if (!path.empty()) {
        // Through the front door, so a document that still names a .flac or
        // an .mp3 - one imported before the conversion existed, or edited by
        // hand - plays rather than failing.
        auto decoded = decodeAudio(path, kSampleRate, error, maxSeconds > 0 ? maxSeconds : kMaxDecodeSeconds);
        if (!decoded) return false;
        sample = decoded.release();
    }
    Mount m;
    m.kind = Mount::Kind::Object;
    m.rack = rack;
    m.slot = slot;
    m.object = sample;
    m.deleter = deleteAs<SampleData>;
    if (!mountWithRetry(m, deleteAs<SampleData>)) { error = "mount queue full"; return false; }
    LOGI("queued sample '%s' for rack %d pad %d", path.c_str(), rack, slot);
    return true;
}

namespace {

/**
 * A sample as min/max pairs, one per column.
 *
 * Shared by the mounted-pad shape and the file shape below, because two
 * pictures of the same sound drawn by two loops is one of them being subtly
 * different and nobody knowing which.
 */
int32_t shapeOf(const SampleData &s, float *dest, int32_t columns, int32_t fromFrame,
                int32_t toFrame) {
    if (dest == nullptr || columns <= 0 || s.frames <= 0) return 0;

    // An empty or nonsensical range is the whole sample, so every caller that
    // does not care about a window says nothing and gets what it always got.
    int64_t first = std::clamp<int64_t>(fromFrame, 0, s.frames - 1);
    int64_t last = toFrame > fromFrame ? std::clamp<int64_t>(toFrame, 1, s.frames) : s.frames;
    if (last <= first) {
        first = 0;
        last = s.frames;
    }
    const int64_t span = last - first;

    for (int32_t c = 0; c < columns; ++c) {
        const int64_t from = first + span * c / columns;
        int64_t to = first + span * (c + 1) / columns;
        if (to <= from) to = from + 1;
        if (to > s.frames) to = s.frames;
        float lo = 0.0f, hi = 0.0f;
        for (int64_t i = from; i < to; ++i) {
            // Both channels, because a waveform that shows only the left is a
            // waveform that lies about anything panned.
            const float l = s.left[static_cast<size_t>(i)];
            const float r = s.stereo ? s.right[static_cast<size_t>(i)] : l;
            lo = std::min(lo, std::min(l, r));
            hi = std::max(hi, std::max(l, r));
        }
        dest[c * 2] = lo;
        dest[c * 2 + 1] = hi;
    }
    return columns;
}

} // namespace

int32_t EngineHost::sampleShape(int rack, int pad, float *dest, int32_t columns, int32_t fromFrame,
                               int32_t toFrame) const {
    if (rack < 0 || rack >= kRackCount) return 0;
    auto *forage = dynamic_cast<machine::Forage *>(sEngine.racks[rack].currentMachine());
    if (forage == nullptr) return 0;
    const SampleData *s = forage->sampleAt(pad);
    if (s == nullptr) return 0;
    return shapeOf(*s, dest, columns, fromFrame, toFrame);
}

int32_t EngineHost::fileShape(const std::string &path, float *dest, int32_t columns,
                              int32_t fromFrame, int32_t toFrame) const {
    std::string error;
    const std::unique_ptr<SampleData> s = WavReader::read(path, kSampleRate, error, kMaxSliceSeconds);
    if (s == nullptr) return 0;
    return shapeOf(*s, dest, columns, fromFrame, toFrame);
}

std::string EngineHost::fileInfo(const std::string &path) const {
    std::string error;
    const std::unique_ptr<SampleData> s = WavReader::read(path, kSampleRate, error, kMaxSliceSeconds);
    if (s == nullptr || s->frames <= 0) return "";
    char out[256];
    std::snprintf(out, sizeof(out), "%s|%d|%d|%d|%.4f", s->name.c_str(), s->frames,
                  s->stereo ? 2 : 1, s->rate, static_cast<double>(s->peak));
    return out;
}

std::string EngineHost::fileSurvey(const std::string &path, float *dest, int32_t columns) const {
    std::string error;
    const std::unique_ptr<SampleData> s = WavReader::read(path, kSampleRate, error, kMaxSliceSeconds);
    if (s == nullptr || s->frames <= 0) return "";
    shapeOf(*s, dest, columns, 0, 0);
    char out[256];
    std::snprintf(out, sizeof(out), "%s|%d|%d|%d|%.4f", s->name.c_str(), s->frames,
                  s->stereo ? 2 : 1, s->rate, static_cast<double>(s->peak));
    return out;
}

std::string EngineHost::auditionFile(const std::string &path) {
    if (path.empty()) {
        sEngine.audition.stop();
        return "";
    }
    std::string error;
    const std::unique_ptr<SampleData> s = WavReader::read(path, kSampleRate, error, kMaxSliceSeconds);
    if (s == nullptr || s->frames <= 0) return error.empty() ? "that file could not be read" : error;
    // Interleaved here, on this thread, so the audio thread has nothing to do
    // but add two numbers per frame.
    std::vector<float> pcm(static_cast<size_t>(s->frames) * 2, 0.0f);
    for (int32_t i = 0; i < s->frames; ++i) {
        const float l = s->left[static_cast<size_t>(i)];
        pcm[static_cast<size_t>(i) * 2] = l;
        pcm[static_cast<size_t>(i) * 2 + 1] = s->stereo ? s->right[static_cast<size_t>(i)] : l;
    }
    sEngine.audition.play(pcm.data(), s->frames);
    return "";
}

bool EngineHost::auditioning() const { return sEngine.audition.active(); }

std::string EngineHost::editSample(const std::string &src, const std::string &dst,
                                   const audio::SampleOps &ops) const {
    std::string error;
    std::unique_ptr<SampleData> s = WavReader::read(src, kSampleRate, error, kMaxSliceSeconds);
    if (s == nullptr) return error.empty() ? "that file could not be read" : error;
    if (!audio::applyEdit(*s, ops, error)) return error;

    // **Through a temporary, always.** The common case is overwriting the file
    // that was just read, and a writer that failed halfway through that would
    // leave a recording that is half of itself with nothing to go back to.
    const std::string tmp = dst + ".part";
    {
        WavWriter writer;
        if (!writer.open(tmp, kSampleRate, EngineSettings::get().recordBits, error)) {
            return error.empty() ? "that file could not be written" : error;
        }
        std::vector<float> block(static_cast<size_t>(kBlockFrames) * 2, 0.0f);
        for (int32_t at = 0; at < s->frames; at += kBlockFrames) {
            const int32_t n = std::min(kBlockFrames, s->frames - at);
            for (int32_t i = 0; i < n; ++i) {
                const float l = s->left[static_cast<size_t>(at + i)];
                block[static_cast<size_t>(i) * 2] = l;
                block[static_cast<size_t>(i) * 2 + 1] =
                    s->stereo ? s->right[static_cast<size_t>(at + i)] : l;
            }
            writer.write(block.data(), n);
        }
        if (!writer.close()) return "that file could not be finished";
    }
    std::remove(dst.c_str());
    if (std::rename(tmp.c_str(), dst.c_str()) != 0) {
        std::remove(tmp.c_str());
        return "that file could not be replaced";
    }
    return "";
}

/**
 * Two lines: a word, then a path or a reason.
 *
 *     ok\n/some/where.wav     converted, all of it
 *     cut\n/some/where.wav    converted, but only the first thirty seconds
 *     err\nwhat went wrong
 *
 * One string crosses the boundary, and the caller has somewhere to put the
 * one thing it could not otherwise find out - that a long file was shortened.
 */
std::string EngineHost::importAudio(const std::string &path, std::string &error, int maxSeconds) const {
    const int seconds = maxSeconds > 0 ? maxSeconds : kMaxDecodeSeconds;
    const AudioFormat format = sniff(path);
    if (format == AudioFormat::Wav) {
        // A WAV is taken as it is, and a long one is truncated when it is
        // read rather than here, so there is nothing to say yet.
        return "ok\n" + path;
    }
    if (format == AudioFormat::Unknown) {
        const char *kind = foreignKind(path);
        error = kind != nullptr ? std::string(kind) + ", which this cannot read"
                                : "not an audio file this can read";
        return "";
    }
    auto decoded = decodeAudio(path, kSampleRate, error, seconds);
    if (!decoded) return "";

    // Alongside, with the extension replaced: `break.flac` becomes
    // `break.wav`, which is the name a player will look for.
    const size_t dot = path.find_last_of('.');
    const size_t slash = path.find_last_of('/');
    const std::string stem = (dot != std::string::npos && (slash == std::string::npos || dot > slash))
                                 ? path.substr(0, dot)
                                 : path;
    // Never the file we are reading from: a source called "break.wav" that
    // turned out not to be a WAV would otherwise be overwritten and then
    // removed, leaving nothing at all where the import used to be.
    std::string out = stem + ".wav";
    if (out == path) out = stem + " converted.wav";
    for (int n = 2; n < 1000; ++n) {
        FILE *exists = std::fopen(out.c_str(), "rb");
        if (exists == nullptr) break;
        std::fclose(exists);
        out = stem + " " + std::to_string(n) + ".wav";
    }

    WavWriter writer;
    if (!writer.open(out, kSampleRate, 24, error)) return "";
    // Interleaved, which is what a sink takes; a mono file is written to both
    // sides rather than kept mono, because every other WAV the app writes is
    // stereo and one shape downstream is worth a little disk.
    std::vector<float> interleaved(static_cast<size_t>(decoded->frames) * 2);
    for (int32_t i = 0; i < decoded->frames; ++i) {
        const float l = decoded->left[static_cast<size_t>(i)];
        const float r = decoded->stereo ? decoded->right[static_cast<size_t>(i)] : l;
        interleaved[static_cast<size_t>(i) * 2] = l;
        interleaved[static_cast<size_t>(i) * 2 + 1] = r;
    }
    writer.write(interleaved.data(), decoded->frames);
    if (!writer.close()) { error = "could not write the converted file"; return ""; }
    std::remove(path.c_str()); // the original was a copy of the player's own file
    // Everything a report of a bad conversion needs, in one line: the format
    // it decided on, what came out of the decoder, and how loud it was.
    LOGI("converted %s (%s) to %s: %d frames at %d Hz, %s, peak %.3f%s", path.c_str(), formatName(format),
         out.c_str(), decoded->frames, decoded->rate, decoded->stereo ? "stereo" : "mono",
         static_cast<double>(decoded->peak), decoded->truncated ? ", truncated" : "");
    return (decoded->truncated ? "cut\n" : "ok\n") + out;
}

std::string EngineHost::slicePoints(const std::string &path, int mode, int count, std::string &error) const {
    // The long ceiling: this is only ever asked about a slice source, which
    // is the one file a whole machine shares.
    auto decoded = decodeAudio(path, kSampleRate, error, kMaxSliceSeconds);
    if (!decoded) return "";
    const std::vector<float> points = audio::slicePoints(
        *decoded, mode == 1 ? audio::SliceMode::Even : audio::SliceMode::Transients, count,
        static_cast<float>(kSampleRate));
    if (points.empty()) { error = "empty"; return ""; }
    std::string out;
    char buf[32];
    for (float v : points) {
        std::snprintf(buf, sizeof(buf), "%.6f", static_cast<double>(v));
        if (!out.empty()) out += ",";
        out += buf;
    }
    return out;
}

std::string EngineHost::soundFontPresets(const std::string &path, std::string &error) {
    std::vector<Sf2Reader::PresetInfo> presets;
    if (!Sf2Reader::listPresets(path, presets, error)) return "";
    std::string out;
    for (const auto &p : presets) {
        out += std::to_string(p.bank) + "|" + std::to_string(p.preset) + "|" + p.name + "\n";
    }
    return out;
}

namespace {
bool mountMap(EngineHost &host, Engine &engine, int rack, SampleMap *built, std::string &error);
}

bool EngineHost::loadSoundFont(int rack, const std::string &path, int presetIndex, std::string &error) {
    if (rack < 0 || rack >= kRackCount) { error = "bad rack"; return false; }
    auto built = Sf2Reader::load(path, presetIndex, error);
    if (!built) return false;
    LOGI("soundfont '%s' preset %d: %zu zones, %zu samples", built->name.c_str(), presetIndex,
         built->zones.size(), built->samples.size());
    return mountMap(*this, sEngine, rack, built.release(), error);
}

bool EngineHost::loadZoneMap(int rack, const std::string &spec, const std::string &name, std::string &error) {
    if (rack < 0 || rack >= kRackCount) { error = "bad rack"; return false; }
    auto built = std::make_unique<SampleMap>();
    built->name = name;
    std::istringstream lines(spec);
    std::string line;
    std::map<std::string, int32_t> loaded;
    while (std::getline(lines, line)) {
        if (line.empty()) continue;
        std::vector<std::string> f;
        size_t start = 0;
        while (true) {
            const size_t bar = line.find('|', start);
            f.push_back(line.substr(start, bar == std::string::npos ? std::string::npos : bar - start));
            if (bar == std::string::npos) break;
            start = bar + 1;
        }
        if (f.size() < 10) { error = "malformed zone line"; return false; }
        auto known = loaded.find(f[0]);
        if (known == loaded.end()) {
            std::string readError;
            auto data = WavReader::read(f[0], 0, readError); // 0: keep the file's own rate
            if (!data) { error = f[0] + ": " + readError; return false; }
            built->samples.push_back(std::move(*data));
            known = loaded.emplace(f[0], static_cast<int32_t>(built->samples.size()) - 1).first;
        }
        MapZone z;
        z.sample = known->second;
        z.lowKey = static_cast<uint8_t>(std::clamp(std::stoi(f[1]), 0, 127));
        z.highKey = static_cast<uint8_t>(std::clamp(std::stoi(f[2]), 0, 127));
        z.rootKey = static_cast<uint8_t>(std::clamp(std::stoi(f[3]), 0, 127));
        z.lowVel = static_cast<uint8_t>(std::clamp(std::stoi(f[4]), 0, 127));
        z.highVel = static_cast<uint8_t>(std::clamp(std::stoi(f[5]), 0, 127));
        z.tuneCents = std::stof(f[6]);
        z.gain = std::stof(f[7]);
        z.pan = std::stof(f[8]);
        if (std::stoi(f[9]) != 0) {
            const SampleData &s = built->samples[static_cast<size_t>(z.sample)];
            z.loopStart = s.loopStart >= 0 ? s.loopStart : 0;
            z.loopEnd = s.loopEnd > 0 ? s.loopEnd : s.frames - 1;
        }
        built->zones.push_back(z);
    }
    if (built->zones.empty()) { error = "no zones"; return false; }
    LOGI("zone map '%s': %zu zones, %zu samples", name.c_str(), built->zones.size(), built->samples.size());
    return mountMap(*this, sEngine, rack, built.release(), error);
}

namespace {
bool mountMap(EngineHost &host, Engine &, int rack, SampleMap *built, std::string &error) {
    Mount m;
    m.kind = Mount::Kind::Object;
    m.rack = rack;
    m.slot = 0;
    m.object = built;
    m.deleter = deleteAs<SampleMap>;
    if (!host.mountObjectWithRetry(m)) { error = "mount queue full"; return false; }
    return true;
}
} // namespace

std::string EngineHost::loadNexusPatch(int rack, const std::string &spec) {
    if (rack < 0 || rack >= kRackCount) return "no such rack";
    Machine *m = sEngine.racks[rack].currentMachine();
    if (m == nullptr || std::strcmp(m->typeName(), "Nexus") != 0) return "that rack is not a Nexus";
    std::string error;
    // Parsed and allocated here, on whatever worker called us, and handed
    // over as one object - the audio thread never builds a graph.
    machine::nexus::Graph *graph = machine::nexus::Graph::parse(spec, static_cast<float>(kSampleRate), error);
    if (graph == nullptr) return error.empty() ? "the patch could not be read" : error;
    const std::string warn = graph->warning();
    Mount mount;
    mount.kind = Mount::Kind::Object;
    mount.rack = rack;
    mount.slot = 0;
    mount.object = graph;
    mount.deleter = deleteAs<machine::nexus::Graph>;
    if (!mountObjectWithRetry(mount)) {
        delete graph;
        return "mount queue full";
    }
    return warn;
}

std::string EngineHost::nexusPalette() const {
    // "name|cap|knob,knob,...|default,default,...|in,in,...|out,out,..." per line.
    std::string out;
    for (int32_t t = 0; t < machine::nexus::TypeCount; ++t) {
        const auto &info = machine::nexus::infoFor(t);
        out += info.name;
        out += "|";
        out += std::to_string(static_cast<int>(info.cap));
        out += "|";
        for (int k = 0; k < machine::nexus::kKnobs; ++k) {
            if (k > 0) out += ",";
            out += info.knob[k] != nullptr ? info.knob[k] : "";
        }
        out += "|";
        for (int k = 0; k < machine::nexus::kKnobs; ++k) {
            if (k > 0) out += ",";
            char buf[16];
            std::snprintf(buf, sizeof(buf), "%.4f", info.def[k]);
            out += buf;
        }
        out += "|";
        for (int p = 0; p < machine::nexus::kPorts; ++p) {
            if (info.in[p] == nullptr) break;
            if (p > 0) out += ",";
            out += info.in[p];
        }
        out += "|";
        for (int p = 0; p < machine::nexus::kPorts; ++p) {
            if (info.out[p] == nullptr) break;
            if (p > 0) out += ",";
            out += info.out[p];
        }
        out += "\n";
    }
    return out;
}

int32_t EngineHost::nexusScope(int rack, float *dest, int32_t max) const {
    if (rack < 0 || rack >= kRackCount || dest == nullptr) return 0;
    Machine *m = sEngine.racks[rack].currentMachine();
    if (m == nullptr || std::strcmp(m->typeName(), "Nexus") != 0) return 0;
    return static_cast<machine::Nexus *>(m)->readScope(dest, max);
}

int32_t EngineHost::nexusActivity(int rack, float *dest, int32_t max) const {
    if (rack < 0 || rack >= kRackCount || dest == nullptr) return 0;
    Machine *m = sEngine.racks[rack].currentMachine();
    if (m == nullptr || std::strcmp(m->typeName(), "Nexus") != 0) return 0;
    return static_cast<machine::Nexus *>(m)->readActivity(dest, max);
}

std::string EngineHost::sampleMapInfo(int rack) const {
    if (rack < 0 || rack >= kRackCount) return "";
    auto *mosaic = dynamic_cast<machine::Mosaic *>(sEngine.racks[rack].currentMachine());
    if (mosaic == nullptr) return "";
    const SampleMap *m = mosaic->currentMap();
    if (m == nullptr) return "";
    int64_t frames = 0;
    for (const auto &s : m->samples) frames += s.frames;
    return m->name + "|" + std::to_string(m->zones.size()) + "|" + std::to_string(m->samples.size()) + "|" +
           std::to_string(static_cast<double>(frames) / 48000.0);
}

std::string EngineHost::sampleInfo(int rack, int slot) const {
    if (rack < 0 || rack >= kRackCount) return "";
    auto *forage = dynamic_cast<machine::Forage *>(sEngine.racks[rack].currentMachine());
    if (forage == nullptr) return "";
    const SampleData *s = forage->sampleAt(slot);
    if (s == nullptr) return "";
    char peak[24];
    std::snprintf(peak, sizeof(peak), "%.6f", static_cast<double>(s->peak));
    return s->name + "|" + std::to_string(s->frames) + "|" + (s->stereo ? "1" : "0") + "|" + peak;
}

const char *EngineHost::mountedMachine(int rack) const {
    return (rack >= 0 && rack < kRackCount) ? mountedType[rack].c_str() : "";
}

void EngineHost::noteOn(int rack, uint8_t note, uint8_t velocity) {
    if (rack < 0 || rack >= kRackCount) return;
    sEngine.pushMidi({static_cast<uint8_t>(0x90 | rack), note, velocity});
}

void EngineHost::noteOff(int rack, uint8_t note) {
    if (rack < 0 || rack >= kRackCount) return;
    sEngine.pushMidi({static_cast<uint8_t>(0x80 | rack), note, 0});
}

namespace {
/**
 * Mod and pressure go in as parameters rather than as MIDI.
 *
 * They come out the far end as MIDI again - Rack::setParam turns them back -
 * so the machine cannot tell the difference. What the detour buys is
 * everything the parameter path already does: the move is recorded into a
 * lane, it marks the control touched so its own lane cannot fight it for
 * the rest of the pass, and on playback the lane drives it. Sending them
 * straight through as MIDI, which is what they used to do, is why they were
 * the one gesture in the app that nothing remembered.
 */
void pushPerformance(int rack, int32_t index, uint8_t value, bool record) {
    ParamMessage p;
    p.rack = rack;
    p.unit = Unit::Performance;
    p.index = index;
    p.value = static_cast<float>(value) / 127.0f;
    p.record = record;
    sEngine.pushParam(p);
}
} // namespace

void EngineHost::controlChange(int rack, uint8_t cc, uint8_t value, bool record) {
    if (rack < 0 || rack >= kRackCount) return;
    if (cc == 1) {
        pushPerformance(rack, kPerfMod, value, record);
        return;
    }
    sEngine.pushMidi({static_cast<uint8_t>(0xb0 | rack), cc, value});
}

void EngineHost::channelPressure(int rack, uint8_t value, bool record) {
    if (rack < 0 || rack >= kRackCount) return;
    pushPerformance(rack, kPerfPressure, value, record);
}

void EngineHost::setMpeZone(int kind, int members, float bendSemis) {
    mpeZoneKind.store(kind, std::memory_order_relaxed);
    mpeZoneMembers.store(members, std::memory_order_relaxed);
    sEngine.setMpeZone(kind, members, bendSemis);
}

bool EngineHost::mpeMemberChannel(uint8_t channel) const {
    if (channel > 15) return false;
    const int kind = mpeZoneKind.load(std::memory_order_relaxed);
    const int members = mpeZoneMembers.load(std::memory_order_relaxed);
    if (kind == 1) return channel >= 1 && channel <= members;
    if (kind == 2) return channel <= 14 && channel >= 15 - members;
    return false;
}

int EngineHost::mpeHeldMask() const { return sEngine.mpeHeldMask(); }

void EngineHost::midiEvent(int rack, uint8_t status, uint8_t d1, uint8_t d2, uint8_t channel) {
    if (rack < 0 || rack >= kRackCount) return;
    const uint8_t kind = status & 0xf0;
    // On a member channel the wheel and the pressure strip are not the
    // channel's, they are one finger's - so they must not be diverted into
    // the performance lane, which has no idea which note it belongs to.
    // They go through as MIDI and the rack works out whose they are.
    if (mpeMemberChannel(channel)) {
        sEngine.pushMidi({static_cast<uint8_t>(kind | rack), d1, d2, channel});
        return;
    }
    // A wheel on a controller is the same gesture as the wheel on screen and
    // is recorded the same way - by going down the same path, not by a
    // parallel one that has to be kept in step with it. Everything else
    // still goes straight through as MIDI: the machine hears it, and nothing
    // yet knows what a lane for it would mean.
    if (kind == 0xb0 && d1 == 1) {
        controlChange(rack, 1, d2);
        return;
    }
    if (kind == 0xd0) {
        channelPressure(rack, d1);
        return;
    }
    sEngine.pushMidi({static_cast<uint8_t>(kind | rack), d1, d2, channel});
}

int EngineHost::paramIndex(const std::string &machineType, const std::string &unit, const std::string &name) const {
    const Unit u = unitFromName(unit);
    if (u == Unit::Machine) {
        int32_t n = 0;
        const ParamDef *defs = MachineRegistry::paramDefs(machineType.c_str(), n);
        for (int32_t i = 0; i < n; ++i) if (name == defs[i].name) return i;
        return -1;
    }
    if (u == Unit::Channel) {
        // From the channel's own table, not a list of names kept here: the
        // list had stopped at `midichannel`, so `swing` - added to the table
        // in M58 - and `output` never resolved, and a per-track swing set in
        // the app never reached the scheduler at all.
        return sEngine.racks[0].channelIndexOf(name.c_str());
    }
    if (u == Unit::Master) return sEngine.master.params().indexOf(name.c_str());
    if (u == Unit::Perform) return sEngine.master.perform.params().indexOf(name.c_str());
    if (u >= Unit::Group1Fx1 && u <= Unit::Group4Fx2) {
        if (name == "bypass") return kEffectBypassIndex;
        const int k = static_cast<int>(u) - static_cast<int>(Unit::Group1Fx1);
        int32_t n = 0;
        const ParamDef *defs = EffectRegistry::paramDefs(
            mountedGroupInsertType[k / kGroupInsertSlots][k % kGroupInsertSlots].c_str(), n);
        for (int32_t i = 0; i < n; ++i) if (name == defs[i].name) return i;
        return -1;
    }
    if (u == Unit::MasterFx1 || u == Unit::MasterFx2) {
        if (name == "bypass") return kEffectBypassIndex;
        int32_t n = 0;
        const ParamDef *defs =
            EffectRegistry::paramDefs(mountedMasterInsertType[u == Unit::MasterFx1 ? 0 : 1].c_str(), n);
        for (int32_t i = 0; i < n; ++i) if (name == defs[i].name) return i;
        return -1;
    }
    if (u == Unit::Send1 || u == Unit::Send2) {
        if (name == "bypass") return kEffectBypassIndex;
        int32_t n = 0;
        const ParamDef *defs = EffectRegistry::paramDefs(mountedSendType[u == Unit::Send1 ? 0 : 1].c_str(), n);
        for (int32_t i = 0; i < n; ++i) if (name == defs[i].name) return i;
        return -1;
    }
    if (u == Unit::Input1 || u == Unit::Input2) {
        if (name == "bypass") return kEffectBypassIndex;
        int32_t n = 0;
        const ParamDef *defs =
            EffectRegistry::paramDefs(mountedInputType[u == Unit::Input1 ? 0 : 1].c_str(), n);
        for (int32_t i = 0; i < n; ++i) if (name == defs[i].name) return i;
        return -1;
    }
    if (u == Unit::Performance) {
        if (name == "mod") return kPerfMod;
        if (name == "pressure") return kPerfPressure;
        return -1;
    }
    if (u == Unit::Effect1 || u == Unit::Effect2) {
        if (name == "bypass") return kEffectBypassIndex;
        int32_t n = 0;
        const ParamDef *defs = EffectRegistry::paramDefs(machineType.c_str(), n); // the effect's type here
        for (int32_t i = 0; i < n; ++i) if (name == defs[i].name) return i;
        return -1;
    }
    if (u == Unit::Mod1 || u == Unit::Mod2 || u == Unit::Mod3) {
        if (name == "bypass") return kInputModBypassIndex;
        int32_t n = 0;
        const ParamDef *defs = InputModRegistry::paramDefs(machineType.c_str(), n); // the modifier's type here
        for (int32_t i = 0; i < n; ++i) if (name == defs[i].name) return i;
        return -1;
    }
    return -1;
}

bool EngineHost::setParam(int rack, const std::string &unit, const std::string &name, float value, bool record,
                          int quantise) {
    const Unit u = unitFromName(unit);
    if (u != Unit::Master && (rack < 0 || rack >= kRackCount)) return false;
    int32_t index = -1;
    if (u == Unit::Machine) {
        int32_t n = 0;
        const ParamDef *defs = MachineRegistry::paramDefs(mountedType[rack].c_str(), n);
        for (int32_t i = 0; i < n; ++i) {
            if (name == defs[i].name) { index = i; break; }
        }
    } else if (u == Unit::Channel) {
        index = sEngine.racks[rack].channelIndexOf(name.c_str()); // see paramIndex
    } else if (u == Unit::Master) {
        index = sEngine.master.params().indexOf(name.c_str());
    } else if (u == Unit::Perform) {
        index = sEngine.master.perform.params().indexOf(name.c_str());
    } else if (u >= Unit::Group1Fx1 && u <= Unit::Group4Fx2) {
        const int k = static_cast<int>(u) - static_cast<int>(Unit::Group1Fx1);
        index = paramIndex(mountedGroupInsertType[k / kGroupInsertSlots][k % kGroupInsertSlots], unit, name);
    } else if (u == Unit::MasterFx1 || u == Unit::MasterFx2) {
        index = paramIndex(mountedMasterInsertType[u == Unit::MasterFx1 ? 0 : 1], unit, name);
    } else if (u == Unit::Send1 || u == Unit::Send2) {
        index = paramIndex(mountedSendType[u == Unit::Send1 ? 0 : 1], unit, name);
    } else if (u == Unit::Input1 || u == Unit::Input2) {
        index = paramIndex(mountedInputType[u == Unit::Input1 ? 0 : 1], unit, name);
    } else if (u == Unit::Effect1 || u == Unit::Effect2) {
        index = paramIndex(mountedEffectType[rack][u == Unit::Effect1 ? 0 : 1], unit, name);
    } else if (u == Unit::Mod1 || u == Unit::Mod2 || u == Unit::Mod3) {
        // Three slots, not two - see the same correction in `paramNormalized`.
        // Here it was worse than a wrong readout: the name was looked up
        // against slot 1's mounted type, did not resolve, and `setParam`
        // returned false. So **no parameter of the third modifier had ever
        // reached the engine** - not from a knob, and not from the snapshot
        // push either, since that goes through here too. The arp ran on its
        // defaults whatever the document said, which is why turning its knobs
        // changed nothing you could hear.
        index = paramIndex(mountedModifierType[rack][u == Unit::Mod1 ? 0 : u == Unit::Mod2 ? 1 : 2], unit, name);
    }
    if (index == -1) return false;
    ParamMessage p;
    p.rack = rack;
    p.unit = u;
    p.index = index;
    p.value = std::clamp(value, 0.0f, 1.0f);
    p.record = record;
    p.quantise = quantise;
    return sEngine.pushParam(p);
}

namespace {

/**
 * Says "this is a render" for as long as it is alive.
 *
 * A scope guard rather than two stores, because both render paths return early
 * in a dozen places - a file that will not open, a scene that does not exist,
 * a mount queue that is full - and a flag left set would silently pin the
 * *live* engine to full quality until the app was restarted, which is the
 * opposite of what the setting is for and would look like the lean switch
 * being broken.
 */
struct OfflineRender {
    OfflineRender() { EngineSettings::get().offlineRender.store(true, std::memory_order_relaxed); }
    ~OfflineRender() { EngineSettings::get().offlineRender.store(false, std::memory_order_relaxed); }
    OfflineRender(const OfflineRender &) = delete;
    OfflineRender &operator=(const OfflineRender &) = delete;
};

} // namespace

// --- Offline render -------------------------------------------------------------

bool EngineHost::renderSong(const std::string &path, float tailSeconds, AudioFormat format, int32_t bits,
                            std::string &error, int32_t startScene, float maxSeconds) {
    // The same flag the audio thread sets, on the thread that renders
    // offline: a tail that flushed live and did not flush here would make
    // an export differ from the performance in the last few dB of every
    // decay. Inaudible, and still a difference this engine does not allow.
    dsp::flushDenormals();

    return renderTargets({RenderTarget{path, -1}}, tailSeconds, format, bits, error, startScene, maxSeconds);
}

bool EngineHost::renderStems(const std::vector<RenderTarget> &targets, float tailSeconds, AudioFormat format,
                             int32_t bits, std::string &error, int32_t startScene, float maxSeconds) {
    if (targets.empty()) {
        error = "nothing to render";
        return false;
    }
    return renderTargets(targets, tailSeconds, format, bits, error, startScene, maxSeconds);
}

bool EngineHost::measureLoudness(float tailSeconds, int32_t startScene, float maxSeconds, float &lufs, float &truePeak,
                                 std::string &error) {
    dsp::Loudness meter;
    meter.prepare(static_cast<float>(kSampleRate));
    if (!renderTargets({}, tailSeconds, AudioFormat::Wav, 24, error, startScene, maxSeconds, &meter)) return false;
    lufs = meter.integrated();
    truePeak = meter.truePeakDb();
    return true;
}

bool EngineHost::renderTargets(const std::vector<RenderTarget> &targets, float tailSeconds, AudioFormat format,
                               int32_t bits, std::string &error, int32_t startScene, float maxSeconds,
                               dsp::Loudness *measure) {
    if (targets.empty() && measure == nullptr) { error = "nothing to render"; return false; }
    if (!running) { error = "engine not running"; return false; }
    if (rendering.exchange(true)) { error = "already rendering"; return false; }
    renderCancel.store(false, std::memory_order_relaxed);
    renderSeconds.store(0.0f, std::memory_order_relaxed);
    renderPeak.store(0.0f, std::memory_order_relaxed);

    // Every file is opened before a block is rendered: finding out on the
    // ninth stem that the directory is not writable, having already taken
    // the engine off the device, would be a poor way to learn it.
    std::vector<std::unique_ptr<AudioSink>> sinks;
    sinks.reserve(targets.size());
    for (const RenderTarget &t : targets) {
        std::unique_ptr<AudioSink> sink = makeSink(format);
        if (!sink) { error = "no writer for that format"; rendering.store(false); return false; }
        if (!sink->open(t.path, kSampleRate, bits, error)) {
            for (auto &open : sinks) open->close();
            for (const RenderTarget &done : targets) {
                if (&done == &t) break;
                std::remove(done.path.c_str());
            }
            rendering.store(false);
            return false;
        }
        sinks.push_back(std::move(sink));
    }

    // Take the engine off the device: from here every block is ours to pull.
    //
    // And at full quality, whatever the setting says: a render has no deadline
    // to miss, so there is nothing for lean to buy, and a file that quietly
    // came out lean because the automatic watcher had chosen it a minute ago
    // is not a file anybody asked for.
    const OfflineRender renderingAtFullQuality;
    sAudio.stop();
    const bool loopSongBefore = sEngine.transport.loopSong();
    const bool loopSceneBefore = sEngine.transport.loopScene();
    // These paths drive the scheduler by hand through one scene. Clip mode
    // would have every rack somewhere else, so it sits out and comes back.
    const bool launcherBefore = sEngine.transport.launcherMode();
    sEngine.transport.setLauncher(false);
    const float clickBefore = sEngine.master.params().normalized(sEngine.master.params().indexOf("clickon"));
    sEngine.transport.setLoopSong(false);
    sEngine.transport.setLoopScene(false);
    sEngine.transport.requestStop();
    float silent[kBlockFrames * 2];
    sEngine.renderBlock(nullptr, silent); // apply the stop, settle

    // Start from silence.
    //
    // Without this a render carries in whatever the engine was holding when
    // it was asked - filter and delay state, the tail of the last thing
    // played - and the top of the file has the previous take bleeding over
    // it. freezeClip already reset the one rack it renders, for this reason.
    //
    // It does *not* make a render reproducible, and it was written in the
    // belief that it would: three renders of the demo peaked at 0.947,
    // 0.838 and 0.897, and they still do. The files agree for their first
    // 0.376 s and then diverge, which points at per-note randomness - a
    // breath or noise source seeded afresh each time - that reset() does
    // not put back. Making an export repeatable means every machine's RNG
    // starting from a known seed, which is its own job across every
    // machine rather than a line here.
    sEngine.panicFlag.store(true, std::memory_order_release);
    sEngine.renderBlock(nullptr, silent);

    ParamMessage click;
    click.rack = 0; click.unit = Unit::Master; click.index = sEngine.master.params().indexOf("clickon"); click.value = 0.0f; click.record = false;
    sEngine.pushParam(click);
    sEngine.transport.requestPlay(startScene);

    float block[kBlockFrames * 2];
    float stem[kBlockFrames * 2];
    // An hour, as a guard - or the caller's own limit, which is how one
    // scene is rendered without the song running on into the next.
    int64_t maxBlocks = static_cast<int64_t>(kSampleRate) * 60 * 60 / kBlockFrames;
    if (maxSeconds > 0.0f) {
        maxBlocks = static_cast<int64_t>(maxSeconds * kSampleRate / kBlockFrames);
    }
    int64_t tailBlocks = static_cast<int64_t>(tailSeconds * kSampleRate / kBlockFrames);
    int64_t blocks = 0;
    float peak = 0.0f;
    bool ended = false, cancelled = false;
    for (;;) {
        if (renderCancel.load(std::memory_order_relaxed)) { cancelled = true; break; }
        sEngine.renderBlock(nullptr, block);
        if (measure != nullptr) {
            float ml[kBlockFrames], mr[kBlockFrames];
            for (int32_t f = 0; f < kBlockFrames; ++f) { ml[f] = block[f * 2]; mr[f] = block[f * 2 + 1]; }
            measure->process(ml, mr, kBlockFrames);
        }
        // A normalised export: the gain its measuring pass asked for, on the
        // mix and every stem alike so the stems still sum to the mix.
        const float renderGain = renderGainDb == 0.0f ? 1.0f : std::pow(10.0f, renderGainDb / 20.0f);
        if (renderGain != 1.0f) for (float &v : block) v *= renderGain;
        for (size_t i = 0; i < targets.size(); ++i) {
            const int32_t rack = targets[i].rack;
            if (rack == -1) {
                sinks[i]->write(block, kBlockFrames);
                continue;
            }
            if (rack <= -2) {
                // A mixer group: -2 is group 0.
                const int32_t g = -2 - rack;
                if (g >= kGroupSlots) continue;
                const float *gl = sEngine.master.groupOutL(g), *gr = sEngine.master.groupOutR(g);
                for (int32_t f = 0; f < kBlockFrames; ++f) {
                    stem[f * 2] = gl[f] * renderGain;
                    stem[f * 2 + 1] = gr[f] * renderGain;
                }
                sinks[i]->write(stem, kBlockFrames);
                continue;
            }
            // A rack's two buffers are separate; a file wants them laced.
            const Rack &source = sEngine.racks[rack];
            for (int32_t f = 0; f < kBlockFrames; ++f) {
                stem[f * 2] = source.bufL[f] * renderGain;
                stem[f * 2 + 1] = source.bufR[f] * renderGain;
            }
            sinks[i]->write(stem, kBlockFrames);
        }
        for (float v : block) { const float a = v < 0 ? -v : v; if (a > peak) peak = a; }
        ++blocks;
        // Two ways to reach the end - the song running out, or the caller's
        // own limit, which is how one scene is rendered without running on
        // into the next. Either way the tail then gets its chance, rather
        // than the file stopping dead on the last note.
        if (!ended && !sEngine.transport.isPlaying()) ended = true;
        if (!ended && blocks >= maxBlocks) ended = true;
        if (ended && --tailBlocks < 0) break;
        if ((blocks & 63) == 0) {
            renderSeconds.store(static_cast<float>(blocks) * kBlockFrames / kSampleRate, std::memory_order_relaxed);
            renderPeak.store(peak, std::memory_order_relaxed);
        }
    }
    renderSeconds.store(static_cast<float>(blocks) * kBlockFrames / kSampleRate, std::memory_order_relaxed);
    renderPeak.store(peak, std::memory_order_relaxed);
    sEngine.transport.requestStop();
    sEngine.renderBlock(nullptr, silent);
    bool closed = true;
    for (auto &sink : sinks) {
        if (!sink->close()) closed = false;
    }

    // Hand the device back exactly as it was.
    sEngine.transport.setLoopSong(loopSongBefore);
    sEngine.transport.setLoopScene(loopSceneBefore);
    sEngine.transport.setLauncher(launcherBefore);
    click.value = clickBefore;
    sEngine.pushParam(click);
    if (!sAudio.start()) LOGE("audio failed to restart after render");
    rendering.store(false);
    LOGI("rendered %zu file(s) from %s: %lld blocks (%.2f s), peak %.3f%s", targets.size(),
         targets.empty() ? "(measuring)" : targets.front().path.c_str(), static_cast<long long>(blocks),
         static_cast<float>(blocks) * kBlockFrames / kSampleRate, peak, cancelled ? ", cancelled" : "");
    if (cancelled) {
        error = "cancelled";
        for (const RenderTarget &t : targets) std::remove(t.path.c_str());
        return false;
    }
    if (!closed) { error = "could not finish the file"; return false; }
    return true;
}

// --- Transport ---------------------------------------------------------------

void EngineHost::transportPlay(int sceneIdx) {
    sEngine.master.resetLoudness(); // integrated loudness is of this playing
    sEngine.transport.requestPlay(sceneIdx);
}
void EngineHost::transportStop() { sEngine.transport.requestStop(); }
void EngineHost::transportRewind() { sEngine.transport.requestRewind(); }
bool EngineHost::isPlaying() const { return sEngine.transport.isPlayingForUi(); }
void EngineHost::setLoopScene(bool on) { sEngine.transport.setLoopScene(on); }
void EngineHost::setLoopSong(bool on) { sEngine.transport.setLoopSong(on); }
void EngineHost::setRecordArmed(bool on) { sEngine.transport.setRecordArmed(on); }
void EngineHost::setStopAtEnd(bool on) { sEngine.transport.setStopAtEnd(on); }
bool EngineHost::isStopAtEndArmed() const { return sEngine.transport.stopAtEndArmed(); }
void EngineHost::queueScene(int idx) { sEngine.transport.queueScene(idx); }
int EngineHost::queuedScene() const { return sEngine.transport.queuedSceneIndex(); }
bool EngineHost::isRecordArmed() const { return sEngine.transport.isRecordArmed(); }
void EngineHost::setTempo(float bpm) {
    sEngine.clock.requestSongTempo(bpm);
    // Somebody *asking* for a tempo is the one thing worth telling a Link
    // session about. Everything else the song does to its own tempo - a
    // scene override, a smooth ramp - stands down while Link owns it, the
    // same way it does under a MIDI clock, so that opening a song cannot
    // quietly re-tempo everybody else in the room.
    if (sEngine.transport.followingLink()) sLink.tempoFromApp(static_cast<double>(bpm));
}
float EngineHost::tempo() const { return sEngine.clock.bpm(); }
int64_t EngineHost::positionPacked() const { return sEngine.transport.position(); }

void EngineHost::setLauncher(bool on) { sEngine.transport.setLauncher(on); }
void EngineHost::setFill(bool on) { sEngine.transport.setFill(on); }
void EngineHost::setLaunchQuantise(int32_t ticks) { sEngine.transport.setLaunchQuantise(ticks); }
void EngineHost::launchClip(int32_t rack, int64_t sceneId) { sEngine.transport.launchClip(rack, sceneId); }
void EngineHost::stopAllClips() { sEngine.transport.requestStopAll(); }
void EngineHost::setClockOut(bool on) { sEngine.transport.setClockOut(on); }

/**
 * Two longs per event: the frame it belongs on, and the bytes. Bulk, like
 * drainRecorded, because one JNI call per MIDI byte at 24 pulses a beat is
 * a call every twenty milliseconds that need not happen.
 */
int EngineHost::drainMidiOut(int64_t *out, int maxEvents) {
    int n = 0;
    MidiOutEvent e;
    while (n < maxEvents && sEngine.midiOut.pop(e)) {
        out[n * 2 + 0] = e.frame;
        out[n * 2 + 1] = (static_cast<int64_t>(e.rack) << 24) | (static_cast<int64_t>(e.status) << 16) |
                         (static_cast<int64_t>(e.data1) << 8) | static_cast<int64_t>(e.data2);
        ++n;
    }
    return n;
}

bool EngineHost::audioAnchor(int64_t &frame, int64_t &nanos, int32_t &sampleRate) const {
    sampleRate = sAudio.getSampleRate() > 0 ? sAudio.getSampleRate() : kSampleRate;
    return sAudio.presentationAnchor(frame, nanos);
}

void EngineHost::setExternalSync(bool on) { sEngine.transport.setExternalSync(on); }

// --- Ableton Link -----------------------------------------------------------
//
// The timebase is handed to the engine once and never taken back: it lives
// as long as the host does, so the audio thread can read the pointer without
// wondering whether the object under it is still there. What switches is the
// transport's sync *source*, which every tempo-setting site already asks
// about - so turning Link on stands the song's own tempo down, and turning
// it off gives it back, without either of them knowing Link exists.
void EngineHost::setLinkEnabled(bool on) {
    sLink.setEnabled(on);
    if (on) {
        sEngine.timebase.store(&sLink, std::memory_order_release);
        // A burst of silence at the head of the buffer is the only latency
        // guess available until the stream has presented enough frames to
        // have a real anchor.
        const int32_t rate = sAudio.getSampleRate() > 0 ? sAudio.getSampleRate() : kSampleRate;
        sLink.setFallbackLatency(static_cast<int64_t>(sAudio.getBufferFrames()) * 1000000LL / rate);
        sLink.setBlockFrames(kBlockFrames, rate);
        // Switching on alone means *our* tempo becomes the session's. It is
        // only when a session is already out there that the tempo is theirs,
        // and Link settles that itself the moment discovery finds one: the
        // peer that joins adopts. Without this, turning Link on at the start
        // of the day dropped a 140 bpm song to Link's own default of 120.
        sLink.tempoFromApp(static_cast<double>(sEngine.clock.bpm()));
        sEngine.transport.setSyncSource(seq::Transport::SyncLink);
    } else if (sEngine.transport.followingLink()) {
        sEngine.transport.setSyncSource(seq::Transport::SyncOff);
    }
}

bool EngineHost::linkEnabled() const { return sLink.enabled(); }

void EngineHost::setLinkStartStop(bool on) {
    sEngine.syncStartStop.store(on, std::memory_order_relaxed);
}

int64_t EngineHost::linkStatus() {
    // The anchor goes down the same call that fetches the readout: it needs
    // refreshing while Link is on, the UI polls this once a second anyway,
    // and one caller is one thing to forget rather than two.
    int64_t frame = 0, nanos = 0;
    if (sAudio.presentationAnchor(frame, nanos)) {
        sLink.setAnchor(frame, nanos, sAudio.getSampleRate() > 0 ? sAudio.getSampleRate() : kSampleRate);
    }
    const int64_t peers = sLink.peers();
    const int64_t tempo = static_cast<int64_t>(sLink.sessionTempo() * 100.0);
    return (peers << 32) | (tempo & 0xffffffffLL);
}
void EngineHost::midiClockIn(int64_t frame, uint8_t status, uint8_t d1, uint8_t d2) {
    sEngine.pushClock({frame, status, d1, d2});
}
int64_t EngineHost::syncState() const { return sEngine.transport.syncState(); }

void EngineHost::cancelLaunch(int32_t rack) { sEngine.transport.launchClip(rack, seq::Launcher::kCancelId); }
void EngineHost::launchStates(int64_t *out, int32_t count) const {
    for (int32_t r = 0; r < count && r < kRackCount; ++r) {
        out[r] = sEngine.transport.launchState(r);
    }
}

int EngineHost::drainRecorded(int64_t *out, int maxEvents) {
    int n = 0;
    seq::RecordedEvent ev;
    while (n < maxEvents && sEngine.recordQueue.pop(ev)) {
        out[n * 5 + 0] = ev.absTick;
        out[n * 5 + 1] = ev.sceneId;
        out[n * 5 + 2] = ev.tickInIteration;
        out[n * 5 + 3] = (static_cast<int64_t>(ev.rack) << 24) | (static_cast<int64_t>(ev.cmd) << 16) |
                         (static_cast<int64_t>(ev.p1) << 8) | static_cast<int64_t>(ev.p2);
        uint32_t bits;
        std::memcpy(&bits, &ev.value, sizeof(bits));
        out[n * 5 + 4] = (static_cast<int64_t>(ev.paramIndex) << 32) | static_cast<int64_t>(bits);
        ++n;
    }
    return n;
}
uint32_t EngineHost::recordedDropped() const { return sEngine.recordQueue.droppedCount(); }

// --- Song snapshot builder -----------------------------------------------------

int64_t EngineHost::snapshotBegin() {
    auto *snap = new seq::SongSnapshot();
    return static_cast<int64_t>(reinterpret_cast<intptr_t>(snap));
}

bool EngineHost::snapshotAddScene(int64_t handle, int64_t sceneId, int ticksPerBar, int repeat, float bpmOverride,
                                  float rampToBpm, int rampBars, bool smooth, bool fadeIn, bool fadeOut) {
    auto *snap = fromHandle(handle);
    if (snap == nullptr || ticksPerBar <= 0) return false;
    seq::SceneInfo sc;
    sc.id = sceneId;
    sc.ticksPerBar = ticksPerBar;
    sc.repeat = std::max(1, repeat);
    sc.bpmOverride = bpmOverride > 0.0f ? bpmOverride : 0.0f;
    sc.smooth = smooth;
    sc.rampToBpm = rampToBpm > 0.0f && rampBars > 0 ? rampToBpm : 0.0f;
    sc.rampBars = rampBars > 0 ? rampBars : 0;
    sc.fadeIn = fadeIn;
    sc.fadeOut = fadeOut;
    snap->scenes.push_back(sc);
    return true;
}

bool EngineHost::snapshotSetClipCached(int64_t handle, int rack, int scene, int64_t rev) {
    auto *snap = fromHandle(handle);
    if (snap == nullptr || scene < 0 || scene >= static_cast<int>(snap->scenes.size())) return false;
    auto it = clipCache.find(rev);
    if (it == clipCache.end()) return false;
    if (it->second->ticksPerBar != snap->scenes[scene].ticksPerBar) return false; // signature changed
    return snap->setClip(rack, scene, it->second);
}

bool EngineHost::snapshotSetClip(int64_t handle, int rack, int scene, int64_t rev, int bars, int playMode, bool mute,
                                 int seed, const int32_t *notes, int noteCount, const float *expr, int exprCount) {
    using namespace seq;
    auto *snap = fromHandle(handle);
    if (snap == nullptr || scene < 0 || scene >= static_cast<int>(snap->scenes.size())) return false;
    auto clip = std::make_shared<Clip>();
    clip->rev = rev;
    clip->bars = std::clamp(bars, 1, 16);
    clip->ticksPerBar = snap->scenes[scene].ticksPerBar;
    // Bit 0 is the play mode; bit 1 is whether the dice roll free. They share
    // a word because the alternative was a tenth argument for one bool.
    clip->playMode = (playMode & 1) == 1 ? PlayMode::OneShot : PlayMode::Loop;
    clip->freeRoll = (playMode & 2) != 0;
    clip->seed = seed;
    clip->mute = mute;
    clip->notes.reserve(static_cast<size_t>(std::max(0, noteCount)));
    clip->expr.reserve(static_cast<size_t>(std::max(0, exprCount)));
    // The expression array is consumed in step with the notes: each note says
    // how many of the points that follow are its own. Ranges are recorded
    // before the sort, and travel with the note through it, because the flat
    // array is never reordered - only pointed into.
    int taken = 0;
    for (int n = 0; n < noteCount; ++n) {
        const int32_t *rec = notes + n * 6;
        ClipNote note;
        note.tick = std::max<int32_t>(0, rec[0]);
        note.length = std::max<int32_t>(1, rec[1]);
        note.pitch = static_cast<uint8_t>(std::clamp<int32_t>(rec[2], 0, 127));
        note.velocity = static_cast<uint8_t>(std::clamp<int32_t>(rec[3], 1, 127));
        note.trig = static_cast<uint16_t>(static_cast<uint32_t>(rec[5]) & 0xFFFFu);
        // Asked once, here, so the player does not have to compute a verdict
        // for notes it is about to skip on every clip in the song.
        if (note.condition() == static_cast<int32_t>(TrigCond::Prev) ||
            note.condition() == static_cast<int32_t>(TrigCond::NotPrev)) {
            clip->hasPrevCond = true;
        }
        const int want = std::clamp<int32_t>(rec[4], 0, exprCount - taken);
        note.exprFirst = static_cast<int32_t>(clip->expr.size());
        note.exprCount = want;
        for (int i = 0; i < want; ++i) {
            const float *pt = expr + (taken + i) * 3;
            ExprPoint p;
            p.kind = std::clamp<int32_t>(static_cast<int32_t>(pt[0]), 0, static_cast<int32_t>(Expr::Count) - 1);
            p.tick = std::max<int32_t>(0, static_cast<int32_t>(pt[1]));
            p.value = std::clamp(pt[2], 0.0f, 1.0f);
            clip->expr.push_back(p);
        }
        // By kind, then by tick: the player takes one contiguous range per
        // curve out of this and never searches again.
        std::stable_sort(clip->expr.begin() + note.exprFirst, clip->expr.end(),
                         [](const ExprPoint &a, const ExprPoint &b) {
                             return a.kind != b.kind ? a.kind < b.kind : a.tick < b.tick;
                         });
        taken += want;
        clip->notes.push_back(note);
    }
    std::stable_sort(clip->notes.begin(), clip->notes.end(),
                     [](const ClipNote &a, const ClipNote &b) { return a.tick < b.tick; });
    return snap->setClip(rack, scene, std::move(clip));
}

bool EngineHost::snapshotSetLane(int64_t handle, int rack, int scene, const std::string &machineType,
                                 const std::string &unit, const std::string &name, bool linear,
                                 const float *points, int pointCount) {
    using namespace seq;
    auto *snap = fromHandle(handle);
    if (snap == nullptr || scene < 0 || scene >= static_cast<int>(snap->scenes.size())) return false;
    const int index = paramIndex(machineType, unit, name);
    if (index < 0) return false;
    const size_t idx = static_cast<size_t>(rack) * snap->scenes.size() + static_cast<size_t>(scene);
    if (idx >= snap->clips.size() || !snap->clips[idx]) return false;
    // A lane edit changes the clip's rev, so a clip receiving lanes is always
    // one freshly built in this snapshot, never a shared cached one.
    auto *clip = const_cast<Clip *>(snap->clips[idx].get());
    Lane lane;
    lane.unit = unitFromName(unit);
    lane.index = index;
    lane.linear = linear;
    lane.points.reserve(static_cast<size_t>(std::max(0, pointCount)));
    for (int n = 0; n < pointCount; ++n) {
        lane.points.push_back({static_cast<int32_t>(points[n * 2]), std::clamp(points[n * 2 + 1], 0.0f, 1.0f)});
    }
    std::stable_sort(lane.points.begin(), lane.points.end(),
                     [](const LanePoint &a, const LanePoint &b) { return a.tick < b.tick; });
    clip->lanes.push_back(std::move(lane));
    return true;
}

bool EngineHost::snapshotCommit(int64_t handle) {
    auto *snap = fromHandle(handle);
    if (snap == nullptr) return false;
    snap->finalize();
    Mount m;
    m.kind = Mount::Kind::Song;
    m.object = snap;
    if (!mountWithRetry(m, deleteAs<seq::SongSnapshot>)) return false;
    clipCache.clear();
    for (const auto &clip : snap->clips) {
        if (clip) clipCache[clip->rev] = clip;
    }
    return true;
}

void EngineHost::snapshotAbandon(int64_t handle) { delete fromHandle(handle); }

// --- Diagnostics -----------------------------------------------------------------

int32_t EngineHost::sampleRate() const { return sAudio.getSampleRate(); }

namespace {


/**
 * A machine is mounted through a queue the audio thread drains, so a worker
 * that asks for it in the same breath as the UI mounted it can arrive
 * first. Wait a few blocks for it rather than failing a load that is only
 * early - 100 ms is thousands of blocks, and the UI is not waiting on us.
 */
Machine *awaitMachine(Engine &engine, int rack, const char *type) {
    for (int attempt = 0; attempt < 20; ++attempt) {
        Machine *m = engine.racks[rack].currentMachine();
        if (m != nullptr && std::strcmp(m->typeName(), type) == 0) return m;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    return nullptr;
}
} // namespace

/**
 * What an audio track is holding, as a line per region.
 *
 *   sceneId|lane|absPath|offset|frames|startTick|ticks|bpm|loop
 *
 * The house's own shape for anything that is a list rather than a number - the
 * zone map and the Nexus patch arrive the same way. One line per lane per
 * cell, so a take sung across four scenes is four lines naming one file.
 *
 * **Each distinct file is decoded once**, however many lines mention it. A
 * take that runs the length of a song is a dozen regions, and decoding it a
 * dozen times is the difference between twenty-nine megabytes and three
 * hundred and fifty. They are converted to int16 here, and a mono file stays
 * mono; see engine/core/Reel.h for why that is the decision the feature rests
 * on.
 *
 * An empty spec clears the reel.
 */
/**
 * One file, as a source: held if it is short, mapped if it is long.
 *
 * The ceiling is `kResidentSeconds`. Under it nothing changes and nothing new
 * happens; over it the file is converted once into `cache/reel` and mapped, so
 * what a twenty-minute vocal costs in memory is the part of it the song is
 * actually playing rather than the whole of it.
 *
 * Returns null and logs when a file cannot be read, so one bad line in a spec
 * loses one lane rather than the reel.
 */
std::shared_ptr<const audio::Reel::Source> EngineHost::sourceFor(const std::string &path,
                                                                int64_t &residentFrames,
                                                                int &mappedCount) {
    std::string error;
    auto made = std::make_shared<audio::Reel::Source>();

    WavStream probe;
    const bool probed = probe.open(path, error);
    const double seconds = probed && probe.rate() > 0
                               ? static_cast<double>(probe.frames()) / probe.rate()
                               : 0.0;
    const bool longTake = probed && seconds > audio::kResidentSeconds;
    probe.close();

    if (longTake && !cacheRoot.empty()) {
        const std::string dest = cacheRoot + "/" + audio::ReelCache::nameFor(path);
        bool stereo = false;
        int64_t frames = 0;
        struct stat st {};
        if (::stat(dest.c_str(), &st) == 0 && st.st_size > 0) {
            // Converted before. The name carries the source's size and mtime,
            // so a file that has been edited since has a different one.
            WavStream again;
            if (again.open(path, error)) {
                stereo = again.channels() == 2;
                frames = static_cast<int64_t>(static_cast<double>(again.frames()) *
                                              kSampleRate / again.rate());
            }
        } else {
            frames = audio::ReelCache::convert(path, dest, stereo, error);
        }
        auto map = std::make_shared<audio::Mapping>();
        if (frames > 0 && map->open(dest) &&
            made->point(map, static_cast<int32_t>(frames), stereo)) {
            ++mappedCount;
            LOGI("reel: %s mapped, %.0f s%s", path.c_str(), seconds, stereo ? " stereo" : " mono");
            return made;
        }
        LOGE("reel: %s would not map (%s); holding it instead", path.c_str(), error.c_str());
    }

    auto data = WavReader::read(path, kSampleRate, error, audio::kResidentSeconds);
    if (!data) {
        LOGE("reel: %s: %s", path.c_str(), error.c_str());
        return nullptr;
    }
    // Planar, in one allocation, in the layout `Source` reads: left then right.
    const int32_t n = data->frames;
    std::vector<int16_t> planes(static_cast<size_t>(n) * (data->stereo ? 2 : 1));
    for (int32_t i = 0; i < n; ++i) planes[static_cast<size_t>(i)] = audio::toI16(data->left[static_cast<size_t>(i)]);
    if (data->stereo) {
        for (int32_t i = 0; i < n; ++i) {
            planes[static_cast<size_t>(n + i)] = audio::toI16(data->right[static_cast<size_t>(i)]);
        }
    }
    residentFrames += static_cast<int64_t>(planes.size());
    made->hold(std::move(planes), n, data->stereo);
    return made;
}

std::string EngineHost::loadReel(int rack, const std::string &spec) {
    if (rack < 0 || rack >= kRackCount) return "no such rack";
    if (awaitMachine(sEngine, rack, "Bias") == nullptr) return "that rack is not a Bias";

    auto reel = std::make_unique<audio::Reel>();
    std::string error;
    // Distinct paths, decoded once each. Not a member: a reel owns its
    // sources, and two racks holding the same file is two decodes rather than
    // a cache that has to outlive both of them.
    std::unordered_map<std::string, std::shared_ptr<const audio::Reel::Source>> decoded;
    int64_t totalFrames = 0;
    int mapped = 0;

    std::istringstream lines(spec);
    std::string line;
    while (std::getline(lines, line)) {
        if (line.empty()) continue;
        std::vector<std::string> f;
        std::string part;
        std::istringstream fields(line);
        while (std::getline(fields, part, '|')) f.push_back(part);
        if (f.size() < 9) {
            LOGE("reel: %zu fields, wanted 9: %s", f.size(), line.c_str());
            continue;
        }
        const int64_t sceneId = std::strtoll(f[0].c_str(), nullptr, 10);
        const int32_t lane = std::atoi(f[1].c_str());
        if (lane < 0 || lane >= audio::kReelLanes) continue;

        auto &src = decoded[f[2]];
        if (src == nullptr) {
            src = sourceFor(f[2], totalFrames, mapped);
            if (src == nullptr) continue;
        }

        audio::Reel::Cell *cell = nullptr;
        for (auto &c : reel->cells) {
            if (c.sceneId == sceneId) { cell = &c; break; }
        }
        if (cell == nullptr) {
            reel->cells.emplace_back();
            cell = &reel->cells.back();
            cell->sceneId = sceneId;
        }
        audio::Reel::Region &r = cell->lanes[lane];
        r.source = src;
        r.offset = std::atoi(f[3].c_str());
        r.frames = std::atoi(f[4].c_str());
        r.startTick = std::atoi(f[5].c_str());
        r.ticks = std::atoi(f[6].c_str());
        r.bpm = static_cast<float>(std::atof(f[7].c_str()));
        r.loop = f[8] == "1";
        // Older specs stop at `loop`; a take with no fades is the ordinary
        // case and nought is what it means.
        r.fadeIn = f.size() > 9 ? std::atoi(f[9].c_str()) : 0;
        r.fadeOut = f.size() > 10 ? std::atoi(f[10].c_str()) : 0;
    }

    // **Ask for the head of every region while still on the loader thread.**
    //
    // A mapped take's first block is the one that can take a major page fault,
    // and a major fault on the audio thread is an xrun. A second of each
    // region is bounded - it is per cell, not per file - and `MADV_WILLNEED`
    // is a hint, so it costs nothing at all when the kernel declines or when
    // the cell is never reached.
    for (const auto &cell : reel->cells) {
        for (const auto &r : cell.lanes) {
            if (r.source) r.source->willNeed(r.offset, kSampleRate);
        }
    }

    LOGI("reel on rack %d: %zu cells, %zu files (%d mapped), %.1f MB resident", rack,
         reel->cells.size(), decoded.size(), mapped,
         static_cast<double>(totalFrames) * sizeof(int16_t) / (1024.0 * 1024.0));

    Mount mount;
    mount.kind = Mount::Kind::Object;
    mount.rack = rack;
    mount.slot = 0;
    mount.object = reel.get();
    mount.deleter = deleteAs<audio::Reel>;
    if (!mountObjectWithRetry(mount)) return "mount queue full";
    reel.release();
    return "";
}

std::string EngineHost::buildCloud(int rack, const float *spectrum01, int32_t count) {
    if (rack < 0 || rack >= kRackCount) return "no such rack";
    Machine *m = awaitMachine(sEngine, rack, "Cumulus");
    if (m == nullptr) return "that rack is not a Cumulus";
    auto *cum = static_cast<machine::Cumulus *>(m);
    const machine::cumulus::CloudSpec built = cum->spec(spectrum01, count);
    LOGI("cumulus rack %d asked for: %d partials, tilt %.1f, bw %.0f, stretch %.3f, comb %.2f, vowel %.2f/%.2f (%d values given)",
         rack, built.partials, built.tilt, built.bandwidth, built.stretch, built.comb, built.formant,
         built.formantAmount, count);
    auto set = machine::cumulus::buildCloud(built, kSampleRate);
    LOGI("cumulus rack %d: %d partials at the bottom, %d in the middle, %d at the top, built in %.0f ms",
         rack, set->partialsUsed[0], set->partialsUsed[1], set->partialsUsed[2], set->buildMs);
    Mount mount;
    mount.kind = Mount::Kind::Object;
    mount.rack = rack;
    mount.slot = 0;
    mount.object = set.get();
    mount.deleter = deleteAs<machine::cumulus::CloudSet>;
    if (!mountObjectWithRetry(mount)) return "mount queue full";
    set.release();
    return "";
}

std::string EngineHost::loadTake(int rack, const std::string &path) {
    if (rack < 0 || rack >= kRackCount) return "no such rack";
    // Either machine that plays one piece of audio with its transients.
    if (awaitMachine(sEngine, rack, "Pollen") == nullptr && awaitMachine(sEngine, rack, "Dice") == nullptr) {
        return "that rack takes no sample";
    }
    if (path.empty()) {
        // An empty path clears the take: the machine falls back to whatever
        // is in its live ring.
        Mount clear;
        clear.kind = Mount::Kind::Object;
        clear.rack = rack;
        clear.slot = 0;
        clear.object = nullptr;
        clear.deleter = deleteAs<audio::Take>;
        return mountObjectWithRetry(clear) ? "" : "mount queue full";
    }
    std::string error;
    auto data = WavReader::read(path, kSampleRate, error);
    if (!data) return error.empty() ? "that file could not be read" : error;
    auto take = std::make_unique<audio::Take>();
    take->name = data->name;
    take->frames = data->frames;
    take->left = std::move(data->left);
    take->right = data->stereo ? std::move(data->right) : take->left;
    // The transients are found here, on a worker, once - the live ring finds
    // its own as it records, with the same detector.
    take->detect(static_cast<float>(kSampleRate));
    LOGI("take on rack %d: '%s', %d frames (%.2f s), %zu onsets", rack, take->name.c_str(), take->frames,
         static_cast<double>(take->frames) / kSampleRate, take->onsets.size());
    Mount mount;
    mount.kind = Mount::Kind::Object;
    mount.rack = rack;
    mount.slot = 0;
    mount.object = take.get();
    mount.deleter = deleteAs<audio::Take>;
    if (!mountObjectWithRetry(mount)) return "mount queue full";
    take.release();
    return "";
}

namespace {

/** Mount an analysed take on a Molt, or say why not. */
std::string mountUtterance(EngineHost &host, int rack, std::unique_ptr<audio::Utterance> utterance) {
    LOGI("molt take on rack %d: '%s', %d frames (%.2f s), %zu marks, root %.1f Hz", rack,
         utterance->name.c_str(), utterance->frames,
         static_cast<double>(utterance->frames) / kSampleRate, utterance->epochs.size(),
         static_cast<double>(utterance->rootHz));
    Mount mount;
    mount.kind = Mount::Kind::Object;
    mount.rack = rack;
    mount.slot = 0;
    mount.object = utterance.get();
    mount.deleter = deleteAs<audio::Utterance>;
    if (!host.mountObjectWithRetry(mount)) return "mount queue full";
    utterance.release();
    return "";
}

} // namespace

std::string EngineHost::loadUtterance(int rack, const std::string &path) {
    if (rack < 0 || rack >= kRackCount) return "no such rack";
    if (awaitMachine(sEngine, rack, "Molt") == nullptr) return "that rack is not a Molt";
    if (path.empty()) {
        Mount clear;
        clear.kind = Mount::Kind::Object;
        clear.rack = rack;
        clear.slot = 0;
        clear.object = nullptr;
        clear.deleter = deleteAs<audio::Utterance>;
        return mountObjectWithRetry(clear) ? "" : "mount queue full";
    }
    std::string error;
    auto data = WavReader::read(path, kSampleRate, error);
    if (!data) return error.empty() ? "that file could not be read" : error;
    auto utterance = std::make_unique<audio::Utterance>();
    utterance->name = data->name;
    utterance->mono.resize(static_cast<size_t>(data->frames));
    for (int32_t i = 0; i < data->frames; ++i) {
        // A voice is mono, and two channels of one are the same voice twice.
        utterance->mono[static_cast<size_t>(i)] =
            data->stereo ? 0.5f * (data->left[static_cast<size_t>(i)] + data->right[static_cast<size_t>(i)])
                         : data->left[static_cast<size_t>(i)];
    }
    // The pitch marks are found here, on a worker, once. It is a few hundred
    // milliseconds for a ten second take and must never be on the audio
    // thread; the machine only ever reads what comes out of this.
    utterance->analyse(static_cast<float>(kSampleRate));
    return mountUtterance(*this, rack, std::move(utterance));
}


std::string EngineHost::loadFormula(int rack, const std::string &formula, const std::string &arp,
                                    const std::string &duty, const std::string &vol) {
    if (rack < 0 || rack >= kRackCount) return "no such rack";
    if (awaitMachine(sEngine, rack, "Formulate") == nullptr) return "that rack is not a Formulate";
    std::string error;
    auto program = machine::formulate::compile(formula, arp, duty, vol, error);
    if (!program) return error.empty() ? "the formula could not be read" : error;
    Mount mount;
    mount.kind = Mount::Kind::Object;
    mount.rack = rack;
    mount.slot = 0;
    mount.object = program.get();
    mount.deleter = deleteAs<machine::formulate::Program>;
    if (!mountObjectWithRetry(mount)) return "mount queue full";
    program.release();
    return "";
}

// --- Freeze -----------------------------------------------------------------------

std::string EngineHost::compCell(int rack, int64_t sceneId, int32_t frames, float bpm,
                                 const std::string &path, float &peakOut) {
    if (rack < 0 || rack >= kRackCount) return "no such rack";
    if (sEngine.transport.isPlaying()) return "stop the transport first";
    if (frames <= 0) return "that cell has no length";
    Machine *m = awaitMachine(sEngine, rack, "Bias");
    if (m == nullptr) return "that rack is not a Bias";
    auto *bias = static_cast<machine::Bias *>(m);

    WavWriter writer;
    std::string error;
    if (!writer.open(path, kSampleRate, 24, error)) return error;

    // The medium is a way of listening and a comp is an edit, so the two must
    // not be baked together - and the patch will still be applied to what this
    // writes, which would be the cassette twice over.
    bias->setColourBypass(true);
    bias->reset();
    bias->params().jumpAll();
    bias->onBlock(0, 0, bpm);

    const double perTick = static_cast<double>(kSampleRate) * 60.0 / (static_cast<double>(bpm) * kPPQN);
    float L[kBlockFrames], R[kBlockFrames];
    std::vector<float> block(static_cast<size_t>(kBlockFrames) * 2);
    float peak = 0.0f;
    for (int32_t at = 0; at < frames; at += kBlockFrames) {
        const int32_t n = frames - at < kBlockFrames ? frames - at : kBlockFrames;
        const auto tick = static_cast<int64_t>(static_cast<double>(at) / perTick);
        bias->onScene(sceneId, tick, true, false);
        bias->render(L, R, n);
        for (int32_t i = 0; i < n; ++i) {
            block[static_cast<size_t>(i) * 2] = L[i];
            block[static_cast<size_t>(i) * 2 + 1] = R[i];
            peak = std::fmax(peak, std::fmax(std::fabs(L[i]), std::fabs(R[i])));
        }
        writer.write(block.data(), n);
    }
    writer.close();
    bias->setColourBypass(false);
    bias->reset();
    bias->params().jumpAll();
    peakOut = peak;
    if (peak <= 0.0f) return "there is nothing in that cell to flatten";
    LOGI("comped rack %d scene %lld: %d frames, peak %.3f", rack, static_cast<long long>(sceneId),
         frames, static_cast<double>(peak));
    return "";
}

std::string EngineHost::freezeClip(int rack, int64_t sceneId, const std::string &path, float tailSeconds,
                                   int32_t &framesOut, int32_t &tailOut, int32_t &ticksOut, float &bpmOut,
                                   float &peakOut) {
    // The same flag the audio thread sets, on the thread that renders
    // offline: a tail that flushed live and did not flush here would make
    // an export differ from the performance in the last few dB of every
    // decay. Inaudible, and still a difference this engine does not allow.
    dsp::flushDenormals();

    if (!running) return "engine not running";
    if (rack < 0 || rack >= kRackCount) return "no such rack";
    if (sEngine.transport.isPlaying()) return "stop the transport first";
    if (rendering.exchange(true)) return "already rendering";

    struct Guard {
        std::atomic<bool> &flag;
        ~Guard() { flag.store(false); }
    } guard{rendering};

    const seq::SongSnapshot *snap = sEngine.scheduler.snapshot();
    if (snap == nullptr) return "no song";
    const int32_t sceneIdx = snap->indexOfScene(sceneId);
    if (sceneIdx < 0) return "no such scene";
    const seq::Clip *clip = snap->clipFor(rack, sceneIdx);
    if (clip == nullptr) return "no clip there";
    const int64_t ticks = clip->lengthTicks();
    if (ticks <= 0) return "that clip has no length";
    if (sEngine.racks[rack].currentMachine() == nullptr) return "that track has no machine";

    const seq::SceneInfo &scene = snap->scenes[static_cast<size_t>(sceneIdx)];
    const float bpm = scene.bpmOverride > 0.0f ? scene.bpmOverride : sEngine.clock.songTempoRequested();
    const double perTick = static_cast<double>(kSampleRate) * 60.0 / (static_cast<double>(bpm) * kPPQN);
    const int64_t clipFrames = static_cast<int64_t>(std::llround(static_cast<double>(ticks) * perTick));
    if (clipFrames <= 0) return "that clip is too short to render";
    const int64_t tailFrames = static_cast<int64_t>(std::max(0.0f, tailSeconds) * kSampleRate);

    // Off the device: from here every block is ours to pull, at whatever
    // speed the CPU manages - and at full quality, for the reason the song
    // render gives. A freeze is baked in and then plays back beside machines
    // running at full, so a lean freeze would be audible as one track being
    // thinner than the rest.
    const OfflineRender renderingAtFullQuality;
    sAudio.stop();
    const bool loopSongBefore = sEngine.transport.loopSong();
    const bool loopSceneBefore = sEngine.transport.loopScene();
    // These paths drive the scheduler by hand through one scene. Clip mode
    // would have every rack somewhere else, so it sits out and comes back.
    const bool launcherBefore = sEngine.transport.launcherMode();
    sEngine.transport.setLauncher(false);
    sEngine.transport.setLoopSong(false);
    sEngine.transport.setLoopScene(true); // stay in this scene for the whole render
    sEngine.transport.requestStop();
    float scratch[kBlockFrames * 2];
    sEngine.renderBlock(nullptr, scratch);

    // A clean start: nothing ringing from whatever was played before.
    //
    // This resets by hand rather than through the panic flag, so everything a
    // panic would have rewound has to be named here - and two things were
    // missing. The clip player carries a pass count and, in a free-rolling
    // clip, the dice; and the modifiers carry a step, which is exactly the
    // fault Engine::renderBlock records for song renders. Without them a
    // freeze captures whatever the track happened to be part way through.
    //
    // **And the third thing, which was missing too: the parameters jump.**
    // Every one of them is smoothed, so a `reset()` on its own leaves them
    // sliding in from wherever they had been - and the first few milliseconds
    // of the render then depend on what was playing before it. `Engine`'s
    // panic path says this in its own comment and does both; this said only
    // half of it, and the cost was a freeze that was **not repeatable**:
    // rendering the demo's bass twice, with nothing changed between, gave two
    // files differing from the first block. A frozen clip is supposed to be
    // the clip, not the clip plus whatever knob was still gliding.
    Rack &r = sEngine.racks[rack];
    r.allNotesOff();
    r.clipPlayer.reset();
    if (Machine *m = r.currentMachine()) {
        m->reset();
        m->params().jumpAll();
    }
    for (int32_t sl = 0; sl < kEffectSlots; ++sl) {
        if (Effect *e = r.currentEffect(sl)) {
            e->reset();
            e->params().jumpAll();
        }
    }
    for (int32_t sl = 0; sl < kInputModSlots; ++sl) {
        if (r.currentInputMod(sl) != nullptr) r.currentInputMod(sl)->reset();
    }

    std::vector<float> left, right;
    left.reserve(static_cast<size_t>(clipFrames + tailFrames));
    right.reserve(static_cast<size_t>(clipFrames + tailFrames));

    r.tapDry = true;
    sEngine.transport.requestPlay(sceneIdx);
    for (int64_t done = 0; done < clipFrames;) {
        sEngine.renderBlock(nullptr, scratch);
        const int64_t n = std::min<int64_t>(kBlockFrames, clipFrames - done);
        left.insert(left.end(), r.dryL, r.dryL + n);
        right.insert(right.end(), r.dryR, r.dryR + n);
        done += n;
    }

    // Then the ring-out - and the transport has to be stopped first, or there
    // is no ring-out to render.
    //
    // The scene is set to loop, so that the clip plays to its end without the
    // arrangement moving on. Which means everything past the clip's end is the
    // clip **coming round again**, at full level, for as long as we care to
    // render it. The old two-second tail was two seconds of the clip playing a
    // second time, folded onto its own opening: measured on the demo's pad,
    // 0.14 RMS across all of it against the clip's own 0.11, and no decay
    // anywhere in it. Stopping is what turns that into a tail - a stop is
    // note-offs, note-offs are releases, and the effects ring on over them,
    // which is exactly what a clip leaves behind when the next scene has
    // nothing for this track.
    //
    // It then runs until the sound has actually gone - a hundredth of a
    // decibel short of nothing, held for three blocks so a gap between two
    // echoes cannot end it early - and the asked-for length is the cap rather
    // than the answer. A flat two seconds was never long enough for a hall nor
    // worth storing for a closed hat.
    sEngine.transport.requestStop();
    constexpr float kSilence = 1.0e-4f; // -80 dB
    constexpr int32_t kQuietBlocks = 3;
    int32_t quiet = 0;
    for (int64_t done = 0; done < tailFrames && quiet < kQuietBlocks;) {
        sEngine.renderBlock(nullptr, scratch);
        const int64_t n = std::min<int64_t>(kBlockFrames, tailFrames - done);
        float loudest = 0.0f;
        for (int64_t i = 0; i < n; ++i) {
            loudest = std::max(loudest, std::max(std::fabs(r.dryL[i]), std::fabs(r.dryR[i])));
        }
        quiet = loudest < kSilence ? quiet + 1 : 0;
        left.insert(left.end(), r.dryL, r.dryL + n);
        right.insert(right.end(), r.dryR, r.dryR + n);
        done += n;
    }
    // Whatever the last blocks were, they were under -80 dB; dropping them
    // keeps a file from carrying a tenth of a second of nothing per freeze.
    // One is kept, always: a tail of nought frames is how a freeze says it was
    // written before any of this existed, and a clip that really does end in
    // silence must not be mistaken for one of those.
    if (quiet >= kQuietBlocks) {
        const size_t drop = static_cast<size_t>(kQuietBlocks - 1) * kBlockFrames;
        if (left.size() >= drop + static_cast<size_t>(clipFrames) + kBlockFrames) {
            left.resize(left.size() - drop);
            right.resize(right.size() - drop);
        }
    }
    const int64_t tailOutFrames = static_cast<int64_t>(left.size()) - clipFrames;
    r.tapDry = false;
    sEngine.renderBlock(nullptr, scratch); // the transport was stopped for the tail

    // And a clean finish, for the same reason as the clean start above.
    //
    // Stopping only sends note-offs, so at this point the machine's voices are
    // in their release stages and both inserts are full of the render - two
    // seconds of tail, since that is what a freeze deliberately renders. The
    // stream is down while all of that happens, so none of it is heard until
    // the line below reopens it, and then it is: a short blurt of the clip's
    // own ending, once per clip, which is what freezing a whole track sounded
    // like. The master too, because the racks fed the sends the entire time.
    r.allNotesOff();
    if (r.currentMachine() != nullptr) r.currentMachine()->reset();
    for (int32_t sl = 0; sl < kEffectSlots; ++sl) {
        if (r.currentEffect(sl) != nullptr) r.currentEffect(sl)->reset();
    }
    sEngine.master.panic();

    sEngine.transport.setLoopSong(loopSongBefore);
    sEngine.transport.setLoopScene(loopSceneBefore);
    sEngine.transport.setLauncher(launcherBefore);
    if (!sAudio.start()) LOGE("audio failed to restart after a freeze");

    // The tail is kept as its own region after the clip, not folded into its
    // head. Folding it was cheaper and wrong in three ways at once: the last
    // pass lost its ring entirely, because a frozen clip is exactly its own
    // length and simply stopped; the first pass gained a ring that no pass had
    // played yet; and a clip shorter than its own tail wrapped it on twice.
    // The rack overlaps it with a second cursor instead, which reproduces all
    // three cases rather than trading one for another.
    const int64_t storedFrames = clipFrames + tailOutFrames;
    float peak = 0.0f;
    std::vector<float> inter(static_cast<size_t>(storedFrames) * 2);
    for (int64_t i = 0; i < storedFrames; ++i) {
        const float a = left[static_cast<size_t>(i)], b = right[static_cast<size_t>(i)];
        inter[static_cast<size_t>(i) * 2] = a;
        inter[static_cast<size_t>(i) * 2 + 1] = b;
        peak = std::max(peak, std::max(std::fabs(a), std::fabs(b)));
    }

    WavWriter wav;
    std::string error;
    // Float, not PCM: this is the rack's output before its fader, which can
    // sit above full scale perfectly legitimately - the mixer is what brings
    // it down. Clamping here would bake in distortion that the live track
    // does not have. Measured on the demo: Hexbeat's bar peaks at 1.84.
    if (!wav.open(path, kSampleRate, 32, error)) return error;
    wav.write(inter.data(), static_cast<int32_t>(storedFrames));
    if (!wav.close()) return "could not finish the file";

    framesOut = static_cast<int32_t>(clipFrames);
    tailOut = static_cast<int32_t>(tailOutFrames);
    ticksOut = static_cast<int32_t>(ticks);
    bpmOut = bpm;
    peakOut = peak;
    LOGI("froze rack %d scene %lld: %lld frames (%.2f s at %.1f bpm) + %.2f s tail, peak %.3f -> %s",
         rack, static_cast<long long>(sceneId), static_cast<long long>(clipFrames),
         static_cast<double>(clipFrames) / kSampleRate, bpm,
         static_cast<double>(tailOutFrames) / kSampleRate, peak, path.c_str());
    return "";
}

std::string EngineHost::loadFrozenSet(int rack, const std::vector<std::pair<int64_t, std::string>> &clips,
                                      const std::vector<float> &bpms, const std::vector<int32_t> &ticks,
                                      const std::vector<int32_t> &tails) {
    if (rack < 0 || rack >= kRackCount) return "no such rack";
    auto set = std::make_unique<FrozenSet>();
    for (size_t i = 0; i < clips.size(); ++i) {
        std::string error;
        auto data = WavReader::read(clips[i].second, kSampleRate, error);
        if (!data) return clips[i].second + ": " + error;
        auto fc = std::make_shared<FrozenClip>();
        // The file is the clip and then its ring-out. `frames` is the loop, so
        // the tail comes off the end of it - and a freeze written before tails
        // existed reports nought and is the whole file, exactly as it was.
        const int32_t tail = i < tails.size() ? tails[i] : 0;
        fc->tail = tail > 0 && tail < data->frames ? tail : 0;
        fc->frames = data->frames - fc->tail;
        fc->left = std::move(data->left);
        fc->right = data->stereo ? std::move(data->right) : fc->left;
        fc->bpm = i < bpms.size() ? bpms[i] : 120.0f;
        fc->ticks = i < ticks.size() ? ticks[i] : 0;
        set->entries.push_back({clips[i].first, std::move(fc)});
    }
    Mount m;
    m.kind = Mount::Kind::Frozen;
    m.rack = rack;
    m.slot = 0;
    m.object = set.get();
    m.deleter = deleteAs<FrozenSet>;
    if (!mountObjectWithRetry(m)) return "mount queue full";
    set.release();
    return "";
}

void EngineHost::setBufferBursts(int32_t bursts) { sAudio.setBufferBursts(bursts); }
int32_t EngineHost::bufferFrames() const { return sAudio.getBufferFrames(); }
void EngineHost::setVoiceLimit(int32_t notes) {
    EngineSettings::get().voiceLimit.store(notes < 0 ? 0 : notes, std::memory_order_relaxed);
}
void EngineHost::setQuality(int32_t level) {
    EngineSettings::get().quality.store(level != 0 ? 1 : 0, std::memory_order_relaxed);
}
void EngineHost::setRecordBits(int32_t bits) {
    EngineSettings::get().recordBits.store(bits == 16 ? 16 : 24, std::memory_order_relaxed);
}

int32_t EngineHost::framesPerBurst() const { return sAudio.getFramesPerBurst(); }
bool EngineHost::lowLatency() const { return sAudio.isLowLatency(); }
int64_t EngineHost::xRunCount() const { return sAudio.getXRunCount(); }
float EngineHost::loadPercent() const { return sEngine.loadPercent(); }
int32_t EngineHost::worstBlockUs() { return sEngine.worstBlockUs(); }
int32_t EngineHost::worstCallbackUs() { return sAudio.readCallbackPeakUs(); }
int32_t EngineHost::worstPhaseUs(int32_t phase) {
    return sEngine.worstPhaseUs(static_cast<Engine::Phase>(phase));
}
int32_t EngineHost::worstCallbackCpuUs() { return sAudio.readCallbackCpuPeakUs(); }
int32_t EngineHost::recentCallbackUs() const { return sAudio.recentCallbackUs(); }
int32_t EngineHost::worstRackUs(int32_t rack) { return sEngine.worstRackUs(rack); }
int32_t EngineHost::rackPercentileUs(int32_t rack) const { return sEngine.rackPercentileUs(rack); }
void EngineHost::resetRackCosts() { sEngine.resetRackCosts(); }
bool EngineHost::worstRackWasFrozen(int32_t rack) const { return sEngine.worstRackWasFrozen(rack); }
float EngineHost::interruptedPercent() const { return sEngine.interruptedPercent(); }
bool EngineHost::hintRunning() const { return sAudio.hintRunning(); }
bool EngineHost::hintAvailable() const { return sAudio.hintAvailable(); }
int32_t EngineHost::hintState() const { return sAudio.hintState(); }
int32_t EngineHost::rackCostUs(int32_t rack) const { return sEngine.rackCostUs(rack); }
int64_t EngineHost::lateCallbacks() const { return sAudio.getLateCallbacks(); }
int64_t EngineHost::stalledCallbacks() const { return sAudio.getStalledCallbacks(); }
int32_t EngineHost::callbackBudgetUs() const { return sAudio.callbackBudgetUs(); }
float EngineHost::peakLevel() const { return sEngine.master.readPeak(); }
void EngineHost::loudness(float *out4) { sEngine.master.readLoudness(out4); }
float EngineHost::groupPeak(int group) { return sEngine.master.readGroupPeak(group); }
void EngineHost::resetLoudness() { sEngine.master.resetLoudness(); }
float EngineHost::rackPeak(int rack) const {
    return (rack >= 0 && rack < kRackCount) ? sEngine.racks[rack].readPeak() : 0.0f;
}
float EngineHost::masterFade() const { return sEngine.master.currentFade(); }
bool EngineHost::startInput(int32_t deviceId) { return sAudio.startInput(deviceId); }
bool EngineHost::stopInput() {
    sAudio.stopInput();
    // Closing the ear stops a capture that was listening through it. That is
    // the right thing to do and the wrong thing to do quietly: the take is
    // cut short, and whoever asked should be told rather than find a file
    // that ends in the middle of a word.
    const bool wasRecording = sEngine.capture.armed() &&
                              sEngine.capture.source() == Capture::FromInput;
    sEngine.capture.stop();
    return wasRecording;
}
bool EngineHost::inputRunning() const { return sAudio.isInputRunning(); }
int32_t EngineHost::inputChannels() const { return sAudio.inputChannels(); }
int32_t EngineHost::inputRate() const { return sAudio.inputRate(); }
int32_t EngineHost::inputDevice() const { return sAudio.inputDevice(); }
bool EngineHost::captureDeaf() const { return sEngine.capture.deaf(); }
float EngineHost::inputPeak() { return sAudio.readInputPeak(); }
void EngineHost::setInputGain(float gain) { sEngine.inputGain.store(gain, std::memory_order_relaxed); }
void EngineHost::setMonitorLevel(float level) { sEngine.monitorLevel.store(level, std::memory_order_relaxed); }
void EngineHost::setSwingUnit(int32_t unit) {
    sEngine.swingPair.store(unit == 1 ? seq::Swing::kEighths : seq::Swing::kSixteenths, std::memory_order_relaxed);
}
void EngineHost::setTunerOn(bool on) { sEngine.tuner.setEnabled(on); }
float EngineHost::tunerHz() {
    const int32_t sr = sAudio.getSampleRate() > 0 ? sAudio.getSampleRate() : kSampleRate;
    return sEngine.tuner.analyse(static_cast<float>(sr));
}

std::string EngineHost::startCapture(const std::string &path, int source) {
    const auto which = source == 1 ? Capture::FromMaster : Capture::FromInput;
    // Recording the input with nothing open would write a silent file and
    // look like a bug at the other end, so say so here.
    if (which == Capture::FromInput && !sAudio.isInputRunning()) return "audio input is not open";
    std::string error;
    if (!sEngine.capture.start(path, kSampleRate, which, error)) return error;
    return "";
}
void EngineHost::stopCapture() { sEngine.capture.stop(); }

void EngineHost::armCapture(int rack) {
    sEngine.marks.reset();
    sEngine.armedRack.store(rack >= 0 && rack < kRackCount ? rack : Engine::kNoRack,
                            std::memory_order_relaxed);
}

int32_t EngineHost::captureMarks(int64_t *out, int32_t max) const {
    if (sEngine.marks.poisoned()) return -1;
    const int32_t n = std::min(sEngine.marks.count(), max);
    for (int32_t i = 0; i < n; ++i) {
        const seq::CaptureMark &m = sEngine.marks.at(i);
        out[i * 5 + 0] = m.frame;
        out[i * 5 + 1] = m.sceneId;
        out[i * 5 + 2] = m.tick;
        out[i * 5 + 3] = m.cycleTicks;
        out[i * 5 + 4] = static_cast<int64_t>(m.bpm * 1000.0f + 0.5f);
    }
    return n;
}
bool EngineHost::capturing() const { return sEngine.capture.armed(); }
float EngineHost::capturedSeconds() const {
    return static_cast<float>(sEngine.capture.frames()) / static_cast<float>(kSampleRate);
}
int64_t EngineHost::capturedFrames() const { return sEngine.capture.frames(); }
float EngineHost::capturedPeak() const { return sEngine.capture.peak(); }
bool EngineHost::captureOverflowed() const { return sEngine.capture.overflowed(); }

void EngineHost::panic() { sEngine.panicFlag.store(true, std::memory_order_release); }
void EngineHost::setCountInBars(int32_t bars) { sEngine.transport.setCountInBars(bars); }
int64_t EngineHost::countInRemaining() const { return sEngine.transport.countInRemaining(); }

uint32_t EngineHost::notesOn(int rack) const {
    return (rack >= 0 && rack < kRackCount) ? sEngine.racks[rack].clipPlayer.notesOn() : 0;
}
float EngineHost::debugParam(int rack, const std::string &name) const {
    if (rack < 0 || rack >= kRackCount) return -1.0f;
    Machine *m = sEngine.racks[rack].currentMachine();
    if (m == nullptr) return -2.0f;
    const int32_t idx = m->params().indexOf(name.c_str());
    return idx < 0 ? -3.0f : m->params().get(idx);
}

float EngineHost::paramNormalized(int rack, const std::string &unit, const std::string &name) const {
    if (rack < 0 || rack >= kRackCount) return -1.0f;
    const Unit u = unitFromName(unit);
    const bool isFx = u == Unit::Effect1 || u == Unit::Effect2;
    const bool isEv = u == Unit::Mod1 || u == Unit::Mod2 || u == Unit::Mod3;
    // **Three modifier slots, two effect slots.** This read `Effect1 || Mod1 ?
    // 0 : 1`, which is right for a pair and wrong for a trio: `mod3` - where
    // the arp lives, because the chips are chord, scale, arp - was answered
    // about slot 1. So the arp's window asked the engine what it held and was
    // told about the scale, every time, and showed its defaults instead.
    const int slot = isFx ? (u == Unit::Effect1 ? 0 : 1)
                          : (u == Unit::Mod1 ? 0 : u == Unit::Mod2 ? 1 : 2);
    const int index = paramIndex(isFx ? mountedEffectType[rack][slot] : (isEv ? mountedModifierType[rack][slot] : mountedType[rack]), unit, name);
    if (index == -1) return -1.0f;
    if (isEv) {
        InputMod *ev = sEngine.racks[rack].currentInputMod(slot);
        if (ev == nullptr) return -1.0f;
        return index == kInputModBypassIndex ? (ev->bypassed() ? 1.0f : 0.0f) : ev->params().normalized(index);
    }
    if (isFx) {
        Effect *fx = sEngine.racks[rack].currentEffect(slot);
        if (fx == nullptr) return -1.0f;
        return index == kEffectBypassIndex ? (fx->bypassed() ? 1.0f : 0.0f) : fx->params().normalized(index);
    }
    if (u == Unit::Machine) {
        Machine *m = sEngine.racks[rack].currentMachine();
        return m ? m->params().normalized(index) : -1.0f;
    }
    if (u == Unit::Channel) return sEngine.racks[rack].channelNormalized(index);
    return -1.0f;
}

uint32_t EngineHost::notesOff(int rack) const {
    return (rack >= 0 && rack < kRackCount) ? sEngine.racks[rack].clipPlayer.notesOff() : 0;
}

} // namespace acidulous
