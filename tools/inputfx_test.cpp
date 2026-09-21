// Is an effect on the input actually printed into the recording?
//
// The whole feature is one ordering claim - the input chain runs *before*
// `InputBus::publish`, and the recorder reads what was published - and an
// ordering claim is exactly the sort of thing that is true when it is written
// and quietly false two refactors later. So this drives the real `Engine`, with
// a real effect mounted, into the real `Capture`, and reads the file back.
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include <engine/core/Constants.h>
#include <engine/core/InputBus.h>
#include <engine/effect/EffectRegistry.h>
#include <engine/format/WavReader.h>
#include <engine/rack/Engine.h>

using namespace acidulous;

namespace {
int checks = 0, failures = 0;

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-52s %s\n", cond ? "ok  " : "FAIL", what, detail.c_str());
    if (!cond) ++failures;
}

/** A block of a loud sine, interleaved, as the audio callback hands it over. */
void fill(float *in, int64_t at) {
    for (int32_t i = 0; i < kBlockFrames; ++i) {
        const float v = 0.4f * std::sin(6.2831853f * 220.0f * static_cast<float>(at + i) / kSampleRate);
        in[i * 2] = v;
        in[i * 2 + 1] = v;
    }
}

} // namespace

int main() {
    printf("an effect on the input is printed into the recording\n");
    const std::string dir = std::getenv("TMPDIR") != nullptr ? std::getenv("TMPDIR") : "/tmp";

    float in[kBlockFrames * 2], out[kBlockFrames * 2];

    // Nothing on the input: what is published is what arrived.
    {
        Engine e;
        e.start();
        fill(in, 0);
        e.renderBlock(in, out);
        const float *pub = InputBus::get().block();
        bool same = pub != nullptr;
        for (int32_t i = 0; same && i < kBlockFrames * 2; ++i) same = pub[i] == in[i];
        ok("with nothing on it, the input is published untouched", same);
        e.stop();
    }

    // An amp on it: what is published is not what arrived.
    {
        Engine e;
        e.start();
        Effect *fx = EffectRegistry::create("Amp");
        fx->prepare(kSampleRate);
        e.inputFx[0] = fx;
        double worst = 0.0;
        for (int64_t b = 0; b < 200; ++b) {
            fill(in, b * kBlockFrames);
            e.renderBlock(in, out);
        }
        const float *pub = InputBus::get().block();
        for (int32_t i = 0; i < kBlockFrames * 2; ++i) {
            worst = std::max(worst, static_cast<double>(std::fabs(pub[i] - in[i])));
        }
        ok("with an amp on it, the published block is coloured", worst > 0.01,
           "worst difference " + std::to_string(worst));
        e.inputFx[0] = nullptr;
        e.stop();
        delete fx;
    }

    // **And the recorder gets the coloured one.** The claim, end to end.
    {
        const std::string wet = dir + "/inputfx-wet.wav";
        const std::string dry = dir + "/inputfx-dry.wav";
        double peaks[2] = {0.0, 0.0};
        for (int pass = 0; pass < 2; ++pass) {
            Engine e;
            e.start();
            Effect *fx = nullptr;
            if (pass == 0) {
                fx = EffectRegistry::create("Amp");
                fx->prepare(kSampleRate);
                // Driven hard, so the difference is not a matter of opinion.
                fx->params().set(fx->params().indexOf("drive"), 1.0f);
                fx->params().set(fx->params().indexOf("master"), 1.0f);
                fx->params().jumpAll();
                e.inputFx[0] = fx;
            }
            std::string error;
            if (!e.capture.start(pass == 0 ? wet : dry, kSampleRate, Capture::FromInput, error)) {
                ok("the capture started", false, error);
                return 1;
            }
            for (int64_t b = 0; b < 400; ++b) {
                fill(in, b * kBlockFrames);
                e.renderBlock(in, out);
            }
            e.capture.stop();
            e.inputFx[0] = nullptr;
            e.stop();
            delete fx;

            const auto got = WavReader::read(pass == 0 ? wet : dry, kSampleRate, error, 30);
            if (!got || got->frames < kSampleRate / 4) {
                ok("the recording was written", false, error);
                return 1;
            }
            // Past the first blocks, where the oversampler's latency and the
            // filters are still filling.
            for (int32_t i = kSampleRate / 8; i < got->frames; ++i) {
                peaks[pass] = std::max(peaks[pass], static_cast<double>(std::fabs(got->left[static_cast<size_t>(i)])));
            }
        }
        ok("the dry recording is the sine that went in",
           std::fabs(peaks[1] - 0.4) < 0.02, "peak " + std::to_string(peaks[1]));
        ok("and the wet one is not - the amp is in the file",
           std::fabs(peaks[0] - peaks[1]) > 0.05,
           "wet " + std::to_string(peaks[0]) + " against dry " + std::to_string(peaks[1]));
        std::remove(wet.c_str());
        std::remove(dry.c_str());
    }

    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
