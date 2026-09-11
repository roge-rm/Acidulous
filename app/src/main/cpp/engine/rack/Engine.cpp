#include "Engine.h"
#include <sequencer/ClockFollower.h>
#include <android/log.h>
#include <sequencer/Song.h>

namespace acidulous {

Engine::Engine() {
    clock.setSampleRate(kSampleRate);
    scheduler.bind(racks, kRackCount, &clock, &transport);
}

Engine::~Engine() { stop(); }

void Engine::start() {
    master.prepare(kSampleRate);
    retirer.start();
}
void Engine::stop() { retirer.stop(); }

void Engine::renderBlock(const float *in, float *out) {
    const auto t0 = std::chrono::steady_clock::now();

    // Publish the input before anything renders, so a machine reading it
    // sees this block's audio and not the last one's.
    const float gain = inputGain.load(std::memory_order_relaxed);
    if (in != nullptr && gain != 1.0f) {
        for (int32_t i = 0; i < kBlockFrames * 2; ++i) inputScratch[i] = in[i] * gain;
        InputBus::get().publish(inputScratch, kBlockFrames);
    } else {
        InputBus::get().publish(in, in != nullptr ? kBlockFrames : 0);
    }

    // Panic first, before anything else runs: whatever is happening, the
    // next thing that leaves this engine should be silence.
    if (panicFlag.exchange(false, std::memory_order_acq_rel)) {
        transport.stopFromAudioThread();
        playing = false;
        startPending = false;
        scheduler.allNotesOff();
        for (int32_t r = 0; r < kRackCount; ++r) {
            racks[r].allNotesOff();
            if (Machine *m = racks[r].currentMachine()) m->reset();
            for (int32_t s = 0; s < kEffectSlots; ++s) {
                if (Effect *e = racks[r].currentEffect(s)) e->reset();
            }
        }
        master.panic();
    }

    // Transport: apply a play/stop the UI asked for, only ever between blocks.
    if (transport.applyRequests()) {
        if (transport.isPlaying()) {
            clock.reset();
            startPending = true;
        } else {
            scheduler.allNotesOff();
            scheduler.stopLauncher();
            transport.clearLaunchRequests();
        }
        playing = transport.isPlaying();
        emitTransport(playing);
    }
    if (startPending) {
        if (transport.takeContinued()) {
            scheduler.resume();
        } else {
            scheduler.start(transport.requestedStartScene());
        }
        startPending = false;
    }

    drainClockIn();
    followExternal();

    if (racks[0].midiOutBound() == false) {
        for (int32_t r = 0; r < kRackCount; ++r) racks[r].bindMidiOut(&midiOut, r);
    }
    for (int32_t r = 0; r < kRackCount; ++r) racks[r].updateMidiOut(framesRendered);

    clock.advance(kBlockFrames);
    // Mounts before parameters: the UI queues a unit and then its values, so
    // draining in that order lands the values on the new unit, not the old one.
    applyMounts();
    drainMidi();
    drainParams();

    // Does any rack play audio it made earlier? Decided before the scheduler
    // fires, because a frozen rack is sent no notes.
    {
        // Per rack, because in clip mode every rack may be on a different
        // scene and frozen audio is stored per (track, scene).
        for (int32_t r = 0; r < kRackCount; ++r)
            racks[r].updateFrozen(scheduler.rackSceneId(r), clock.bpm(), playing);
    }

    // Where in the scene this block starts, before the scheduler moves on.
    const int64_t tickStart = scheduler.currentTickInIteration();
    const seq::SceneInfo *sceneBefore = scheduler.currentSceneInfo();
    const int32_t repeatBefore = scheduler.currentRepeat();

    if (playing) {
        if (!scheduler.process(clock.blockStart(), clock.blockEnd())) {
            scheduler.allNotesOff();
            transport.stopFromAudioThread();
            playing = false;
        }
    } else {
        scheduler.applyIdleTempo();
    }

    // Twenty-four pulses a quarter note, which at 240 PPQN is every tenth
    // tick exactly, at every tempo. The clock runs whether or not the
    // transport does, because that is what the specification asks for and
    // what the engine's free-running clock already did.
    emitClock(clock.blockStart(), clock.blockEnd());

    // Metronome: every beat boundary this block crossed, at its sample offset.
    if (playing && master.clickEnabled() && scheduler.launcherActive()) {
        // No scene owns the bar line here, so the song's signature counts
        // from the transport's own zero.
        const int64_t ticksPerBar = scheduler.songTicksPerBar();
        const float samplesPerTick = static_cast<float>(kSampleRate) * 60.0f / (clock.bpm() * static_cast<float>(kPPQN));
        const int64_t from = clock.blockStart(), to = clock.blockEnd();
        int64_t t = (from / kPPQN) * kPPQN;
        if (t < from) t += kPPQN;
        for (; t < to; t += kPPQN) {
            const int32_t offset = static_cast<int32_t>(static_cast<float>(t - from) * samplesPerTick);
            master.clickAt(t % ticksPerBar == 0, offset < kBlockFrames ? offset : kBlockFrames - 1);
        }
    } else if (playing && master.clickEnabled() && sceneBefore != nullptr) {
        const int64_t tickEnd = scheduler.currentTickInIteration();
        const int64_t ticksPerBar = sceneBefore->ticksPerBar;
        const int64_t iterLen = sceneBefore->iterationTicks() > 0 ? sceneBefore->iterationTicks() : ticksPerBar;
        // The offset comes from the clock, which knows the sub-tick phase.
        // Worked out here instead, from the block's start as though it began
        // on a tick boundary, every click was late by up to a whole tick -
        // two milliseconds at 120 bpm - and any offset past the block was
        // clamped to its end. It had been doing that since M2.
        const int64_t absStart = clock.blockStart();
        auto beatsIn = [&](int64_t from, int64_t to, int64_t baseOffsetTicks) {
            int64_t t = (from / kPPQN) * kPPQN;
            if (t < from) t += kPPQN;
            for (; t < to; t += kPPQN) {
                const double at = clock.frameOffsetOfTick(absStart + baseOffsetTicks + t - from, kBlockFrames);
                int32_t offset = static_cast<int32_t>(at < 0.0 ? 0.0 : at);
                if (offset >= kBlockFrames) offset = kBlockFrames - 1;
                master.clickAt(t % ticksPerBar == 0, offset);
            }
        };
        if (tickEnd >= tickStart) {
            beatsIn(tickStart, tickEnd, 0);
        } else { // wrapped an iteration inside this block
            beatsIn(tickStart, iterLen, 0);
            beatsIn(0, tickEnd, iterLen - tickStart);
        }
    }

    // Scene fades: in over the first bar of the first pass, out over the last
    // bar of the last pass. Stateless - derived from the position each block.
    float fade = 1.0f;
    if (playing && sceneBefore != nullptr && !scheduler.launcherActive()) {
        const int64_t tpb = sceneBefore->ticksPerBar;
        const int64_t iterLen = sceneBefore->iterationTicks();
        if (sceneBefore->fadeIn && repeatBefore == 0 && tickStart < tpb) {
            fade = static_cast<float>(tickStart) / static_cast<float>(tpb);
        } else if (sceneBefore->fadeOut && repeatBefore == sceneBefore->repeat - 1 && tickStart >= iterLen - tpb) {
            fade = static_cast<float>(iterLen - tickStart) / static_cast<float>(tpb);
        }
    }

    for (int32_t r = 0; r < kRackCount; ++r) {
        if (racks[r].isActive()) {
            if (racks[r].frozenActive()) {
                racks[r].syncFrozen(scheduler.rackTick(r), clock.bpm());
            } else {
                racks[r].onBlock(clock.blockStart(), clock.blockEnd(), clock.bpm());
            }
            racks[r].render(kBlockFrames);
        }
    }
    master.process(racks, kRackCount, out, kBlockFrames, clock.bpm(), fade);

    // Monitoring is after the master so it is heard at the master's level,
    // and deliberately not recorded when capturing the input: nobody wants
    // their own monitor path printed into the sample.
    const InputBus &bus = InputBus::get();
    if (capture.armed()) {
        capture.push(capture.source() == Capture::FromInput && bus.live() ? bus.block() : out, kBlockFrames);
    }
    const float monitor = monitorLevel.load(std::memory_order_relaxed);
    if (monitor > 0.0001f && bus.live()) {
        const float *src = bus.block();
        for (int32_t i = 0; i < kBlockFrames * 2; ++i) out[i] += src[i] * monitor;
    }

    transport.publishPosition(scheduler.packedPosition());
    framesRendered += kBlockFrames;

    // Block budget at 48 kHz / 64 frames is 1333 us.
    const auto us = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - t0).count();
    const float pct = static_cast<float>(us) / 1333.3f * 100.0f;
    load.store(load.load(std::memory_order_relaxed) * 0.95f + pct * 0.05f, std::memory_order_relaxed);
}

