// Reading a machine from the UI while it's being replaced.
//
// The audio thread swaps a new machine or sample in and hands the old one to
// the retire worker, which deletes it. The UI reads the current one directly:
// knob readouts, Nexus's scope, a pad's waveform. If the delete lands during
// a read, the read is of freed memory.
//
// This renders on one thread, replaces rack 0's machine over and over on a
// second, and reads its knobs on a third, as a panel does. Built with the
// address sanitizer, which stops at the first freed read.
#include <atomic>
#include <chrono>
#include <cstdio>
#include <thread>
#include <vector>

#include <engine/machine/MachineRegistry.h>
#include <engine/rack/Engine.h>

using namespace acidulous;

int main() {
    std::printf("retire_test\n");
    auto engine = std::make_unique<Engine>();
    engine->start();
    std::atomic<bool> stop{false};
    std::vector<float> in(static_cast<size_t>(kBlockFrames) * 2, 0.0f), out(in.size(), 0.0f);

    std::thread audio([&] {
        while (!stop.load()) engine->renderBlock(nullptr, out.data());
    });
    std::thread mounter([&] {
        const char *types[] = {"Reflux", "Trinity", "Hexbeat"};
        for (int i = 0; i < 4000 && !stop.load(); ++i) {
            if (i % 8 == 0) std::this_thread::sleep_for(std::chrono::milliseconds(1));
            Machine *m = MachineRegistry::create(types[i % 3]);
            m->prepare(kSampleRate);
            Mount mount;
            mount.kind = Mount::Kind::Machine;
            mount.rack = 0;
            mount.object = m;
            while (!engine->mount(mount)) std::this_thread::yield();
        }
        stop.store(true);
    });
    std::atomic<int64_t> reads{0};
    std::thread reader([&] {
        float sum = 0.0f;
        while (!stop.load()) {
            // What EngineHost::paramNormalized does for a machine knob.
            {
                auto guard = engine->readLive();
                if (Machine *m = engine->racks[0].currentMachine()) {
                    for (int32_t k = 0; k < m->params().size(); ++k) sum += m->params().normalized(k);
                    // A long read, like drawing a pad's waveform from a long
                    // sample (EngineHost::sampleShape), holds on for a while.
                    if (reads.load() % 64 == 0) {
                        std::this_thread::sleep_for(std::chrono::milliseconds(3));
                        for (int32_t k = 0; k < m->params().size(); ++k) sum += m->params().normalized(k);
                    }
                }
            }
            reads.fetch_add(1);
        }
        if (sum < -1.0f) std::printf("%f\n", sum);
    });
    mounter.join();
    reader.join();
    audio.join();
    engine->stop();
    std::printf("  ok   %lld reads across 4000 machine swaps, none of freed memory\n",
                static_cast<long long>(reads.load()));
    std::printf("\n1 checks, 0 failures\n");
    return 0;
}
