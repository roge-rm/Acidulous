#include "InputMods.h"
#include <cstring>
#include <initializer_list>

namespace acidulous::modifier {

using namespace acidulous::music;

namespace {
inline uint8_t clampNote(int n) { return static_cast<uint8_t>(n < 0 ? 0 : (n > 127 ? 127 : n)); }
inline uint8_t clampVel(float v) { return static_cast<uint8_t>(v < 1.0f ? 1 : (v > 127.0f ? 127 : static_cast<int>(v + 0.5f))); }
inline int stepOf(const ParamSet &p, int index) { return static_cast<int>(p.get(index) + 0.5f); }
inline int stepOfSigned(const ParamSet &p, int index) { const float v = p.get(index); return static_cast<int>(v < 0 ? v - 0.5f : v + 0.5f); }
constexpr float kTicksPerQuarter = 240.0f;
} // namespace

// --- Scale --------------------------------------------------------------------------

const ParamDef *Scale::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"key", 0.0f, 11.0f, 0.0f, Curve::Stepped, 12, ""},
        {"scale", 0.0f, static_cast<float>(kScaleCount - 1), 0.0f, Curve::Stepped, kScaleCount, ""},
        {"mode", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},   // snap, degree
        {"snap", 0.0f, 2.0f, 0.0f, Curve::Stepped, 3, ""},   // nearest, down, up
        {"transpose", -12.0f, 12.0f, 0.0f, Curve::Stepped, 25, "st"},
        {"octave", -2.0f, 2.0f, 0.0f, Curve::Stepped, 5, ""},
    };
    count = Count;
    return defs;
}

void Scale::reset() { for (auto &o : outOf) o = -1; outs = OutputNotes(); }

int Scale::map(int note) const {
    const auto &p = params_;
    const int key = stepOf(p, Key);
    const ScaleDef &s = kScales[stepOf(p, ScaleType)];
    int pitch;
    if (stepOf(p, Mode) == 1) {
        // Degree mode: every key is a scale step, counted from C4 = the root.
        const int d = note - 60;
        const int root = 60 + key - (key > 6 ? 12 : 0);
        pitch = root + degreeInterval(s, d);
    } else {
        const int rel = note - key;
        const int pc = floorMod(rel, 12), oct = floorDiv(rel, 12);
        int chosen = 0;
        switch (stepOf(p, Snap)) {
        case 1: chosen = s.intervals[degreeAtOrBelow(s, pc)]; break;
        case 2: {
            chosen = 12; // the next root if nothing in this octave is at or above
            for (int i = s.count - 1; i >= 0; --i) if (s.intervals[i] >= pc) chosen = s.intervals[i];
            break;
        }
        default: {
            int best = 99;
            for (int i = 0; i < s.count; ++i) {
                for (int cand : {static_cast<int>(s.intervals[i]), static_cast<int>(s.intervals[i]) + 12, static_cast<int>(s.intervals[i]) - 12}) {
                    const int dist = cand > pc ? cand - pc : pc - cand;
                    if (dist < best || (dist == best && cand < chosen)) { best = dist; chosen = cand; }
                }
            }
            break;
        }
        }
        pitch = key + oct * 12 + chosen;
    }
    return clampNote(pitch + stepOfSigned(p, Transpose) + 12 * stepOfSigned(p, Octave));
}

void Scale::handleMidi(uint8_t status, uint8_t d1, uint8_t d2, MidiSink &out) {
    const uint8_t kind = status & 0xf0;
    if (kind == 0x90 && d2 != 0) {
        if (outOf[d1] >= 0) outs.off(static_cast<uint8_t>(outOf[d1]), out);
        const int pitch = map(d1);
        outOf[d1] = static_cast<int16_t>(pitch);
        outs.on(static_cast<uint8_t>(pitch), d2, out);
    } else if (kind == 0x80 || kind == 0x90) {
        if (outOf[d1] >= 0) { outs.off(static_cast<uint8_t>(outOf[d1]), out); outOf[d1] = -1; }
    } else {
        out.send(status, d1, d2);
    }
}

