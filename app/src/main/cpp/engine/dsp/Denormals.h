#pragma once

// Flush-to-zero, for the threads that do DSP.
//
// A denormal is a float so small the hardware cannot represent it normally, and
// on most CPUs arithmetic on one takes a slow path - tens of cycles where a
// normal number takes one. They are not a curiosity: **they are what the inside
// of a decaying tail is made of.** A reverb fading out, a delay's feedback
// emptying, a modal resonator ringing down, a release envelope approaching zero
// - every one of those spends its last few dB in denormal, so a block where
// several voices are finishing costs far more than a block where they are loud.
//
// That is a spike at musically irregular moments with a low average, which is
// exactly the shape of "it clicks occasionally at twenty-six per cent".
//
// The app already had `dsp::undenormal`, applied by hand in a dozen places -
// and conspicuously absent from `Biquad` (so every EQ, tone stack and cabinet),
// from `Resonance::Mode` (eight pads times twenty-four modes), from the delay
// lines and from every feedback path in the modulation effects. Rather than add
// a hundred more guards and keep missing some, set the flag once on the thread:
// the hardware then flushes every denormal to zero for free, everywhere.
//
// **Set it on every thread that renders**, not only the audio one. An offline
// export that did not flush while the live path did would differ from it in the
// last few dB of every tail - inaudible, and still a difference between a
// render and a performance that nothing else in this engine allows.
#include <cstdint>

namespace acidulous::dsp {

/**
 * Turn on flush-to-zero for the calling thread. Idempotent and cheap.
 *
 * Returns true if the flag could be set, which is a fact worth having rather
 * than a guarantee: it is a hardware mode and not every target has one.
 */
inline bool flushDenormals() {
#if defined(__aarch64__)
    // FPCR bit 24 is FZ. AArch64 has no DAZ bit: FZ covers both inputs and
    // results, which is what the x86 pair does between them.
    uint64_t fpcr = 0;
    __asm__ __volatile__("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ull << 24);
    __asm__ __volatile__("msr fpcr, %0" : : "r"(fpcr));
    return true;
#elif defined(__arm__)
    uint32_t fpscr = 0;
    __asm__ __volatile__("vmrs %0, fpscr" : "=r"(fpscr));
    fpscr |= (1u << 24);
    __asm__ __volatile__("vmsr fpscr, %0" : : "r"(fpscr));
    return true;
#elif defined(__SSE__) || defined(__x86_64__)
    // Two flags on x86: FTZ makes results flush, DAZ makes inputs read as zero.
    // Both, or a denormal arriving from a buffer is still slow.
    uint32_t csr = __builtin_ia32_stmxcsr();
    csr |= 0x8040u; // FTZ (1 << 15) | DAZ (1 << 6)
    __builtin_ia32_ldmxcsr(csr);
    return true;
#else
    return false;
#endif
}

/** Sets it once per thread, for somewhere that is called often. */
inline void flushDenormalsOnce() {
    static thread_local bool done = false;
    if (!done) {
        done = true;
        flushDenormals();
    }
}

} // namespace acidulous::dsp