/**
 * A pulse at every tenth tick this block crossed, on the frame it truly
 * falls on rather than the frame the block began on.
 */
void Engine::emitClock(int64_t blockStartTick, int64_t blockEndTick) {
    if (!transport.clockOut()) {
        lastClockTick = blockEndTick;
        return;
    }
    // (start, end]: the range whose frames the clock can place exactly.
    int64_t t = blockStartTick + 1;
    if (lastClockTick >= blockStartTick) t = lastClockTick + 1;
    for (; t <= blockEndTick; ++t) {
        if (t % (kPPQN / 24) != 0) continue;
        const double at = clock.frameOffsetOfTick(t, kBlockFrames);
        const int64_t frame = framesRendered + static_cast<int64_t>(at < 0.0 ? 0.0 : at);
        midiOut.push({frame, 0xf8, 0, 0, 0xff});
    }
    lastClockTick = blockEndTick;
}

/**
 * Start, stop, and - when the playhead is not at the top of the song -
 * a song position followed by continue, which is what a hardware sequencer
 * needs in order to join in at the right bar rather than from its own start.
 */
void Engine::emitTransport(bool nowPlaying) {
    if (!transport.clockOut()) return;
    if (!nowPlaying) {
        midiOut.push({framesRendered, 0xfc, 0, 0, 0xff});
        return;
    }
    int64_t songTick = 0;
    const seq::SongSnapshot *snap = scheduler.snapshot();
    if (snap != nullptr && !transport.launcherMode()) {
        songTick = snap->songTickAt(scheduler.currentScene(), scheduler.currentRepeat(),
                                    scheduler.currentTickInIteration());
    }
    // A MIDI beat is a sixteenth: 60 ticks at 240 PPQN.
    const int64_t beats = songTick / (kPPQN / 4);
    if (beats > 0) {
        midiOut.push({framesRendered, 0xf2, static_cast<uint8_t>(beats & 0x7f),
                      static_cast<uint8_t>((beats >> 7) & 0x7f), 0xff});
        midiOut.push({framesRendered, 0xfb, 0, 0, 0xff});
    } else {
        midiOut.push({framesRendered, 0xfa, 0, 0, 0xff});
    }
}

