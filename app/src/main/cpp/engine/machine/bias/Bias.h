#pragma once
#include "Colour.h"
#include <engine/dsp/Wsola.h>
#include <engine/core/Reel.h>
#include <engine/machine/Machine.h>

// Bias - a four-track running the length of the song.
//
// Every other machine here answers notes. This one answers the *arrangement*:
// it is told which cell the rack is in and how far through that cell's cycle,
// and it plays whatever was recorded there. That is the whole difference, and
// it is why `Machine::onScene` exists.
//
// **Four lanes, sounding together**, because a four-track is four tracks along
// one length of tape and layering is the point: two takes of the same line is
// a doubled vocal, and choosing between three is muting two of them. Each lane
// has a level and a mute, and they are ordinary parameters rather than fields
// on the recording - which is what makes them automatable, mappable to a pad,
// and recordable into a lane, all of it for free and none of it written here.
//
// What it deliberately does not do:
//
//   - **Stretch.** Audio does not, and pretending otherwise inside a render is
//     how a vocal ends up a chipmunk. At another tempo a region still enters
//     on the bar line and runs at its own rate; the drift is bounded by one
//     cycle because the anchor resets at every one. Molt's pulse machinery is
//     where stretching will come from, and it is a milestone of its own.
//   - **Loop by default.** Past the end of its region a lane goes silent. A
//     cell that covers its whole cycle never reaches the end; one that does
//     not was trimmed on purpose.
namespace acidulous::machine {

class Bias final : public Machine {
  public:
    enum P : int32_t {
        Lane1, Lane2, Lane3, Lane4,
        Mute1, Mute2, Mute3, Mute4,
        Gain,
        // The medium. See bias/Colour.h: these colour what comes *out* and
        // never the recordings, which is what makes a Bias patch a way of
        // listening rather than an edit.
        /**
         * Whether the takes follow the song rather than their own tempo.
         *
         * Off, a take enters on the bar and runs at the speed it was recorded
         * at, which is what M54 shipped and what "audio does not stretch"
         * meant. On, each lane is read through `dsp::Wsola` at the ratio
         * between the song's tempo and the take's, so the take lasts as long
         * as the cell does **and sings the same notes**.
         */
        Stretch,
        Hiss, HissTone, LowCut, HighCut, Bump, BumpFreq,
        Sat, Comp, Wow, Flutter, Speed, Bleed, Drop,
        Bits, Rate, Smear, Width,
        Count
    };

    Bias() { initParams(); }

    const char *typeName() const override { return "Bias"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void reset() override;

    void onScene(int64_t sceneId, int64_t cycleTick, bool playing, bool clipMuted) override;
    /** The song's tempo, which is half of what a stretch ratio is made of. */
    void onBlock(int64_t, int64_t, float bpm) override { songBpm = bpm; }
    bool render(float *L, float *R, int32_t frames) override;

    /**
     * Render the lanes without the medium, for a comp.
     *
     * **A comp flattens the four lanes and not the tape they are played
     * back through.** The medium is a way of listening - that is the whole of
     * what a Bias patch is - so baking it into a comp would make it
     * permanent *and* leave the patch applying it a second time on top. Set
     * only offline, with the transport stopped.
     */
    void setColourBypass(bool on) { colourBypass = on; }

    /** Slot 0 is the reel. Nothing else is mounted here. */
    void *swapObject(int32_t slot, void *object) override;

    // Bias is not played from the keyboard. The notes in its clip are not
    // its business either - what it plays is decided by where the song is.
    void noteOn(uint8_t, uint8_t) override {}
    void noteOff(uint8_t) override {}
    void allNotesOff() override {}

  private:
    bias::ColourSpec colourOf();

    bias::Colour colour;
    dsp::Biquad bleedHp[2];
    /** One stretcher a lane, seeded at the top of every cycle. */
    dsp::Wsola stretcher[audio::kReelLanes];
    float songBpm = 120.0f;
    int64_t lastCycleTick = -1;
    /** Set when a cycle comes round; the one moment a stretcher may be moved. */
    bool reseed = true;
    bool colourBypass = false;
    const audio::Reel *reel = nullptr;
    const audio::Reel::Cell *cell = nullptr;
    audio::FrameCursor cursors[audio::kReelLanes];
    int64_t cycleTick = 0;
    float sampleRate = 48000.0f;
    bool playing = false;
    bool muted = false;
};

} // namespace acidulous::machine
