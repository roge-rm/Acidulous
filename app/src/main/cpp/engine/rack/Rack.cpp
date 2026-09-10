#include "Rack.h"
#include <cmath>

namespace acidulous {

namespace {
const ParamDef kChannelDefs[Rack::ChannelCount] = {
    {"gain", 0.0f, 1.5f, 1.0f, Curve::Linear, 0, ""},
    {"pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    {"mute", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
    {"solo", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
    {"sendreverb", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    {"senddelay", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
};
} // namespace

Rack::Rack() {
    channel.init(kChannelDefs, ChannelCount);
    for (int32_t i = 0; i <= kEventorSlots; ++i) {
        sinks[i].rack = this;
        sinks[i].stage = i;
    }
}

void Rack::Sink::send(uint8_t status, uint8_t d1, uint8_t d2) { rack->deliver(stage + 1, status, d1, d2); }

// Stage 0 is "before eventor 1"; stage kEventorSlots is "at the machine".
void Rack::deliver(int32_t fromStage, uint8_t status, uint8_t d1, uint8_t d2) {
    for (int32_t s = fromStage; s < kEventorSlots; ++s) {
        if (eventors[s] != nullptr && !eventors[s]->bypassed()) {
            eventors[s]->handleMidi(status, d1, d2, sinks[s]);
            return; // the eventor forwards through its sink
        }
    }
    if (machine != nullptr) machine->handleMidi(status, d1, d2);
}

void Rack::handleMidi(uint8_t status, uint8_t d1, uint8_t d2) { deliver(0, status, d1, d2); }

void Rack::allNotesOff() {
    for (int32_t s = 0; s < kEventorSlots; ++s) {
        if (eventors[s] != nullptr) eventors[s]->allNotesOff(sinks[s]);
    }
    if (machine != nullptr) machine->allNotesOff();
}

void Rack::onBlock(int64_t tickStart, int64_t tickEnd, float bpm) {
    for (int32_t s = 0; s < kEventorSlots; ++s) {
        if (eventors[s] != nullptr) eventors[s]->run(tickStart, tickEnd, bpm, sinks[s]);
    }
    for (int32_t s = 0; s < kEffectSlots; ++s) {
        if (effects[s] != nullptr) effects[s]->onBlock(tickStart, tickEnd, bpm);
    }
}

void Rack::render(int32_t frames) {
    if (machine == nullptr) {
        for (int32_t i = 0; i < frames; ++i) bufL[i] = bufR[i] = 0.0f;
        stereo = false;
        return;
    }
    stereo = machine->render(bufL, bufR, frames);
    for (int32_t s = 0; s < kEffectSlots; ++s) {
        if (effects[s] != nullptr) stereo = effects[s]->run(bufL, bufR, frames, stereo);
    }
    // Channel strip: gain and equal-power pan, smoothed; a mono source pans
    // from L and becomes stereo here.
    channel.tick();
    const float gain = channel.get(Mute) >= 0.5f ? 0.0f : channel.get(Gain);
    const float pan = channel.get(Pan);
    const float angle = (pan + 1.0f) * 0.25f * 3.14159265f; // -1..1 -> 0..pi/2
    const float gl = gain * std::cos(angle);
    const float gr = gain * std::sin(angle);
    float peak = 0.0f;
    if (stereo) {
        for (int32_t i = 0; i < frames; ++i) {
            bufL[i] *= gl * 1.4142f;
            bufR[i] *= gr * 1.4142f;
            const float a = std::fabs(bufL[i]), b = std::fabs(bufR[i]);
            if (a > peak) peak = a;
            if (b > peak) peak = b;
        }
    } else {
        for (int32_t i = 0; i < frames; ++i) {
            const float m = bufL[i];
            bufL[i] = m * gl * 1.4142f;
            bufR[i] = m * gr * 1.4142f;
            const float a = std::fabs(bufL[i]), b = std::fabs(bufR[i]);
            if (a > peak) peak = a;
            if (b > peak) peak = b;
        }
        stereo = true;
    }
    if (peak > peakHold.load(std::memory_order_relaxed)) peakHold.store(peak, std::memory_order_relaxed);
}

Machine *Rack::swapMachine(Machine *next) {
    Machine *old = machine;
    if (old != nullptr) old->allNotesOff();
    machine = next;
    return old;
}

Effect *Rack::swapEffect(int32_t slot, Effect *next) {
    if (slot < 0 || slot >= kEffectSlots) return next; // caller retires it
    Effect *old = effects[slot];
    effects[slot] = next;
    return old;
}

Eventor *Rack::swapEventor(int32_t slot, Eventor *next) {
    if (slot < 0 || slot >= kEventorSlots) return next;
    Eventor *old = eventors[slot];
    if (old != nullptr) old->allNotesOff(sinks[slot]); // its sounding notes end cleanly
    eventors[slot] = next;
    return old;
}

void Rack::setParam(Unit unit, int32_t index, float v01) {
    switch (unit) {
    case Unit::Machine: if (machine) machine->params().set(index, v01); break;
    case Unit::Effect1:
    case Unit::Effect2: {
        Effect *fx = effects[unit == Unit::Effect1 ? 0 : 1];
        if (fx == nullptr) break;
        if (index == kEffectBypassIndex) fx->setBypass(v01 >= 0.5f);
        else fx->params().set(index, v01);
        break;
    }
    case Unit::Eventor1:
    case Unit::Eventor2: {
        const int32_t s = unit == Unit::Eventor1 ? 0 : 1;
        Eventor *ev = eventors[s];
        if (ev == nullptr) break;
        if (index == kEventorBypassIndex) ev->setBypass(v01 >= 0.5f, sinks[s]);
        else ev->params().set(index, v01);
        break;
    }
    case Unit::Channel: channel.set(index, v01); break;
    }
}

} // namespace acidulous
