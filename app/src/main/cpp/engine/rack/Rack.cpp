#include "Rack.h"
#include <cmath>
#include <cstring>
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
    /**
     * How late this track's offbeats are, as a percentage of the pair.
     *
     * Fifty is straight and is what every track gets unless the document
     * says otherwise: the song's own value is resolved on the way in, so
     * the engine never has to know what "follow the song" means. It sits on
     * the channel for the same reason `midimode` does - it belongs to the
     * track rather than to any machine, and arriving as a parameter means
     * it can be automated and recorded without a second path.
     */
    {"swing", 50.0f, 75.0f, 50.0f, Curve::Linear, 0, "%"},
    // Where this track's sound goes: 0 the master, 1..4 a group in the mixer.
    // Seventeen steps because it shipped that way, when a group was a track,
    // and a stepped parameter's count is what a saved value is normalised
    // against. A decision like `midimode`, so it never ramps.
    {"output", 0.0f, 16.0f, 0.0f, Curve::Stepped, 17, ""},
};
} // namespace

Rack::Rack() {
    channel.init(kChannelDefs, ChannelCount);
    // Here, because it allocates: seventeen kilobytes of overlap buffer per
    // rack, and a rack is built long before the audio thread exists.
    frozenStretch.prepare();
    for (int32_t i = 0; i <= kInputModSlots; ++i) {
        sinks[i].rack = this;
        sinks[i].stage = i;
    }
}

void Rack::Sink::send(uint8_t status, uint8_t d1, uint8_t d2) { rack->deliver(stage + 1, status, d1, d2); }

