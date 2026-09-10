#include "EngineHost.h"

#include <algorithm>
#include <android/log.h>
#include <cstring>
#include <chrono>
#include <drivers/AudioDriver.h>
#include <engine/core/Constants.h>
#include <engine/machine/MachineRegistry.h>
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
    sAudio.registerCallback([](float *, float *out, unsigned long) { sEngine.renderBlock(out); });
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
    LOGI("engine stopped");
}

bool EngineHost::mountWithRetry(Mount &m, void (*deleter)(void *)) {
    // The audio thread applies one mount per block (1.33 ms); a full queue is a
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
    }
    if (index < 0) return false;
    ParamMessage p;
    p.rack = rack;
    p.unit = u;
    p.index = index;
    p.value = std::clamp(value, 0.0f, 1.0f);
    p.record = record;
    return sEngine.pushParam(p);
}

// --- Transport ---------------------------------------------------------------

void EngineHost::transportPlay(int sceneIdx) { sEngine.transport.requestPlay(sceneIdx); }
void EngineHost::transportStop() { sEngine.transport.requestStop(); }
bool EngineHost::isPlaying() const { return sEngine.transport.isPlayingForUi(); }
void EngineHost::setLoopScene(bool on) { sEngine.transport.setLoopScene(on); }
void EngineHost::setLoopSong(bool on) { sEngine.transport.setLoopSong(on); }
void EngineHost::setRecordArmed(bool on) { sEngine.transport.setRecordArmed(on); }
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
int32_t EngineHost::framesPerBurst() const { return sAudio.getFramesPerBurst(); }
bool EngineHost::lowLatency() const { return sAudio.isLowLatency(); }
int64_t EngineHost::xRunCount() const { return sAudio.getXRunCount(); }
float EngineHost::loadPercent() const { return sEngine.loadPercent(); }
float EngineHost::peakLevel() const { return sEngine.master.readPeak(); }
float EngineHost::rackPeak(int rack) const {
    return (rack >= 0 && rack < kRackCount) ? sEngine.racks[rack].readPeak() : 0.0f;
}
float EngineHost::masterFade() const { return sEngine.master.currentFade(); }
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
    const int index = paramIndex(mountedType[rack], unit, name);
    if (index < 0) return -1.0f;
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
