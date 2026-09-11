// Does a song-absolute tick survive the round trip?
#include <cstdio>
#include <sequencer/Song.h>
using namespace acidulous; using namespace acidulous::seq;
namespace { int failures = 0, checks = 0; }
int main() {
    SongSnapshot s;
    // mixed lengths and repeats, which is the whole point of the model
    const struct { int bars, repeat, tpb; } spec[] = {
        {1, 2, 960}, {4, 1, 960}, {2, 3, 960}, {8, 1, 720}, {1, 1, 960},
    };
    for (auto &sp : spec) {
        SceneInfo si; si.bars = sp.bars; si.repeat = sp.repeat; si.ticksPerBar = sp.tpb;
        s.scenes.push_back(si);
    }
    int bad = 0, total = 0;
    for (int sc = 0; sc < (int)s.scenes.size(); ++sc) {
        const int64_t iter = s.scenes[sc].iterationTicks();
        for (int rp = 0; rp < s.scenes[sc].repeat; ++rp) {
            for (int64_t t = 0; t < iter; t += 37) {
                const int64_t abs = s.songTickAt(sc, rp, t);
                int32_t sc2, rp2; int64_t t2;
                s.locate(abs, sc2, rp2, t2);
                ++total;
                if (sc2 != sc || rp2 != rp || t2 != t) {
                    if (++bad <= 3) printf("  FAIL (%d,%d,%lld) -> %lld -> (%d,%d,%lld)\n",
                        sc, rp, (long long)t, (long long)abs, sc2, rp2, (long long)t2);
                }
            }
        }
    }
    ++checks; if (bad) ++failures;
    printf("  %-46s %d of %d positions\n", bad ? "FAIL round trip" : "ok   round trip", total - bad, total);

    // the timeline is monotonic and gapless
    int64_t prev = -1; bool mono = true;
    for (int sc = 0; sc < (int)s.scenes.size(); ++sc)
        for (int rp = 0; rp < s.scenes[sc].repeat; ++rp) {
            const int64_t at = s.songTickAt(sc, rp, 0);
            if (at <= prev) mono = false;
            prev = at;
        }
    ++checks; if (!mono) ++failures;
    printf("  %-46s\n", mono ? "ok   every pass starts later than the last" : "FAIL not monotonic");

    // total length is the sum of the parts
    int64_t want = 0;
    for (auto &sc : s.scenes) want += sc.iterationTicks() * sc.repeat;
    const int64_t got = s.songTickAt((int)s.scenes.size() - 1, s.scenes.back().repeat - 1,
                                     s.scenes.back().iterationTicks());
    ++checks; if (got != want) ++failures;
    printf("  %-46s %lld / %lld\n", got == want ? "ok   the song ends where its parts add up to" : "FAIL length",
           (long long)got, (long long)want);

    // past the end clamps to the last scene rather than running off
    int32_t sc2, rp2; int64_t t2;
    s.locate(want * 4, sc2, rp2, t2);
    ++checks; if (sc2 != (int)s.scenes.size() - 1) ++failures;
    printf("  %-46s scene %d\n", sc2 == (int)s.scenes.size() - 1 ? "ok   past the end clamps to the last scene" : "FAIL", sc2);

    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
