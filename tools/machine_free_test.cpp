// A replaced machine frees the objects mounted on it.
//
// Mounts a Cumulus with a cloud, replaces it with another machine, over and
// over. The leak checker (machine_free_test.sh) reports any cloud left behind.
#include <chrono>
#include <cstdio>
#include <memory>
#include <thread>
#include <vector>

#include <engine/machine/MachineRegistry.h>
#include <engine/machine/cumulus/Cloud.h>
#include <engine/rack/Engine.h>

using namespace acidulous;

int main() {
    std::printf("machine_free_test\n");
    auto engine = std::make_unique<Engine>();
    engine->start();
    std::vector<float> out(static_cast<size_t>(kBlockFrames) * 2, 0.0f);
    auto put = [&](Machine *m) {
        m->prepare(kSampleRate);
        Mount mount;
        mount.kind = Mount::Kind::Machine;
        mount.rack = 0;
        mount.object = m;
        while (!engine->mount(mount)) engine->renderBlock(nullptr, out.data());
        engine->renderBlock(nullptr, out.data());
    };
    for (int i = 0; i < 6; ++i) {
        put(MachineRegistry::create("Cumulus"));
        machine::cumulus::CloudSpec spec;
        spec.partials = 8;
        auto set = machine::cumulus::buildCloud(spec, kSampleRate);
        Mount mount;
        mount.kind = Mount::Kind::Object;
        mount.rack = 0;
        mount.object = set.release();
        mount.deleter = deleteAs<machine::cumulus::CloudSet>;
        mount.machineType = "Cumulus";
        while (!engine->mount(mount)) engine->renderBlock(nullptr, out.data());
        engine->renderBlock(nullptr, out.data());
        put(MachineRegistry::create("Hexbeat"));
    }
    for (int i = 0; i < 200; ++i) {
        engine->renderBlock(nullptr, out.data());
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    engine->stop();
    return 0;
}
