#include "Engine.h"
#include <sequencer/Song.h>

namespace acidulous {

Engine::Engine() {
    clock.setSampleRate(kSampleRate);
    scheduler.bind(racks, kRackCount, &clock, &transport);
}

Engine::~Engine() { stop(); }

void Engine::start() { retirer.start(); }
void Engine::stop() { retirer.stop(); }

void Engine::renderBlock(float *out) {
    const auto t0 = std::chrono::steady_clock::now();

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
    drainMidi();
    drainParams();

    if (playing) {
        if (!scheduler.process(clock.blockStart(), clock.blockEnd())) {
            scheduler.allNotesOff();
            transport.stopFromAudioThread();
            playing = false;
        }
    } else {
        scheduler.applyIdleTempo();
    }

    for (int32_t r = 0; r < kRackCount; ++r) {
        if (racks[r].isActive()) {
            racks[r].onBlock(clock.blockStart(), clock.blockEnd());
            racks[r].render(kBlockFrames);
        }
    }
    master.mix(racks, kRackCount, out, kBlockFrames);

    transport.publishPosition(scheduler.packedPosition());
    applyMounts();

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
        if (p.rack >= 0 && p.rack < kRackCount) racks[p.rack].setParam(p.unit, p.index, p.value);
    }
}

// One mount per block keeps the worst case bounded; the UI's builder retries
// when the queue is momentarily full.
void Engine::applyMounts() {
    Mount m;
    if (!mounts.pop(m)) return;
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
