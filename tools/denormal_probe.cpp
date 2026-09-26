// What a decaying tail costs, for every machine and every effect: the question
// of what a browser pays for denormals, which WebAssembly cannot flush.
//
// Each unit plays two bars and is stopped, and the twenty seconds after the
// first are timed - the stretch where filter states and feedback lines are
// decaying through the smallest floats there are. Built and run three ways by
// denormal_probe.sh: as shipped with the CPU's flush-to-zero off (what a
// browser gets without the guards), with ACID_SOFT_DENORMALS (what a browser
// gets), and with flush-to-zero on (what a phone gets). A unit whose middle
// column is well over its last is a filter or a feedback path that still needs
// dsp::guardDenormal.
//
// Output is one line a unit: type|effect|idle|playing|tail, in us a block.
#include <chrono>
#include <cstdio>
#include <memory>
#include <vector>
#include <engine/dsp/Denormals.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/effect/EffectRegistry.h>
#include <engine/rack/Engine.h>
#include <sequencer/Song.h>
using namespace acidulous;
using namespace acidulous::seq;
constexpr int32_t kBar = kPPQN * 4;

static double timeBlocks(Engine &e, int n) {
    float out[kBlockFrames * 2];
    auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < n; ++i) e.renderBlock(nullptr, out);
    return std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - t0).count() / n;
}

static void probe(const char *type, const char *fx0 = "Delay", const char *fx1 = "Reverb") {
    auto e = std::make_unique<Engine>();
    auto snap = std::make_shared<SongSnapshot>();
    e->racks[0].swapMachine(MachineRegistry::create(type));
    if (fx0) e->racks[0].swapEffect(0, EffectRegistry::create(fx0));
    if (fx1) e->racks[0].swapEffect(1, EffectRegistry::create(fx1));
    if (Machine *m = e->racks[0].currentMachine()) { m->prepare(kSampleRate); m->reset(); m->params().jumpAll(); }
    for (int s = 0; s < 2; ++s) if (Effect *x = e->racks[0].currentEffect(s)) { x->prepare(kSampleRate); x->reset(); x->params().jumpAll(); }
    SceneInfo sc; sc.id = 1; sc.bars = 2; sc.repeat = 1; sc.ticksPerBar = kBar; snap->scenes.push_back(sc);
    auto c = std::make_shared<Clip>(); c->rev = 1; c->bars = 2; c->ticksPerBar = kBar;
    for (int i = 0; i < 8; ++i) { ClipNote n{}; n.tick = i * (kBar / 4); n.length = kBar / 8; n.pitch = uint8_t(36 + (i % 5) * 3); n.velocity = 110; c->notes.push_back(n); }
    snap->setClip(0, 0, c); snap->rackCount = 1;
    e->scheduler.swapSnapshot(snap.get());
    float out[kBlockFrames * 2];
    e->panicFlag.store(true); e->renderBlock(nullptr, out);
    const double idle = timeBlocks(*e, 750);            // never played
    e->transport.requestPlay(0);
    const double playing = timeBlocks(*e, 3000);        // two bars and a bit
    e->transport.requestStop();
    const double early = timeBlocks(*e, 750);           // first second of tail
    const double late = timeBlocks(*e, 7500 * 2);       // the next twenty
    printf("%s|%s|%.1f|%.1f|%.1f\n", type, fx0 ? fx0 : "-", idle, playing, late);
    for (int32_t s = 0; s < kEffectSlots; ++s) delete e->racks[0].swapEffect(s, nullptr);
    delete e->racks[0].swapMachine(nullptr);
}

int main(int argc, char **argv) {
    if (argc > 1) dsp::flushDenormals();
    for (int32_t i = 0; i < MachineRegistry::count(); ++i) probe(MachineRegistry::name(i), nullptr, nullptr);
    for (int32_t i = 0; i < EffectRegistry::count(); ++i) probe("Reflux", EffectRegistry::name(i), nullptr);
}
