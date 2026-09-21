#include "Bias.h"
#include <cmath>
#include <engine/core/Constants.h>
#include <engine/dsp/Math.h>

namespace acidulous::machine {

const ParamDef *Bias::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        // The four lanes. A level and a mute apiece, and both are parameters
        // rather than fields on the recording - which is what puts them in the
        // automation lane list, under a mapped pad, and into a recording pass,
        // without any of that being written here.
        {"lane1", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"lane2", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"lane3", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"lane4", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"mute1", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        {"mute2", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        {"mute3", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        {"mute4", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
        // Off by default: a take plays at the speed it was recorded at, which
        // is what somebody expects of a recording until they ask otherwise.
        {"stretch", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        // The medium. Every default is "nothing at all", so a fresh track
        // plays a file back untouched and the Init patch is what the machine
        // already is rather than a setting that undoes something.
        {"hiss", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"hisstone", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"lowcut", 20.0f, 800.0f, 20.0f, Curve::Exponential, 0, "Hz"},
        {"highcut", 1000.0f, 20000.0f, 20000.0f, Curve::Exponential, 0, "Hz"},
        {"bump", -6.0f, 12.0f, 0.0f, Curve::Linear, 0, "dB"},
        {"bumpfreq", 30.0f, 200.0f, 90.0f, Curve::Exponential, 0, "Hz"},
        {"sat", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"comp", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"wow", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"flutter", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"speed", 0.25f, 4.0f, 1.0f, Curve::Exponential, 0, ""},
        {"bleed", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"drop", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"bits", 4.0f, 24.0f, 24.0f, Curve::Stepped, 21, ""},
        {"rate", 0.05f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"smear", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"width", 0.0f, 2.0f, 1.0f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

void Bias::prepare(int32_t rate) {
    sampleRate = static_cast<float>(rate);
    colour.prepare(sampleRate);
    for (auto &w : stretcher) w.prepare();
    // A shelf that cuts everything below rather than a corner: what crosses
    // between two strips of one tape thins out, it does not stop.
    bleedHp[0].lowShelf(300.0f, -18.0f, sampleRate);
    bleedHp[1].lowShelf(300.0f, -18.0f, sampleRate);
    reset();
}

/**
 * The medium, read off the knobs once a block.
 *
 * Smoothed values throughout: everything here either multiplies a sample - in
 * which case a stepped value clicks - or sets a coefficient that is only
 * recomputed when it has actually moved.
 */
bias::ColourSpec Bias::colourOf() {
    bias::ColourSpec s;
    s.hiss = paramOfIndex(Hiss);
    s.hissTone = paramOfIndex(HissTone);
    s.lowCut = paramOfIndex(LowCut);
    s.highCut = paramOfIndex(HighCut);
    s.bump = paramOfIndex(Bump);
    s.bumpFreq = paramOfIndex(BumpFreq);
    s.sat = paramOfIndex(Sat);
    s.comp = paramOfIndex(Comp);
    s.wow = paramOfIndex(Wow);
    s.flutter = paramOfIndex(Flutter);
    s.speed = paramOfIndex(Speed);
    s.drop = paramOfIndex(Drop);
    s.bits = paramOfIndex(Bits);
    s.rate = paramOfIndex(Rate);
    s.smear = paramOfIndex(Smear);
    s.width = paramOfIndex(Width);
    // **Nothing at all to do is a state worth having.** Init is the default
    // patch and the one the sample-identical stem export proves, so the whole
    // chain is skipped rather than run with every control at its neutral
    // value - which would still cost two biquads a sample and, worse, would
    // still round.
    s.any = s.hiss > 0.0f || s.lowCut > 21.0f || s.highCut < 19500.0f ||
            s.bump != 0.0f || s.sat > 0.0f || s.comp > 0.0f || s.wow > 0.0f ||
            s.flutter > 0.0f || s.drop > 0.0f || s.bits < 23.5f || s.rate < 0.999f ||
            s.smear > 0.0f || std::fabs(s.width - 1.0f) > 0.001f;
    return s;
}

void Bias::reset() {
    colour.reset();
    bleedHp[0].reset();
    bleedHp[1].reset();
    for (auto &c : cursors) c.invalidate();
    for (auto &w : stretcher) w.reset();
    lastCycleTick = -1;
    cell = nullptr;
    cycleTick = 0;
    playing = false;
    muted = false;
    params_.jumpAll();
}

void *Bias::swapObject(int32_t slot, void *object) {
    if (slot != 0) return object; // nothing else is mounted here; retire it
    auto *old = const_cast<audio::Reel *>(reel);
    reel = static_cast<const audio::Reel *>(object);
    // The cell is a pointer *into* the reel that just went away.
    cell = nullptr;
    for (auto &c : cursors) c.invalidate();
    return old;
}

void Bias::onScene(int64_t sceneId, int64_t tick, bool isPlaying, bool clipMuted) {
    playing = isPlaying;
    muted = clipMuted;
    // **A cycle that has come round is the only place a stretcher may be
    // moved.** Seeking one mid-phrase throws away the overlap it is in the
    // middle of, which is a click; free-running between cycles is safe because
    // the clock is exact, so the musical position cannot drift even though
    // which samples are copied moves about within a few milliseconds.
    const bool wrapped = tick < lastCycleTick;
    lastCycleTick = tick;
    cycleTick = tick;
    const audio::Reel::Cell *want = (reel != nullptr) ? reel->find(sceneId) : nullptr;
    if (want == cell) {
        if (wrapped) reseed = true;
        return;
    }
    reseed = true;
    // Crossing into another cell: every lane starts again from where the new
    // cell says, rather than from where the last one had got to.
    cell = want;
    for (auto &c : cursors) c.invalidate();
}

bool Bias::render(float *L, float *R, int32_t frames) {
    params_.tick();
    for (int32_t i = 0; i < frames; ++i) L[i] = R[i] = 0.0f;
    const bias::ColourSpec spec = colourOf();
    colour.setBlock(spec);
    if (cell == nullptr || !playing || muted) {
        // A muted or empty cell still runs the medium, because a tape with
        // nothing on it is not silent - it is hiss. Skipped entirely when
        // there is no medium, which is the default.
        if (spec.any) colour.process(L, R, frames);
        return true;
    }

    const float gain = dsp::dbToGain(paramOfIndex(Gain));
    // **Crosstalk is why a lane is rendered even when it is muted.** The
    // signal is on the tape whatever the monitor is doing, so it is also
    // faintly on the head next door - which is the reason muting a track on a
    // four-track never quite silences it, and the reason this is a parameter
    // of the medium rather than a fault. Nothing extra is rendered when the
    // patch has no bleed in it, which is every patch but two.
    const float bleed = paramOfIndex(Bleed);
    const bool bleeding = bleed > 0.001f;
    const bool stretching = steppedTargetOf(Stretch) >= 1;
    float bleedL[kBlockFrames] = {0.0f};
    float bleedR[kBlockFrames] = {0.0f};

    for (int32_t lane = 0; lane < audio::kReelLanes; ++lane) {
        if (!cell->has(lane)) continue;
        const bool laneMuted = steppedTargetOf(Mute1 + lane) >= 1;
        if (laneMuted && !bleeding) continue;
        const float raw = paramOfIndex(Lane1 + lane) * gain;
        const float level = laneMuted ? 0.0f : raw;
        if (level <= 0.0f && !bleeding) continue;

        const audio::Reel::Region &r = cell->lanes[lane];
        const audio::Reel::Source &src = *r.source;
        const int64_t into = cycleTick - r.startTick;
        if (into < 0) continue; // a punch-in the song has not reached yet

        // **The take follows the song, or the song leaves it behind.**
        //
        // Off, this is what M54 shipped: the entry lands on the bar and the
        // take runs at the speed it was recorded at, so at another tempo it
        // drifts and the cell says so in amber. On, the ratio between the two
        // tempos is a rate, and `dsp::Wsola` reads the take at that rate
        // without moving its pitch - so the take lasts exactly as long as the
        // cell and sings the same notes.
        if (stretching && r.bpm > 1.0f && std::fabs(songBpm / r.bpm - 1.0f) > 0.002f) {
            const float rate = songBpm / r.bpm;
            // Seeded at the top of the cycle and free-running after it; the
            // clock inside the stretcher is exact, so nothing drifts.
            if (reseed) {
                // Where in the take the cycle begins, at the *song's* tempo,
                // because that is the clock the cell is measured in now.
                const double perSongTick =
                    static_cast<double>(sampleRate) * 60.0 / (static_cast<double>(songBpm) * kPPQN);
                const auto out = static_cast<int64_t>(static_cast<double>(into) * perSongTick);
                stretcher[lane].seek(r.offset + static_cast<int64_t>(out * rate));
            }
            float taken[kBlockFrames] = {0.0f};
            const int32_t got = stretcher[lane].fill(
                taken, frames, src.lp, r.offset,
                r.offset + (r.frames < src.frames - r.offset ? r.frames : src.frames - r.offset),
                rate);
            for (int32_t i = 0; i < got; ++i) {
                const float v = taken[i];
                L[i] += v * level;
                R[i] += v * level;
                if (bleeding) {
                    bleedL[i] += v * raw;
                    bleedR[i] += v * raw;
                }
            }
            continue;
        }

        // Where in the region this block begins, in **real time since the
        // entry** - which is what not stretching means.
        //
        // **This used to use the take's own tempo and it juddered.** The
        // anchor said "at song tick T you are at T times the take's frames a
        // tick", while the cursor between anchors advances one frame per
        // frame; at any other tempo those two disagree by the ratio, the drift
        // passes `FrameCursor`'s 256 frames every couple of ticks, and the
        // read position is yanked backwards over and over. The amber warning
        // on the cell promised a drift and what it delivered was a stutter.
        // The song's tempo is the right one here precisely *because* the audio
        // does not stretch: seconds since the entry is the whole of it, and
        // the take's own tempo now decides one thing only, which is the
        // stretch ratio above.
        const double perTick =
            static_cast<double>(sampleRate) * 60.0 / (static_cast<double>(songBpm) * kPPQN);
        const int64_t want = static_cast<int64_t>(static_cast<double>(into) * perTick);
        if (!r.loop && want >= r.frames) continue; // past the end: silent, not wrapped
        cursors[lane].anchor(r.loop && r.frames > 0 ? want % r.frames : want);

        int64_t at = cursors[lane].at;
        for (int32_t i = 0; i < frames; ++i) {
            if (at >= r.frames) {
                if (!r.loop) break;
                at = 0;
            }
            const int64_t s = r.offset + at;
            if (s >= 0 && s < src.frames) {
                // Through the pointers, so a take held in memory and a take
                // mapped from a cache file are the same two lines here.
                const float l = static_cast<float>(src.lp[static_cast<size_t>(s)]) * (1.0f / 32768.0f);
                const float rr = src.stereo
                                     ? static_cast<float>(src.rp[static_cast<size_t>(s)]) * (1.0f / 32768.0f)
                                     : l;
                L[i] += l * level;
                R[i] += rr * level;
                if (bleeding) {
                    bleedL[i] += l * raw;
                    bleedR[i] += rr * raw;
                }
            }
            ++at;
        }
        cursors[lane].at = at;
    }

    if (bleeding) {
        // Thin, because what crosses between two strips of one tape is the
        // top and not the bottom: a bleed with the bass in it is a second
        // copy of the track, not a ghost of it.
        const float amount = bleed * bleed * 0.25f;
        for (int32_t i = 0; i < frames; ++i) {
            L[i] += bleedHp[0].process(bleedL[i]) * amount;
            R[i] += bleedHp[1].process(bleedR[i]) * amount;
        }
    }

    if (spec.any) colour.process(L, R, frames);
    reseed = false;
    return true; // always stereo: four lanes may disagree about it
}

} // namespace acidulous::machine
