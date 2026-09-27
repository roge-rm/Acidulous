// A standalone Link peer for testing the app's Link on a device.
//
// The Android emulator can't reach the local network, but two peers on one
// device find each other the same way two apps on a phone do. That covers
// discovery, the session tempo, and start and stop.
//
//   link_peer <seconds> [tempo-to-propose]
//
// Prints peers, session tempo and beat every quarter second, so it can be
// compared with the app's readout.
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
        // Wait to find other peers first. Proposing into an empty session and
        // then joining one isn't the same as proposing into it.
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
