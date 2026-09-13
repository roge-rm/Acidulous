// A Link peer in a box, for proving the app's own Link on a device.
//
// An Android emulator has no route to the machine's network, so the two
// phones this milestone is really for cannot be had here - but two peers on
// *one* device find each other exactly as two apps on one phone do, which is
// the whole chain: discovery, the session tempo, and start and stop.
//
//   link_peer <seconds> [tempo-to-propose]
//
// It prints a line a quarter second: peers, the session tempo, and the beat,
// so the app's readout and this can be compared while both are running.
#include <ableton/Link.hpp>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <thread>

int main(int argc, char **argv) {
    const double seconds = argc > 1 ? std::atof(argv[1]) : 10.0;
    const double propose = argc > 2 ? std::atof(argv[2]) : 0.0;
    const bool play = argc > 3 && std::atoi(argv[3]) != 0;

    ableton::Link link(120.0);
    link.enableStartStopSync(true);
    link.enable(true);

    if (propose > 0.0) {
        // A moment to find anybody first: proposing into an empty session
        // and then joining one is a different thing from proposing into it.
        std::this_thread::sleep_for(std::chrono::milliseconds(1500));
        auto state = link.captureAppSessionState();
        state.setTempo(propose, link.clock().micros());
        if (play) state.setIsPlaying(true, link.clock().micros());
        link.commitAppSessionState(state);
        printf("proposed %.2f bpm%s\n", propose, play ? " and play" : "");
    }

    const auto until = std::chrono::steady_clock::now() +
                       std::chrono::milliseconds(static_cast<int64_t>(seconds * 1000.0));
    while (std::chrono::steady_clock::now() < until) {
        const auto state = link.captureAppSessionState();
        printf("peers %zu  tempo %.2f  beat %.3f  playing %d\n", link.numPeers(), state.tempo(),
               state.beatAtTime(link.clock().micros(), 4.0), state.isPlaying() ? 1 : 0);
        fflush(stdout);
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }
    link.enable(false);
    return 0;
}