/**
 * What a master is telling us. The frame each byte carries was worked out
 * on the far side from the audio stream's own anchor, so it is in the same
 * time base the clock counts in.
 */
void Engine::drainClockIn() {
    MidiInEvent e;
    while (clockIn.pop(e)) {
        switch (e.status) {
        case 0xf8:
            follower.pulse(e.frame);
            break;
        case 0xfa: // start: from the top
            follower.relocate(0);
            if (transport.externalSync()) {
                scheduler.locateTo(0);
                transport.requestPlay(0);
            }
            break;
        case 0xfb: // continue: from wherever the locate left us
            if (transport.externalSync()) {
                transport.requestContinue();
            }
            break;
        case 0xfc:
            if (transport.externalSync()) {
                transport.requestStop();
            }
            break;
        case 0xf2: { // song position, in sixteenths
            const int64_t beats = static_cast<int64_t>(e.data1) | (static_cast<int64_t>(e.data2) << 7);
            const int64_t songTick = beats * (kPPQN / 4);
            follower.relocate(songTick);
            if (transport.externalSync()) {
                scheduler.locateTo(songTick);
            }
            break;
        }
        default:
            break;
        }
    }
}

/**
 * Run the clock at the follower's rate, and lean on it gently until the
 * engine's own position agrees with the master's.
 *
 * The rate alone would keep time but drift in phase, because nothing would
 * ever correct where the two started. The pull is deliberately slow - a few
 * per cent of the error a block - so it shows up as the engine easing into
 * line rather than as a tempo that wavers.
 */
void Engine::followExternal() {
    if (!transport.externalSync() || !follower.running()) {
        return;
    }
    const double perTick = follower.framesPerTick();
    if (perTick < 1.0) {
        return;
    }
    double want = perTick;
    if (playing && follower.locked()) {
        const double theirs = follower.tickAt(framesRendered);
        const double ours = static_cast<double>(clock.position());
        const double errTicks = ours - theirs;
        const double pull = errTicks > 8.0 ? 8.0 : (errTicks < -8.0 ? -8.0 : errTicks);
        want = perTick * (1.0 + pull * 0.002);
    }
    clock.setExternalFramesPerTick(want);
    if (follower.stale(framesRendered)) {
        transport.publishSync(0);
    } else {
        const int32_t bpmMilli = static_cast<int32_t>(follower.bpm() * 100.0f);
        const int32_t errMicro = static_cast<int32_t>(follower.phaseErrorMs() * 1000.0f);
        transport.publishSync((static_cast<int64_t>(follower.locked() ? 1 : 0) << 56) |
                              (static_cast<int64_t>(bpmMilli & 0xffffff) << 32) |
                              (static_cast<int64_t>(errMicro) & 0xffffffffLL));
    }
}

