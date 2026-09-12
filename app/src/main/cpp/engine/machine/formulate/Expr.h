#pragma once
#include <cstdint>
#include <string>
#include <vector>

// A tiny integer expression language, compiled to a stack machine.
//
// Typing an equation and hearing it is bytebeat's idea. The language
// people already write those in is C - `t*((t>>12|t>>8)&63&t>>4)` and its
// relatives - so this is that subset: integers, the C operators in the C
// precedence, a handful of functions, and the named values the machine
// exposes. Parsed on a worker into a vector of ops; evaluated per sample on
// the audio thread with no allocation, no recursion and no branching on
// anything but the opcode.
namespace acidulous::machine::formulate {

/** What a formula can read. Filled in per sample by the voice. */
struct Vars {
    int32_t t = 0;    // sample counter, this voice's own
    int32_t f = 440;  // frequency, Hz
    int32_t n = 60;   // MIDI note
    int32_t v = 100;  // velocity 0..127
    int32_t x = 0;    // the oscillator's sample, 0..255 - so a formula can shape it
    int32_t a = 0, b = 0, c = 0; // the three macro knobs, 0..255
    int32_t s = 0;    // table step counter
    int32_t r = 0;    // a fresh random, 0..255
    int32_t sr = 48000;
};

class Expr {
  public:
    /** Parse, or fail with a reason a person can act on. Worker thread. */
    static bool parse(const std::string &source, Expr &out, std::string &error);

    bool empty() const { return ops.empty(); }
    const std::string &source() const { return text; }

    /** Audio thread. Integer arithmetic throughout; divide by zero is zero. */
    int32_t eval(const Vars &vars) const;

  private:
    enum class Op : uint8_t {
        Push, VarT, VarF, VarN, VarV, VarX, VarA, VarB, VarC, VarS, VarR, VarSr,
        Add, Sub, Mul, Div, Mod, And, Or, Xor, Shl, Shr,
        Lt, Gt, Le, Ge, Eq, Ne, AndAnd, OrOr,
        Neg, Not, BitNot, Sin, Abs, Min, Max, Sel, // Sel: c ? a : b, three deep
    };
    struct Code { Op op; int32_t arg; };

    std::vector<Code> ops;
    std::string text;

    friend class Parser;
};

} // namespace acidulous::machine::formulate
