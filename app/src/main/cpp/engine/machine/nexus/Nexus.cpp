#include "Nexus.h"
#include <cstdio>
#include <cmath>
#include <cstring>

namespace acidulous::machine {
using namespace nexus;

Nexus::Nexus() { initParams(); }

const ParamDef *Nexus::paramDefs(int32_t &count) const {
    static ParamDef defs[Count];
    static char names[Count][12];
    static bool built = false;
    if (!built) {
        auto put = [&](int32_t i, const char *n, float mn, float mx, float df, Curve c, int32_t steps,
                       const char *u) {
            std::snprintf(names[i], sizeof(names[i]), "%s", n);
            defs[i] = ParamDef{names[i], mn, mx, df, c, steps, u};
        };
        char n[12];
        // Every slot knob is the same 0..1 control, and that is the point:
        // a slot keeps its automation when the module in it changes, because
        // the name never depended on what the module was.
        for (int s = 0; s < kSlots; ++s) {
            for (int k = 0; k < kKnobs; ++k) {
                std::snprintf(n, sizeof(n), "s%02d_p%d", s, k + 1);
                put(SlotBase + s * kKnobs + k, n, 0.0f, 1.0f, 0.0f, Curve::Linear, 0, "");
            }
        }
        for (int c = 0; c < kCables; ++c) {
            std::snprintf(n, sizeof(n), "c%02d_a", c);
            put(CableBase + c * 2, n, -4.0f, 4.0f, 1.0f, Curve::Linear, 0, "");
            std::snprintf(n, sizeof(n), "c%02d_b", c);
            put(CableBase + c * 2 + 1, n, -4.0f, 4.0f, 1.0f, Curve::Linear, 0, "");
        }
        for (int m = 0; m < kMacros; ++m) {
            std::snprintf(n, sizeof(n), "macro%d", m + 1);
            put(MacroBase + m, n, 0.0f, 1.0f, 0.0f, Curve::Linear, 0, "");
        }
        put(Morph, "morph", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, "");
        put(VoiceMode, "voicemode", 0.0f, 2.0f, 0.0f, Curve::Stepped, 3, "");
        put(Glide, "glide", 0.001f, 2.0f, 0.001f, Curve::Exponential, 0, "s");
        put(BendRange, "bend", 0.0f, 12.0f, 2.0f, Curve::Linear, 0, "");
        put(Octave, "octave", -3.0f, 3.0f, 0.0f, Curve::Linear, 0, "");
        put(Transpose, "transpose", -12.0f, 12.0f, 0.0f, Curve::Linear, 0, "");
        put(Fine, "fine", -50.0f, 50.0f, 0.0f, Curve::Linear, 0, "c");
        put(Volume, "volume", 0.0f, 1.0f, 0.8f, Curve::Linear, 0, "");
        put(Pan, "pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, "");
        put(Drive, "drive", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, "");
        built = true;
    }
    count = Count;
    return defs;
}

void Nexus::prepare(int32_t sr) {
    sampleRate = static_cast<float>(sr);
    ctx.sampleRate = sampleRate;
    ctx.pitchOf = pitchOf;
    ctx.gateOf = gateOf;
    ctx.velocityOf = velocityOf;
    ctx.randomOf = randomOf;
    ctx.triggerOf = triggerOf;
    reset();
}

void Nexus::reset() {
    for (auto &v : voices) { v.used = false; v.gate = false; v.quiet = 0.0f; }
    for (int i = 0; i < kVoices; ++i) { gateOf[i] = 0.0f; triggerOf[i] = 0.0f; }
    if (graph != nullptr) graph->reset();
}

void Nexus::noteOn(uint8_t note, uint8_t velocity) {
    Voice *v = nullptr;
    for (auto &cand : voices) if (!cand.used) { v = &cand; break; }
    if (v == nullptr) {
        int64_t oldest = INT64_MAX;
        for (auto &cand : voices) if (cand.age < oldest) { oldest = cand.age; v = &cand; }
    }
    const int32_t index = static_cast<int32_t>(v - voices);
    v->used = true;
    v->gate = true;
    v->note = note;
    v->age = ++counter;
    v->quiet = 0.0f;
    v->velocity = static_cast<float>(velocity) / 127.0f;
    rng = rng * 1664525u + 1013904223u;
    v->random = static_cast<float>((rng >> 9) & 0xffff) / 65536.0f;
    v->trigger = 1.0f;
    pitchOf[index] = static_cast<float>(note);
    velocityOf[index] = v->velocity;
    randomOf[index] = v->random;
    gateOf[index] = 1.0f;
}

void Nexus::noteOff(uint8_t note) {
    for (auto &v : voices) {
        if (!v.used || !v.gate || v.note != note) continue;
        v.gate = false;
        gateOf[static_cast<int32_t>(&v - voices)] = 0.0f;
    }
}

void Nexus::allNotesOff() {
    for (int i = 0; i < kVoices; ++i) { voices[i].gate = false; gateOf[i] = 0.0f; }
}

void Nexus::controlChange(uint8_t cc, uint8_t value) {
    if (cc == 1) ctx.modWheel = static_cast<float>(value) / 127.0f;
}
void Nexus::channelPressure(uint8_t value) { ctx.pressure = static_cast<float>(value) / 127.0f; }
void Nexus::pitchBend(int16_t value14) { bendSemis = static_cast<float>(value14) / 8192.0f; }

void Nexus::onBlock(int64_t tickStart, int64_t tickEnd, float bpm) {
    ctx.bpm = bpm > 1.0f ? bpm : 120.0f;
    tickCursor = static_cast<double>(tickStart);
    tickStep = static_cast<double>(tickEnd - tickStart);
}

void *Nexus::swapObject(int32_t slot, void *object) {
    if (slot != 0) return object;
    auto *next = static_cast<Graph *>(object);
    Graph *old = graph;
    if (next != nullptr && old != nullptr) next->adoptFrom(*old);
    graph = next;
    return old;
}

int32_t Nexus::readScope(float *dest, int32_t max) const {
    return graph != nullptr ? graph->scopePoints(dest, max) : 0;
}

bool Nexus::render(float *L, float *R, int32_t frames) {
    params_.tick();
    Graph *g = graph;
    if (g == nullptr) {
        for (int32_t i = 0; i < frames; ++i) { L[i] = 0.0f; R[i] = 0.0f; }
        return true;
    }

    for (int32_t i = 0; i < kSlots * kKnobs; ++i) knobBuffer[i] = paramOf(SlotBase + i);
    for (int32_t i = 0; i < kCables * 2; ++i) cableBuffer[i] = paramOf(CableBase + i);
    g->applyKnobs(knobBuffer);
    g->applyCableDepths(cableBuffer, paramOf(Morph), frames);
    for (int32_t m = 0; m < kMacros; ++m) ctx.macro[m] = paramOf(MacroBase + m);
    ctx.bend = bendSemis * paramOf(BendRange);

    const float shift = paramOf(Octave) * 12.0f + paramOf(Transpose) + paramOf(Fine) * 0.01f;
    int32_t activeCount = 0;
    for (int32_t i = 0; i < kVoices; ++i) {
        if (!voices[i].used) continue;
        pitchOf[i] = static_cast<float>(voices[i].note) + shift;
        active[activeCount++] = i;
    }
    ctx.tickInc = frames > 0 ? tickStep / static_cast<double>(frames) : 0.0;
    ctx.tick = tickCursor;

    const float volume = paramOf(Volume);
    const float pan = paramOf(Pan);
    const float drive = paramOf(Drive);
    const float angle = (pan + 1.0f) * 0.25f * 3.14159265f;
    const float gl = std::cos(angle) * 1.4142f, gr = std::sin(angle) * 1.4142f;
    const float floorLevel = 0.00006f;   // about -84 dB
    const float quietStep = 1.0f / sampleRate;

    for (int32_t i = 0; i < frames; ++i) {
        g->setInputCursor(i);
        float outL = 0.0f, outR = 0.0f;
        g->step(ctx, active, activeCount, outL, outR);
        g->advanceCables();
        ctx.tick += ctx.tickInc;

        if (drive > 0.0f) {
            outL = std::tanh(outL * (1.0f + drive * 8.0f));
            outR = std::tanh(outR * (1.0f + drive * 8.0f));
        }
        if (!std::isfinite(outL)) outL = 0.0f;
        if (!std::isfinite(outR)) outR = 0.0f;
        const float mono = 0.5f * (outL + outR);
        L[i] = (outL * gl) * volume;
        R[i] = (outR * gr) * volume;

        // A modular has no amp envelope to end a note, so a voice is freed
        // when what it is contributing has stayed below the floor for a
        // while. Without this, a released voice holds a slot for ever.
        const float level = std::fabs(mono);
        for (int32_t a = 0; a < activeCount; ++a) {
            Voice &v = voices[active[a]];
            if (v.gate) { v.quiet = 0.0f; continue; }
            v.quiet = level < floorLevel ? v.quiet + quietStep : 0.0f;
        }
        for (int32_t v = 0; v < kVoices; ++v) triggerOf[v] = voices[v].trigger;
        for (auto &v : voices) v.trigger = 0.0f;
    }

    for (auto &v : voices) {
        if (v.used && !v.gate && v.quiet > 0.032f) { v.used = false; v.quiet = 0.0f; }
    }
    tickCursor += tickStep;
    return true;
}

} // namespace acidulous::machine
