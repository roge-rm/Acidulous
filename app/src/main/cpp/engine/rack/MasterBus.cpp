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
    // The groups' faders, on the same range as a track's.
    {"g1gain", 0.0f, 1.5f, 1.0f, Curve::Linear, 0, ""}, {"g1mute", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
    {"g1solo", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
    {"g2gain", 0.0f, 1.5f, 1.0f, Curve::Linear, 0, ""}, {"g2mute", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
    {"g2solo", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
    {"g3gain", 0.0f, 1.5f, 1.0f, Curve::Linear, 0, ""}, {"g3mute", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
    {"g3solo", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
    {"g4gain", 0.0f, 1.5f, 1.0f, Curve::Linear, 0, ""}, {"g4mute", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
    {"g4solo", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
    {"g1pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""}, {"g2pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    {"g3pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""}, {"g4pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
};
} // namespace

MasterBus::MasterBus() {
    params_.init(kDefs, Count);
    fadeSmooth.init(1.0f, 0.5f);
}

void MasterBus::prepare(int32_t sampleRate) {
    limiter.prepare(sampleRate);
    perform.prepare(static_cast<float>(sampleRate));
    loudness.prepare(static_cast<float>(sampleRate));
    this->sampleRate = static_cast<float>(sampleRate);
    click.prepare(sampleRate);
    params_.jumpAll();
}

void MasterBus::process(Rack *racks, int32_t rackCount, float *out, int32_t frames, float bpm, float fade,
                        int64_t tickStart, int64_t tickEnd) {
    params_.tick();

    // Solo: if anyone is soloed - a track or a group - only they are heard.
    // A soloed group is heard with all its members, and a soloed member is
    // heard through its group with the group's other members silent. A
    // member's sends go the same way as its sound.
    const auto groupSoloed = [&](int32_t g) { return params_.get(groupParam(g) + 2) >= 0.5f; };
    bool anySolo = false;
    for (int32_t r = 0; r < rackCount && !anySolo; ++r) anySolo = racks[r].isActive() && racks[r].soloed();
    for (int32_t g = 0; g < kGroupSlots && !anySolo; ++g) anySolo = groupSoloed(g);
    const auto heard = [&](int32_t r) {
        const Rack &rack = racks[r];
        if (!anySolo || rack.soloed()) return true;
        return rack.routedTo >= 0 && groupSoloed(rack.routedTo);
    };
    bool groupUsed[kGroupSlots] = {};
    for (int32_t g = 0; g < kGroupSlots; ++g) {
        for (int32_t i = 0; i < frames; ++i) groupL[g][i] = groupR[g][i] = 0.0f;
    }

    for (int32_t i = 0; i < frames; ++i) sumL[i] = sumR[i] = 0.0f;
    for (int32_t s = 0; s < kSendSlots; ++s) {
        for (int32_t i = 0; i < frames; ++i) sendSum[s][i] = 0.0f;
    }
    for (int32_t r = 0; r < rackCount; ++r) {
        Rack &rack = racks[r];
        if (!rack.isActive() || !heard(r)) continue;
        // A track routed into a group reaches the master through the group,
        // not also on its own; its sends still go straight to the send buses.
        float *toL = sumL, *toR = sumR;
        if (rack.routedTo >= 0 && rack.routedTo < kGroupSlots) {
            toL = groupL[rack.routedTo];
            toR = groupR[rack.routedTo];
            groupUsed[rack.routedTo] = true;
        }
        for (int32_t i = 0; i < frames; ++i) {
            toL[i] += rack.bufL[i];
            toR[i] += rack.bufR[i];
        }
        for (int32_t s = 0; s < kSendSlots; ++s) {
            const float amount = rack.sendAmount(s);
            if (amount <= 0.0f) continue;
            for (int32_t i = 0; i < frames; ++i) {
                sendSum[s][i] += (rack.bufL[i] + rack.bufR[i]) * 0.5f * amount;
            }
        }
    }

    // Where the held effects run this block. A group with nothing routed
    // into it has nothing to play them on, so they fall back to the whole
    // mix. Moving from one to the other lets go and starts clean, or a
    // repeat caught on the drums would carry on playing over the whole song.
    const int32_t wanted = perform.wantedGroup();
    const int32_t performOn = wanted >= 0 && wanted < kGroupSlots && groupUsed[wanted] ? wanted : -1;
    if (performOn != performWas) {
        perform.release();
        perform.reset();
        performWas = performOn;
    }

    // **The groups**: their members summed above, through two inserts and a
    // fader of their own, into the master. A group nothing is routed into
    // does no work at all.
    for (int32_t g = 0; g < kGroupSlots; ++g) {
        if (!groupUsed[g]) continue;
        for (int32_t s = 0; s < kGroupInsertSlots; ++s) {
            Effect *fx = groupInserts[g][s];
            if (fx == nullptr) continue;
            fx->onBlock(tickStart, tickEnd, bpm);
            fx->run(groupL[g], groupR[g], frames, true);
        }
        if (g == performOn) perform.process(groupL[g], groupR[g], frames, bpm);
        const float gain = params_.get(groupParam(g) + 1) >= 0.5f ? 0.0f : params_.get(groupParam(g));
        // Pan as a balance on the stereo group, with the same law a track's
        // pan uses, so the centre is unity.
        const float angle = (params_.get(G1Pan + g) + 1.0f) * 0.25f * 3.14159265f;
        const float gl = gain * std::cos(angle) * 1.4142f, gr = gain * std::sin(angle) * 1.4142f;
        float peak = 0.0f;
        for (int32_t i = 0; i < frames; ++i) {
            groupL[g][i] *= gl;
            groupR[g][i] *= gr;
            sumL[i] += groupL[g][i];
            sumR[i] += groupR[g][i];
            peak = std::fmax(peak, std::fmax(std::fabs(groupL[g][i]), std::fabs(groupR[g][i])));
        }
        if (peak > groupPeakHold[g].load(std::memory_order_relaxed)) groupPeakHold[g].store(peak, std::memory_order_relaxed);
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

    // **The master's inserts**: on the whole mix, sends and all, before the
    // fader and the limiter - where an EQ or a glue compressor for the song
    // goes. In place, stereo in, like a track's.
    for (int32_t s = 0; s < kMasterInsertSlots; ++s) {
        Effect *fx = inserts[s];
        if (fx == nullptr) continue;
        fx->onBlock(tickStart, tickEnd, bpm);
        fx->run(sumL, sumR, frames, true);
    }

    // The held effects, on everything the inserts hand on - unless they are
    // on a group this block.
    if (performOn < 0) perform.process(sumL, sumR, frames, bpm);

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

    if (fadeJump) {
        fadeSmooth.jump(fade);
        fadeJump = false;
    } else {
        fadeSmooth.set(fade);
    }
    const float f = fadeSmooth.next();
    fadeNow.store(f, std::memory_order_relaxed);
    for (int32_t i = 0; i < frames; ++i) { sumL[i] *= f; sumR[i] *= f; }

    // Loudness of the song, before the metronome is added to it.
    if (loudnessResetWanted.exchange(false, std::memory_order_relaxed)) {
        loudness.reset();
        lufsM.store(dsp::Loudness::kSilent, std::memory_order_relaxed);
        lufsS.store(dsp::Loudness::kSilent, std::memory_order_relaxed);
        lufsI.store(dsp::Loudness::kSilent, std::memory_order_relaxed);
        truePeakDb.store(dsp::Loudness::kSilent, std::memory_order_relaxed);
    }
    const int32_t watch = loudnessWatch.load(std::memory_order_relaxed);
    if (watch > 0) {
        loudnessWatch.store(watch - 1, std::memory_order_relaxed);
        loudness.process(sumL, sumR, frames);
        lufsM.store(loudness.momentary(), std::memory_order_relaxed);
        lufsS.store(loudness.shortTerm(), std::memory_order_relaxed);
        lufsI.store(loudness.integrated(), std::memory_order_relaxed);
        truePeakDb.store(loudness.truePeakDb(), std::memory_order_relaxed);
    }

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
    //
    // And every parameter here jumps to where it is going, as the racks' do.
    // Left gliding - or stopped a hair short of its target, which a smoother
    // can do for ever - the first second of a render depended on what had
    // played before it, and two exports of one song differed at the top.
    for (Effect *fx : sends) {
        if (fx != nullptr) { fx->reset(); fx->params().jumpAll(); }
    }
    for (Effect *fx : inserts) {
        if (fx != nullptr) { fx->reset(); fx->params().jumpAll(); }
    }
    for (auto &group : groupInserts) {
        for (Effect *fx : group) if (fx != nullptr) { fx->reset(); fx->params().jumpAll(); }
    }
    params_.jumpAll();
    // The scene fade starts at whatever the first block asks for, rather than
    // gliding there from where the last playing left it.
    fadeJump = true;
    limiter.reset();
    perform.release();
    perform.reset();
    perform.params().jumpAll();
    panicRamp = 0.0f;
}

} // namespace acidulous
