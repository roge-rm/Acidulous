#include "Tape.h"
#include <cmath>
#include <engine/core/Constants.h>
#include <engine/dsp/Math.h>

namespace acidulous::machine {

const ParamDef *Tape::paramDefs(int32_t &count) const {
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
    };
    count = Count;
    return defs;
}

void Tape::prepare(int32_t rate) {
    sampleRate = static_cast<float>(rate);
    reset();
}

void Tape::reset() {
    for (auto &c : cursors) c.invalidate();
    cell = nullptr;
    cycleTick = 0;
    playing = false;
    muted = false;
    params_.jumpAll();
}

void *Tape::swapObject(int32_t slot, void *object) {
    if (slot != 0) return object; // nothing else is mounted here; retire it
    auto *old = const_cast<audio::Reel *>(reel);
    reel = static_cast<const audio::Reel *>(object);
    // The cell is a pointer *into* the reel that just went away.
    cell = nullptr;
    for (auto &c : cursors) c.invalidate();
    return old;
}

void Tape::onScene(int64_t sceneId, int64_t tick, bool isPlaying, bool clipMuted) {
    playing = isPlaying;
    muted = clipMuted;
    cycleTick = tick;
    const audio::Reel::Cell *want = (reel != nullptr) ? reel->find(sceneId) : nullptr;
    if (want == cell) return;
    // Crossing into another cell: every lane starts again from where the new
    // cell says, rather than from where the last one had got to.
    cell = want;
    for (auto &c : cursors) c.invalidate();
}

bool Tape::render(float *L, float *R, int32_t frames) {
    params_.tick();
    for (int32_t i = 0; i < frames; ++i) L[i] = R[i] = 0.0f;
    if (cell == nullptr || !playing || muted) return true;

    const float gain = dsp::dbToGain(paramOfIndex(Gain));
    bool any = false;

    for (int32_t lane = 0; lane < audio::kReelLanes; ++lane) {
        if (!cell->has(lane)) continue;
        if (steppedTargetOf(Mute1 + lane) >= 1) continue;
        const float level = paramOfIndex(Lane1 + lane) * gain;
        if (level <= 0.0f) continue;

        const audio::Reel::Region &r = cell->lanes[lane];
        // Where in the region this block begins. The region's own tempo, not
        // the song's: audio does not stretch, so what the tempo decides is
        // where the *entry* falls and nothing about the rate.
        const double perTick =
            static_cast<double>(sampleRate) * 60.0 / (static_cast<double>(r.bpm) * kPPQN);
        const int64_t into = cycleTick - r.startTick;
        if (into < 0) continue; // a punch-in the song has not reached yet
        const int64_t want = static_cast<int64_t>(static_cast<double>(into) * perTick);
        if (!r.loop && want >= r.frames) continue; // past the end: silent, not wrapped
        cursors[lane].anchor(r.loop && r.frames > 0 ? want % r.frames : want);

        const audio::Reel::Source &src = *r.source;
        int64_t at = cursors[lane].at;
        for (int32_t i = 0; i < frames; ++i) {
            if (at >= r.frames) {
                if (!r.loop) break;
                at = 0;
            }
            const int64_t s = r.offset + at;
            if (s >= 0 && s < src.frames) {
                const float l = static_cast<float>(src.left[static_cast<size_t>(s)]) * (1.0f / 32768.0f);
                const float rr = src.stereo
                                     ? static_cast<float>(src.right[static_cast<size_t>(s)]) * (1.0f / 32768.0f)
                                     : l;
                L[i] += l * level;
                R[i] += rr * level;
            }
            ++at;
        }
        cursors[lane].at = at;
        any = true;
    }
    (void)any;
    return true; // always stereo: four lanes may disagree about it
}

} // namespace acidulous::machine
