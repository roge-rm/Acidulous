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
    void applyCableDepths(const float *cableParams, float morph, int32_t frames);
    void advanceCables();
    int32_t scopePoints(float *dest, int32_t max) const;
    void setInputCursor(int32_t frame);

    const std::string &warning() const { return warn; }

  private:
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
    std::string warn;
};

} // namespace acidulous::machine::nexus
