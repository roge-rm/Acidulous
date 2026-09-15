#pragma once
#include <cmath>
#include <engine/dsp/Biquad.h>
#include <engine/dsp/DelayLine.h>
#include <engine/dsp/Math.h>

// The cabinet. A rotary speaker is not an effect applied to an organ, it is
// part of the instrument: a horn spinning one way over a drum spinning the
// other, heard by microphones that are somewhere in particular. What reaches
// the mics is three things at once - the level rising as a mouth comes round,
// the pitch bending as it approaches and recedes, and the tone dulling as it
// points away - and a chorus pedal gives you none of them.
//
// The two rotors ramp at different rates, and up faster than down, because
// they are heavy and the motor has more authority speeding up than the drag
// has slowing down. That ramp is most of the sound of hitting the switch.
//
// Both phases are readable, because in this organ the cabinet is also a
// modulation source: see Manual's matrix.
namespace acidulous::machine {

class Rotary {
  public:
    void prepare(float sr) {
        sampleRate = sr;
        hornL.prepare(static_cast<int32_t>(sr * 0.01f));
        hornR.prepare(static_cast<int32_t>(sr * 0.01f));
        drumL.prepare(static_cast<int32_t>(sr * 0.01f));
        drumR.prepare(static_cast<int32_t>(sr * 0.01f));
        lowSplit.lowpass(800.0f, 0.707f, sr);
        highSplit.lowpass(800.0f, 0.707f, sr);
        hornTone.lowpass(4200.0f, 0.6f, sr);
        reset();
    }

    void reset() {
        hornPhase = 0.0f;
        drumPhase = 0.25f;
        hornHz = drumHz = 0.0f;
        hornL.clear(); hornR.clear(); drumL.clear(); drumR.clear();
        lowSplit.reset(); highSplit.reset(); hornTone.reset();
        lpL = lpR = 0.0f;
    }

    // Per block: where the rotors are being asked to go, and how fast they
    // are allowed to get there.
    void setTargets(float hornTargetHz, float drumTargetHz, float rampUpSec, float rampDownSec) {
        hornTarget = hornTargetHz;
        drumTarget = drumTargetHz;
        upCoeff = dsp::onePoleCoeff(rampUpSec, sampleRate);
        downCoeff = dsp::onePoleCoeff(rampDownSec, sampleRate);
    }

    void setMic(float distance01, float angle01, float spread01) {
        // Close in, the level swing and the Doppler are extreme; further back
        // the room averages them out.
        //
        // **Three things here used to multiply into an auto-panner.** The
        // depth reached 0.58, so one channel swung 11 dB on its own; the
        // half-angle reached a quarter turn, which puts the two mics a half
        // turn apart and therefore in *opposition*, so what one gained the
        // other lost; and the width below widened the difference again. Ten
        // to thirteen decibels of ping-pong, on every patch with the cabinet
        // on. A horn going round a room is a few decibels and a Doppler, and
        // the Doppler is most of what tells you it is turning.
        depth = 0.08f + 0.24f * (1.0f - distance01);
        doppler = (0.25f + 0.75f * (1.0f - distance01)) * 0.0016f * sampleRate;
        // Half the included angle. A pair of microphones on a cabinet is
        // perhaps a third of a turn apart in total, not a half.
        angle = angle01 * 1.0471976f;
        spread = spread01;
    }

    // One sample in, stereo out. `x` is the driven signal.
    void process(float x, float &outL, float &outR) {
        hornHz += (hornTarget - hornHz) * (hornTarget > hornHz ? upCoeff : downCoeff);
        drumHz += (drumTarget - drumHz) * (drumTarget > drumHz ? upCoeff : downCoeff);
        hornPhase += hornHz / sampleRate;
        if (hornPhase >= 1.0f) hornPhase -= 1.0f;
        drumPhase -= drumHz / sampleRate; // counter-rotating, as the cabinet is
        if (drumPhase < 0.0f) drumPhase += 1.0f;

        const float low = lowSplit.process(x);
        const float high = hornTone.process(x - low);

        const float hw = 6.2831853f * hornPhase, dw = 6.2831853f * drumPhase;
        const float hl = std::cos(hw + angle), hr = std::cos(hw - angle);
        const float dl = std::cos(dw + angle), dr = std::cos(dw - angle);

        // Doppler: the mouth's distance to each mic, as a delay.
        hornL.write(high);
        hornR.write(high);
        drumL.write(low);
        drumR.write(low);
        const float hdL = hornL.read(doppler * (1.0f + hl));
        const float hdR = hornR.read(doppler * (1.0f + hr));
        const float ddL = drumL.read(doppler * 0.35f * (1.0f + dl));
        const float ddR = drumR.read(doppler * 0.35f * (1.0f + dr));

        // Pointing away is quieter and duller; one pole is enough for the
        // dullness and it costs nothing.
        const float ampHL = 1.0f + depth * hl, ampHR = 1.0f + depth * hr;
        const float ampDL = 1.0f + depth * 0.7f * dl, ampDR = 1.0f + depth * 0.7f * dr;
        float l = hdL * ampHL + ddL * ampDL;
        float r = hdR * ampHR + ddR * ampDR;
        // How much duller it gets pointing away has to scale with how close
        // the microphone is, exactly as the level swing does. It did not: the
        // coefficient swept 0.35 to 0.65 whatever the mic distance said, so
        // most of the wobble was a tone modulation that no control reached,
        // and backing the mic off made a patch *worse* rather than better.
        const float tilt = depth * 1.1f;
        const float k = std::fmax(0.08f, 0.62f - tilt * (1.0f - hl) * 0.5f);
        lpL += (l - lpL) * k;
        lpR += (r - lpR) * k;
        l = lpL; r = lpR;

        // The width narrows from what the microphones actually heard; it
        // does not widen past it. Anything over 1.0 here is inventing
        // difference that no pair of mics in a room could have picked up.
        const float mid = 0.5f * (l + r), side = 0.5f * (l - r) * (0.25f + 0.75f * spread);
        outL = mid + side;
        outR = mid - side;
    }

    float horn01() const { return hornPhase; }
    float drum01() const { return drumPhase; }
    float hornRateHz() const { return hornHz; }

  private:
    float sampleRate = 48000.0f;
    float hornPhase = 0.0f, drumPhase = 0.25f;
    float hornHz = 0.0f, drumHz = 0.0f;
    float hornTarget = 0.0f, drumTarget = 0.0f;
    float upCoeff = 0.001f, downCoeff = 0.0005f;
    float depth = 0.5f, doppler = 40.0f, angle = 1.5f, spread = 0.7f;
    float lpL = 0.0f, lpR = 0.0f;
    dsp::DelayLine hornL, hornR, drumL, drumR;
    dsp::Biquad lowSplit, highSplit, hornTone;
};

} // namespace acidulous::machine
