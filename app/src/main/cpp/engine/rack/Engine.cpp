#include "Engine.h"
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
        }
        playing = transport.isPlaying();
    }
    if (startPending) {
        scheduler.start(transport.requestedStartScene());
        startPending = false;
    }

    clock.advance(kBlockFrames);
    // Mounts before parameters: the UI queues a unit and then its values, so
    // draining in that order lands the values on the new unit, not the old one.
    applyMounts();
    drainMidi();
    drainParams();

    // Does any rack play audio it made earlier? Decided before the scheduler
    // fires, because a frozen rack is sent no notes.
    {
        const int64_t sceneNow = scheduler.currentSceneId();
        for (int32_t r = 0; r < kRackCount; ++r) racks[r].updateFrozen(sceneNow, clock.bpm(), playing);
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

    // Metronome: every beat boundary this block crossed, at its sample offset.
    if (playing && master.clickEnabled() && sceneBefore != nullptr) {
        const int64_t tickEnd = scheduler.currentTickInIteration();
        const int64_t ticksPerBar = sceneBefore->ticksPerBar;
        const int64_t iterLen = sceneBefore->iterationTicks() > 0 ? sceneBefore->iterationTicks() : ticksPerBar;
        const float samplesPerTick = static_cast<float>(kSampleRate) * 60.0f / (clock.bpm() * static_cast<float>(kPPQN));
        auto beatsIn = [&](int64_t from, int64_t to, int64_t baseOffsetTicks) {
            int64_t t = (from / kPPQN) * kPPQN;
            if (t < from) t += kPPQN;
            for (; t < to; t += kPPQN) {
                const int32_t offset = static_cast<int32_t>(static_cast<float>(baseOffsetTicks + t - from) * samplesPerTick);
                master.clickAt(t % ticksPerBar == 0, offset < kBlockFrames ? offset : kBlockFrames - 1);
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
    if (playing && sceneBefore != nullptr) {
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
                racks[r].syncFrozen(scheduler.currentTickInIteration(), clock.bpm());
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

    // Block budget at 48 kHz / 64 frames is 1333 us.
    const auto us = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - t0).count();
    const float pct = static_cast<float>(us) / 1333.3f * 100.0f;
    load.store(load.load(std::memory_order_relaxed) * 0.95f + pct * 0.05f, std::memory_order_relaxed);
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
            ev.sceneId = scheduler.currentSceneId();
            ev.tickInIteration = scheduler.currentTickInIteration();
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
                ev.sceneId = scheduler.currentSceneId();
                ev.tickInIteration = scheduler.currentTickInIteration();
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
