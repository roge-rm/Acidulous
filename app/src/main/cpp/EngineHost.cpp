#include "EngineHost.h"
#include <engine/core/Settings.h>
#include <engine/machine/nexus/Nexus.h>

#include <algorithm>
#include <android/log.h>
#include <cstring>
#include <chrono>
#include <drivers/AudioDriver.h>
#include <engine/core/Constants.h>
#include <engine/core/Frozen.h>
#include <engine/core/WavReader.h>
#include <engine/dsp/Wavetable.h>
#include <engine/core/WavWriter.h>
#include <engine/effect/EffectRegistry.h>
#include <engine/eventor/EventorRegistry.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/core/Sf2Reader.h>
#include <engine/machine/forage/Forage.h>
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

Unit unitFromName(const std::string &u) {
    if (u == "effect1") return Unit::Effect1;
    if (u == "effect2") return Unit::Effect2;
    if (u == "eventor1") return Unit::Eventor1;
    if (u == "eventor2") return Unit::Eventor2;
    if (u == "channel") return Unit::Channel;
    if (u == "master") return Unit::Master;
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
    for (auto &r : mountedEventorType) for (auto &t : r) t.clear();
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

bool EngineHost::mountEventor(int rack, int slot, const std::string &typeName) {
    if (rack < 0 || rack >= kRackCount || slot < 0 || slot >= kEventorSlots) return false;
    Eventor *ev = nullptr;
    if (!typeName.empty()) {
        ev = EventorRegistry::create(typeName.c_str());
        if (ev == nullptr) {
            LOGE("unknown eventor '%s'", typeName.c_str());
            return false;
        }
        ev->reset();
    }
    Mount m;
    m.kind = Mount::Kind::Eventor;
    m.rack = rack;
    m.slot = slot;
    m.object = ev;
    if (!mountWithRetry(m, deleteAs<Eventor>)) return false;
    mountedEventorType[rack][slot] = typeName;
    LOGI("queued eventor '%s' for rack %d slot %d", typeName.c_str(), rack, slot);
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

bool EngineHost::loadSample(int rack, int slot, const std::string &path, std::string &error) {
    if (rack < 0 || rack >= kRackCount) { error = "bad rack"; return false; }
    SampleData *sample = nullptr;
    if (!path.empty()) {
        auto decoded = WavReader::read(path, kSampleRate, error);
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
    return s->name + "|" + std::to_string(s->frames) + "|" + (s->stereo ? "1" : "0");
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

void EngineHost::controlChange(int rack, uint8_t cc, uint8_t value) {
    if (rack < 0 || rack >= kRackCount) return;
    sEngine.pushMidi({static_cast<uint8_t>(0xb0 | rack), cc, value});
}

void EngineHost::channelPressure(int rack, uint8_t value) {
    if (rack < 0 || rack >= kRackCount) return;
    sEngine.pushMidi({static_cast<uint8_t>(0xd0 | rack), value, 0});
}

void EngineHost::midiEvent(int rack, uint8_t status, uint8_t d1, uint8_t d2) {
    if (rack < 0 || rack >= kRackCount) return;
    sEngine.pushMidi({static_cast<uint8_t>((status & 0xf0) | rack), d1, d2});
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
        if (name == "gain") return Rack::Gain;
        if (name == "pan") return Rack::Pan;
        if (name == "mute") return Rack::Mute;
        if (name == "solo") return Rack::Solo;
        if (name == "sendreverb") return Rack::SendReverb;
        if (name == "senddelay") return Rack::SendDelay;
        return -1;
    }
    if (u == Unit::Master) return sEngine.master.params().indexOf(name.c_str());
    if (u == Unit::Effect1 || u == Unit::Effect2) {
        if (name == "bypass") return kEffectBypassIndex;
        int32_t n = 0;
        const ParamDef *defs = EffectRegistry::paramDefs(machineType.c_str(), n); // the effect's type here
        for (int32_t i = 0; i < n; ++i) if (name == defs[i].name) return i;
        return -1;
    }
    if (u == Unit::Eventor1 || u == Unit::Eventor2) {
        if (name == "bypass") return kEventorBypassIndex;
        int32_t n = 0;
        const ParamDef *defs = EventorRegistry::paramDefs(machineType.c_str(), n); // the eventor's type here
        for (int32_t i = 0; i < n; ++i) if (name == defs[i].name) return i;
        return -1;
    }
    return -1;
}

bool EngineHost::setParam(int rack, const std::string &unit, const std::string &name, float value, bool record) {
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
        if (name == "gain") index = Rack::Gain;
        else if (name == "pan") index = Rack::Pan;
        else if (name == "mute") index = Rack::Mute;
        else if (name == "solo") index = Rack::Solo;
        else if (name == "sendreverb") index = Rack::SendReverb;
        else if (name == "senddelay") index = Rack::SendDelay;
    } else if (u == Unit::Master) {
        index = sEngine.master.params().indexOf(name.c_str());
    } else if (u == Unit::Effect1 || u == Unit::Effect2) {
        index = paramIndex(mountedEffectType[rack][u == Unit::Effect1 ? 0 : 1], unit, name);
    } else if (u == Unit::Eventor1 || u == Unit::Eventor2) {
        index = paramIndex(mountedEventorType[rack][u == Unit::Eventor1 ? 0 : 1], unit, name);
    }
    if (index == -1) return false;
    ParamMessage p;
    p.rack = rack;
    p.unit = u;
    p.index = index;
    p.value = std::clamp(value, 0.0f, 1.0f);
    p.record = record;
    return sEngine.pushParam(p);
}

// --- Offline render -------------------------------------------------------------

bool EngineHost::renderSong(const std::string &path, float tailSeconds, std::string &error) {
    if (!running) { error = "engine not running"; return false; }
    if (rendering.exchange(true)) { error = "already rendering"; return false; }
    renderCancel.store(false, std::memory_order_relaxed);
    renderSeconds.store(0.0f, std::memory_order_relaxed);
    renderPeak.store(0.0f, std::memory_order_relaxed);

    WavWriter wav;
    const int32_t bits = EngineSettings::get().recordBits.load(std::memory_order_relaxed);
    if (!wav.open(path, kSampleRate, error, bits)) { rendering.store(false); return false; }

    // Take the engine off the device: from here every block is ours to pull.
    sAudio.stop();
    const bool loopSongBefore = sEngine.transport.loopSong();
    const bool loopSceneBefore = sEngine.transport.loopScene();
    const float clickBefore = sEngine.master.params().normalized(sEngine.master.params().indexOf("clickon"));
    sEngine.transport.setLoopSong(false);
    sEngine.transport.setLoopScene(false);
    sEngine.transport.requestStop();
    float silent[kBlockFrames * 2];
    sEngine.renderBlock(nullptr, silent); // apply the stop, settle
    ParamMessage click;
    click.rack = 0; click.unit = Unit::Master; click.index = sEngine.master.params().indexOf("clickon"); click.value = 0.0f; click.record = false;
    sEngine.pushParam(click);
    sEngine.transport.requestPlay(0);

    float block[kBlockFrames * 2];
    const int64_t maxBlocks = static_cast<int64_t>(kSampleRate) * 60 * 60 / kBlockFrames; // an hour, as a guard
    int64_t tailBlocks = static_cast<int64_t>(tailSeconds * kSampleRate / kBlockFrames);
    int64_t blocks = 0;
    float peak = 0.0f;
    bool ended = false, cancelled = false;
    for (;;) {
        if (renderCancel.load(std::memory_order_relaxed)) { cancelled = true; break; }
        sEngine.renderBlock(nullptr, block);
        wav.write(block, kBlockFrames);
        for (float v : block) { const float a = v < 0 ? -v : v; if (a > peak) peak = a; }
        ++blocks;
        if (!ended && !sEngine.transport.isPlaying()) ended = true; // the song ran out
        if (ended && --tailBlocks < 0) break;
        if (blocks >= maxBlocks) { ended = true; break; }
        if ((blocks & 63) == 0) {
            renderSeconds.store(static_cast<float>(blocks) * kBlockFrames / kSampleRate, std::memory_order_relaxed);
            renderPeak.store(peak, std::memory_order_relaxed);
        }
    }
    renderSeconds.store(static_cast<float>(blocks) * kBlockFrames / kSampleRate, std::memory_order_relaxed);
    renderPeak.store(peak, std::memory_order_relaxed);
    sEngine.transport.requestStop();
    sEngine.renderBlock(nullptr, silent);
    const bool closed = wav.close();

    // Hand the device back exactly as it was.
    sEngine.transport.setLoopSong(loopSongBefore);
    sEngine.transport.setLoopScene(loopSceneBefore);
    click.value = clickBefore;
    sEngine.pushParam(click);
    if (!sAudio.start()) LOGE("audio failed to restart after render");
    rendering.store(false);
    LOGI("rendered %s: %lld blocks (%.2f s), peak %.3f%s", path.c_str(), static_cast<long long>(blocks),
         static_cast<float>(blocks) * kBlockFrames / kSampleRate, peak, cancelled ? ", cancelled" : "");
    if (cancelled) { error = "cancelled"; std::remove(path.c_str()); return false; }
    if (!closed) { error = "could not finish the file"; return false; }
    return true;
}

// --- Transport ---------------------------------------------------------------

void EngineHost::transportPlay(int sceneIdx) { sEngine.transport.requestPlay(sceneIdx); }
void EngineHost::transportStop() { sEngine.transport.requestStop(); }
bool EngineHost::isPlaying() const { return sEngine.transport.isPlayingForUi(); }
void EngineHost::setLoopScene(bool on) { sEngine.transport.setLoopScene(on); }
void EngineHost::setLoopSong(bool on) { sEngine.transport.setLoopSong(on); }
void EngineHost::setRecordArmed(bool on) { sEngine.transport.setRecordArmed(on); }
void EngineHost::setStopAtEnd(bool on) { sEngine.transport.setStopAtEnd(on); }
bool EngineHost::isStopAtEndArmed() const { return sEngine.transport.stopAtEndArmed(); }
void EngineHost::queueScene(int idx) { sEngine.transport.queueScene(idx); }
int EngineHost::queuedScene() const { return sEngine.transport.queuedSceneIndex(); }
bool EngineHost::isRecordArmed() const { return sEngine.transport.isRecordArmed(); }
void EngineHost::setTempo(float bpm) { sEngine.clock.requestSongTempo(bpm); }
float EngineHost::tempo() const { return sEngine.clock.bpm(); }
int64_t EngineHost::positionPacked() const { return sEngine.transport.position(); }

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
                                  bool smooth, bool fadeIn, bool fadeOut) {
    auto *snap = fromHandle(handle);
    if (snap == nullptr || ticksPerBar <= 0) return false;
    seq::SceneInfo sc;
    sc.id = sceneId;
    sc.ticksPerBar = ticksPerBar;
    sc.repeat = std::max(1, repeat);
    sc.bpmOverride = bpmOverride > 0.0f ? bpmOverride : 0.0f;
    sc.smooth = smooth;
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
                                 const int32_t *notes, int noteCount) {
    using namespace seq;
    auto *snap = fromHandle(handle);
    if (snap == nullptr || scene < 0 || scene >= static_cast<int>(snap->scenes.size())) return false;
    auto clip = std::make_shared<Clip>();
    clip->rev = rev;
    clip->bars = std::clamp(bars, 1, 16);
    clip->ticksPerBar = snap->scenes[scene].ticksPerBar;
    clip->playMode = playMode == 1 ? PlayMode::OneShot : PlayMode::Loop;
    clip->mute = mute;
    clip->notes.reserve(static_cast<size_t>(std::max(0, noteCount)));
    for (int n = 0; n < noteCount; ++n) {
        const int32_t *rec = notes + n * 4;
        ClipNote note;
        note.tick = std::max<int32_t>(0, rec[0]);
        note.length = std::max<int32_t>(1, rec[1]);
        note.pitch = static_cast<uint8_t>(std::clamp<int32_t>(rec[2], 0, 127));
        note.velocity = static_cast<uint8_t>(std::clamp<int32_t>(rec[3], 1, 127));
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

// --- Freeze -----------------------------------------------------------------------

std::string EngineHost::freezeClip(int rack, int64_t sceneId, const std::string &path, float tailSeconds,
                                   int32_t &framesOut, int32_t &ticksOut, float &bpmOut, float &peakOut) {
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
    // speed the CPU manages.
    sAudio.stop();
    const bool loopSongBefore = sEngine.transport.loopSong();
    const bool loopSceneBefore = sEngine.transport.loopScene();
    sEngine.transport.setLoopSong(false);
    sEngine.transport.setLoopScene(true); // stay in this scene for the whole render
    sEngine.transport.requestStop();
    float scratch[kBlockFrames * 2];
    sEngine.renderBlock(nullptr, scratch);

    // A clean start: nothing ringing from whatever was played before.
    Rack &r = sEngine.racks[rack];
    r.allNotesOff();
    if (r.currentMachine() != nullptr) r.currentMachine()->reset();
    for (int32_t sl = 0; sl < kEffectSlots; ++sl) {
        if (r.currentEffect(sl) != nullptr) r.currentEffect(sl)->reset();
    }

    std::vector<float> left(static_cast<size_t>(clipFrames + tailFrames), 0.0f);
    std::vector<float> right(static_cast<size_t>(clipFrames + tailFrames), 0.0f);

    r.tapDry = true;
    sEngine.transport.requestPlay(sceneIdx);
    int64_t done = 0;
    const int64_t total = clipFrames + tailFrames;
    while (done < total) {
        sEngine.renderBlock(nullptr, scratch);
        const int64_t n = std::min<int64_t>(kBlockFrames, total - done);
        for (int64_t i = 0; i < n; ++i) {
            left[static_cast<size_t>(done + i)] = r.dryL[i];
            right[static_cast<size_t>(done + i)] = r.dryR[i];
        }
        done += n;
    }
    r.tapDry = false;
    sEngine.transport.requestStop();
    sEngine.renderBlock(nullptr, scratch);
    sEngine.transport.setLoopSong(loopSongBefore);
    sEngine.transport.setLoopScene(loopSceneBefore);
    if (!sAudio.start()) LOGE("audio failed to restart after a freeze");

    // The tail belongs at the start: a clip loops, so what is still ringing
    // when it ends is heard over its own beginning. Without this a frozen
    // clip would cut its own reverb off every bar.
    for (int64_t i = 0; i < tailFrames; ++i) {
        const size_t dst = static_cast<size_t>(i % clipFrames);
        left[dst] += left[static_cast<size_t>(clipFrames + i)];
        right[dst] += right[static_cast<size_t>(clipFrames + i)];
    }

    float peak = 0.0f;
    std::vector<float> inter(static_cast<size_t>(clipFrames) * 2);
    for (int64_t i = 0; i < clipFrames; ++i) {
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
    if (!wav.open(path, kSampleRate, error, 32)) return error;
    wav.write(inter.data(), static_cast<int32_t>(clipFrames));
    if (!wav.close()) return "could not finish the file";

    framesOut = static_cast<int32_t>(clipFrames);
    ticksOut = static_cast<int32_t>(ticks);
    bpmOut = bpm;
    peakOut = peak;
    LOGI("froze rack %d scene %lld: %lld frames (%.2f s at %.1f bpm), peak %.3f -> %s",
         rack, static_cast<long long>(sceneId), static_cast<long long>(clipFrames),
         static_cast<double>(clipFrames) / kSampleRate, bpm, peak, path.c_str());
    return "";
}

std::string EngineHost::loadFrozenSet(int rack, const std::vector<std::pair<int64_t, std::string>> &clips,
                                      const std::vector<float> &bpms, const std::vector<int32_t> &ticks) {
    if (rack < 0 || rack >= kRackCount) return "no such rack";
    auto set = std::make_unique<FrozenSet>();
    for (size_t i = 0; i < clips.size(); ++i) {
        std::string error;
        auto data = WavReader::read(clips[i].second, kSampleRate, error);
        if (!data) return clips[i].second + ": " + error;
        auto fc = std::make_shared<FrozenClip>();
        fc->frames = data->frames;
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
float EngineHost::peakLevel() const { return sEngine.master.readPeak(); }
float EngineHost::rackPeak(int rack) const {
    return (rack >= 0 && rack < kRackCount) ? sEngine.racks[rack].readPeak() : 0.0f;
}
float EngineHost::masterFade() const { return sEngine.master.currentFade(); }
bool EngineHost::startInput() { return sAudio.startInput(); }
void EngineHost::stopInput() {
    sAudio.stopInput();
    sEngine.capture.stop();
}
bool EngineHost::inputRunning() const { return sAudio.isInputRunning(); }
float EngineHost::inputPeak() { return sAudio.readInputPeak(); }
void EngineHost::setInputGain(float gain) { sEngine.inputGain.store(gain, std::memory_order_relaxed); }
void EngineHost::setMonitorLevel(float level) { sEngine.monitorLevel.store(level, std::memory_order_relaxed); }

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
bool EngineHost::capturing() const { return sEngine.capture.armed(); }
float EngineHost::capturedSeconds() const {
    return static_cast<float>(sEngine.capture.frames()) / static_cast<float>(kSampleRate);
}
float EngineHost::capturedPeak() const { return sEngine.capture.peak(); }
bool EngineHost::captureOverflowed() const { return sEngine.capture.overflowed(); }

void EngineHost::panic() { sEngine.panicFlag.store(true, std::memory_order_release); }

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
    const bool isEv = u == Unit::Eventor1 || u == Unit::Eventor2;
    const int slot = (u == Unit::Effect1 || u == Unit::Eventor1) ? 0 : 1;
    const int index = paramIndex(isFx ? mountedEffectType[rack][slot] : (isEv ? mountedEventorType[rack][slot] : mountedType[rack]), unit, name);
    if (index == -1) return -1.0f;
    if (isEv) {
        Eventor *ev = sEngine.racks[rack].currentEventor(slot);
        if (ev == nullptr) return -1.0f;
        return index == kEventorBypassIndex ? (ev->bypassed() ? 1.0f : 0.0f) : ev->params().normalized(index);
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