// Stage 0 is "before modifier 1"; stage kInputModSlots is "at the machine".
void Rack::deliver(int32_t fromStage, uint8_t status, uint8_t d1, uint8_t d2) {
    for (int32_t s = fromStage; s < kInputModSlots; ++s) {
        if (modifiers[s] != nullptr && !modifiers[s]->bypassed()) {
            modifiers[s]->handleMidi(status, d1, d2, sinks[s]);
            return; // the modifier forwards through its sink
        }
    }
    toMachine(status, d1, d2, true);
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

void Rack::toMachine(uint8_t status, uint8_t d1, uint8_t d2, bool live) {
    // Anything that got here through the chain came from a finger, or from a
    // modifier acting on one, so this is what a recording should keep: the
    // arpeggio rather than the key that started it. A clip's own notes arrive
    // by the other door and are not written down again.
    if (live && modifiedSink != nullptr) modifiedSink->onModifiedNote(rackIndex, status, d1, d2);

    // After the modifiers, so what leaves for the hardware is what you hear -
    // arpeggiated and scale-corrected. Before the voice limiter, which is a
    // property of the machine and no business of a synthesizer on the other
    // end of a cable.
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

void Rack::playSequenced(uint8_t status, uint8_t d1, uint8_t d2) { toMachine(status, d1, d2, false); }

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

void Rack::noteExpressionValue(int32_t kind, uint8_t note, float v01) {
    if (machine == nullptr) return;
    switch (static_cast<Expr>(kind)) {
    case Expr::Bend: machine->noteBend(note, exprBendFrom01(v01)); break;
    case Expr::Pressure: machine->notePressure(note, expr7From01(v01)); break;
    case Expr::Timbre: machine->noteTimbre(note, expr7From01(v01)); break;
    default: break;
    }
}

void Rack::allNotesOff() {
    for (int32_t s = 0; s < kInputModSlots; ++s) {
        if (modifiers[s] != nullptr) modifiers[s]->allNotesOff(sinks[s]);
    }
    if (machine != nullptr) machine->allNotesOff();
    // Panic reaches the rack through here, and panic means silence - so the
    // frozen ring-out stops with everything else that was still sounding.
    tailClip = nullptr;
    heldCount = 0;
}

void Rack::onBlock(int64_t tickStart, int64_t tickEnd, float bpm) {
    for (int32_t s = 0; s < kInputModSlots; ++s) {
        if (modifiers[s] != nullptr) modifiers[s]->run(tickStart, tickEnd, bpm, sinks[s]);
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
void Rack::updateFrozen(int64_t sceneId, float bpm, bool playing, bool ramping) {
    const FrozenClip *want = nullptr;
    // **A muted clip is muted whether or not it was frozen.**
    //
    // `ClipPlayer` has always skipped a muted clip's notes, and nothing here
    // ever looked at a clip at all - so muting a frozen clip silenced notes
    // that were not being played and left the audio running. The mute button
    // simply did nothing on the one kind of clip whose whole point is that
    // the machine is not running.
    //
    // The player's clip is the rack's own and is set per rack per scene, so it
    // is the right one in clip mode as well as in the arranger.
    const seq::Clip *clip = clipPlayer.clip();
    const bool muted = clip != nullptr && clip->mute;
    if (playing && !muted && frozenSet != nullptr) {
        const FrozenClip *c = frozenSet->find(sceneId);
        // The tempo has to be the one it was rendered at, to a hundredth of
        // a beat: a second of audio at 121 bpm is a different number of
        // frames than at 120, so the loop would walk away from the beat.
        //
        // Unless the clock is ramping, in which case there is no tempo to
        // match - it is between two of them - and the ratio is handed to the
        // stretcher instead. Bounded, because a rate far from one is a warble
        // rather than a tempo and the machine is the better answer there.
        if (c != nullptr && c->frames > 0 && c->bpm > 0.0f) {
            const float rate = bpm / c->bpm;
            if (std::fabs(c->bpm - bpm) < 0.01f) {
                want = c;
                frozenRate = 1.0f;
            } else if (ramping && rate > 0.5f && rate < 2.0f) {
                want = c;
                frozenRate = rate;
            }
        }
    }
    if (want == nullptr) frozenRate = 1.0f;
    if (want == frozenNow) return;
    // A frozen clip is exactly its own length, so when it stops it stops - and
    // what the machine would have done is go on ringing. That is what the tail
    // is for: hand it to the second cursor on the way out and the scene change
    // sounds like the live track did, reverb and releases and all.
    //
    // On a stop as well as on a scene change, because a stop is note-offs and
    // note-offs are a release: a live track goes quiet over a second or two
    // rather than at the instant the button is pressed, and the tail is by
    // construction exactly that long. Panic is the one that cuts.
    if (frozenNow != nullptr && want == nullptr && frozenNow->tail > 0) {
        tailClip = frozenNow;
        tailCursor = frozenNow->frames;
    }
    // Crossing in either direction: whatever the machine was holding has to
    // stop, or it hangs while the audio takes over and after it hands back.
    if (machine != nullptr) machine->allNotesOff();
    frozenNow = want;
    frozenCursor = -1;
    stretching = nullptr; // a new clip is a new cycle, so the stretcher re-seeks
}

void Rack::updateScene(int64_t sceneId, int64_t cycleTick, bool playing) {
    if (machine == nullptr) return;
    // The clip is the rack's own, set per rack per scene, so the mute is the
    // right one in both modes - the same reason updateFrozen asks it.
    const seq::Clip *clip = clipPlayer.clip();
    machine->onScene(sceneId, cycleTick, playing, clip != nullptr && clip->mute);
}

void Rack::syncFrozen(int64_t tickInIteration, float bpm) {
    (void)bpm;
    if (frozenNow == nullptr) return;
    // **The clip's own tempo, not the clock's.** The target is a position in
    // the recorded audio, and that audio's seconds are the ones it was
    // rendered at - which is the same number until the clock ramps, and a
    // different one the moment it does.
    const float at = frozenNow->bpm > 0.0f ? frozenNow->bpm : 120.0f;
    const double perTick = static_cast<double>(kSampleRate) * 60.0 / (static_cast<double>(at) * kPPQN);
    const int64_t ticks = frozenNow->ticks > 0 ? frozenNow->ticks : 1;
    const int64_t target = static_cast<int64_t>((tickInIteration % ticks) * perTick) % frozenNow->frames;
    if (frozenRate == 1.0f) {
        // `stretching` is *not* cleared here. The stretcher has to stay alive
        // until the crossfade out of it has finished, and render is what knows
        // when that is.
        //
        // The cursor runs free between blocks and is only pulled back when it
        // has drifted audibly - recomputing it from the tick every block would
        // step the read position by a sample or two each time, which clicks.
        if (frozenCursor < 0 || std::llabs(target - frozenCursor) > 256) frozenCursor = target;
        frozenSyncTarget = target;
        return;
    }
    // Stretching, and the read position is the stretcher's own. Nudging it
    // between hops is the one thing `Stretcher` forbids: the next join would
    // be searched against audio from somewhere else. So it is seeded when the
    // clip arrives and re-seeded only where a cycle begins, which the tick
    // going backwards is how we know.
    if (stretching != frozenNow || target < frozenSyncTarget) {
        frozenStretch.seek(target);
        stretching = frozenNow;
    }
    frozenSyncTarget = target;
}

/**
 * The frozen clip read at the rate it was rendered at: a memory read, and the
 * whole of what freezing saves. Lifted out of `render` so the crossfade into
 * and out of the stretcher can mix it against the stretched read.
 */
void Rack::readFrozenPlain(int32_t frames) {
    const FrozenClip *f = frozenNow;
    if (f == nullptr) return;
    int64_t at = frozenCursor < 0 ? 0 : frozenCursor;
    for (int32_t i = 0; i < frames; ++i) {
        if (at >= f->frames) {
            at = 0;
            // Round again, and the pass that just ended starts ringing over
            // the top of this one - which is the whole reason a loop of a
            // frozen clip does not cut its own reverb off at the bar line.
            // It is started here rather than from the tick, because this is
            // the one place that knows the audio itself came round.
            if (f->tail > 0) {
                tailClip = f;
                tailCursor = f->frames;
            }
        }
        bufL[i] = f->left[static_cast<size_t>(at)];
        bufR[i] = f->right[static_cast<size_t>(at)];
        ++at;
    }
    frozenCursor = at;
}

void Rack::render(int32_t frames) {
    // Frozen audio, in one of three states: read plainly, read through the
    // stretcher, or crossfading between the two. The blend is what the third
    // one is, and it exists because both edges are discontinuities - see
    // `frozenBlend`. It is stepped once per block rather than per sample,
    // because ten milliseconds of ramp across a 64-frame block is a
    // hundred-and-thirty step staircase and each step is a fifth of a
    // percent: below anything audible, and it saves a multiply a sample.
    if (frozenNow != nullptr) {
        const bool wantStretch = frozenRate != 1.0f && stretching == frozenNow;
        const float target = wantStretch ? 1.0f : 0.0f;
        const float step = static_cast<float>(frames) / static_cast<float>(kBlendFrames);
        if (frozenBlend < target) frozenBlend = std::min(target, frozenBlend + step);
        else if (frozenBlend > target) frozenBlend = std::max(target, frozenBlend - step);
        if (frozenBlend <= 0.0f && !wantStretch) stretching = nullptr; // the fade is done with it
    } else {
        frozenBlend = 0.0f;
        stretching = nullptr;
    }

    if (frozenNow != nullptr && frozenBlend > 0.0f && stretching == frozenNow) {
        // Following a tempo the audio was not rendered at, by stretching it.
        //
        // The source runs to the end of the **tail**, not the end of the clip,
        // and that is deliberate: `Stretcher::fill` stops a window short of
        // whatever end it is given, so bounding it at the clip would drop the
        // last thirty milliseconds of every pass. The tail is the audio that
        // followed the clip, contiguous in the same buffer, so it is exactly
        // the right runway to read into.
        const FrozenClip *f = frozenNow;
        float *dst[2] = {bufL, bufR};
        const float *src[2] = {f->left.data(), f->right.data()};
        const int64_t last = static_cast<int64_t>(f->frames) + f->tail;
        int32_t made = frozenStretch.fill(dst, src, 0, last, frames, frozenRate);
        // Round again when the source has passed the clip's end. A loop point
        // inside a ramp is joined by the stretcher's own search rather than
        // sample-exactly, which is the one thing here that is approximate -
        // and a ramp is one bar, so it needs a clip shorter than that to
        // happen at all.
        while (made < frames) {
            if (f->tail > 0) {
                tailClip = f;
                tailCursor = f->frames;
            }
            frozenStretch.seek(0);
            float *more[2] = {bufL + made, bufR + made};
            const int32_t got = frozenStretch.fill(more, src, 0, last, frames - made, frozenRate);
            if (got <= 0) {
                for (int32_t i = made; i < frames; ++i) bufL[i] = bufR[i] = 0.0f;
                break;
            }
            made += got;
        }
        if (frozenStretch.sourcePosition() >= f->frames) {
            if (f->tail > 0) {
                tailClip = f;
                tailCursor = f->frames;
            }
            frozenStretch.seek(0);
        }
        stereo = true;
        // **Not `frozenCursor = sourcePosition()`.** That is where the *next*
        // hop will read from, which runs ahead of the audio just emitted by up
        // to a whole hop - so handing it to the plain read on the way out
        // skipped up to fifteen milliseconds rather than merely clicking. The
        // plain cursor is left where the tick put it, which is authoritative,
        // and the crossfade covers the join.
        if (frozenBlend < 1.0f) {
            // Mid-fade, so the plain read is wanted too. It goes in a scratch
            // and the two are mixed; the stretched read is already in buf.
            float wetL[kBlockFrames], wetR[kBlockFrames];
            for (int32_t i = 0; i < frames; ++i) {
                wetL[i] = bufL[i];
                wetR[i] = bufR[i];
            }
            readFrozenPlain(frames);
            const float wet = frozenBlend, dry = 1.0f - frozenBlend;
            for (int32_t i = 0; i < frames; ++i) {
                bufL[i] = bufL[i] * dry + wetL[i] * wet;
                bufR[i] = bufR[i] * dry + wetR[i] * wet;
            }
        }
    } else if (frozenNow != nullptr) {
        readFrozenPlain(frames);
        stereo = true;
    } else if (machine == nullptr) {
        for (int32_t i = 0; i < frames; ++i) bufL[i] = bufR[i] = 0.0f;
        stereo = false;
        // Nothing to render - but a tail may still be ringing out of a clip
        // this rack was playing before its machine was taken away, so the
        // shortcut only holds when there is truly nothing left to hear.
        if (tailClip == nullptr) {
            for (int32_t i = 0; i < frames; ++i) keyBuf[i] = 0.0f;
            return;
        }
    } else {
        stereo = machine->render(bufL, bufR, frames);
        for (int32_t s = 0; s < kEffectSlots; ++s) {
            if (effects[s] != nullptr) stereo = effects[s]->run(bufL, bufR, frames, stereo);
        }
    }
    // The ring-out, over the top of all three cases above: over the next pass
    // of a loop, over whatever live clip the next scene brought, or over
    // silence when this track has nothing more to play. It is the rack's own
    // sound, so it goes in before the dry tap and before the channel strip -
    // the fader still moves it and freezing a track still captures it.
    if (tailClip != nullptr) {
        const FrozenClip *t = tailClip;
        const int64_t end = static_cast<int64_t>(t->frames) + t->tail;
        if (!stereo) {
            for (int32_t i = 0; i < frames; ++i) bufR[i] = bufL[i];
            stereo = true;
        }
        int64_t at = tailCursor;
        for (int32_t i = 0; i < frames && at < end; ++i, ++at) {
            bufL[i] += t->left[static_cast<size_t>(at)];
            bufR[i] += t->right[static_cast<size_t>(at)];
        }
        tailCursor = at;
        if (at >= end) tailClip = nullptr; // rung out
    }
    // The sidechain tap: this rack as another rack's detector hears it -
    // after its own inserts, before its fader and its mute. Pre-fader so that
    // pulling the kick down in the mix does not take the duck away with it,
    // and pre-mute so a muted kick can still drive a pump nobody hears.
    if (stereo) {
        for (int32_t i = 0; i < frames; ++i) keyBuf[i] = (bufL[i] + bufR[i]) * 0.5f;
    } else {
        for (int32_t i = 0; i < frames; ++i) keyBuf[i] = bufL[i];
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

InputMod *Rack::swapInputMod(int32_t slot, InputMod *next) {
    if (slot < 0 || slot >= kInputModSlots) return next;
    InputMod *old = modifiers[slot];
    if (old != nullptr) old->allNotesOff(sinks[slot]); // its sounding notes end cleanly
    modifiers[slot] = next;
    return old;
}

void Rack::setParam(Unit unit, int32_t index, float v01, bool jump) {
    switch (unit) {
    case Unit::Machine:
        if (machine) {
            if (jump) machine->params().jump(index, v01);
            else machine->params().set(index, v01);
        }
        break;
    case Unit::Effect1:
    case Unit::Effect2: {
        Effect *fx = effects[unit == Unit::Effect1 ? 0 : 1];
        if (fx == nullptr) break;
        if (index == kEffectBypassIndex) fx->setBypass(v01 >= 0.5f);
        else if (jump) fx->params().jump(index, v01);
        else fx->params().set(index, v01);
        break;
    }
    case Unit::Mod1:
    case Unit::Mod2:
    case Unit::Mod3: {
        const int32_t s = unit == Unit::Mod1 ? 0 : (unit == Unit::Mod2 ? 1 : 2);
        InputMod *ev = modifiers[s];
        if (ev == nullptr) break;
        if (index == kInputModBypassIndex) ev->setBypass(v01 >= 0.5f, sinks[s]);
        else if (jump) ev->params().jump(index, v01);
        else ev->params().set(index, v01);
        break;
    }
    case Unit::Channel: channel.set(index, v01); break;
    case Unit::Performance: {
        // Back into the MIDI it arrived as, so a lane and a finger on the
        // strip reach the machine by exactly the same path - through the
        // modifiers, as a controller, the way a hardware wheel would.
        const auto byte = static_cast<uint8_t>(
            v01 <= 0.0f ? 0 : (v01 >= 1.0f ? 127 : static_cast<int32_t>(v01 * 127.0f + 0.5f)));
        if (index == kPerfMod) {
            handleMidi(0xb0, 1, byte);
        } else if (index == kPerfPressure) {
            handleMidi(0xd0, byte, 0);
        }
        break;
    }
    case Unit::Perform: if (performSink != nullptr) performSink->set(index, v01); break;
    case Unit::Master: break; // never addressed at a rack
    }
}

} // namespace acidulous
