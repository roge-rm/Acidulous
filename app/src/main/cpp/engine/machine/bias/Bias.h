#pragma once
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
        Gain, Count
    };

    Bias() { initParams(); }

    const char *typeName() const override { return "Bias"; }
    const ParamDef *paramDefs(int32_t &count) const override;
    void prepare(int32_t sampleRate) override;
    void reset() override;

    void onScene(int64_t sceneId, int64_t cycleTick, bool playing, bool clipMuted) override;
    bool render(float *L, float *R, int32_t frames) override;

    /** Slot 0 is the reel. Nothing else is mounted here. */
    void *swapObject(int32_t slot, void *object) override;

    // Bias is not played from the keyboard. The notes in its clip are not
    // its business either - what it plays is decided by where the song is.
    void noteOn(uint8_t, uint8_t) override {}
    void noteOff(uint8_t) override {}
    void allNotesOff() override {}

  private:
    const audio::Reel *reel = nullptr;
    const audio::Reel::Cell *cell = nullptr;
    audio::FrameCursor cursors[audio::kReelLanes];
    int64_t cycleTick = 0;
    float sampleRate = 48000.0f;
    bool playing = false;
    bool muted = false;
};

} // namespace acidulous::machine
