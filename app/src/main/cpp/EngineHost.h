#pragma once
#include <atomic>
#include <cstdint>
#include <memory>
#include <sequencer/Clip.h>
#include <engine/core/Reel.h>
#include <engine/core/SampleEdit.h>
#include <engine/format/AudioSink.h>
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
    bool mountSend(int slot, const std::string &typeName);
    std::string loadReel(int rack, const std::string &spec);
    /**
     * Where converted long takes live, set once at startup.
     *
     * Empty means there is nowhere to put them, and a long take is then held
     * in memory up to the resident ceiling rather than refused - a missing
     * cache directory is a reason to do less, not a reason to fail.
     */
    void setCacheRoot(const std::string &path) { cacheRoot = path; }
    const char *mountedEffect(int rack, int slot) const;
    bool mountEventor(int rack, int slot, const std::string &typeName);
    // Builds anything a machine needs before it can be mounted (Trinity's
    // wavetables). Safe to call from a worker at startup; mounting waits on it.
    static void prewarm();

    // Decodes a WAV here and mounts it into the machine's `slot` (a pad). An
    // empty path clears the slot. Returns false if the file cannot be read.
    // [maxSeconds] is how much of a long file to keep - see kMaxDecodeSeconds
    // and kMaxSliceSeconds.
    bool loadSample(int rack, int slot, const std::string &path, std::string &error, int maxSeconds = 0);

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
    /**
     * Where a file's slices fall, as fractions of its length.
     *
     * [mode] 0 finds transients and 1 divides evenly. Returns [count]+1
     * boundaries, "a,b,c,..." - so slice n runs from boundary n to n+1 and
     * the caller has start and end for every pad without arithmetic of its
     * own. Empty on any failure, with [error] saying why.
     */
    std::string slicePoints(const std::string &path, int mode, int count, std::string &error) const;

    /**
     * Make an imported file into one the rest of the app can read.
     *
     * Returns the path to use - the same one when it was already a WAV, a new
     * `.wav` beside it otherwise, with the original removed. Empty on failure,
     * with [error] saying what the file turned out to be and what was wrong
     * with it.
     *
     * The conversion is here rather than at each machine so that there is one
     * of it: everything downstream still opens a WAV, a file is decoded once
     * rather than on every song load, and a document keeps naming something
     * that plainly exists on disk.
     */
    std::string importAudio(const std::string &path, std::string &error, int maxSeconds = 0) const;

    /**
     * A pad's sample as something to draw: [columns] pairs of min and max.
     *
     * Not the samples themselves. A thirty-second file is close to three
     * million of them and a phone is a thousand pixels wide, so what a
     * waveform display wants is the extremes within each column - which is
     * what makes a drawn waveform look like the sound rather than like an
     * aliased sine. [dest] wants 2 * [columns] floats; returns how many it
     * filled, which is nought when the pad holds nothing.
     *
     * [fromFrame] and [toFrame] are the window to shape, in frames, which is
     * what makes zooming worth doing: a display that only ever shaped the
     * whole sample could be magnified but never resolved, and ten minutes
     * across nine hundred columns is thirty thousand frames a column. An
     * empty range means the whole of it. Frames and not fractions because a
     * float fraction of twenty-eight million frames resolves to about two of
     * them, which is the wrong end of the zoom to go blunt at.
     */
    int32_t sampleShape(int rack, int pad, float *dest, int32_t columns, int32_t fromFrame = 0,
                        int32_t toFrame = 0) const;
    /**
     * The same picture, of a **file** rather than of a mounted pad.
     *
     * `sampleShape` asks a Forage for its pad, which is why the sample editor
     * has only ever worked on one machine: Dice, Pollen, Molt and Mosaic all
     * hold their material as something else and answer nothing. A recording
     * being trimmed is not mounted anywhere yet at all. So the window that
     * edits a file reads the file, and the two share their column walk.
     *
     * Reads and decodes on the calling thread - a worker, never the audio one.
     */
    int32_t fileShape(const std::string &path, float *dest, int32_t columns,
                      int32_t fromFrame = 0, int32_t toFrame = 0) const;
    /** "name|frames|channels|rate|peak" for a file on disk, "" if unreadable. */
    std::string fileInfo(const std::string &path) const;
    /**
     * [fileInfo] and [fileShape] in one decode.
     *
     * Both of those read the whole file, and a take being put on one of Bias's lanes
     * wants both answers about the same file at the same moment - so asking
     * separately decodes five minutes of audio twice, for two numbers and forty
     * pairs. The peak transient is the reason this exists rather than tidiness:
     * see the note in `assemble`.
     */
    std::string fileSurvey(const std::string &path, float *dest, int32_t columns) const;
    /**
     * Read [src], apply [ops], write [dst]. "" or a reason.
     *
     * [dst] may be [src], which is the overwrite. Written to a temporary and
     * renamed, so a failure halfway leaves the original where it was rather
     * than half of it.
     */
    std::string editSample(const std::string &src, const std::string &dst,
                           const audio::SampleOps &ops) const;
    /**
     * Play a file once, to hear what it is. An empty path stops it.
     *
     * Outside the song: not recorded, not exported, not frozen, and stopped
     * by a panic like everything else this engine makes a sound with.
     */
    std::string auditionFile(const std::string &path);
    bool auditioning() const;

    int32_t nexusActivity(int rack, float *dest, int32_t max) const;
    // "name|frames|stereo" for a loaded slot, "" for none. UI thread.
    std::string sampleInfo(int rack, int slot) const;
    const char *mountedMachine(int rack) const;
    // Resolves a parameter name for a unit; -1 if unknown. UI thread.
    int paramIndex(const std::string &machineType, const std::string &unit, const std::string &name) const;

    // --- Offline render ---------------------------------------------------------
    /** One file of a render: the master mix, or one rack on its own. */
    struct RenderTarget {
        std::string path;
        int32_t rack = -1; // -1 is the master mix
    };

    // Blocks: renders the whole song from the top (song loop off, metronome
    // off) plus `tailSeconds` of silence-driven tail, then hands the stream
    // back to the device. Call from a worker thread.
    bool renderSong(const std::string &path, float tailSeconds, AudioFormat format, int32_t bits,
                    std::string &error, int32_t startScene = 0, float maxSeconds = 0.0f);
    /**
     * The same single pass, written to several files at once.
     *
     * Stems are not the song rendered once per track: every rack renders
     * every block anyway, and the master only sums what they already made.
     * So this opens a sink per target and copies each rack's own buffer as
     * it goes - post-fader, post-pan and post-mute, which is what that rack
     * contributes to the mix. Solo is not applied, being a monitoring state
     * rather than a mix decision, and the master bus - its sends, volume and
     * limiter - is by definition not in any single track.
     */
    bool renderStems(const std::vector<RenderTarget> &targets, float tailSeconds, AudioFormat format,
                     int32_t bits, std::string &error, int32_t startScene = 0, float maxSeconds = 0.0f);
    void cancelRender() { renderCancel.store(true, std::memory_order_relaxed); }
    bool isRendering() const { return rendering.load(std::memory_order_relaxed); }
    float renderedSeconds() const { return renderSeconds.load(std::memory_order_relaxed); }
    float renderedPeak() const { return renderPeak.load(std::memory_order_relaxed); }

    void noteOn(int rack, uint8_t note, uint8_t velocity);
    void noteOff(int rack, uint8_t note);
    // Performance controllers. They travel as MIDI so the eventor chain and,
    // later, a USB controller share one path into the machine.
    void controlChange(int rack, uint8_t cc, uint8_t value, bool record = true);
    void channelPressure(int rack, uint8_t value, bool record = true);
    // A channel message straight from a MIDI port. The rack is the channel:
    // whatever the message was addressed to on the wire is re-addressed here.
    void midiEvent(int rack, uint8_t status, uint8_t d1, uint8_t d2, uint8_t channel = kNoChannel);
    /** The MPE zone: kind 0 off / 1 lower / 2 upper. One, for the input. */
    void setMpeZone(int kind, int members, float bendSemis);
    bool mpeMemberChannel(uint8_t channel) const;
    /** Which member channels are holding a note, a bit per channel. */
    int mpeHeldMask() const;

    // unit: "machine" | "effect1" | "effect2" | "eventor1" | "eventor2" | "channel".
    // value is normalised 0..1. Names are resolved here, on the UI thread.
    // `record`: a user gesture (recordable) rather than the document syncing state.
    bool setParam(int rack, const std::string &unit, const std::string &name, float value, bool record);

    // --- Transport -----------------------------------------------------------
    void transportPlay(int sceneIdx);
    void transportStop();
    void transportRewind();
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

    // Clip mode: the grid as a launcher rather than an arranger.
    void setLauncher(bool on);
    /** Whether Fill trigs may sound. A finger on a button, nothing more. */
    void setFill(bool on);
    void setLaunchQuantise(int32_t ticks);
    void launchClip(int32_t rack, int64_t sceneId);
    void stopAllClips();
    void cancelLaunch(int32_t rack);
    void launchStates(int64_t *out, int32_t count) const;

    // MIDI out: the queue the audio thread fills, and the anchor that turns
    // a frame into a time the far side can schedule against.
    void setClockOut(bool on);
    int drainMidiOut(int64_t *out, int maxEvents);
    bool audioAnchor(int64_t &frame, int64_t &nanos, int32_t &sampleRate) const;
    void setExternalSync(bool on);

    // --- Ableton Link -------------------------------------------------------
    /** On opens the discovery sockets and hands the tempo to the session. */
    void setLinkEnabled(bool on);
    bool linkEnabled() const;
    /** Does a peer starting or stopping start and stop us too? */
    void setLinkStartStop(bool on);
    /**
     * Peers and the session tempo, for the readout - and, on the way past,
     * the stream's current anchor handed to Link. Poll it.
     * Packed: peers in the top word, tempo in hundredths in the bottom.
     */
    int64_t linkStatus();
    void midiClockIn(int64_t frame, uint8_t status, uint8_t d1, uint8_t d2);
    int64_t syncState() const;

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
    // notes: flat [tick, length, pitch, velocity, curvePointCount, trig] x count,
    // where `trig` is seq::packTrig's word. `seed` is the clip's own dice; a
    // `playMode` with bit 1 set means the dice roll free rather than seeded.
    bool snapshotSetClip(int64_t handle, int rack, int scene, int64_t rev, int bars, int playMode, bool mute,
                         int seed, const int32_t *notes, int noteCount, const float *expr, int exprCount);
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
     * Decode a WAV, find its transients, and hand the whole take to the
     * rack - for Pollen to granulate or Dice to cut up. Worker only.
     * "" or the reason it would not load.
     */
    std::string loadTake(int rack, const std::string &path);

    /**
     * A sung take for a Molt: decoded, summed to mono and pitch-marked on a
     * worker, then mounted. An empty path clears it.
     */
    std::string loadUtterance(int rack, const std::string &path);
    /** The same, from what the machine just recorded through the input bus. */
    /** Bumped when a capture finishes, so the UI can notice and analyse it. */

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
  private:
    bool renderTargets(const std::vector<RenderTarget> &targets, float tailSeconds, AudioFormat format,
                       int32_t bits, std::string &error, int32_t startScene, float maxSeconds);

  public:
    std::string freezeClip(int rack, int64_t sceneId, const std::string &path, float tailSeconds,
                           int32_t &framesOut, int32_t &ticksOut, float &bpmOut, float &peakOut);

    /**
     * Flatten one Bias cell's four lanes into one file: a comp.
     *
     * Not the freeze renderer, which would drive the whole scheduler and bake
     * the track's inserts in as well. A cell's audio does not depend on the
     * transport at all - it depends on where in the cycle it is - so this
     * simply walks the cycle and asks the machine for it, with the medium
     * switched off: **a comp flattens the lanes and not the tape they are
     * played through.**
     *
     * [frames] and [bpm] are the cell's own, which the document knows and the
     * engine would have to go looking for. Returns "" or the reason.
     */
    std::string compCell(int rack, int64_t sceneId, int32_t frames, float bpm,
                         const std::string &path, float &peakOut);

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
    /** [deviceId] from the platform's own list, or nought for the default. */
    bool startInput(int32_t deviceId = 0);
    /** True if this cut a recording short - see the definition. */
    bool stopInput();
    bool inputRunning() const;
    /** What the open stream actually is, which is not always what was asked. */
    int32_t inputChannels() const;
    int32_t inputRate() const;
    int32_t inputDevice() const;
    /** True once an input capture has run with nothing arriving. */
    bool captureDeaf() const;
    float inputPeak();
    void setInputGain(float gain);
    void setMonitorLevel(float level);
    /** Record either what is coming in or what is going out. */
    std::string startCapture(const std::string &path, int source);
    void stopCapture();
    bool capturing() const;
    float capturedSeconds() const;
    /** How long the finished file is, exactly. Seconds as a float loses frames. */
    int64_t capturedFrames() const;
    float capturedPeak() const;
    bool captureOverflowed() const;

    /**
     * Which rack a recording is being made *for*, or -1 for none.
     *
     * Only an armed rack has its boundaries stamped - see `CaptureMarks` - and
     * only one can be, because there is one capture. Arming resets the marks,
     * so a second take does not inherit the first one's boundaries.
     */
    void armCapture(int rack);
    /**
     * The boundaries the last recording crossed, five longs each:
     *
     *     frame, sceneId, tick, cycleTicks, millibpm
     *
     * The tempo goes over as thousandths so that the whole record is one
     * array of longs rather than two arrays that have to be kept in step.
     * Returns how many marks were written, or **-1 when the capture dropped
     * frames**, which means the split has to refuse.
     */
    int32_t captureMarks(int64_t *out, int32_t max) const;

    /** Stop everything and silence every tail. Safe from any thread. */
    void panic();
    /** Bars of clicks before a start actually starts. 0 is none. */
    void setCountInBars(int32_t bars);
    /** Ticks left of the count, for the screen; 0 when not counting. */
    int64_t countInRemaining() const;

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
    /** One file as a source: held if it is short, mapped if it is long. */
    std::shared_ptr<const audio::Reel::Source> sourceFor(const std::string &path,
                                                         int64_t &residentFrames, int &mappedCount);
    /** Where converted long takes live. Empty means nowhere; see setCacheRoot. */
    std::string cacheRoot;

    bool running = false;
    std::string mountedType[16];
    std::atomic<bool> rendering{false}, renderCancel{false};
    // The zone, read on the MIDI thread and written from the UI.
    std::atomic<int> mpeZoneKind{0};
    std::atomic<int> mpeZoneMembers{15};
    std::atomic<float> renderSeconds{0.0f}, renderPeak{0.0f};
    std::string mountedEffectType[16][2];
    /** What is on each send bus, for resolving its parameters by name. */
    std::string mountedSendType[2];
    std::string mountedEventorType[16][2];
    std::unordered_map<int64_t, std::shared_ptr<const seq::Clip>> clipCache;
};

} // namespace acidulous
