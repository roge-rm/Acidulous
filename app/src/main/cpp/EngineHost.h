#pragma once
#include <cstdint>
#include <memory>
#include <sequencer/Clip.h>
#include <string>
#include <unordered_map>

// The app's handle on the engine. Owns the Engine and the audio stream and
// exposes the small surface the JNI bridge needs.
//
// Thread model:
//   - everything here is called from the JNI (UI) thread;
//   - nothing here blocks the audio thread: calls either enqueue, or build an
//     object and enqueue a Mount for it.

namespace acidulous {

class EngineHost {
  public:
    static EngineHost &instance();

    bool start();
    void stop();
    bool isRunning() const { return running; }

    // Builds the machine here, hands it to the audio thread through a Mount.
    bool mountMachine(int rack, const std::string &typeName);
    void unmountMachine(int rack);

    // Decodes a WAV here and mounts it into the machine's `slot` (a pad). An
    // empty path clears the slot. Returns false if the file cannot be read.
    bool loadSample(int rack, int slot, const std::string &path, std::string &error);
    // "name|frames|stereo" for a loaded slot, "" for none. UI thread.
    std::string sampleInfo(int rack, int slot) const;
    const char *mountedMachine(int rack) const;
    // Resolves a parameter name for a unit; -1 if unknown. UI thread.
    int paramIndex(const std::string &machineType, const std::string &unit, const std::string &name) const;

    void noteOn(int rack, uint8_t note, uint8_t velocity);
    void noteOff(int rack, uint8_t note);

    // unit: "machine" | "effect1" | "effect2" | "eventor1" | "eventor2" | "channel".
    // value is normalised 0..1. Names are resolved here, on the UI thread.
    // `record`: a user gesture (recordable) rather than the document syncing state.
    bool setParam(int rack, const std::string &unit, const std::string &name, float value, bool record);

    // --- Transport -----------------------------------------------------------
    void transportPlay(int sceneIdx);
    void transportStop();
    bool isPlaying() const;
    void setLoopScene(bool on);
    void setLoopSong(bool on);
    void setRecordArmed(bool on);
    bool isRecordArmed() const;
    void setTempo(float bpm);
    float tempo() const;
    int64_t positionPacked() const;

    // Drain stamped live events into `out`, 5 longs per event:
    //   absTick, sceneId, tickInIteration, (rack << 24 | cmd << 16 | p1 << 8 | p2),
    //   and for parameter events (cmd 0xf0, p1 = unit): (index << 32 | float bits of value)
    int drainRecorded(int64_t *out, int maxEvents);
    uint32_t recordedDropped() const;

    // --- Song snapshot builder -------------------------------------------------
    int64_t snapshotBegin();
    bool snapshotAddScene(int64_t handle, int64_t sceneId, int ticksPerBar, int repeat, float bpmOverride,
                          bool smooth, bool fadeIn, bool fadeOut);
    bool snapshotSetClipCached(int64_t handle, int rack, int scene, int64_t rev);
    bool snapshotSetClip(int64_t handle, int rack, int scene, int64_t rev, int bars, int playMode, bool mute,
                         const int32_t *notes, int noteCount);
    // points: flat [tick, value] × count, any order. unit/name resolve against
    // `machineType`'s table (for "machine") or the channel table.
    bool snapshotSetLane(int64_t handle, int rack, int scene, const std::string &machineType,
                         const std::string &unit, const std::string &name, bool linear,
                         const float *points, int pointCount);
    bool snapshotCommit(int64_t handle);
    void snapshotAbandon(int64_t handle);

    // --- Diagnostics ----------------------------------------------------------
    int32_t sampleRate() const;
    int32_t framesPerBurst() const;
    bool lowLatency() const;
    int64_t xRunCount() const;
    float loadPercent() const;
    float peakLevel() const;
    float rackPeak(int rack) const;
    float masterFade() const;
    uint32_t notesOn(int rack) const;
    uint32_t notesOff(int rack) const;
    // Debug: the live (smoothed, unit-range) value of a mounted machine's parameter.
    float debugParam(int rack, const std::string &name) const;
    // The normalised 0..1 value of a rack unit's parameter, or -1. UI thread.
    float paramNormalized(int rack, const std::string &unit, const std::string &name) const;

  private:
    EngineHost() = default;
    ~EngineHost();
    EngineHost(const EngineHost &) = delete;
    EngineHost &operator=(const EngineHost &) = delete;

    bool mountWithRetry(struct Mount &m, void (*deleter)(void *));

    bool running = false;
    std::string mountedType[16];
    std::unordered_map<int64_t, std::shared_ptr<const seq::Clip>> clipCache;
};

} // namespace acidulous
