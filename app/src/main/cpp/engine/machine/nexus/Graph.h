#pragma once
#include <engine/machine/nexus/Modules.h>
#include <memory>
#include <string>
#include <vector>

// A built patch.
//
// Parsed, allocated and ordered on a worker thread, then handed to the audio
// thread as one object, the way Mosaic's sample map is. After that it is
// read-only structure and mutable state, touched by nobody but the audio
// thread.
//
// Per-voice module state lives here and not on the voice, because this is
// the thing that gets replaced when somebody adds a cable. A voice holding a
// module pointer would be left holding a dangling one.
namespace acidulous::machine::nexus {

struct Node {
    int32_t slot = -1;
    int32_t type = TBlank;
    bool poly = true;
    bool unknown = false;       // a type this build does not have: holds its place
    int32_t outBase = 0;        // where this node's outputs start in the port row
    int32_t inCount = 0, outCount = 0;
    int32_t cableFirst = 0, cableCount = 0; // cables arriving here, contiguous
    // Which input ports actually have a cable in them, one bit each.
    //
    // A module is handed its inputs as plain floats and cannot tell an
    // unpatched jack from a patched one carrying silence - and for almost
    // every module that is right, because nothing is exactly the same thing as
    // zero. The output block is the exception: it wants to know whether its
    // right input is wired so it can mirror the left when it is not, which is
    // what lets a stereo module reach the speakers without every mono patch
    // in existence losing a channel.
    uint32_t inMask = 0;
    float x = 0.0f, y = 0.0f;   // canvas position; audio ignores it
    std::vector<std::unique_ptr<Module>> inst; // one, or one per voice
};

struct Cable {
    int32_t srcNode = -1, srcPort = 0;
    int32_t dstNode = -1, dstPort = 0;
    int32_t modNode = -1, modPort = 0;
    float modAmount = 0.0f;
    int32_t paramIndex = -1;    // which cNN_a/b pair owns its depth, or -1
    float depthA = 1.0f, depthB = 1.0f; // used when it owns no parameter
    bool delayed = false;       // closes a loop, so it reads the previous sample
    int32_t prevSlot = -1;      // where its source is kept in prev[]
    // per block
    float base = 0.0f, baseInc = 0.0f, baseLast = 0.0f;
};

class Graph {
  public:
    /** Worker thread. Returns null and fills `error` if the text is unusable. */
    static Graph *parse(const std::string &text, float sampleRate, std::string &error);

    ~Graph() = default;

    /** Audio thread, at hand-over: keep what the old graph already had going. */
    void adoptFrom(Graph &old);
    void reset();

    int32_t nodeCount() const { return static_cast<int32_t>(nodes.size()); }
    const Node &nodeAt(int32_t i) const { return nodes[static_cast<size_t>(i)]; }

    /** One sample for every node, in order. Returns the stereo sink. */
    void step(Context &ctx, const int32_t *activeVoices, int32_t activeCount, float &outL, float &outR);

    void applyKnobs(const float *slotKnobs);
    /**
     * What each occupied slot's knobs should be if nobody has said otherwise.
     *
     * A slot knob is a machine parameter, and every one of them defaults to
     * zero - so a graph built from text alone has an oscillator whose level is
     * nought and makes no sound at all. The module table already carries the
     * value each knob wants; this is how a caller that has just built a graph
     * can find it. Slots with nothing in them are left at zero.
     *
     * [out] is kSlots * kKnobs floats. The caller decides what to do with
     * them, because the answer differs: a patch being loaded has its own saved
     * values and must keep them, while a graph parsed from a bank has none and
     * wants these.
     */
    void defaultKnobs(float *out) const;
    void applyCableDepths(const float *cableParams, float morph, int32_t frames);
    void advanceCables();
    int32_t scopePoints(float *dest, int32_t max) const;

    /**
     * How much is moving, per slot and per cable, for the editor to draw.
     *
     * [dest] is filled with kSlots slot levels followed by kCables cable
     * levels, so the layout does not depend on what the patch contains and the
     * editor needs no mapping table. Slots are indexed by slot number and
     * cables by the order they appear in the patch text - which is *not* the
     * order this class keeps them in, because they are sorted by destination
     * so the inner loop can walk a span. `Cable::paramIndex` is the text
     * index, and is what a cable's depth knobs are already addressed by.
     *
     * A level is a decaying peak, not an instantaneous sample: at a screen's
     * thirty frames a second, an instantaneous reading of an audio-rate signal
     * is a random number between plus and minus the amplitude, and a cable
     * carrying a loud sine would flicker rather than glow.
     */
    int32_t activity(float *dest, int32_t max) const;
    void setInputCursor(int32_t frame);

    const std::string &warning() const { return warn; }

  private:
    void meter(const int32_t *active, int32_t activeCount);
    float readPort(int32_t node, int32_t port, int32_t voice) const;
    void gather(const Node &n, int32_t nodeIndex, int32_t voice, const int32_t *active, int32_t activeCount,
                float *in) const;

    std::vector<Node> nodes;
    std::vector<Cable> cables;
    std::vector<int32_t> order;      // node indices, topological
    std::vector<float> port;         // (kVoices + 1) rows x totalPorts; row kVoices is mono
    std::vector<float> prev;         // (kVoices + 1) rows x delayedPorts.size()
    std::vector<int32_t> delayedPorts;
    float sampleRate = 48000.0f;
    int32_t totalPorts = 0;
    int32_t outNode = -1;
    int32_t scopeNode = -1;
    // Display only, and touched by nobody but the audio thread. Read across
    // the JNI boundary without a lock: a torn float here is one frame of one
    // cable being slightly the wrong brightness.
    float slotLevel[kSlots] = {};
    float cableLevel[kCables] = {};
    int32_t meterCountdown = 0;
    std::string warn;
};

} // namespace acidulous::machine::nexus