void Scale::allNotesOff(MidiSink &out) { outs.allOff(out); for (auto &o : outOf) o = -1; }

// --- Chord --------------------------------------------------------------------------

const ParamDef *Chord::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        {"mode", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},    // fixed, diatonic
        {"type", 0.0f, static_cast<float>(kChordCount - 1), 0.0f, Curve::Stepped, kChordCount, ""},
        {"key", 0.0f, 11.0f, 0.0f, Curve::Stepped, 12, ""},
        {"scale", 0.0f, static_cast<float>(kScaleCount - 1), 0.0f, Curve::Stepped, kScaleCount, ""},
        {"voicing", 0.0f, 2.0f, 0.0f, Curve::Stepped, 3, ""}, // triad, 7th, 9th
        {"inversion", 0.0f, 3.0f, 0.0f, Curve::Stepped, 4, ""},
        {"spread", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},  // drop-2
        {"bass", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},    // root an octave below
        {"strum", 0.0f, 200.0f, 0.0f, Curve::Linear, 0, "ms"},
        {"strumdir", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""}, // up, down
        {"velspread", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

void Chord::reset() {
    for (auto &v : voices) v.count = 0;
    for (auto &q : pending) q.live = false;
    outs = OutputNotes();
}

int Chord::build(int note, int *tones) const {
    const auto &p = params_;
    int n = 0;
    if (stepOf(p, Mode) == 1) {
        // Diatonic: the chord the scale gives at the played degree, thirds stacked in scale steps.
        const int key = stepOf(p, Key);
        const ScaleDef &s = kScales[stepOf(p, ScaleType)];
        const int rel = note - key;
        const int oct = floorDiv(rel, 12), deg = degreeAtOrBelow(s, floorMod(rel, 12));
        const int root = key + oct * 12;
        const int stack = 3 + stepOf(p, Voicing); // triad 3, 7th 4, 9th 5
        for (int i = 0; i < stack; ++i) tones[n++] = root + degreeInterval(s, deg + 2 * i);
    } else {
        const ChordDef &c = kChords[stepOf(p, Type)];
        for (int i = 0; i < c.count; ++i) tones[n++] = note + c.intervals[i];
    }
    // Inversion: the lowest notes go up an octave, one per step.
    const int inv = stepOf(p, Inversion);
    for (int i = 0; i < inv && i < n - 1; ++i) tones[i] += 12;
    // Drop-2: the second-highest voice down an octave for an open sound.
    if (stepOf(p, Spread) == 1 && n >= 3) {
        int hi = 0; for (int i = 1; i < n; ++i) if (tones[i] > tones[hi]) hi = i;
        int second = -1; for (int i = 0; i < n; ++i) if (i != hi && (second < 0 || tones[i] > tones[second])) second = i;
        if (second >= 0) tones[second] -= 12;
    }
    if (stepOf(p, Bass) == 1 && n < kMaxTones) tones[n++] = note - 12;
    // Sort low to high; strum order and velocity taper work on that.
    for (int i = 1; i < n; ++i) { int t = tones[i], j = i - 1; while (j >= 0 && tones[j] > t) { tones[j + 1] = tones[j]; --j; } tones[j + 1] = t; }
    return n;
}

void Chord::handleMidi(uint8_t status, uint8_t d1, uint8_t d2, MidiSink &out) {
    const uint8_t kind = status & 0xf0;
    const auto &p = params_;
    if (kind == 0x90 && d2 != 0) {
        Voice &v = voices[d1];
        for (int i = 0; i < v.count; ++i) outs.off(static_cast<uint8_t>(v.tones[i]), out);
        int tones[kMaxTones];
        const int n = build(d1, tones);
        v.count = static_cast<int8_t>(n);
        const float strumTicks = p.get(Strum) * 0.001f * bpm / 60.0f * kTicksPerQuarter;
        const bool down = stepOf(p, StrumDir) == 1;
        for (int i = 0; i < n; ++i) {
            const int order = down ? n - 1 - i : i;
            const int pitch = clampNote(tones[i]);
            v.tones[i] = static_cast<int16_t>(pitch);
            const uint8_t vel = clampVel(static_cast<float>(d2) * (1.0f - p.get(VelSpread) * (n > 1 ? static_cast<float>(i) / static_cast<float>(n - 1) : 0.0f)));
            const int64_t due = nowTick + static_cast<int64_t>(strumTicks * static_cast<float>(order) / static_cast<float>(n > 1 ? n - 1 : 1));
            if (due <= nowTick) { outs.on(static_cast<uint8_t>(pitch), vel, out); continue; }
            for (auto &q : pending) {
                if (!q.live) { q = {due, d1, static_cast<uint8_t>(pitch), vel, true}; break; }
            }
        }
    } else if (kind == 0x80 || kind == 0x90) {
        Voice &v = voices[d1];
        for (auto &q : pending) if (q.live && q.src == d1) q.live = false; // never struck: never sounds
        for (int i = 0; i < v.count; ++i) outs.off(static_cast<uint8_t>(v.tones[i]), out);
        v.count = 0;
    } else {
        out.send(status, d1, d2);
    }
}

void Chord::onBlock(int64_t tickStart, int64_t tickEnd, float bpmNow, MidiSink &out) {
    bpm = bpmNow;
    nowTick = tickStart;
    for (auto &q : pending) {
        if (q.live && q.tick < tickEnd) { q.live = false; outs.on(q.pitch, q.vel, out); }
    }
}

void Chord::allNotesOff(MidiSink &out) {
    for (auto &q : pending) q.live = false;
    for (auto &v : voices) v.count = 0;
    outs.allOff(out);
}

// --- Arp ----------------------------------------------------------------------------

namespace {
constexpr int kRateCount = 10;
constexpr int kRateTicks[kRateCount] = {960, 480, 320, 240, 160, 120, 80, 60, 40, 30}; // 1/1 .. 1/32 with triplets
enum ArpMode { Up, Down, UpDown, DownUp, UpAndDown, Converge, Diverge, Random, Walk, Played, ChordMode, Pinky, Thumb, ModeCount };
} // namespace

const ParamDef *Arp::paramDefs(int32_t &count) const {
    static ParamDef defs[Count];
    static bool built = false;
    if (!built) {
        int i = 0;
        defs[i++] = {"rate", 0.0f, static_cast<float>(kRateCount - 1), 5.0f, Curve::Stepped, kRateCount, ""};
        defs[i++] = {"gate", 5.0f, 200.0f, 60.0f, Curve::Linear, 0, "%"};
        defs[i++] = {"swing", 0.0f, 75.0f, 0.0f, Curve::Linear, 0, "%"};
        defs[i++] = {"mode", 0.0f, static_cast<float>(ModeCount - 1), 0.0f, Curve::Stepped, ModeCount, ""};
        defs[i++] = {"octaves", 1.0f, 4.0f, 1.0f, Curve::Stepped, 4, ""};
        defs[i++] = {"octmode", 0.0f, 2.0f, 0.0f, Curve::Stepped, 3, ""}; // up, down, alternate
        defs[i++] = {"length", 1.0f, 16.0f, 16.0f, Curve::Stepped, 16, ""};
        static const char *stepNames[16] = {"s01", "s02", "s03", "s04", "s05", "s06", "s07", "s08", "s09", "s10", "s11", "s12", "s13", "s14", "s15", "s16"};
        for (int s = 0; s < 16; ++s) defs[i++] = {stepNames[s], 0.0f, 1.0f, 1.0f, Curve::Stepped, 2, ""};
        defs[i++] = {"ratchet", 1.0f, 4.0f, 1.0f, Curve::Stepped, 4, ""};
        defs[i++] = {"ratchetchance", 0.0f, 100.0f, 100.0f, Curve::Linear, 0, "%"};
        defs[i++] = {"chance", 0.0f, 100.0f, 100.0f, Curve::Linear, 0, "%"};
        defs[i++] = {"velmode", 0.0f, 4.0f, 0.0f, Curve::Stepped, 5, ""}; // played, fixed, accent, ramp up, ramp down
        defs[i++] = {"accent", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""};
        defs[i++] = {"latch", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""};
        defs[i++] = {"shift", -12.0f, 12.0f, 0.0f, Curve::Stepped, 25, "st"};
        defs[i++] = {"cycles", 1.0f, 8.0f, 1.0f, Curve::Stepped, 8, ""};
        defs[i++] = {"sync", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""}; // restart, free
        defs[i++] = {"humanise", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""};
        built = true;
    }
    count = Count;
    return defs;
}

void Arp::reset() {
    heldCount = 0; latched = false; seqLen = 0; pos = 0; dir = 1; stepCounter = 0; cycle = 0; lastPitch = -1;
    nextStep = -1;
    // The three that used to be left behind. `rng` drives step chance,
    // ratchets, humanised timing and humanised velocity, so an arp carried
    // it from one playing to the next and the "same" bar came out
    // differently - which is what made an offline render of a song with an
    // arp on it unrepeatable. A reset means from the beginning.
    anchor = 0;
    orderCounter = 0;
    rng = 0x9E3779B9u;
    for (auto &q : pending) q.live = false;
    outs = OutputNotes();
}

void Arp::addHeld(uint8_t pitch, uint8_t vel) {
    for (int i = 0; i < heldCount; ++i) if (held[i].pitch == pitch) { held[i].vel = vel; return; }
    if (heldCount >= kMaxHeld) return;
    held[heldCount++] = {pitch, vel, orderCounter++};
}

void Arp::removeHeld(uint8_t pitch) {
    for (int i = 0; i < heldCount; ++i) {
        if (held[i].pitch == pitch) { for (int j = i; j < heldCount - 1; ++j) held[j] = held[j + 1]; --heldCount; return; }
    }
}

// The play order for the current held set, mode and octave settings.
void Arp::rebuild() {
    const auto &p = params_;
    Held sorted[kMaxHeld];
    for (int i = 0; i < heldCount; ++i) sorted[i] = held[i];
    const int mode = stepOf(p, Mode);
    if (mode != Played) { // by pitch
        for (int i = 1; i < heldCount; ++i) { Held h = sorted[i]; int j = i - 1; while (j >= 0 && sorted[j].pitch > h.pitch) { sorted[j + 1] = sorted[j]; --j; } sorted[j + 1] = h; }
    } else {
        for (int i = 1; i < heldCount; ++i) { Held h = sorted[i]; int j = i - 1; while (j >= 0 && sorted[j].order > h.order) { sorted[j + 1] = sorted[j]; --j; } sorted[j + 1] = h; }
    }
    const int octaves = stepOf(p, Octaves), octMode = stepOf(p, OctMode);
    int base[kMaxSeq]; uint8_t baseVel[kMaxSeq]; int n = 0;
    auto push = [&](int pitch, uint8_t vel) { if (n < kMaxSeq) { base[n] = pitch; baseVel[n] = vel; ++n; } };
    switch (mode) {
    case Down: for (int i = heldCount - 1; i >= 0; --i) push(sorted[i].pitch, sorted[i].vel); break;
    case UpDown:
        for (int i = 0; i < heldCount; ++i) push(sorted[i].pitch, sorted[i].vel);
        for (int i = heldCount - 2; i >= 1; --i) push(sorted[i].pitch, sorted[i].vel);
        break;
    case DownUp:
        for (int i = heldCount - 1; i >= 0; --i) push(sorted[i].pitch, sorted[i].vel);
        for (int i = 1; i < heldCount - 1; ++i) push(sorted[i].pitch, sorted[i].vel);
        break;
    case UpAndDown:
        for (int i = 0; i < heldCount; ++i) push(sorted[i].pitch, sorted[i].vel);
        for (int i = heldCount - 1; i >= 0; --i) push(sorted[i].pitch, sorted[i].vel);
        break;
    case Converge: { int lo = 0, hi = heldCount - 1; while (lo <= hi) { push(sorted[lo].pitch, sorted[lo].vel); if (lo != hi) push(sorted[hi].pitch, sorted[hi].vel); ++lo; --hi; } break; }
    case Diverge: {
        int mid = (heldCount - 1) / 2, lo = mid, hi = mid + 1;
        while (lo >= 0 || hi < heldCount) { if (lo >= 0) push(sorted[lo].pitch, sorted[lo].vel); if (hi < heldCount) push(sorted[hi].pitch, sorted[hi].vel); --lo; ++hi; }
        break;
    }
    case Pinky: for (int i = 0; i < heldCount - 1; ++i) { push(sorted[i].pitch, sorted[i].vel); push(sorted[heldCount - 1].pitch, sorted[heldCount - 1].vel); } if (heldCount == 1) push(sorted[0].pitch, sorted[0].vel); break;
    case Thumb: for (int i = 1; i < heldCount; ++i) { push(sorted[0].pitch, sorted[0].vel); push(sorted[i].pitch, sorted[i].vel); } if (heldCount == 1) push(sorted[0].pitch, sorted[0].vel); break;
    default: for (int i = 0; i < heldCount; ++i) push(sorted[i].pitch, sorted[i].vel); break; // Up, Random, Walk, Played, Chord
    }
    // Octave passes: the same order repeated an octave apart, in the chosen direction.
    seqLen = 0;
    auto passes = [&](int o) { for (int i = 0; i < n && seqLen < kMaxSeq; ++i) { seq[seqLen] = base[i] + 12 * o; seqVel[seqLen] = baseVel[i]; ++seqLen; } };
    if (octMode == 1) for (int o = octaves - 1; o >= 0; --o) passes(o);
    else if (octMode == 2) { for (int o = 0; o < octaves; ++o) passes(o); for (int o = octaves - 2; o >= 1; --o) passes(o); }
    else for (int o = 0; o < octaves; ++o) passes(o);
    if (pos >= seqLen) pos = 0;
}

int Arp::nextIndex() {
    const int mode = stepOf(params_, Mode);
    if (seqLen <= 0) return -1;
    int idx;
    if (mode == Random) idx = static_cast<int>(rnd() * static_cast<float>(seqLen)) % seqLen;
    else if (mode == Walk) { const float r = rnd(); pos = floorMod(pos + (r < 0.4f ? -1 : (r < 0.8f ? 1 : 0)), seqLen); idx = pos; }
    else { idx = pos; if (++pos >= seqLen) { pos = 0; ++cycle; } }
    return idx;
}

void Arp::schedule(int64_t tick, uint8_t pitch, uint8_t vel, bool on) {
    for (auto &q : pending) if (!q.live) { q = {tick, pitch, vel, on, true}; return; }
}

void Arp::flush(int64_t upTo, MidiSink &out) {
    // Offs before ons at the same tick so a retriggered pitch restarts.
    for (auto &q : pending) if (q.live && !q.on && q.tick < upTo) { q.live = false; outs.off(q.pitch, out); }
    for (auto &q : pending) if (q.live && q.on && q.tick < upTo) { q.live = false; outs.on(q.pitch, q.vel, out); }
}

void Arp::handleMidi(uint8_t status, uint8_t d1, uint8_t d2, MidiSink &out) {
    const uint8_t kind = status & 0xf0;
    if (kind == 0x90 && d2 != 0) {
        if (latched) { heldCount = 0; latched = false; } // a new phrase replaces the latched one
        const bool first = heldCount == 0;
        addHeld(d1, d2);
        rebuild();
        if (first) {
            pos = 0; dir = 1; stepCounter = 0; cycle = 0;
            if (stepOf(params_, Sync) == 0) nextStep = -2; // restart: the next block's first tick
        }
    } else if (kind == 0x80 || kind == 0x90) {
        if (stepOf(params_, Latch) == 1) { latched = heldCount > 0 || latched; return; } // keys up, memory kept
        removeHeld(d1);
        rebuild();
        if (heldCount == 0) {
            for (auto &q : pending) if (q.live && q.on) q.live = false; // nothing new starts; gates end on time
            nextStep = -1;
        }
    } else {
        out.send(status, d1, d2);
    }
}

void Arp::onBlock(int64_t tickStart, int64_t tickEnd, float, MidiSink &out) {
    const auto &p = params_;
    const int rate = kRateTicks[stepOf(p, Rate)];
    const bool playing = heldCount > 0 && seqLen > 0;
    if (nextStep == -2) { nextStep = tickStart; anchor = tickStart; }
    if (playing && nextStep < 0) { // free-running: lock to the grid
        nextStep = ((tickStart + rate - 1) / rate) * rate;
        anchor = 0;
    }
    if (stepOf(p, Sync) == 1 && playing) anchor = 0;
    while (playing && nextStep < tickEnd) {
        const int64_t stepTick = nextStep;
        const int stepNo = stepCounter++;
        nextStep += rate;
        const int len = stepOf(p, Length);
        const bool stepOn = stepOf(p, S01 + (stepNo % len)) == 1;
        const float chance = p.get(Chance);
        if (!stepOn || (chance < 100.0f && rnd() * 100.0f >= chance)) { nextIndex(); continue; }
        const int mode = stepOf(p, Mode);
        const int shift = stepOfSigned(p, Shift) * (cycle % stepOf(p, Cycles));
        const float swing = (stepNo & 1) ? p.get(Swing) * 0.01f * static_cast<float>(rate) * 0.5f : 0.0f;
        const float human = p.get(Humanise);
        int ratchets = stepOf(p, Ratchet);
        if (ratchets > 1 && rnd() * 100.0f >= p.get(RatchetChance)) ratchets = 1;
        const int64_t sub = rate / ratchets;
        const float gate = p.get(Gate) * 0.01f;
        for (int r = 0; r < ratchets; ++r) {
            int64_t on = stepTick + static_cast<int64_t>(swing) + r * sub;
            if (human > 0.0f) on += static_cast<int64_t>((rnd() - 0.5f) * human * static_cast<float>(sub) * 0.3f);
            if (on < stepTick) on = stepTick;
            const int64_t off = on + static_cast<int64_t>(gate * static_cast<float>(sub));
            auto fire = [&](int idx) {
                if (idx < 0) return;
                const uint8_t pitch = clampNote(seq[idx] + shift);
                float vel = static_cast<float>(seqVel[idx]);
                switch (stepOf(p, VelMode)) {
                case 1: vel = 100.0f; break;
                case 2: vel = (stepNo % len) == 0 ? 127.0f : vel * (1.0f - p.get(Accent) * 0.5f); break;
                case 3: vel = 40.0f + 87.0f * static_cast<float>(stepNo % len) / static_cast<float>(len > 1 ? len - 1 : 1); break;
                case 4: vel = 127.0f - 87.0f * static_cast<float>(stepNo % len) / static_cast<float>(len > 1 ? len - 1 : 1); break;
                default: break;
                }
                if (human > 0.0f) vel *= 1.0f + (rnd() - 0.5f) * human * 0.4f;
                schedule(on, pitch, clampVel(vel), true);
                schedule(off, pitch, 0, false);
            };
            if (mode == ChordMode) { if (r == 0) nextIndex(); for (int i = 0; i < seqLen; ++i) fire(i); }
            else fire(r == 0 ? nextIndex() : (pos > 0 ? pos - 1 : seqLen - 1)); // ratchets repeat the step's note
        }
    }
    flush(tickEnd, out);
}

void Arp::allNotesOff(MidiSink &out) {
    for (auto &q : pending) q.live = false;
    outs.allOff(out);
    heldCount = 0; latched = false; seqLen = 0; nextStep = -1;
}

} // namespace acidulous::modifier
