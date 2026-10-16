// An object mount built for one machine type must not reach another.
//
// A worker checks that a rack holds a Cumulus, builds a cloud, and queues the
// mount. If the rack's machine is replaced meanwhile, the audio thread must
// refuse the object and retire it with its own deleter.
#include <atomic>
#include <chrono>
#include <cstdio>
#include <memory>
#include <thread>
#include <vector>

#include <engine/core/Take.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/rack/Engine.h>

using namespace acidulous;

static std::atomic<int> deleted{0};

int main() {
    std::printf("mount_type_test\n");
    auto engine = std::make_unique<Engine>();
    engine->start();
    std::vector<float> out(static_cast<size_t>(kBlockFrames) * 2, 0.0f);

    Machine *m = MachineRegistry::create("Nexus");
    m->prepare(kSampleRate);
    Mount machine;
    machine.kind = Mount::Kind::Machine;
    machine.rack = 0;
    machine.object = m;
    engine->mount(machine);
    for (int i = 0; i < 8; ++i) engine->renderBlock(nullptr, out.data());

    int *object = new int(7);
    Mount mount;
    mount.kind = Mount::Kind::Object;
    mount.rack = 0;
    mount.object = object;
    mount.deleter = [](void *p) { delete static_cast<int *>(p); deleted.fetch_add(1); };
    mount.machineType = "Cumulus";
    engine->mount(mount);

    for (int i = 0; i < 400 && deleted.load() == 0; ++i) {
        engine->renderBlock(nullptr, out.data());
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    if (deleted.load() != 1) {
        std::printf("FAIL: the refused object was retired %d times, wanted 1\n", deleted.load());
        return 1;
    }
    // A machine change queued ahead of an object built for it: the object lands.
    deleted.store(0);
    Machine *dice = MachineRegistry::create("Dice");
    dice->prepare(kSampleRate);
    Mount change;
    change.kind = Mount::Kind::Machine;
    change.rack = 0;
    change.object = dice;
    engine->mount(change);
    Mount take;
    take.kind = Mount::Kind::Object;
    take.rack = 0;
    take.object = new audio::Take();
    take.deleter = [](void *p) { delete static_cast<audio::Take *>(p); deleted.fetch_add(1); };
    take.machineType = "Dice";
    engine->mount(take);
    for (int i = 0; i < 100; ++i) {
        engine->renderBlock(nullptr, out.data());
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    if (deleted.load() != 0) {
        std::printf("FAIL: an object for the machine queued ahead of it was refused\n");
        return 1;
    }
    engine->stop();
    std::printf("PASS\n");
    return 0;
}