void Engine::drainMidi() {
    MidiMessage m;
    while (midiIn.pop(m)) {
        const int32_t rack = m.status & 0x0f;
        uint8_t status = m.status & 0xf0;
        uint8_t d2 = m.data2;
        if (status == 0x90 && d2 == 0) status = 0x80;
        if (!racks[rack].isActive()) continue;
        racks[rack].handleMidi(status, m.data1, d2);
        if (transport.isRecording()) {
            seq::RecordedEvent ev;
            ev.absTick = clock.position();
            // The rack's own clip, not the scheduler's: in clip mode a take
            // recorded onto a launched clip must land in *that* cell.
            ev.sceneId = scheduler.rackSceneId(rack);
            ev.tickInIteration = scheduler.rackTick(rack);
            ev.rack = rack;
            ev.cmd = status;
            ev.p1 = m.data1;
            ev.p2 = d2;
            recordQueue.push(ev);
        }
    }
}

void Engine::drainParams() {
    ParamMessage p;
    while (paramsIn.pop(p)) {
        if (p.unit == Unit::Master) {
            master.params().set(p.index, p.value);
        } else if (p.rack >= 0 && p.rack < kRackCount) {
            racks[p.rack].setParam(p.unit, p.index, p.value);
            if (p.record && transport.isRecording()) {
                racks[p.rack].touch(p.unit, p.index);
                seq::RecordedEvent ev;
                ev.absTick = clock.position();
                ev.sceneId = scheduler.rackSceneId(p.rack);
                ev.tickInIteration = scheduler.rackTick(p.rack);
                ev.rack = p.rack;
                ev.cmd = 0xf0;
                ev.p1 = static_cast<uint8_t>(p.unit);
                ev.p2 = 0;
                ev.paramIndex = p.index;
                ev.value = p.value;
                recordQueue.push(ev);
            }
        }
    }
}

// One mount per block keeps the worst case bounded; the UI's builder retries
// when the queue is momentarily full.
void Engine::applyMounts() {
    // Every mount queued so far, bounded: a swap is a pointer exchange and a
    // retire push, so a burst (a loaded song mounting its machines and
    // effects) lands whole in one block, ahead of the parameters behind it.
    for (int32_t n = 0; n < kMaxMountsPerBlock; ++n) {
        Mount m;
        if (!mounts.pop(m)) return;
        applyMount(m);
    }
}

void Engine::applyMount(const Mount &m) {
    switch (m.kind) {
    case Mount::Kind::Machine:
        if (m.rack >= 0 && m.rack < kRackCount) {
            retirer.retire(racks[m.rack].swapMachine(static_cast<Machine *>(m.object)), deleteAs<Machine>);
        } else {
            retirer.retire(m.object, deleteAs<Machine>);
        }
        break;
    case Mount::Kind::Effect:
        if (m.rack >= 0 && m.rack < kRackCount) {
            retirer.retire(racks[m.rack].swapEffect(m.slot, static_cast<Effect *>(m.object)), deleteAs<Effect>);
        } else {
            retirer.retire(m.object, deleteAs<Effect>);
        }
        break;
    case Mount::Kind::Eventor:
        if (m.rack >= 0 && m.rack < kRackCount) {
            retirer.retire(racks[m.rack].swapEventor(m.slot, static_cast<Eventor *>(m.object)), deleteAs<Eventor>);
        } else {
            retirer.retire(m.object, deleteAs<Eventor>);
        }
        break;
    case Mount::Kind::Object: {
        void *back = m.object;
        if (m.rack >= 0 && m.rack < kRackCount && racks[m.rack].currentMachine() != nullptr) {
            back = racks[m.rack].currentMachine()->swapObject(m.slot, m.object);
        }
        if (m.deleter != nullptr) retirer.retire(back, m.deleter);
        break;
    }
    case Mount::Kind::Frozen: {
        if (m.rack >= 0 && m.rack < kRackCount) {
            const FrozenSet *old = racks[m.rack].swapFrozen(static_cast<const FrozenSet *>(m.object));
            retirer.retire(const_cast<FrozenSet *>(old), deleteAs<FrozenSet>);
        } else if (m.deleter != nullptr) {
            retirer.retire(m.object, m.deleter);
        }
        break;
    }
    case Mount::Kind::Song: {
        const seq::SongSnapshot *old = scheduler.swapSnapshot(static_cast<const seq::SongSnapshot *>(m.object));
        retirer.retire(const_cast<seq::SongSnapshot *>(old), deleteAs<seq::SongSnapshot>);
        break;
    }
    default:
        break;
    }
}

} // namespace acidulous
