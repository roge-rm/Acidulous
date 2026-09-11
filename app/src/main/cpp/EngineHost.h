#pragma once
#include <atomic>
#include <cstdint>
#include <memory>
#include <sequencer/Clip.h>
#include <string>
#include <utility>
#include <vector>
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
    // Insert effects: two slots per rack. An empty type name clears the slot.
    bool mountEffect(int rack, int slot, const std::string &typeName);
    const char *mountedEffect(int rack, int slot) const;
    bool mountEventor(int rack, int slot, const std::string &typeName);
    // Builds anything a machine needs before it can be mounted (Trinity's
    // wavetables). Safe to call from a worker at startup; mounting waits on it.
    static void prewarm();

    // Decodes a WAV here and mounts it into the machine's `slot` (a pad). An
    // empty path clears the slot. Returns false if the file cannot be read.
    bool loadSample(int rack, int slot, const std::string &path, std::string &error);

    // --- Multisample maps (Mosaic) ------------------------------------------
    // Both build the whole instrument on the calling thread and hand it over
    // as one object mount, so call them from a worker.
    static std::string soundFontPresets(const std::string &path, std::string &error);
    bool loadSoundFont(int rack, const std::string &path, int presetIndex, std::string &error);
    /** One zone per line: path|lowKey|highKey|rootKey|lowVel|highVel|cents|gain|pan|loop */
    bool loadZoneMap(int rack, const std::string &spec, const std::string &name, std::string &error);
    /** "name|zones|samples|seconds" for the mounted map, or "". */
    std::string sampleMapInfo(int rack) const;

    /** Build a Nexus patch from its text and hand it to the rack. "" or an error. */
    std::string loadNexusPatch(int rack, const std::string &spec);
    /** The palette, so the editor never keeps a second copy of it. */
    std::string nexusPalette() const;
    /** The scope trace from a rack's Nexus, into a caller-owned array. */
    int32_t nexusScope(int rack, float *dest, int32_t max) const;
    // "name|frames|stereo" for a loaded slot, "" for none. UI thread.
    std::string sampleInfo(int rack, int slot) const;
    const char *mountedMachine(int rack) const;
    // Resolves a parameter name for a unit; -1 if unknown. UI thread.
    int paramIndex(const std::string &machineType, const std::string &unit, const std::string &name) const;

    // --- Offline render ---------------------------------------------------------
    // Blocks: renders the whole song from the top (song loop off, metronome
    // off) plus `tailSeconds` of silence-driven tail into a 24-bit WAV, then
    // hands the stream back to the device. Call from a worker thread.
    bool renderSong(const std::string &path, float tailSeconds, std::string &error);
    void cancelRender() { renderCancel.store(true, std::memory_order_relaxed); }
    bool isRendering() const { return rendering.load(std::memory_order_relaxed); }
    float renderedSeconds() const { return renderSeconds.load(std::memory_order_relaxed); }
    float renderedPeak() const { return renderPeak.load(std::memory_order_relaxed); }

    void noteOn(int rack, uint8_t note, uint8_t velocity);
    void noteOff(int rack, uint8_t note);
    // Performance controllers. They travel as MIDI so the eventor chain and,
    // later, a USB controller share one path into the machine.
    void controlChange(int rack, uint8_t cc, uint8_t value);
    void channelPressure(int rack, uint8_t value);
    // A channel message straight from a MIDI port. The rack is the channel:
    // whatever the message was addressed to on the wire is re-addressed here.
    void midiEvent(int rack, uint8_t status, uint8_t d1, uint8_t d2);

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
    void setStopAtEnd(bool on);
    bool isStopAtEndArmed() const;
    void queueScene(int idx);
    int queuedScene() const;
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

    /**
     * Build Cumulus's tables for a rack from its current spectrum
     * parameters, and mount them. Tens of milliseconds and a few megabytes,
     * so: worker thread only.
     */
    std::string buildCloud(int rack, const float *spectrum01, int32_t count);

    /**
     * Compile Formulate's expression and its three step tables, and mount
     * them. Returns "" or the reason it would not read - which the panel
     * shows, because a typed formula that fails silently is a trap.
     */
    std::string loadFormula(int rack, const std::string &formula, const std::string &arp,
                            const std::string &duty, const std::string &vol);

    // --- Freeze ---------------------------------------------------------
    /**
     * Render one clip to a WAV, off the device: the rack's own output after
     * its effects and before its channel strip, exactly one clip long, with
     * whatever is still ringing at the end wrapped back into the start so
     * the loop joins. Returns "" on success and fills in what the playback
     * side needs to know; anything else is the reason it did not happen.
     */
    std::string freezeClip(int rack, int64_t sceneId, const std::string &path, float tailSeconds,
                           int32_t &framesOut, int32_t &ticksOut, float &bpmOut, float &peakOut);

    /**
     * Give a rack its frozen clips: pairs of scene id and WAV path, read
     * here and handed over as one object. An empty list thaws the rack.
     */
    std::string loadFrozenSet(int rack, const std::vector<std::pair<int64_t, std::string>> &clips,
                              const std::vector<float> &bpms, const std::vector<int32_t> &ticks);

    // --- Settings that belong to the device -----------------------------
    /** Output buffer depth in bursts: 1 tight, 2 default, 4 safe. */
    void setBufferBursts(int32_t bursts);
    int32_t bufferFrames() const;
    /** Held notes per rack, 0 for no limit. */
    void setVoiceLimit(int32_t notes);
    /** 1 full, 0 lean: reverb density and distortion oversampling. */
    void setQuality(int32_t level);
    /** Bits in a recorded or exported WAV: 24 or 16. */
    void setRecordBits(int32_t bits);

    // --- Diagnostics ----------------------------------------------------------
    int32_t sampleRate() const;
    int32_t framesPerBurst() const;
    bool lowLatency() const;
    int64_t xRunCount() const;
    float loadPercent() const;
    float peakLevel() const;
    float rackPeak(int rack) const;
    float masterFade() const;
    // --- Audio in -------------------------------------------------------
    bool startInput();
    void stopInput();
    bool inputRunning() const;
    float inputPeak();
    void setInputGain(float gain);
    void setMonitorLevel(float level);
    /** Record either what is coming in or what is going out. */
    std::string startCapture(const std::string &path, int source);
    void stopCapture();
    bool capturing() const;
    float capturedSeconds() const;
    float capturedPeak() const;
    bool captureOverflowed() const;

    /** Stop everything and silence every tail. Safe from any thread. */
    void panic();

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

  public:
    bool mountObjectWithRetry(struct Mount &m);

  private:

    bool running = false;
    std::string mountedType[16];
    std::atomic<bool> rendering{false}, renderCancel{false};
    std::atomic<float> renderSeconds{0.0f}, renderPeak{0.0f};
    std::string mountedEffectType[16][2];
    std::string mountedEventorType[16][2];
    std::unordered_map<int64_t, std::shared_ptr<const seq::Clip>> clipCache;
};

} // namespace acidulous
