#include "MasterBus.h"
#include <cmath>

namespace acidulous {

namespace {
const ParamDef kDefs[MasterBus::Count] = {
    {"volume", 0.0f, 1.5f, 0.8f, Curve::Linear, 0, ""},
    {"reverbon", 0.0f, 1.0f, 1.0f, Curve::Stepped, 2, ""},
    {"reverbsize", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
    {"reverbdamp", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
    {"reverbtone", 0.0f, 1.0f, 0.6f, Curve::Linear, 0, ""},
    {"delayon", 0.0f, 1.0f, 1.0f, Curve::Stepped, 2, ""},
    {"delaytime", 0.0f, 6.0f, 3.0f, Curve::Stepped, dsp::Delay::kTimes, ""}, // index into Delay::kBeats
    {"delayfeedback", 0.0f, 1.0f, 0.4f, Curve::Linear, 0, ""},
    {"delaytone", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
    {"delaypingpong", 0.0f, 1.0f, 1.0f, Curve::Stepped, 2, ""},
    {"limiteron", 0.0f, 1.0f, 1.0f, Curve::Stepped, 2, ""},
    {"limiterdrive", 0.0f, 1.0f, 0.2f, Curve::Linear, 0, ""},
    {"clickon", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
    {"clickvolume", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
};
} // namespace

MasterBus::MasterBus() {
    params_.init(kDefs, Count);
    fadeSmooth.init(1.0f, 0.5f);
}

void MasterBus::prepare(int32_t sampleRate) {
    reverb.prepare(sampleRate);
    delay.prepare(sampleRate);
    limiter.prepare(sampleRate);
    this->sampleRate = static_cast<float>(sampleRate);
    click.prepare(sampleRate);
    params_.jumpAll();
}

void MasterBus::process(Rack *racks, int32_t rackCount, float *out, int32_t frames, float bpm, float fade) {
    params_.tick();

    // Solo: if anyone is soloed, only they are heard.
    bool anySolo = false;
    for (int32_t r = 0; r < rackCount; ++r) {
        if (racks[r].isActive() && racks[r].soloed()) { anySolo = true; break; }
    }

    for (int32_t i = 0; i < frames; ++i) sumL[i] = sumR[i] = sendR[i] = sendD[i] = 0.0f;
    for (int32_t r = 0; r < rackCount; ++r) {
        Rack &rack = racks[r];
        if (!rack.isActive() || (anySolo && !rack.soloed())) continue;
        const float sr = rack.sendReverb(), sd = rack.sendDelay();
        for (int32_t i = 0; i < frames; ++i) {
            sumL[i] += rack.bufL[i];
            sumR[i] += rack.bufR[i];
            const float mono = (rack.bufL[i] + rack.bufR[i]) * 0.5f;
            sendR[i] += mono * sr;
            sendD[i] += mono * sd;
        }
    }

    if (params_.get(ReverbOn) >= 0.5f) {
        reverb.set(params_.get(ReverbSize), params_.get(ReverbDamp), params_.get(ReverbTone));
        reverb.process(sendR, sumL, sumR, frames);
    }
    if (params_.get(DelayOn) >= 0.5f) {
        delay.set(static_cast<int>(params_.get(DelayTime) + 0.5f), params_.get(DelayFeedback), params_.get(DelayTone),
                  params_.get(DelayPingPong) >= 0.5f, bpm);
        delay.process(sendD, sumL, sumR, frames);
    }

    const float volume = params_.get(Volume);
    for (int32_t i = 0; i < frames; ++i) { sumL[i] *= volume; sumR[i] *= volume; }

    if (params_.get(LimiterOn) >= 0.5f) {
        limiter.set(params_.get(LimiterDrive), 0.95f);
        limiter.process(sumL, sumR);
    }

    fadeSmooth.set(fade);
    const float f = fadeSmooth.next();
    fadeNow.store(f, std::memory_order_relaxed);
    for (int32_t i = 0; i < frames; ++i) { sumL[i] *= f; sumR[i] *= f; }

    if (clickEnabled()) click.process(sumL, sumR, frames, params_.get(ClickVolume));

    // Coming back from a panic, the output is ramped rather than switched,
    // so the recovery itself cannot click.
    if (panicRamp < 1.0f) {
        const float step = 1.0f / (0.03f * sampleRate);
        for (int32_t i = 0; i < frames; ++i) {
            panicRamp = panicRamp + step > 1.0f ? 1.0f : panicRamp + step;
            sumL[i] *= panicRamp;
            sumR[i] *= panicRamp;
        }
    }

    float peak = 0.0f;
    for (int32_t i = 0; i < frames; ++i) {
        float l = sumL[i], r = sumR[i];
        // A NaN is not greater than one and not less than minus one, so the
        // clamp below lets it straight through to the speakers as noise at
        // full scale. Whatever produced it, it stops here.
        if (!std::isfinite(l)) l = 0.0f;
        if (!std::isfinite(r)) r = 0.0f;
        if (l > 1.0f) l = 1.0f; else if (l < -1.0f) l = -1.0f;
        if (r > 1.0f) r = 1.0f; else if (r < -1.0f) r = -1.0f;
        out[i * 2] = l;
        out[i * 2 + 1] = r;
        const float a = std::fabs(l), b = std::fabs(r);
        if (a > peak) peak = a;
        if (b > peak) peak = b;
    }
    if (peak > peakHold.load(std::memory_order_relaxed)) peakHold.store(peak, std::memory_order_relaxed);
}

void MasterBus::panic() {
    // Everything with a tail is emptied: a runaway that has already filled
    // the reverb and the delay would otherwise go on sounding after the
    // machines that made it have stopped.
    reverb.reset();
    delay.reset();
    limiter.reset();
    panicRamp = 0.0f;
}

} // namespace acidulous
