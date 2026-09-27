// Ableton Link, for the browser build: never on. Link finds its peers by
// multicast on the local network, which a web page has no way to send or
// hear, so the timebase is the one Link switched off is - no session, and
// the engine keeps its own time. The class is the real one's (link/
// LinkTimebase.h), so EngineHost builds unchanged.
//
// The Windows build uses it too for now: Link supports Windows, but only its
// Linux and POSIX platform headers are vendored (third_party/link).

#include <link/LinkTimebase.h>

namespace acidulous {

struct LinkTimebase::Impl {};

LinkTimebase::LinkTimebase() : impl(new Impl()) {}
LinkTimebase::~LinkTimebase() = default;

void LinkTimebase::setEnabled(bool) {}
bool LinkTimebase::enabled() const { return false; }
int32_t LinkTimebase::peers() const { return 0; }
double LinkTimebase::sessionTempo() const { return 0.0; }
void LinkTimebase::setQuantum(double) {}
void LinkTimebase::setAnchor(int64_t, int64_t, int32_t) {}
void LinkTimebase::setFallbackLatency(int64_t) {}
void LinkTimebase::tempoFromApp(double) {}
Timebase::State LinkTimebase::capture(int64_t) { return {}; }
void LinkTimebase::proposeTempo(double) {}
void LinkTimebase::proposePlaying(bool) {}
void LinkTimebase::setBlockFrames(int32_t, int32_t) {}

} // namespace acidulous
