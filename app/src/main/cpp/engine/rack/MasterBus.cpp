#include "MasterBus.h"
#include <cmath>

namespace acidulous {

namespace {
const ParamDef kDefs[MasterBus::Count] = {
    {"volume", 0.0f, 1.5f, 0.8f, Curve::Linear, 0, ""},
    {"limiteron", 0.0f, 1.0f, 1.0f, Curve::Stepped, 2, ""},
    {"limiterdrive", 0.0f, 1.0f, 0.2f, Curve::Linear, 0, ""},
    {"clickon", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
    {"clickvolume", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
    {"clickvoice", 0.0f, 2.0f, 0.0f, Curve::Stepped, 3, ""},  // blip, stick, cowbell
    {"clickdiv", 0.0f, 4.0f, 1.0f, Curve::Stepped, 5, ""},    // bar, 1/4, 1/8, 1/16, 1/8T
    {"clickwhen", 0.0f, 2.0f, 0.0f, Curve::Stepped, 3, ""},   // always, recording, count-in only
};
} // namespace

MasterBus::MasterBus() {
    params_.init(kDefs, Count);
    fadeSmooth.init(1.0f, 0.5f);
}

void MasterBus::prepare(int32_t sampleRate) {
    limiter.prepare(sampleRate);
    this->sampleRate = static_cast<float>(sampleRate);
    click.prepare(sampleRate);
    params_.jumpAll();
}

void MasterBus::process(Rack *racks, int32_t rackCount, float *out, int32_t frames, float bpm, float fade,
                        int64_t tickStart, int64_t tickEnd) {
    params_.tick();

    // Solo: if anyone is soloed, only they are heard.
    bool anySolo = false;
    for (int32_t r = 0; r < rackCount; ++r) {
        if (racks[r].isActive() && racks[r].soloed()) { anySolo = true; break; }
    }

    for (int32_t i = 0; i < frames; ++i) sumL[i] = sumR[i] = 0.0f;
    for (int32_t s = 0; s < kSendSlots; ++s) {
        for (int32_t i = 0; i < frames; ++i) sendSum[s][i] = 0.0f;
    }
    for (int32_t r = 0; r < rackCount; ++r) {
        Rack &rack = racks[r];
        if (!rack.isActive() || (anySolo && !rack.soloed())) continue;
        for (int32_t i = 0; i < frames; ++i) {
            sumL[i] += rack.bufL[i];
            sumR[i] += rack.bufR[i];
        }
        for (int32_t s = 0; s < kSendSlots; ++s) {
            const float amount = rack.sendAmount(s);
            if (amount <= 0.0f) continue;
            for (int32_t i = 0; i < frames; ++i) {
                sendSum[s][i] += (rack.bufL[i] + rack.bufR[i]) * 0.5f * amount;
            }
        }
    }

    // **The return is the effect's output and nothing else.** An insert is
    // given the dry signal and hands back dry-plus-wet; a send is given a
    // copy and what comes back is added to the dry that is already in the
    // mix - so anything of the input that survives the effect arrives twice.
    // That is what the slot's `mix` is pinned open for, one layer up: here
    // the rule is simply that whatever the effect returns is what is added.
    for (int32_t s = 0; s < kSendSlots; ++s) {
        Effect *fx = sends[s];
        if (fx == nullptr) continue;
        fx->onBlock(tickStart, tickEnd, bpm);
        for (int32_t i = 0; i < frames; ++i) wetL[i] = wetR[i] = sendSum[s][i];
        // Mono in, so the effect is told so and may make its own width.
        fx->run(wetL, wetR, frames, false);
        for (int32_t i = 0; i < frames; ++i) {
            sumL[i] += wetL[i];
            sumR[i] += wetR[i];
        }
    }

    const float volume = params_.get(Volume);
    for (int32_t i = 0; i < frames; ++i) { sumL[i] *= volume; sumR[i] *= volume; }

    // The click goes in after the limiter, so that a metronome can never
    // duck the music every beat. That leaves it free to push the sum past
    // full scale and into the clamp below, and it did: the limiter aims at
    // 0.95 and a click at its default adds 0.30 on top, so every beat over
    // a loud mix clipped - which sounds like the song distorting, not like
    // the metronome.
    //
    // So the limiter gives up exactly the headroom the click is about to
    // use. While the metronome is on the ceiling comes down by the click's
    // own peak; the music loses a fixed fraction of a decibel instead of
    // pumping, and has it back the moment the metronome goes off.
    const float clickPeak = clickAudible() ? dsp::Click::peakFor(params_.get(ClickVolume)) : 0.0f;
    if (params_.get(LimiterOn) >= 0.5f) {
        const float ceiling = 0.95f - clickPeak;
        limiter.set(params_.get(LimiterDrive), ceiling < 0.2f ? 0.2f : ceiling);
        limiter.process(sumL, sumR);
    }

    fadeSmooth.set(fade);
    const float f = fadeSmooth.next();
    fadeNow.store(f, std::memory_order_relaxed);
    for (int32_t i = 0; i < frames; ++i) { sumL[i] *= f; sumR[i] *= f; }

    // With the limiter off there is no ceiling to borrow from, so a loud
    // mix plus a click can still meet the clamp. That is the user's own
    // arrangement of things, and the metronome is not what broke it.
    if (clickAudible()) {
        // Stepped, so off the target rather than the smoothed value: a
        // voice sliding from blip to cowbell would pass through stick.
        click.setVoice(static_cast<int32_t>(params_.normalized(ClickVoice) * 2.0f + 0.5f));
        click.process(sumL, sumR, frames, params_.get(ClickVolume));
    }

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
    // the sends would otherwise go on sounding after the machines that made
    // it have stopped.
    for (Effect *fx : sends) {
        if (fx != nullptr) fx->reset();
    }
    limiter.reset();
    panicRamp = 0.0f;
}

} // namespace acidulous
