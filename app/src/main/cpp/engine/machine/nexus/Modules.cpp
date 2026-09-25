#include "Modules.h"

// The palette's table of contents. The engine publishes this to the UI, so
// the editor's labels, its jack names and a module's defaults all come from
// here - there is no second copy in Kotlin to keep in step by hand, which is
// a trap five other panels in this app are already caught in.
namespace acidulous::machine::nexus {

namespace {
constexpr const char *kNone = nullptr;

#define KNOBS(...) {__VA_ARGS__}
const ModuleInfo kInfo[TypeCount] = {
    // name        knobs                                                                 defaults                                       inputs                                  outputs                            cap
    {"blank",   {kNone, kNone, kNone, kNone, kNone, kNone, kNone, kNone},                {0, 0, 0, 0, 0, 0, 0, 0},                      {kNone}, {kNone}, CapBoth},
    {"voice",   {"bend", "glide", kNone, kNone, kNone, kNone, kNone, kNone},             {0.08f, 0, 0, 0, 0, 0, 0, 0},                  {kNone},
                                                                                          {"pitch", "gate", "vel", "rnd", "trig"}, CapPoly},
    {"perf",    {kNone, kNone, kNone, kNone, kNone, kNone, kNone, kNone},                {0, 0, 0, 0, 0, 0, 0, 0},                      {kNone},
                                                                                          {"mod", "prs", "bend"}, CapMono},
    {"macro",   {kNone, kNone, kNone, kNone, kNone, kNone, kNone, kNone},                {0, 0, 0, 0, 0, 0, 0, 0},                      {kNone},
                                                                                          {"1", "2", "3", "4", "5", "6", "7", "8"}, CapMono},
    {"out",     {"level", "pan", kNone, kNone, kNone, kNone, kNone, kNone},              {0.5f, 0.5f, 0, 0, 0, 0, 0, 0},                {"in", "pan", "in R"}, {"L", "R"}, CapBoth},
    {"scope",   {"zoom", kNone, kNone, kNone, kNone, kNone, kNone, kNone},               {0.2f, 0, 0, 0, 0, 0, 0, 0},                   {"in", "b"}, {"thru", "b"}, CapMono},

    {"osc",     {"wave", "semis", "fine", "width", "fm", "level", kNone, kNone},         {0, 0.5f, 0.5f, 0.5f, 0.25f, 1.0f, 0, 0},      {"pitch", "fm", "pw"}, {"out"}, CapBoth},
    {"wtosc",   {"table", "frame", "semis", "fm", "level", kNone, kNone, kNone},         {0, 0, 0.5f, 0.25f, 1.0f, 0, 0, 0},            {"pitch", "fm", "frame"}, {"out"}, CapBoth},
    {"noise",   {"colour", "level", kNone, kNone, kNone, kNone, kNone, kNone},           {0, 1.0f, 0, 0, 0, 0, 0, 0},                   {kNone}, {"out"}, CapBoth},
    {"string",  {"semis", "sustain", "tone", "stiff", "tension", "damp at", "damp", "level"},
                                                                                          {0.5f, 0.9f, 0.45f, 0, 0.15f, 0.5f, 0, 0.5f}, {"excite", "pitch", "sustain"}, {"out", "ring"}, CapBoth},
    {"wheels",  {"timbre", "semis", "level", kNone, kNone, kNone, kNone, kNone},         {0.12f, 0.5f, 1.0f, 0, 0, 0, 0, 0},            {"pitch"}, {"out"}, CapBoth},
    {"op",      {"mode", "ratio", "fine", "fb", "level", kNone, kNone, kNone},           {0, 0.033f, 0.5f, 0, 1.0f, 0, 0, 0},           {"phase", "aux", "pitch"}, {"out"}, CapBoth},
    {"grain",   {"pos", "size", "density", "spray", "pitch", "level", kNone, kNone},     {0.1f, 0.4f, 0.4f, 0.2f, 0.5f, 0.5f, 0, 0},    {"in", "pos", "density"}, {"out"}, CapBoth},
    {"audioin", {"gain", kNone, kNone, kNone, kNone, kNone, kNone, kNone},               {0.25f, 0, 0, 0, 0, 0, 0, 0},                  {kNone}, {"L", "R"}, CapMono},

    {"filter",  {"type", "cutoff", "res", "drive", "amount", "key", kNone, kNone},       {0, 0.6f, 0.2f, 0, 0, 0, 0, 0},                {"in", "cutoff", "res"}, {"out"}, CapBoth},
    {"vca",     {"offset", "curve", kNone, kNone, kNone, kNone, kNone, kNone},           {0, 0, 0, 0, 0, 0, 0, 0},                      {"in", "cv"}, {"out"}, CapBoth},
    {"mix",     {"a", "b", "c", "d", "out", kNone, kNone, kNone},                        {0.75f, 0.75f, 0.75f, 0.75f, 0.5f, 0, 0, 0},   {"a", "b", "c", "d"}, {"out"}, CapBoth},
    {"math",    {"mode", "amount", kNone, kNone, kNone, kNone, kNone, kNone},            {0, 0.5f, 0, 0, 0, 0, 0, 0},                   {"a", "b"}, {"out"}, CapBoth},
    {"delay",   {"time", "feedback", "tone", "mix", kNone, kNone, kNone, kNone},         {0.55f, 0.3f, 0.5f, 0.5f, 0, 0, 0, 0},         {"in", "time"}, {"out"}, CapMono},
    {"rotary",  {"horn", "drum", "ramp", "distance", "angle", "spread", kNone, kNone},   {0.75f, 0.7f, 0.5f, 0.35f, 0.8f, 0.75f, 0, 0}, {"in", "speed"}, {"L", "R"}, CapMono},
    {"bands",   {"shift", "follow", "width", "level", kNone, kNone, kNone, kNone},       {0.5f, 0.35f, 0.5f, 0.25f, 0, 0, 0, 0},        {"carrier", "modulator"}, {"out", "loud"}, CapMono},

    {"env",     {"attack", "decay", "sustain", "release", "loop", kNone, kNone, kNone},  {0.1f, 0.4f, 0.7f, 0.3f, 0, 0, 0, 0},          {"gate"}, {"out", "busy"}, CapBoth},
    {"lfo",     {"wave", "rate", "sync", "slew", "depth", kNone, kNone, kNone},          {0, 0.4f, 0, 0, 1.0f, 0, 0, 0},                {"rate"}, {"out", "uni"}, CapBoth},
    {"snh",     {"track", kNone, kNone, kNone, kNone, kNone, kNone, kNone},              {0, 0, 0, 0, 0, 0, 0, 0},                      {"in", "trig"}, {"out"}, CapBoth},
    {"slew",    {"rise", "fall", kNone, kNone, kNone, kNone, kNone, kNone},              {0.3f, 0.3f, 0, 0, 0, 0, 0, 0},                {"in"}, {"out"}, CapBoth},

    {"clock",   {"division", "width", kNone, kNone, kNone, kNone, kNone, kNone},         {0.25f, 0.5f, 0, 0, 0, 0, 0, 0},               {kNone}, {"gate", "ramp", "reset"}, CapMono},
    {"euclid",  {"steps", "pulses", "rotate", kNone, kNone, kNone, kNone, kNone},        {0.45f, 0.25f, 0, 0, 0, 0, 0, 0},              {"clock", "reset"}, {"gate", "step"}, CapBoth},
    {"prob",    {"chance", kNone, kNone, kNone, kNone, kNone, kNone, kNone},             {0.5f, 0, 0, 0, 0, 0, 0, 0},                   {"in", "cv"}, {"pass", "skip"}, CapBoth},
    {"rand",    {"bipolar", "steps", kNone, kNone, kNone, kNone, kNone, kNone},          {0, 0, 0, 0, 0, 0, 0, 0},                      {"trig"}, {"out"}, CapBoth},
    {"quant",   {"scale", "root", kNone, kNone, kNone, kNone, kNone, kNone},             {0, 0, 0, 0, 0, 0, 0, 0},                      {"in"}, {"out", "trig"}, CapBoth},
    {"logic",   {"mode", kNone, kNone, kNone, kNone, kNone, kNone, kNone},               {0, 0, 0, 0, 0, 0, 0, 0},                      {"a", "b"}, {"out", "not"}, CapBoth},
    {"touch",   {kNone, kNone, kNone, kNone, kNone, kNone, kNone, kNone},                {0, 0, 0, 0, 0, 0, 0, 0},                      {kNone}, {"prs", "slide"}, CapPoly},
};
} // namespace

const ModuleInfo &infoFor(int32_t type) {
    if (type < 0 || type >= TypeCount) return kInfo[TBlank];
    return kInfo[type];
}

int32_t typeFromName(const char *name) {
    if (name == nullptr) return TBlank;
    for (int32_t i = 0; i < TypeCount; ++i) {
        if (std::strcmp(kInfo[i].name, name) == 0) return i;
    }
    return -1; // unknown: the caller puts a blank in the slot and says so
}

Module *makeModule(int32_t type) {
    switch (type) {
    case TVoice: return new VoiceMod();
    case TPerf: return new PerfMod();
    case TMacro: return new MacroMod();
    case TOut: return new OutMod();
    case TScope: return new ScopeMod();
    case TOsc: return new OscMod();
    case TWtOsc: return new WtOscMod();
    case TNoise: return new NoiseMod();
    case TString: return new StringMod();
    case TWheels: return new WheelsMod();
    case TOp: return new OpMod();
    case TGrain: return new GrainMod();
    case TAudioIn: return new AudioInMod();
    case TFilter: return new FilterMod();
    case TVca: return new VcaMod();
    case TMix: return new MixMod();
    case TMath: return new MathMod();
    case TDelay: return new DelayMod();
    case TRotary: return new RotaryMod();
    case TBands: return new BandsMod();
    case TEnv: return new EnvMod();
    case TLfo: return new LfoMod();
    case TSnh: return new SnhMod();
    case TSlew: return new SlewMod();
    case TClock: return new ClockMod();
    case TEuclid: return new EuclidMod();
    case TProb: return new ProbMod();
    case TRand: return new RandMod();
    case TQuant: return new QuantMod();
    case TLogic: return new LogicMod();
    case TTouch: return new TouchMod();
    default: return new BlankMod();
    }
}

} // namespace acidulous::machine::nexus
