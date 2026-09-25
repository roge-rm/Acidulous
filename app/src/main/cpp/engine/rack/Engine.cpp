#include "Engine.h"
#include <sequencer/ClockFollower.h>
#include <sequencer/LinkFollower.h>
#include <sequencer/Song.h>
#include <cmath>
#include <ctime>

namespace acidulous {

Engine::Engine() {
    clock.setSampleRate(kSampleRate);
    scheduler.bind(racks, kRackCount, &clock, &transport);
    // Every rack reports what leaves its modifier chain, so a recording keeps
    // what was heard rather than what was pressed.
    for (int32_t r = 0; r < kRackCount; ++r) racks[r].setModifiedNoteSink(this);
    for (int32_t r = 0; r < kRackCount; ++r) racks[r].performSink = &master.perform.params();
}

Engine::~Engine() { stop(); }

void Engine::start() {
    master.prepare(kSampleRate);
    retirer.start();
}
void Engine::stop() { retirer.stop(); }

/**
 * The input chain, on the interleaved block that is about to be published.
 *
 * Split out of `renderBlock` because it is the one place in the engine that
 * deinterleaves and puts back, and burying that in the first ten lines of the
 * render would make them unreadable.
 *
 * The tick range is the *previous* block's, because the clock has not advanced
 * yet - so a tempo-synced effect on the input is one block behind the same
 * effect on a track, which is 1.3 ms and not worth reordering the render for.
 */
void Engine::runInputChain() {
    float L[kBlockFrames], R[kBlockFrames];
    for (int32_t i = 0; i < kBlockFrames; ++i) {
        L[i] = inputScratch[i * 2];
        R[i] = inputScratch[i * 2 + 1];
    }
    bool stereo = true;
    for (int32_t s = 0; s < kInputSlots; ++s) {
        if (inputFx[s] == nullptr) continue;
        inputFx[s]->onBlock(clock.blockStart(), clock.blockEnd(), clock.bpm());
        stereo = inputFx[s]->run(L, R, kBlockFrames, stereo);
    }
    for (int32_t i = 0; i < kBlockFrames; ++i) {
        inputScratch[i * 2] = L[i];
        inputScratch[i * 2 + 1] = stereo ? R[i] : L[i];
    }
}

/**
 * This thread's own CPU time, in microseconds.
 *
 * Duplicated from `AudioDriver` rather than shared, so the engine keeps no
 * dependency on the platform layer for five lines of clock.
 *
 * **Not a substitute for the wall clock, a companion to it.** The deadline is
 * wall time - the speaker does not care why we were late. What this answers is
 * the second question, which the per-track list could not: of that wall time,
 * how much did we spend computing?
 */
static int64_t threadCpuUs() {
    timespec ts{};
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000 + ts.tv_nsec / 1000;
}

void Engine::renderBlock(const float *in, float *out) {
    const auto t0 = std::chrono::steady_clock::now();
    const int64_t cpu0 = threadCpuUs();

    // The tuner hears it first, at the level it arrived at and with nothing
    // applied. Costs one branch when it is off, which is almost always.
    tuner.push(in, in != nullptr ? kBlockFrames : 0);

    // Publish the input before anything renders, so a machine reading it
    // sees this block's audio and not the last one's.
    const float gain = inputGain.load(std::memory_order_relaxed);
    const bool chained = inputFx[0] != nullptr || inputFx[1] != nullptr;
    if (in != nullptr && (gain != 1.0f || chained)) {
        for (int32_t i = 0; i < kBlockFrames * 2; ++i) inputScratch[i] = in[i] * gain;
        if (chained) runInputChain();
        InputBus::get().publish(inputScratch, kBlockFrames);
    } else {
        InputBus::get().publish(in, in != nullptr ? kBlockFrames : 0);
    }

    const auto tInput = std::chrono::steady_clock::now();

    // Panic first, before anything else runs: whatever is happening, the
    // next thing that leaves this engine should be silence.
    if (panicFlag.exchange(false, std::memory_order_acq_rel)) {
        transport.stopFromAudioThread();
        playing = false;
        startPending = false;
        scheduler.allNotesOff();
        // And the clip players' own beginning: a pass count and, in a
        // free-rolling clip, the dice. Same reason the modifiers are reset
        // below - a render panics first, so this is where "from the
        // beginning" has to mean it.
        scheduler.resetClipPlayers();
        // Panic means silence, and a file being auditioned is a sound this
        // engine is making. It is not part of the song, which is exactly why
        // it would otherwise be the one thing still playing afterwards.
        audition.stop();
        for (auto &n : mpeChannelNote) n = -1;
        for (int32_t r = 0; r < kRackCount; ++r) {
            racks[r].allNotesOff();
            // Parameters jump rather than glide. Every one of them is
            // smoothed, so after a reset they were still sliding in from
            // wherever they had been - which left the first few
            // milliseconds of a render depending on what had been playing
            // before it. A panic is a discontinuity by definition; there
            // is nothing here to be smooth about.
            if (Machine *m = racks[r].currentMachine()) panicMachine(*m);
            racks[r].jumpChannel();
            for (int32_t s = 0; s < kEffectSlots; ++s) {
                if (Effect *e = racks[r].currentEffect(s)) { e->reset(); e->params().jumpAll(); }
            }
            // The input chain too, once. A delay on the way in would otherwise
            // keep repeating into a song that has been panicked silent.
            if (r == 0) {
                for (Effect *e : inputFx) {
                    if (e != nullptr) { e->reset(); e->params().jumpAll(); }
                }
            }
            // The modifiers too. They were missed here from the start, and
            // the cost was not obvious: an arpeggiator keeps a step, so a
            // panic - or an offline render, which panics first - left it
            // part way through its pattern and the next notes to arrive
            // came out somewhere else in the run. It is why exporting the
            // same song twice gave two different files.
            for (int32_t s = 0; s < kInputModSlots; ++s) {
                if (InputMod *e = racks[r].currentInputMod(s)) e->reset();
            }
        }
        master.panic();
    }

    // Transport: apply a play/stop the UI asked for, only ever between blocks.
    if (transport.applyRequests()) {
        if (transport.isPlaying()) {
            clock.reset();
            // A count-in is a number of bars of clicks before the song
            // moves at all. The clock runs through them - it is what the
            // clicks are counted by - but the scheduler is not started, so
            // nothing sounds and nothing is recorded until the count is
            // out. The bars are the song's own, so 7/8 counts seven.
            //
            // Only when armed. A count-in counts you in to a take; pressing
            // play to hear where you are should not make you sit through
            // four bars of clicks first. The setting stays on - it is how
            // you record - and simply has nothing to do on a plain play.
            const int32_t bars = transport.isRecordArmed() ? transport.countInBarsWanted() : 0;
            const int64_t ticks = bars > 0 ? static_cast<int64_t>(bars) * scheduler.songTicksPerBar() : 0;
            // Counted in frames rather than ticks, and as a double.
            //
            // A block is 0.64 ticks at 120 bpm, and rounding that to a whole
            // tick per block drained a two-bar count in 2.56 seconds instead
            // of four - and at some tempos would round to nought and never
            // drain at all. Frames divide exactly into blocks; ticks do not.
            countInFrames = static_cast<double>(ticks) * clock.samplesPerTickNow();
            countInPerTick = clock.samplesPerTickNow();
            preRollFrames = countInPerTick * static_cast<double>(kPreRollTicks);
            earlyCount = 0;
            startPending = countInFrames <= 0.0;
            // Under Link, a plain play waits for the session's next downbeat
            // instead of starting where the finger landed - which is the
            // whole point of a shared phase. A count-in is its own bar line
            // and keeps its meaning, so the two do not both apply; the pull
            // brings the count-in's bar into line over the following one.
            //
            // **Only when somebody is out there.** Link left switched on with
            // no peers is the common case, not the exotic one: it persists
            // across launches and nothing on screen says it is on, so every
            // press of play sat waiting up to a whole bar to come into phase
            // with a session of one. There is no phase to join on your own,
            // and the wait reads as the transport being broken.
            linkWaiting = startPending && transport.followingLink() &&
                          timebase.load(std::memory_order_acquire) != nullptr && linkInSession;
            if (linkWaiting) startPending = false;
        } else {
            scheduler.allNotesOff();
            scheduler.stopLauncher();
            transport.clearLaunchRequests();
            linkWaiting = false;
            // A lane that pressed repeat and was stopped before it let go
            // would otherwise leave the song looping a beat in silence.
            master.perform.release();
            // And a mute waiting for a bar that will not come now.
            for (PendingParam &waiting : pendingParams) waiting.waiting = false;
            // **Stop means stop, not pause.** The playhead stayed where it
            // was, and the header's play button starts from the scene the
            // readout is showing - so a stop half way through a song and a
            // press of play carried on from there. There is no separate
            // pause, so the one control has to be the one people expect, and
            // what they expect of a stop button is the top of the song.
            //
            // The launcher is left alone: there is no "beginning" to go back
            // to when every track is somewhere of its own, and stopping there
            // already has its own two-stage meaning.
            if (!transport.launcherMode()) {
                clock.reset();
                scheduler.start(0);
            }
        }
        playing = transport.isPlaying();
        emitTransport(playing);
    }
    // Counting. The clock is advanced by hand here, because the scheduler -
    // which normally drives it - is deliberately not running yet.
    if (countInFrames > 0.0) {
        countInFrames -= static_cast<double>(kBlockFrames);
        if (countInFrames <= 0.0) {
            countInFrames = 0.0;
            startPending = true; // the bar line the count was counting to
        }
    }
    transport.publishCountIn(countInPerTick > 0.0
                                 ? static_cast<int64_t>(countInFrames / countInPerTick)
                                 : 0);

    // A rewind while stopped: put the playhead back at the top of the song so
    // the readout says so. Between blocks like everything else here, and
    // before the start below, so a play that arrives in the same block still
    // decides where it starts from.
    if (transport.takeRewind()) {
        clock.reset();
        scheduler.start(0);
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
    followTimebase();

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
        for (int32_t r = 0; r < kRackCount; ++r) {
            racks[r].updateFrozen(scheduler.rackSceneId(r), clock.bpm(), playing, clock.isRamping());
            // And the same question asked of the machine, for one that plays
            // the arrangement rather than notes out of it.
            racks[r].updateScene(scheduler.rackSceneId(r), scheduler.rackCycleTick(r), playing);
        }
    }

    // Where in the scene this block starts, before the scheduler moves on.
    const int64_t tickStart = scheduler.currentTickInIteration();
    const seq::SceneInfo *sceneBefore = scheduler.currentSceneInfo();
    const int32_t repeatBefore = scheduler.currentRepeat();

    // While counting, the transport is "playing" - the clock runs and the
    // clicks are counted by it - but the scheduler must not, or the song
    // would sound underneath its own count-in.
    const bool counting = countInFrames > 0.0;
    // The bus renders the count's clicks even with the metronome switched off,
    // and borrows the limiter's headroom for them while it does.
    master.setCountingIn(counting);
    if (playing && !counting) {
        scheduler.setSwingPair(swingPair.load(std::memory_order_relaxed));
        if (!scheduler.process(clock.blockStart(), clock.blockEnd())) {
            scheduler.allNotesOff();
            transport.stopFromAudioThread();
            playing = false;
        }
    } else if (!counting) {
        scheduler.applyIdleTempo();
    }

    // Twenty-four pulses a quarter note, which at 240 PPQN is every tenth
    // tick exactly, at every tempo. The clock runs whether or not the
    // transport does, because that is what the specification asks for and
    // what the engine's free-running clock already did.
    emitClock(clock.blockStart(), clock.blockEnd());

    // Metronome: every click boundary this block crossed, at its own sample
    // offset. The step is a bar or a division of the beat; the accent says
    // which of the three it is, because a metronome ticking sixteenths all
    // at one level is a buzz you cannot find the beat in.
    // A count-in always clicks - that is the whole of what it is - so it
    // does not ask whether the metronome is switched on.
    if ((playing && master.clickEnabled() && master.clickAllowed(transport.isRecordArmed())) || counting) {
        const int64_t stepTicks = master.clickStepTicks();
        auto accentFor = [](int64_t tickInBar, int64_t ticksPerBar) {
            if (ticksPerBar > 0 && tickInBar % ticksPerBar == 0) return static_cast<int32_t>(dsp::Click::Bar);
            if (tickInBar % kPPQN == 0) return static_cast<int32_t>(dsp::Click::Beat);
            return static_cast<int32_t>(dsp::Click::Division);
        };

        if (counting || scheduler.launcherActive()) {
            // No scene owns the bar line here, so the song's signature
            // counts from the transport's own zero.
            const int64_t ticksPerBar = scheduler.songTicksPerBar();
            // A count-in counts beats, whatever the metronome is set to.
            // "One, two, three, four" is the entire point of it, and
            // counting sixteenths would not be counting.
            const int64_t step = counting ? kPPQN
                                          : (stepTicks > 0 ? stepTicks : (ticksPerBar > 0 ? ticksPerBar : kPPQN));
            const int64_t from = clock.blockStart(), to = clock.blockEnd();
            int64_t t = (from / step) * step;
            if (t < from) t += step;
            for (; t < to; t += step) {
                const double at = clock.frameOffsetOfTick(t, kBlockFrames);
                master.clickAt(accentFor(t % ticksPerBar, ticksPerBar),
                               static_cast<int32_t>(at < 0.0 ? 0.0 : at));
            }
        } else if (sceneBefore != nullptr) {
            const int64_t tickEnd = scheduler.currentTickInIteration();
            const int64_t ticksPerBar = sceneBefore->ticksPerBar;
            const int64_t iterLen = sceneBefore->iterationTicks() > 0 ? sceneBefore->iterationTicks() : ticksPerBar;
            const int64_t step = stepTicks > 0 ? stepTicks : (ticksPerBar > 0 ? ticksPerBar : kPPQN);
            // The offset comes from the clock, which knows the sub-tick
            // phase. Worked out here instead, from the block's start as
            // though it began on a tick boundary, every click was late by up
            // to a whole tick - two milliseconds at 120 bpm - and any offset
            // past the block was clamped to its end. It had been doing that
            // since M2. Nothing is clamped now: an offset past this block is
            // carried into the next one, which a fast division needs.
            const int64_t absStart = clock.blockStart();
            auto clicksIn = [&](int64_t from, int64_t to, int64_t baseOffsetTicks) {
                int64_t t = (from / step) * step;
                if (t < from) t += step;
                for (; t < to; t += step) {
                    const double at = clock.frameOffsetOfTick(absStart + baseOffsetTicks + t - from, kBlockFrames);
                    master.clickAt(accentFor(t % ticksPerBar, ticksPerBar),
                                   static_cast<int32_t>(at < 0.0 ? 0.0 : at));
                }
            };
            if (tickEnd >= tickStart) {
                clicksIn(tickStart, tickEnd, 0);
            } else { // wrapped an iteration inside this block
                clicksIn(tickStart, iterLen, 0);
                clicksIn(0, tickEnd, iterLen - tickStart);
            }
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

    const auto tSeq = std::chrono::steady_clock::now();

    // And the sound itself. This loop and the master call under it were
    // deleted by an over-long slice edit in M35, which took the scene fade
    // with them: `out` was then never written at all, so every block handed
    // back whatever the caller's stack happened to hold.
    int32_t rackUsThisBlock[kRackCount]{};
    bool rackFrozenThisBlock[kRackCount]{};
    // **Sources before listeners.** A rack whose compressor, gate or filter
    // listens to another is rendered after it, so the duck lands on the same
    // block as the kick that caused it rather than one block late. Worked out
    // every block because a sidechain is a parameter and can be automated.
    // A loop - two racks each listening to the other - cannot be satisfied;
    // the lower-numbered one goes first and hears the other a block late.
    settleRouting();
    int32_t order[kRackCount];
    sidechainOrder(order);
    for (int32_t n = 0; n < kRackCount; ++n) {
        const int32_t r = order[n];
        if (racks[r].isActive()) {
            // Hand each detector its key for this block: the source's tap,
            // which is this block's if it has rendered and the last one's if
            // it has not, or silence for a rack with nothing on it.
            for (int32_t s = 0; s < kEffectSlots; ++s) {
                if (Effect *e = racks[r].currentEffect(s)) e->setKey(keyFor(e->sidechainRack(), r));
            }
            const auto tRack = std::chrono::steady_clock::now();
            if (racks[r].frozenActive()) {
                racks[r].syncFrozen(scheduler.rackTick(r), clock.bpm());
            } else {
                racks[r].onBlock(clock.blockStart(), clock.blockEnd(), clock.bpm());
            }
            racks[r].render(kBlockFrames);
            const auto rackUs = static_cast<int32_t>(std::chrono::duration_cast<std::chrono::microseconds>(
                                                         std::chrono::steady_clock::now() - tRack)
                                                         .count());
            // Held, not published: whether this is a cost or an interruption
            // is not known until the whole block has been timed in both
            // clocks, and the answer is the same for every rack in it.
            rackUsThisBlock[r] = rackUs;
            rackFrozenThisBlock[r] = racks[r].frozenActive();
        }
    }

    const auto tRacks = std::chrono::steady_clock::now();

    // The same tick range the racks hand their own inserts, so a tempo-synced
    // effect behaves the same whether it is on a track or on a send.
    // The sends listen too: every rack has rendered by now, so theirs is
    // always this block's.
    for (int32_t s = 0; s < kSendSlots; ++s) {
        if (Effect *e = master.send(s)) e->setKey(keyFor(e->sidechainRack(), -1));
    }
    for (int32_t s = 0; s < kMasterInsertSlots; ++s) {
        if (Effect *e = master.insert(s)) e->setKey(keyFor(e->sidechainRack(), -1));
    }
    for (int32_t g = 0; g < kGroupSlots; ++g) {
        for (int32_t s = 0; s < kGroupInsertSlots; ++s) {
            if (Effect *e = master.groupInsert(g, s)) e->setKey(keyFor(e->sidechainRack(), -1));
        }
    }
    // Where this block began, to a fraction of a tick: the tick at the
    // block's end, less the frames between the block's start and that tick.
    master.perform.setTransport(
        playing, static_cast<double>(clock.blockEnd()) -
                     clock.frameOffsetOfTick(clock.blockEnd(), kBlockFrames) / clock.samplesPerTickNow());
    master.process(racks, kRackCount, out, kBlockFrames, clock.bpm(), fade, clock.blockStart(), clock.blockEnd());

    const auto tMaster = std::chrono::steady_clock::now();

    // Monitoring is after the master so it is heard at the master's level,
    // and deliberately not recorded when capturing the input: nobody wants
    // their own monitor path printed into the sample.
    const InputBus &bus = InputBus::get();
    if (capture.armed()) {
        const int64_t framesBefore = capture.pushed();
        if (capture.source() != Capture::FromInput) {
            capture.push(out, kBlockFrames);
        } else if (bus.live()) {
            capture.push(bus.block(), kBlockFrames);
        } else {
            // **Silence, and say so.** This used to fall through to `out`,
            // so a capture armed for the microphone with no input stream open
            // recorded the speakers instead - which is not a near miss, it is
            // the one recording nobody wanted, and it arrived named as the
            // one they asked for. The screen reads `deaf()` and can tell them.
            capture.pushSilence(kBlockFrames);
        }
        // **Where the song was, stamped as the frames go in.**
        //
        // After the push, not before it: a mark names the frame the cell
        // *starts* at, and that is the ring's index once this block has been
        // accepted into it minus this block - which is to say the index as it
        // was. Taken before the push it would be right; taken after, it would
        // be one block late on every boundary. So the count is read first.
        //
        // The ring dropping anything ends the matter: every frame index after
        // a drop names the wrong moment in the song, and a split built on them
        // would put somebody's second verse under their first.
        // **Not while counting in, and not while stopped.** The scheduler is
        // deliberately idle through a count-in, so its tick stands still - a
        // mark taken there would say the cell begins at the first click and
        // put four beats of nothing at the top of somebody's vocal. It is the
        // same trap the performance lanes hit, in a different recorder.
        const int32_t armed = armedRack.load(std::memory_order_relaxed);
        if (playing && !counting && armed >= 0 && armed < kRackCount) {
            if (capture.overflowed()) marks.poison();
            marks.observe(framesBefore, scheduler.rackSceneId(armed),
                          scheduler.rackCycleTick(armed), scheduler.rackCycleTicks(armed),
                          clock.bpm());
        }
    }
    // After the capture, like the monitor and for the same reason: hearing
    // what a file is should not print it into the take being recorded.
    audition.mix(out, kBlockFrames);

    const float monitor = monitorLevel.load(std::memory_order_relaxed);
    if (monitor > 0.0001f && bus.live()) {
        const float *src = bus.block();
        for (int32_t i = 0; i < kBlockFrames * 2; ++i) out[i] += src[i] * monitor;
    }

    transport.publishPosition(scheduler.packedPosition());
    framesRendered += kBlockFrames;

    // Block budget at 48 kHz / 64 frames is 1333 us.
    const auto tEnd = std::chrono::steady_clock::now();
    const auto us = std::chrono::duration_cast<std::chrono::microseconds>(tEnd - t0).count();
    const float pct = static_cast<float>(us) / 1333.3f * 100.0f;
    load.store(load.load(std::memory_order_relaxed) * 0.95f + pct * 0.05f, std::memory_order_relaxed);

    // And the same span again, kept rather than averaged. The EMA above has a
    // 27 ms memory and is read every 80 ms, so the block that caused a dropout
    // has decayed out of it before anybody looks; this is the one that answers
    // "how bad did it get".
    // **Was this block interrupted, or was it slow?**
    //
    // Every span above is wall time, and a thread that is taken off its core
    // mid-block hands the whole of that absence to whatever it happened to be
    // measuring. That is not a hypothetical: the per-track list reported
    // `Pad 0.92` for a rack that was playing frozen audio, which costs 1.5 us
    // measured off-device, and the same readout claimed 19.91 ms in the
    // sequencer - a phase that does bookkeeping and nothing else. Both were
    // the same 20 ms of being descheduled, billed to whoever held the clock.
    //
    // Comparing the block's two clocks catches it for two reads rather than
    // the twenty-two it would take to time every rack on the CPU clock - and
    // `CLOCK_THREAD_CPUTIME_ID` is a real syscall on this platform, not a vDSO
    // call, so twenty-two of them a block is not a diagnostic, it is a cost.
    // If the block as a whole ran uninterrupted then nothing inside it was
    // interrupted either, and every span in it can be believed.
    const int64_t cpuUs = threadCpuUs() - cpu0;
    const bool interrupted = us - cpuUs > kPreemptedUs;
    // An EMA of how often that happens, because a per-track list that is never
    // updated looks the same as one with nothing to say. This is the per-block
    // twin of the driver's stall counter, and it is the number that says
    // whether to optimise the DSP or go after the scheduler.
    const float wasInterrupted = interrupted ? 100.0f : 0.0f;
    interruptedPct.store(interruptedPct.load(std::memory_order_relaxed) * 0.99f + wasInterrupted * 0.01f,
                         std::memory_order_relaxed);
    if (interrupted) return;

    // The cost, not the elapsed time. They are the same on a clean block, and
    // this is only ever reached on a clean block.
    keepPeak(blockPeak, static_cast<int32_t>(cpuUs));
    const auto span = [](auto a, auto b) {
        return static_cast<int32_t>(std::chrono::duration_cast<std::chrono::microseconds>(b - a).count());
    };
    keepPeak(phasePeak[static_cast<size_t>(Phase::Input)], span(t0, tInput));
    keepPeak(phasePeak[static_cast<size_t>(Phase::Sequencer)], span(tInput, tSeq));
    keepPeak(phasePeak[static_cast<size_t>(Phase::Racks)], span(tSeq, tRacks));
    keepPeak(phasePeak[static_cast<size_t>(Phase::Master)], span(tRacks, tMaster));
    keepPeak(phasePeak[static_cast<size_t>(Phase::Capture)], span(tMaster, tEnd));
    for (int32_t r = 0; r < kRackCount; ++r) {
        if (rackUsThisBlock[r] > rackPeak[r].load(std::memory_order_relaxed)) {
            rackPeakFrozen[r].store(rackFrozenThisBlock[r], std::memory_order_relaxed);
        }
        keepPeak(rackPeak[r], rackUsThisBlock[r]);
        keepDecaying(rackRecent[r], rackUsThisBlock[r]);
        // Only blocks where this rack did something: a track that plays in one
        // scene should report what it costs while playing.
        if (rackUsThisBlock[r] > 0) {
            std::atomic<int32_t> &bin = rackHist[r][bucketOf(rackUsThisBlock[r])];
            bin.store(bin.load(std::memory_order_relaxed) + 1, std::memory_order_relaxed);
        }
    }
}

void Engine::settleRouting() {
    // `output` 1..4 is a group in the mixer; anything else is the master.
    for (int32_t r = 0; r < kRackCount; ++r) {
        Rack &rack = racks[r];
        const int32_t g = rack.outputRequested();
        rack.routedTo = rack.isActive() && g >= 0 && g < kGroupSlots ? g : -1;
    }
}

const float *Engine::keyFor(int32_t source, int32_t self) const {
    static const float kSilence[kBlockFrames] = {};
    if (source < 0 || source >= kRackCount || source == self) return nullptr; // its own input
    return racks[source].isActive() ? racks[source].keyBuf : kSilence;
}

void Engine::sidechainOrder(int32_t *order) const {
    bool placed[kRackCount] = {};
    const auto ready = [&](int32_t r) {
        for (int32_t s = 0; s < kEffectSlots; ++s) {
            const Effect *e = racks[r].currentEffect(s);
            if (e == nullptr) continue;
            const int32_t src = e->sidechainRack();
            if (src >= 0 && src < kRackCount && src != r && racks[src].isActive() && !placed[src]) return false;
        }
        return true;
    };
    int32_t n = 0;
    while (n < kRackCount) {
        bool progressed = false;
        for (int32_t r = 0; r < kRackCount; ++r) {
            if (placed[r] || !ready(r)) continue;
            placed[r] = true;
            order[n++] = r;
            progressed = true;
        }
        if (progressed) continue;
        // A loop: take the lowest rack still waiting and let it go first.
        for (int32_t r = 0; r < kRackCount; ++r) {
            if (placed[r]) continue;
            placed[r] = true;
            order[n++] = r;
            break;
        }
    }
}

int32_t Engine::rackPercentileUs(int32_t rack, int32_t perMille) const {
    if (rack < 0 || rack >= kRackCount) return 0;
    int64_t total = 0;
    int32_t counts[kCostBuckets];
    for (int32_t i = 0; i < kCostBuckets; ++i) {
        counts[i] = rackHist[rack][i].load(std::memory_order_relaxed);
        total += counts[i];
    }
    if (total == 0) return 0;
    // From the top down: the bucket the tail reaches into. `perMille` of 990
    // means the worst one block in a hundred, which needs a hundred blocks to
    // exist at all - a tenth of a second - before it means anything.
    const int64_t want = total - total * perMille / 1000;
    int64_t seen = 0;
    for (int32_t i = kCostBuckets - 1; i >= 0; --i) {
        seen += counts[i];
        if (seen > want) return usOf(i);
    }
    return 0;
}

void Engine::resetRackCosts() {
    for (int32_t r = 0; r < kRackCount; ++r) {
        for (int32_t i = 0; i < kCostBuckets; ++i) rackHist[r][i].store(0, std::memory_order_relaxed);
    }
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
            if (transport.followingMidi()) {
                scheduler.locateTo(0);
                transport.requestPlay(0);
            }
            break;
        case 0xfb: // continue: from wherever the locate left us
            if (transport.followingMidi()) {
                transport.requestContinue();
            }
            break;
        case 0xfc:
            if (transport.followingMidi()) {
                transport.requestStop();
            }
            break;
        case 0xf2: { // song position, in sixteenths
            const int64_t beats = static_cast<int64_t>(e.data1) | (static_cast<int64_t>(e.data2) << 7);
            const int64_t songTick = beats * (kPPQN / 4);
            follower.relocate(songTick);
            if (transport.followingMidi()) {
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
    if (!transport.followingMidi() || !follower.running()) {
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

/**
 * Following a Link session: the tempo is theirs, and the bar line is theirs.
 *
 * The shape is the MIDI follower's, one floor up. What arrives is not a
 * stream of pulses to be smoothed but a session state that is already
 * smooth - Link does that work - so there is no loop here, only the two
 * things that have to happen every block: run at their tempo, and lean
 * gently until our bar line sits on theirs.
 *
 * Phase, not position. A Link session has no idea what a song is, so there
 * is nothing to locate to; what is shared is *where in the bar* everyone is.
 * Two machines playing different songs at the same tempo are in time with
 * each other, which is the whole idea.
 */
void Engine::followTimebase() {
    Timebase *tb = timebase.load(std::memory_order_acquire);
    if (tb == nullptr || !transport.followingLink()) {
        linkWaiting = false;
        linkSeen = false;
        linkToldPlaying = false;
        linkInSession = false;
        return;
    }
    // Our bar, in beats, before anything is asked of the session: a phase is
    // only meaningful against a bar both sides agree on.
    const double barTicks = static_cast<double>(scheduler.barTicks());
    if (barTicks > 0.0) tb->setQuantum(barTicks / static_cast<double>(kPPQN));

    const Timebase::State s = tb->capture(framesRendered);
    if (!s.valid || s.bpm < 1.0) {
        linkInSession = false;
        return;
    }
    // Read every block, because the decision that needs it - whether to hold
    // play for a downbeat - is taken at the top of a block, before this runs.
    // A block old is close enough for something that changes when a machine
    // joins the network.
    linkInSession = seq::LinkFollower::waitsForDownbeat(s);

    const double perTick = clock.framesPerTickAt(s.bpm);
    const seq::LinkFollower::Advice advice = seq::LinkFollower::advise(
        s, static_cast<double>(scheduler.currentTickInIteration()), barTicks, perTick,
        playing && !linkWaiting);
    clock.setExternalFramesPerTick(advice.framesPerTick);

    // The downbeat we were waiting for.
    if (linkWaiting && advice.downbeat) {
        startPending = true;
        linkWaiting = false;
    }

    // Start and stop travel both ways, when the setting says so - and both
    // ways on the **edge**, not on the level.
    //
    // On the level it cannot work, and the way it fails is instructive: a
    // session nobody has started yet reads as stopped, so the moment we
    // press play we are told to stop, thirteen hundred times a second, and
    // the transport sits there saying it is playing while the playhead never
    // leaves the first tick. On the edge, only somebody actually pressing
    // something moves anybody.
    //
    // Waiting for the downbeat is not playing yet, so it is not announced as
    // such: a peer that followed it would start a bar before we did.
    const bool reallyPlaying = playing && !linkWaiting;
    if (!linkSeen) {
        linkSawPlaying = s.playing;
        linkToldPlaying = reallyPlaying;
        linkSeen = true;
    }
    if (syncStartStop.load(std::memory_order_relaxed)) {
        if (reallyPlaying != linkToldPlaying) {
            tb->proposePlaying(reallyPlaying);
            linkToldPlaying = reallyPlaying;
        }
        if (s.playing != linkSawPlaying) {
            if (s.playing && !playing) {
                transport.requestPlay(seq::Transport::kCurrentScene);
            } else if (!s.playing && playing) {
                transport.requestStop();
            }
        }
    } else {
        linkToldPlaying = reallyPlaying;
    }
    linkSawPlaying = s.playing;

    // The same packing the MIDI follower publishes, so one readout reads
    // both: locked, the tempo in hundredths, and the error in microseconds.
    const int32_t bpmMilli = static_cast<int32_t>(s.bpm * 100.0);
    const double errMs = advice.errorTicks * perTick * 1000.0 / static_cast<double>(clock.rate());
    const int32_t errMicro = static_cast<int32_t>(errMs * 1000.0);
    transport.publishSync((static_cast<int64_t>(1) << 56) |
                          (static_cast<int64_t>(bpmMilli & 0xffffff) << 32) |
                          (static_cast<int64_t>(errMicro) & 0xffffffffLL));
}

void Engine::drainMidi() {
    // The notes played just before the downbeat, filed now that there is a
    // scene to file them against. They land on tick zero: the player meant
    // the start of the bar, and was early by less than a thirty-second.
    if (recordingNow() && earlyCount > 0) {
        for (int32_t i = 0; i < earlyCount; ++i) {
            const EarlyNote &e = earlyNotes[i];
            seq::RecordedEvent ev;
            ev.absTick = clock.position();
            ev.sceneId = scheduler.rackSceneId(e.rack);
            ev.tickInIteration = 0;
            ev.rack = e.rack;
            ev.cmd = e.status;
            ev.p1 = e.d1;
            ev.p2 = e.d2;
            recordQueue.push(ev);
        }
        earlyCount = 0;
    }
    MidiMessage m;
    while (midiIn.pop(m)) {
        const int32_t rack = m.status & 0x0f;
        uint8_t status = m.status & 0xf0;
        uint8_t d2 = m.data2;
        if (status == 0x90 && d2 == 0) status = 0x80;
        if (!racks[rack].isActive()) continue;

        // **Poly aftertouch is a finger's pressure without MPE**: the message
        // names its note, so it needs no zone and no channel to find it. It
        // was dropped - a machine's MIDI handler had no case for it - so a
        // controller that presses per key outside MPE mode pressed nothing.
        if (status == 0xa0) {
            racks[rack].noteExpression(0xd0, m.data1, d2, 0, mpeBendSemis);
            recordExpression(rack, 0xff, m.data1, 0xd0, d2, 0);
            continue;
        }
        // A member channel is one finger. Its note goes down the ordinary
        // path, through the modifiers like any other; its bend, pressure and
        // slide belong to that note alone and go straight to the machine.
        // A controller other than slide is not a finger's, even sent on a
        // finger's channel: an MPE controller sends everything a note does
        // on that note's channel, the mod wheel and the sustain pedal too,
        // and they were dropped here. They go to the whole track, as they
        // would on the master channel.
        const bool fingerCc = status == 0xb0 && m.data1 == 74;
        // **Which note each channel is holding is kept whether or not it is a
        // finger yet.** A zone found from the fingers themselves is switched
        // on at the second one, and the first went in as an ordinary note:
        // kept only for members, its bend would have had nowhere to go until
        // it was played again.
        if (m.channel < 16) {
            if (status == 0x90 && d2 > 0) {
                mpeChannelNote[m.channel] = m.data1;
                // A new finger on the channel starts a new curve: whatever the
                // last one left behind must not be mistaken for a repeat.
                for (float &v : lastExprSent[m.channel]) v = -1.0f;
            } else if (status == 0x80 && mpeChannelNote[m.channel] == m.data1) {
                mpeChannelNote[m.channel] = -1;
            }
        }
        if (mpeMember(m.channel) && (status != 0xb0 || fingerCc)) {
            if (status != 0x90 && status != 0x80) {
                const int32_t held = mpeChannelNote[m.channel];
                // Expression for a finger that is not down has nowhere to go.
                if (held < 0) continue;
                racks[rack].noteExpression(status, static_cast<uint8_t>(held), m.data1, d2,
                                           mpeBendSemis);
                recordExpression(rack, m.channel, static_cast<uint8_t>(held), status, m.data1, d2);
                continue;
            }
        }
        // Into the modifiers, and out the far end into `onModifiedNote` -
        // which is where it is written down, if it is being written down at
        // all. Nothing is recorded here any more: what a finger sent is not
        // what the song keeps once a chord or an arp is in the way.
        racks[rack].handleMidi(status, m.data1, d2);
    }
}

/**
 * A note that has come out of a rack's modifier chain.
 *
 * On the audio thread, inside the rack's own delivery. Everything the chain
 * produces arrives here - the key itself when nothing is enabled, the three
 * notes of a chord, each step of an arpeggio - and while recording, that is
 * what goes into the clip.
 */
void Engine::onModifiedNote(int32_t rack, uint8_t status, uint8_t d1, uint8_t d2) {
    if (rack < 0 || rack >= kRackCount) return;
    // Played just before the downbeat, with the scheduler not yet running:
    // hold it rather than lose it. Flushed at the top of the first block that
    // is actually recording, where the scene is known.
    if (!recordingNow()) {
        if (countInPreRoll() && earlyCount < kMaxEarlyNotes) {
            earlyNotes[earlyCount++] = {rack, status, d1, d2};
        }
        return;
    }
    seq::RecordedEvent ev;
    ev.absTick = clock.position();
    // The rack's own clip, not the scheduler's: in clip mode a take recorded
    // onto a launched clip must land in *that* cell.
    ev.sceneId = scheduler.rackSceneId(rack);
    ev.tickInIteration = scheduler.rackTick(rack);
    ev.rack = rack;
    ev.cmd = status;
    ev.p1 = d1;
    ev.p2 = d2;
    recordQueue.push(ev);
}

/**
 * A finger's bend, press or slide, on its way to the clip the note is being
 * recorded into.
 *
 * Filed against the note and not the channel, because the channel is an
 * accident of the controller and the note is the music. The value is
 * normalised the way the document stores it - bend in semitones scaled to
 * MPE's own maximum, and not the fourteen bits that arrived - so a take
 * recorded with a 24-semitone controller plays back as the notes it was
 * played as, on any desk.
 */
void Engine::recordExpression(int32_t rack, uint8_t channel, uint8_t note, uint8_t status, uint8_t d1,
                              uint8_t d2) {
    if (!recordingNow()) return;
    Expr kind;
    float value;
    switch (status) {
    case 0xe0: {
        const float bend14 = static_cast<float>((d2 << 7) | d1) - 8192.0f;
        kind = Expr::Bend;
        value = exprBendTo01(bend14 / 8192.0f * mpeBendSemis);
        break;
    }
    case 0xd0:
        kind = Expr::Pressure;
        value = expr7To01(d1);
        break;
    case 0xb0:
        if (d1 != 74) return; // the only CC that is slide; the rest are not a note's
        kind = Expr::Timbre;
        value = expr7To01(d2);
        break;
    default: return;
    }
    // A finger that is holding still sends the same value over and over -
    // a seven-bit pressure especially, where a whole gesture is 127 distinct
    // values and thousands of messages. Dropping the repeats here rather
    // than at the far end is what keeps five fingers inside the queue.
    if (channel < 16) {
        float &last = lastExprSent[channel][static_cast<int32_t>(kind)];
        if (last == value) return;
        last = value;
    }
    seq::RecordedEvent ev;
    ev.absTick = clock.position();
    ev.sceneId = scheduler.rackSceneId(rack);
    ev.tickInIteration = scheduler.rackTick(rack);
    ev.rack = rack;
    ev.cmd = seq::kRecNoteExpression;
    ev.p1 = note;
    ev.p2 = static_cast<uint8_t>(kind);
    ev.value = value;
    recordQueue.push(ev);
}

void Engine::drainParams() {
    ParamMessage p;
    while (paramsIn.pop(p)) {
        if (p.unit == Unit::Master) {
            master.params().set(p.index, p.value);
        } else if (p.unit >= Unit::Group1Fx1 && p.unit <= Unit::Group4Fx2) {
            const int32_t k = static_cast<int32_t>(p.unit) - static_cast<int32_t>(Unit::Group1Fx1);
            Effect *fx = master.groupInsert(k / kGroupInsertSlots, k % kGroupInsertSlots);
            if (fx != nullptr) {
                if (p.index == kEffectBypassIndex) fx->setBypass(p.value >= 0.5f);
                else fx->params().set(p.index, p.value);
            }
        } else if (p.unit == Unit::MasterFx1 || p.unit == Unit::MasterFx2) {
            Effect *fx = master.insert(p.unit == Unit::MasterFx1 ? 0 : 1);
            if (fx != nullptr) {
                if (p.index == kEffectBypassIndex) fx->setBypass(p.value >= 0.5f);
                else fx->params().set(p.index, p.value);
            }
        } else if (p.unit == Unit::Send1 || p.unit == Unit::Send2) {
            Effect *fx = master.send(p.unit == Unit::Send1 ? 0 : 1);
            if (fx != nullptr) {
                if (p.index == kEffectBypassIndex) fx->setBypass(p.value >= 0.5f);
                else fx->params().set(p.index, p.value);
            }
        } else if (p.unit == Unit::Input1 || p.unit == Unit::Input2) {
            Effect *fx = inputFx[p.unit == Unit::Input1 ? 0 : 1];
            if (fx != nullptr) {
                if (p.index == kEffectBypassIndex) fx->setBypass(p.value >= 0.5f);
                else fx->params().set(p.index, p.value);
            }
        } else if (p.rack >= 0 && p.rack < kRackCount) {
            if (p.quantise > 0 && playing) {
                // Parked until the rack's next line: a newer one replaces it.
                const int64_t q = p.quantise;
                const int64_t into = scheduler.rackTick(p.rack) % q;
                PendingParam &waiting = pendingParams[p.rack];
                waiting.message = p;
                waiting.message.quantise = 0;
                waiting.due = clock.position() + (into == 0 ? 0 : q - into);
                waiting.waiting = true;
            } else {
                applyRackParam(p);
            }
        }
    }
    // And whatever has reached its line.
    for (int32_t r = 0; r < kRackCount; ++r) {
        PendingParam &waiting = pendingParams[r];
        if (waiting.waiting && clock.position() >= waiting.due) {
            waiting.waiting = false;
            applyRackParam(waiting.message);
        }
    }
}

void Engine::applyRackParam(const ParamMessage &p) {
    racks[p.rack].setParam(p.unit, p.index, p.value);
    if (p.record && recordingNow()) {
        racks[p.rack].touch(p.unit, p.index);
        seq::RecordedEvent ev;
        ev.absTick = clock.position();
        ev.sceneId = scheduler.rackSceneId(p.rack);
        ev.tickInIteration = scheduler.rackTick(p.rack);
        ev.rack = p.rack;
        ev.cmd = seq::kRecParam;
        ev.p1 = static_cast<uint8_t>(p.unit);
        ev.p2 = 0;
        ev.paramIndex = p.index;
        ev.value = p.value;
        recordQueue.push(ev);
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
    case Mount::Kind::Input:
        if (m.slot >= 0 && m.slot < kInputSlots) {
            Effect *wasThere = inputFx[m.slot];
            inputFx[m.slot] = static_cast<Effect *>(m.object);
            retirer.retire(wasThere, deleteAs<Effect>);
        } else {
            retirer.retire(m.object, deleteAs<Effect>);
        }
        break;
    case Mount::Kind::GroupInsert:
        // The rack field carries the group.
        retirer.retire(master.swapGroupInsert(m.rack, m.slot, static_cast<Effect *>(m.object)), deleteAs<Effect>);
        break;
    case Mount::Kind::MasterInsert:
        retirer.retire(master.swapInsert(m.slot, static_cast<Effect *>(m.object)), deleteAs<Effect>);
        break;
    case Mount::Kind::Send:
        // Slot-checked inside swapSend, which hands back whatever it displaced
        // - or the new one straight back if the slot was not a slot.
        retirer.retire(master.swapSend(m.slot, static_cast<Effect *>(m.object)), deleteAs<Effect>);
        break;
    case Mount::Kind::InputMod:
        if (m.rack >= 0 && m.rack < kRackCount) {
            retirer.retire(racks[m.rack].swapInputMod(m.slot, static_cast<InputMod *>(m.object)), deleteAs<InputMod>);
        } else {
            retirer.retire(m.object, deleteAs<InputMod>);
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
