#pragma once
#include <algorithm>
#include <cmath>
#include <engine/dsp/Math.h>

// A jaw harp's reed: a strip fixed at one end, ringing in the modes of a
// clamped bar, set going by a pluck and, blown or drawn hard, kept going by
// the air.
namespace acidulous::machine::tongue {
using dsp::clampf;

class Reed {
  public:
    static constexpr int kModes = 3;
    /** A clamped-free bar's second and third modes against its first (beam theory). */
    static constexpr float kRatio[kModes] = {1.0f, 6.267f, 17.55f};
    /** What's left of a ringing reed's swing when the finger meets it to pluck again. */
    static constexpr float kCaught = 0.3f;

    void prepare(float sampleRate) { sr = sampleRate; clear(); }

    void clear() {
        for (int m = 0; m < kModes; ++m) y1[m] = y2[m] = 0.0f;
        pulseLeft = snapLeft = 0;
        x = xBefore = v = 0.0f;
    }

    /**
     * The note (Hz), how long it rings (T60, seconds), how much of the pluck
     * reaches the overtones and how long they ring against the note. A reed
     * that's ringing keeps its motion.
     */
    void tune(float hz, float ring, float overtones, float overRing) {
        note = hz;
        for (int m = 0; m < kModes; ++m) {
            const float f = hz * kRatio[m];
            const float was = w[m];
            w[m] = 6.28318530718f * clampf(f, 10.0f, 0.45f * sr) / sr;
            // A ringing mode keeps its swing and phase at the new pitch: the
            // last sample is rebuilt from them, or a drop in pitch would
            // grow the swing by the ratio.
            if (was > 0.0f && was != w[m]) {
                const float c = (y1[m] * std::cos(was) - y2[m]) / std::sin(was);
                y2[m] = y1[m] * std::cos(w[m]) - c * std::sin(w[m]);
            }
            // The finger's push reaches the note; the overtones are set going
            // by the snap as it leaves (pluck()), [overtones] of the note's
            // swing times 1/ratio, so each moves the air about as much as the note.
            gain[m] = m == 0 && f < 0.45f * sr ? std::sin(w[m]) : 0.0f;
            snapLevel[m] = m > 0 && f < 0.45f * sr ? overtones / kRatio[m] : 0.0f;
        }
        rings[0] = ring;
        rings[1] = ring * overRing;
        rings[2] = ring * overRing * 0.4f;
        setDrive(drive);
    }

    /**
     * Air feeding the reed, nepers a second added to its own loss: 0 lets it
     * die as plucked, enough cancels the loss and it sustains. Per block.
     */
    void setDrive(float nepers) {
        drive = nepers;
        for (int m = 0; m < kModes; ++m) {
            // Only the note is fed: the air pushes at the reed's own swing.
            const float rate = -6.907755f / std::fmax(rings[m], 0.005f) + (m == 0 ? nepers : 0.0f);
            const float r = std::fmin(std::exp(rate / sr), 0.99999f);
            a1[m] = 2.0f * r * std::cos(w[m]);
            a2[m] = -r * r;
        }
    }

    /**
     * A pluck: half a sine of force [contact] seconds long, swinging it by
     * about [swing], then the snap as the finger leaves, [snap] 0 to 1, which
     * sets the overtones going. The finger meets the reed on the way, so most
     * of what was ringing is taken out first.
     */
    void pluck(float swing, float contact, float snap) {
        for (int m = 0; m < kModes; ++m) {
            y1[m] *= kCaught;
            y2[m] *= kCaught;
        }
        x *= kCaught;
        xBefore *= kCaught;
        // A finger can't touch a high reed for longer than a third of its
        // swing, or the push would cancel itself out.
        const float touch = std::fmin(contact, 0.35f / note);
        pulseLength = touch * sr < 2.0f ? 2 : static_cast<int>(touch * sr);
        pulseLeft = pulseLength;
        // What a half sine of force reaches at the note, against a quick tap.
        const float fT = note * static_cast<float>(pulseLength) / sr;
        const float reach = std::cos(3.14159265f * fT) / (1.0f - 4.0f * fT * fT);
        pulseScale = swing * 3.14159265f / (2.0f * static_cast<float>(pulseLength)) / std::fmax(reach, 0.3f);
        // The snap: a quarter of the first overtone's period, short enough to
        // set it going, scaled for what a pulse that long reaches at each.
        snapLength = std::max(2, static_cast<int>(0.25f * sr / (note * kRatio[1])));
        snapLeft = 0;
        for (int m = 1; m < kModes; ++m) {
            const float fT = note * kRatio[m] * static_cast<float>(snapLength) / sr;
            const float reachM = std::cos(3.14159265f * fT) / (1.0f - 4.0f * fT * fT);
            snapScale[m] = std::sin(w[m]) * swing * snap * snapLevel[m] * 3.14159265f / (2.0f * static_cast<float>(snapLength)) /
                           std::fmax(reachM, 0.3f);
        }
    }

    /** One sample; [push] is any other force on it. Returns where the reed is. */
    float step(float push) {
        float force = push;
        if (pulseLeft > 0) {
            force += pulseScale * std::sin(3.14159265f * static_cast<float>(pulseLength - pulseLeft) / static_cast<float>(pulseLength));
            --pulseLeft;
            if (pulseLeft == 0) snapLeft = snapLength;
        }
        float snapForce = 0.0f;
        if (snapLeft > 0) {
            snapForce = std::sin(3.14159265f * static_cast<float>(snapLength - snapLeft) / static_cast<float>(snapLength));
            --snapLeft;
        }
        float out = 0.0f;
        for (int m = 0; m < kModes; ++m) {
            const float y = gain[m] * force + snapScale[m] * snapForce + a1[m] * y1[m] + a2[m] * y2[m];
            y2[m] = y1[m];
            y1[m] = y;
            out += y;
        }
        xBefore = x;
        x = out;
        // Velocity in the swing's units a radian of the note: a reed swinging
        // by 1 reaches about 1.
        v = (x - xBefore) / w[0];
        return x;
    }

    float position() const { return x; }
    float velocity() const { return v; }
    bool plucking() const { return pulseLeft > 0 || snapLeft > 0; }
    float hz() const { return note; }

  private:
    float sr = 48000.0f, note = 200.0f, drive = 0.0f;
    float w[kModes] = {}, gain[kModes] = {}, a1[kModes] = {}, a2[kModes] = {};
    float rings[kModes] = {2.0f, 0.3f, 0.1f};
    float y1[kModes] = {}, y2[kModes] = {};
    int pulseLeft = 0, pulseLength = 2;
    float pulseScale = 0.0f;
    int snapLeft = 0, snapLength = 2;
    float snapLevel[kModes] = {}, snapScale[kModes] = {};
    float x = 0.0f, xBefore = 0.0f, v = 0.0f;
};

} // namespace acidulous::machine::tongue
