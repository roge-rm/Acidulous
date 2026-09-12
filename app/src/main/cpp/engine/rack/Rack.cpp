#include "Rack.h"
#include <cmath>
#include <cstdlib> // std::llabs, which the NDK happens to pull in and a host g++ does not

namespace acidulous {

namespace {
const ParamDef kChannelDefs[Rack::ChannelCount] = {
    {"gain", 0.0f, 1.5f, 1.0f, Curve::Linear, 0, ""},
    {"pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    {"mute", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
    {"solo", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
    {"sendreverb", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    {"senddelay", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    // Where this track's notes go: the machine, the machine and the world,
    // or only the world. Stepped, and read unsmoothed, because a three-way
    // switch must not ramp through the middle on its way across.
    {"midimode", 0.0f, 2.0f, 0.0f, Curve::Stepped, 3, ""},
    {"midichannel", 0.0f, 15.0f, 0.0f, Curve::Stepped, 16, ""},
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
    toMachine(status, d1, d2);
}

/**
 * The voice limit, applied here rather than in each machine: a machine knows
 * how to steal one of its own voices, but only the rack knows how many notes
 * are being asked for in the first place - including the ones an arpeggiator
 * made up. Over the limit, the oldest held note is released before the new
 * one starts, which is the same thing a real instrument does when it runs
 * out of strings.
 */
int32_t Rack::midiOutMode() const {
    // Normalised, not smoothed: a three-way switch must not ramp through
    // "both" on its way from one end to the other.
    const float v = channel.normalized(MidiMode);
    return static_cast<int32_t>(v * 2.0f + 0.5f);
}

void Rack::updateMidiOut(int64_t frame) {
    outFrame = frame;
    const int32_t mode = midiOutMode();
    const uint8_t ch = static_cast<uint8_t>(channel.normalized(MidiChannel) * 15.0f + 0.5f);
    if ((mode != lastOutMode || ch != lastOutChannel) && lastOutMode != OutInternal && outQueue != nullptr) {
        // It was sending and now it is sending somewhere else, or nowhere.
        // Whatever it left sounding out there is its responsibility.
        outQueue->push({frame, static_cast<uint8_t>(0xb0 | lastOutChannel), 123, 0,
                        static_cast<uint8_t>(rackIndex)});
    }
    lastOutMode = mode;
    lastOutChannel = ch;
}

void Rack::toMachine(uint8_t status, uint8_t d1, uint8_t d2) {
    // After the eventors, so what leaves for the hardware is what you hear -
    // arpeggiated, scale-corrected, and the same whether it came from a clip,
    // the on-screen keyboard or a controller, because all three arrive here.
    // Before the voice limiter, which is a property of the machine and no
    // business of a synthesizer on the other end of a cable.
    const int32_t mode = lastOutMode;
    if (mode != OutInternal && outQueue != nullptr) {
        outQueue->push({outFrame, static_cast<uint8_t>((status & 0xf0) | lastOutChannel), d1, d2,
                        static_cast<uint8_t>(rackIndex)});
    }
    if (mode == OutMidi) return;
    if (machine == nullptr) return;
    const uint8_t kind = status & 0xf0;
    const bool on = kind == 0x90 && d2 > 0;
    const bool off = kind == 0x80 || (kind == 0x90 && d2 == 0);
    if (on) {
        forgetHeld(d1); // a retrigger is not a second note
        int32_t limit = EngineSettings::get().voiceLimit.load(std::memory_order_relaxed);
        if (limit > 0) {
            if (limit > kMaxHeld) limit = kMaxHeld;
            while (heldCount >= limit) {
                const uint8_t oldest = held[0];
                machine->handleMidi(0x80, oldest, 0);
                forgetHeld(oldest);
            }
        }
        if (heldCount < kMaxHeld) held[heldCount++] = d1;
    } else if (off) {
        forgetHeld(d1);
    }
    machine->handleMidi(status, d1, d2);
}

void Rack::forgetHeld(uint8_t note) {
    for (int32_t i = 0; i < heldCount; ++i) {
        if (held[i] != note) continue;
        for (int32_t j = i; j + 1 < heldCount; ++j) held[j] = held[j + 1];
        --heldCount;
        return;
    }
}

void Rack::handleMidi(uint8_t status, uint8_t d1, uint8_t d2) { deliver(0, status, d1, d2); }

void Rack::noteExpression(uint8_t kind, uint8_t note, uint8_t d1, uint8_t d2, float bendSemis) {
    if (machine == nullptr) return;
    switch (kind) {
    case 0xe0: {
        const float bend14 = static_cast<float>((d2 << 7) | d1) - 8192.0f;
        machine->noteBend(note, bend14 / 8192.0f * bendSemis);
        break;
    }
    case 0xd0: machine->notePressure(note, d1); break;
    case 0xb0: if (d1 == 74) machine->noteTimbre(note, d2); break;
    default: break;
    }
}

void Rack::allNotesOff() {
    for (int32_t s = 0; s < kEventorSlots; ++s) {
        if (eventors[s] != nullptr) eventors[s]->allNotesOff(sinks[s]);
    }
    if (machine != nullptr) machine->allNotesOff();
    heldCount = 0;
}

void Rack::onBlock(int64_t tickStart, int64_t tickEnd, float bpm) {
    for (int32_t s = 0; s < kEventorSlots; ++s) {
        if (eventors[s] != nullptr) eventors[s]->run(tickStart, tickEnd, bpm, sinks[s]);
    }
    for (int32_t s = 0; s < kEffectSlots; ++s) {
        if (effects[s] != nullptr) effects[s]->onBlock(tickStart, tickEnd, bpm);
    }
    if (machine != nullptr) machine->onBlock(tickStart, tickEnd, bpm);
}

/**
 * Is this rack playing audio it made earlier?
 *
 * Only when a clip for the scene now playing has been frozen *and* the song
 * is at the tempo it was frozen at - audio does not stretch, and a frozen
 * clip played at another tempo would be in the wrong place within a beat of
 * starting. Falling back to the machine is both correct and quiet about it;
 * the UI says the freeze is stale.
 */
void Rack::updateFrozen(int64_t sceneId, float bpm, bool playing) {
    const FrozenClip *want = nullptr;
    if (playing && frozenSet != nullptr) {
        const FrozenClip *c = frozenSet->find(sceneId);
        // The tempo has to be the one it was rendered at, to a hundredth of
        // a beat: a second of audio at 121 bpm is a different number of
        // frames than at 120, so the loop would walk away from the beat.
        if (c != nullptr && c->frames > 0 && std::fabs(c->bpm - bpm) < 0.01f) want = c;
    }
    if (want == frozenNow) return;
    // Crossing in either direction: whatever the machine was holding has to
    // stop, or it hangs while the audio takes over and after it hands back.
    if (machine != nullptr) machine->allNotesOff();
    frozenNow = want;
    frozenCursor = -1;
}

void Rack::syncFrozen(int64_t tickInIteration, float bpm) {
    if (frozenNow == nullptr) return;
    const double perTick = static_cast<double>(kSampleRate) * 60.0 / (static_cast<double>(bpm) * kPPQN);
    const int64_t ticks = frozenNow->ticks > 0 ? frozenNow->ticks : 1;
    const int64_t target = static_cast<int64_t>((tickInIteration % ticks) * perTick) % frozenNow->frames;
    // The cursor runs free between blocks and is only pulled back when it has
    // drifted audibly - recomputing it from the tick every block would step
    // the read position by a sample or two each time, which clicks.
    if (frozenCursor < 0 || std::llabs(target - frozenCursor) > 256) frozenCursor = target;
}

void Rack::render(int32_t frames) {
    if (frozenNow != nullptr) {
        const FrozenClip *f = frozenNow;
        int64_t at = frozenCursor < 0 ? 0 : frozenCursor;
        for (int32_t i = 0; i < frames; ++i) {
            if (at >= f->frames) at = 0; // the loop, which the render wrapped its tail into
            bufL[i] = f->left[static_cast<size_t>(at)];
            bufR[i] = f->right[static_cast<size_t>(at)];
            ++at;
        }
        frozenCursor = at;
        stereo = true;
    } else if (machine == nullptr) {
        for (int32_t i = 0; i < frames; ++i) bufL[i] = bufR[i] = 0.0f;
        stereo = false;
        return;
    } else {
        stereo = machine->render(bufL, bufR, frames);
        for (int32_t s = 0; s < kEffectSlots; ++s) {
            if (effects[s] != nullptr) stereo = effects[s]->run(bufL, bufR, frames, stereo);
        }
    }
    if (tapDry) {
        for (int32_t i = 0; i < frames; ++i) {
            dryL[i] = bufL[i];
            dryR[i] = stereo ? bufR[i] : bufL[i];
        }
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
    heldCount = 0; // the notes went with the machine
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
    case Unit::Eventor2:
    case Unit::Eventor3: {
        const int32_t s = unit == Unit::Eventor1 ? 0 : (unit == Unit::Eventor2 ? 1 : 2);
        Eventor *ev = eventors[s];
        if (ev == nullptr) break;
        if (index == kEventorBypassIndex) ev->setBypass(v01 >= 0.5f, sinks[s]);
        else ev->params().set(index, v01);
        break;
    }
    case Unit::Channel: channel.set(index, v01); break;
    case Unit::Performance: {
        // Back into the MIDI it arrived as, so a lane and a finger on the
        // strip reach the machine by exactly the same path - through the
        // eventors, as a controller, the way a hardware wheel would.
        const auto byte = static_cast<uint8_t>(
            v01 <= 0.0f ? 0 : (v01 >= 1.0f ? 127 : static_cast<int32_t>(v01 * 127.0f + 0.5f)));
        if (index == kPerfMod) {
            handleMidi(0xb0, 1, byte);
        } else if (index == kPerfPressure) {
            handleMidi(0xd0, byte, 0);
        }
        break;
    }
    case Unit::Master: break; // never addressed at a rack
    }
}

} // namespace acidulous
