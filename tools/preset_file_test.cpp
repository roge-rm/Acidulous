// The engine's .acpreset reader and writer (engine/core/PresetFile.h) and
// EngineHost's calls on them. Reads the app's fixture, writes the one the
// app's PresetFixtureTest.kt imports, and saves and reads a rack's patch.
//
// ACIDULOUS_WRITE_FIXTURES=1 writes native-bank.acpreset again.
// `preset_file_test --unzip FILE` lists a zip's entries with their sizes,
// for preset_file_test.sh to check the inflate against Python's zips.
#include <EngineHost.h>
#include <drivers/AudioDriver.h>
#include <engine/core/PresetFile.h>
#include <engine/effect/EffectRegistry.h>
#include <engine/machine/MachineRegistry.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <string>
#include <thread>

using namespace acidulous;

namespace {

int failures = 0;

void check(bool ok, const std::string &what) {
    if (!ok) {
        std::printf("  FAIL %s\n", what.c_str());
        ++failures;
    }
}

std::vector<uint8_t> load(const std::string &path) {
    std::ifstream in(path, std::ios::binary);
    return std::vector<uint8_t>(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
}

void save(const std::string &path, const std::vector<uint8_t> &bytes) {
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char *>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
}

// The same as PresetFixtureTest.document().
std::string document() {
    std::string s;
    for (int i = 1; i <= 60; ++i) {
        const std::string n = std::to_string(i);
        s += "block osc" + n + " saw \"quoted\"\tpatch osc" + n + ".out -> mix.in" + n + "\n";
    }
    return s + "caf\xc3\xa9 \xe2\x9c\x93 \xf0\x9f\x8e\xb9";
}

// The same as PresetFixtureTest.patches().
Preset fixture() {
    Preset p;
    p.unit = "Trinity";
    PresetPatch glass;
    glass.machine = "Trinity";
    glass.name = "Fixture Glass";
    glass.params = {{"cutoff", 0.25f}, {"res", 0.1f}, {"drive", 1.0f}, {"level", 0.0f}, {"tiny", 3.0e-5f}, {"odd", 0.123456789f}};
    glass.settings = {{"macro1", "Bright"}, {"nexus", document()}};
    glass.low = 48;
    glass.high = 72;
    glass.family = "lead";
    PatchInsert chorus;
    chorus.type = "Chorus";
    chorus.params = {{"mix", 0.4f}, {"rate", 0.7f}};
    chorus.bypass = true;
    chorus.settings = {{"formula", "x*2"}};
    PatchInsert delay;
    delay.type = "Delay";
    delay.params = {{"time", 0.5f}};
    glass.chain.inserts = {chorus, delay};
    glass.chain.hasSends = true;
    glass.chain.sendReverb = 0.3f;
    glass.chain.sendDelay = 0.0f;
    PresetPatch init;
    init.machine = "Trinity";
    init.name = "Fixture Init";
    init.params = {{"cutoff", 0.5f}};
    p.patches = {glass, init};
    return p;
}

template <typename T>
bool sameSet(std::vector<std::pair<std::string, T>> a, std::vector<std::pair<std::string, T>> b) {
    std::sort(a.begin(), a.end());
    std::sort(b.begin(), b.end());
    return a == b;
}

bool samePatch(const PresetPatch &a, const PresetPatch &b, const std::string &what) {
    bool ok = a.machine == b.machine && a.name == b.name && a.low == b.low && a.high == b.high &&
              a.family == b.family && a.version == b.version && sameSet(a.params, b.params) &&
              sameSet(a.settings, b.settings) && a.chain.hasSends == b.chain.hasSends &&
              a.chain.sendReverb == b.chain.sendReverb && a.chain.sendDelay == b.chain.sendDelay &&
              a.chain.inserts.size() == b.chain.inserts.size();
    for (size_t i = 0; ok && i < a.chain.inserts.size(); ++i) {
        const PatchInsert &x = a.chain.inserts[i], &y = b.chain.inserts[i];
        ok = x.type == y.type && x.bypass == y.bypass && sameSet(x.params, y.params) && sameSet(x.settings, y.settings);
    }
    check(ok, what + ": " + a.name);
    return ok;
}

void same(const Preset &got, const Preset &want, const std::string &what) {
    check(got.kind == want.kind && got.unit == want.unit, what + ": kind and unit");
    check(got.patches.size() == want.patches.size(), what + ": patch count");
    for (size_t i = 0; i < got.patches.size() && i < want.patches.size(); ++i) samePatch(got.patches[i], want.patches[i], what);
}

void fixtures(const std::string &dir) {
    std::string error;
    Preset got;
    const std::vector<uint8_t> app = load(dir + "/app-bank.acpreset");
    check(!app.empty(), "the app's fixture is there");
    check(presetfile::read(app.data(), app.size(), got, error), "the app's fixture reads: " + error);
    same(got, fixture(), "the app's fixture");

    const std::vector<uint8_t> mine = presetfile::write(fixture());
    const char *rewrite = std::getenv("ACIDULOUS_WRITE_FIXTURES");
    if (rewrite != nullptr && std::string(rewrite) == "1") save(dir + "/native-bank.acpreset", mine);
    check(mine == load(dir + "/native-bank.acpreset"), "the writer gives native-bank.acpreset (see this file's comment)");
    Preset back;
    check(presetfile::read(mine.data(), mine.size(), back, error), "its own file reads: " + error);
    same(back, fixture(), "its own file");
    check(presetfile::write(fixture()) == mine, "the same patches give the same bytes");
}

void refusals() {
    std::string error;
    Preset out;
    const std::vector<uint8_t> good = presetfile::write(fixture());
    check(!presetfile::read(good.data(), good.size() / 2, out, error) && !error.empty(), "a cut-off file");
    const uint8_t junk[40] = {'n', 'o', 't'};
    check(!presetfile::read(junk, sizeof junk, out, error), "not a zip");

    Preset bad = fixture();
    bad.unit = "../Trinity";
    std::vector<uint8_t> b = presetfile::write(bad);
    check(!presetfile::read(b.data(), b.size(), out, error), "a unit name that could leave its folder");
    bad.unit = "Trinity";
    bad.patches.clear();
    b = presetfile::write(bad);
    check(!presetfile::read(b.data(), b.size(), out, error), "no patches");

    // A flipped byte in a patch fails its CRC.
    b = good;
    const std::string marker = "Fixture Glass";
    auto at = std::search(b.begin(), b.end(), marker.begin(), marker.end());
    check(at != b.end(), "the name is in the stored entry");
    if (at != b.end()) *at = 'f';
    check(!presetfile::read(b.data(), b.size(), out, error) && error.find("CRC") != std::string::npos, "a bad CRC");

    Preset effect;
    effect.kind = "effect";
    effect.unit = "Chorus";
    PresetPatch fx;
    fx.name = "Wide";
    fx.params = {{"mix", 0.5f}};
    effect.patches = {fx};
    b = presetfile::write(effect);
    check(presetfile::read(b.data(), b.size(), out, error) && out.kind == "effect" && out.unit == "Chorus" &&
              out.patches.size() == 1 && out.patches[0].machine == "Chorus",
          "an effect preset");
}

void waitBlocks(int n) {
    const int64_t until = AudioDriver::blocks() + n;
    for (int i = 0; i < 2000 && AudioDriver::blocks() < until; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(1));
}

void host() {
    EngineHost &h = EngineHost::instance();
    check(h.start(), "the host starts");
    const int rack = 2;
    check(h.mountMachine(rack, "Trinity"), "Trinity mounts");
    check(h.mountEffect(rack, 0, "Chorus") && h.mountEffect(rack, 1, "Formula"), "inserts mount");
    waitBlocks(4);
    int32_t n = 0;
    const ParamDef *defs = MachineRegistry::paramDefs("Trinity", n);
    std::vector<std::pair<std::string, float>> want;
    for (int32_t i = 0; i < n && i < 6; ++i) {
        const float v = 0.1f + 0.13f * static_cast<float>(i);
        h.setParam(rack, "machine", defs[i].name, v, false);
        want.emplace_back(defs[i].name, v);
    }
    int32_t fxCount = 0;
    const ParamDef *fxDefs = EffectRegistry::paramDefs("Chorus", fxCount);
    check(fxCount > 0, "Chorus has parameters");
    if (fxCount > 0) h.setParam(rack, "effect1", fxDefs[0].name, 0.625f, false);
    h.setParam(rack, "effect1", "bypass", 1.0f, false);
    // A mod on the chorus, and none on the formula.
    h.setParam(rack, "effect1", "mod_source", 0.25f, false);
    h.setParam(rack, "effect1", "mod_target1", 2.0f / 64.0f, false);
    const std::string formulaError = h.loadEffectFormula(rack, "effect2", "x/2");
    check(formulaError.empty(), "a formula loads: " + formulaError);
    h.setParam(rack, "channel", "sendreverb", 0.25f, false);
    h.setMacroNames(rack, "Bright\n\nWide\n\n\n\n\n");
    waitBlocks(4);

    std::string why;
    const PresetPatch p = h.rackPatch(rack, "Saved", why);
    check(why.empty(), "the rack's patch: " + why);
    check(p.machine == "Trinity" && p.name == "Saved", "machine and name");
    check(static_cast<int32_t>(p.params.size()) == n, "every parameter");
    for (const auto &kv : want) {
        bool found = false;
        for (const auto &have : p.params) if (have.first == kv.first) found = have.second == kv.second;
        check(found, "the value set on " + kv.first);
    }
    check(sameSet(p.settings, {{"macro1", "Bright"}, {"macro3", "Wide"}}), "the named macros, and only those");
    check(p.chain.inserts.size() == 2, "both inserts");
    if (p.chain.inserts.size() == 2) {
        const PatchInsert &chorus = p.chain.inserts[0];
        int32_t modCount = 0;
        slotmod::defs(modCount);
        check(chorus.type == "Chorus" && chorus.bypass && static_cast<int32_t>(chorus.params.size()) == fxCount + modCount,
              "the chorus, bypassed, with all its parameters and its mod's");
        bool target = false;
        for (const auto &kv : chorus.params) if (kv.first == "mod_target1") target = kv.second == 2.0f / 64.0f;
        check(target, "the chorus's mod target");
        check(p.chain.inserts[1].params.size() == static_cast<size_t>(EffectRegistry::paramDefs("Formula", modCount) ? modCount : 0),
              "the formula, with no mod, saves none of the mod's");
        if (fxCount > 0 && !chorus.params.empty()) check(chorus.params[0].second == 0.625f, "the chorus's knob");
        check(p.chain.inserts[1].type == "Formula" && sameSet(p.chain.inserts[1].settings, {{"formula", "x/2"}}),
              "the formula");
    }
    check(p.chain.hasSends && p.chain.sendReverb == 0.25f && p.chain.sendDelay == 0.0f, "the sends");
    check(h.rackPatch(rack, "Dry", why, false).chain.inserts.empty(), "without effects");
    check(h.rackPatch(5, "None", why).machine.empty() && !why.empty(), "an empty rack");
    check(h.writePreset(5, "None", why).empty() && !why.empty(), "an empty rack writes nothing");

    std::vector<uint8_t> bytes = h.writePreset(rack, "Saved", why, {{"nexus", "a\nb"}, {"macro1", "Dark"}}, 36, 60);
    Preset back;
    std::string error;
    check(EngineHost::readPreset(bytes.data(), bytes.size(), back, error), "the rack's file reads: " + error);
    if (back.patches.size() == 1) {
        PresetPatch expect = p;
        expect.low = 36;
        expect.high = 60;
        expect.settings = {{"macro1", "Dark"}, {"macro3", "Wide"}, {"nexus", "a\nb"}};
        samePatch(back.patches[0], expect, "the rack's file");
    } else {
        check(false, "one patch in the rack's file");
    }

    // And onto another track by the usual path.
    const int other = 4;
    h.mountMachine(other, back.unit);
    for (const auto &kv : back.patches[0].params) h.setParam(other, "machine", kv.first, kv.second, false);
    PatchTrackState state;
    h.applyPatchChain(other, back.patches[0].chain, state, 0.0f, 0.0f);
    waitBlocks(4);
    const PresetPatch copy = h.rackPatch(other, "Saved", why);
    PresetPatch expect = p;
    expect.settings.clear(); // macro names and the formula are the host's to pass on
    expect.chain.inserts[1].settings = {{"formula", "x/2"}};
    samePatch(copy, expect, "the patch on another track");

    // Sends a patch holds are saved even at zero, as the app does; the player's at zero aren't.
    const int quiet = 6;
    h.mountMachine(quiet, "Trinity");
    PatchChain zero;
    zero.hasSends = true;
    PatchTrackState held;
    h.applyPatchChain(quiet, zero, held, 0.0f, 0.0f);
    waitBlocks(4);
    const PresetPatch kept = h.rackPatch(quiet, "Quiet", why, true, &held);
    check(held.sendsHeld && kept.chain.hasSends && kept.chain.sendReverb == 0.0f && kept.chain.sendDelay == 0.0f,
          "held sends at zero are saved");
    check(!h.rackPatch(quiet, "Quiet", why).chain.hasSends, "without the track's state, sends at zero aren't");
    h.setParam(quiet, "channel", "senddelay", 0.5f, false);
    waitBlocks(4);
    const PresetPatch moved = h.rackPatch(quiet, "Quiet", why, true, &held);
    check(moved.chain.hasSends && moved.chain.sendDelay == 0.5f, "a send moved by hand is the player's, saved when up");

    // A mount the audio thread hasn't taken yet: no patch, and why.
    h.stop();
    h.mountEffect(quiet, 0, "Chorus");
    const PresetPatch early = h.rackPatch(quiet, "Early", why);
    check(early.machine.empty() && !why.empty(), "a pending insert mount fails");
    check(h.writePreset(quiet, "Early", why).empty(), "and writes nothing");
    h.mountMachine(quiet, "Reflux");
    check(h.rackPatch(quiet, "Early", why).machine.empty(), "a pending machine mount fails");
    check(h.start(), "the host starts again");
    waitBlocks(4);
    const PresetPatch landed = h.rackPatch(quiet, "Landed", why);
    check(landed.machine == "Reflux" && landed.chain.inserts.size() == 1 && why.empty(), "once they land it reads: " + why);
    h.stop();
}

// A zip by hand: stored entries, each pointing at the data at [offsets].
struct RawZip {
    std::vector<uint8_t> bytes;
    void put16(std::vector<uint8_t> &o, uint32_t v) { o.push_back(v & 0xff); o.push_back((v >> 8) & 0xff); }
    void put32(std::vector<uint8_t> &o, uint32_t v) { put16(o, v & 0xffff); put16(o, v >> 16); }
    uint32_t local(const std::string &name, const std::vector<uint8_t> &data, uint32_t crc) {
        const uint32_t at = static_cast<uint32_t>(bytes.size());
        put32(bytes, 0x04034b50u);
        put16(bytes, 20); put16(bytes, 0); put16(bytes, 0); put16(bytes, 0); put16(bytes, 0x21);
        put32(bytes, crc); put32(bytes, data.size()); put32(bytes, data.size());
        put16(bytes, name.size()); put16(bytes, 0);
        bytes.insert(bytes.end(), name.begin(), name.end());
        bytes.insert(bytes.end(), data.begin(), data.end());
        return at;
    }
    struct Entry { std::string name; uint32_t offset, size, crc; };
    void finish(const std::vector<Entry> &entries) {
        std::vector<uint8_t> dir;
        for (const Entry &e : entries) {
            put32(dir, 0x02014b50u);
            put16(dir, 20); put16(dir, 20); put16(dir, 0); put16(dir, 0); put16(dir, 0); put16(dir, 0x21);
            put32(dir, e.crc); put32(dir, e.size); put32(dir, e.size);
            put16(dir, e.name.size()); put16(dir, 0); put16(dir, 0); put16(dir, 0); put16(dir, 0);
            put32(dir, 0); put32(dir, e.offset);
            dir.insert(dir.end(), e.name.begin(), e.name.end());
        }
        const uint32_t at = static_cast<uint32_t>(bytes.size());
        bytes.insert(bytes.end(), dir.begin(), dir.end());
        put32(bytes, 0x06054b50u);
        put16(bytes, 0); put16(bytes, 0); put16(bytes, entries.size()); put16(bytes, entries.size());
        put32(bytes, dir.size()); put32(bytes, at); put16(bytes, 0);
    }
};

// The CRC the reader checks, worked out the slow way.
uint32_t crcOf(const std::vector<uint8_t> &data) {
    uint32_t c = 0xffffffffu;
    for (const uint8_t b : data) {
        c ^= b;
        for (int k = 0; k < 8; ++k) c = (c & 1u) ? 0xedb88320u ^ (c >> 1) : c >> 1;
    }
    return c ^ 0xffffffffu;
}

void hostile() {
    std::string error;
    Preset out;
    const std::vector<uint8_t> mb(1u << 20, ' ');
    const uint32_t mbSize = static_cast<uint32_t>(mb.size());
    const uint32_t crc = crcOf(mb);
    {
        // One megabyte named forty times: every entry shares one local header.
        RawZip z;
        const uint32_t at = z.local("patches/1.json", mb, crc);
        std::vector<RawZip::Entry> entries;
        for (int i = 1; i <= 40; ++i) entries.push_back({"patches/" + std::to_string(i) + ".json", at, mbSize, crc});
        z.finish(entries);
        check(!presetfile::read(z.bytes.data(), z.bytes.size(), out, error) && error.find("shares") != std::string::npos,
              "entries sharing their data are turned down: " + error);
    }
    {
        // Nine separate megabytes: past the total.
        RawZip z;
        std::vector<RawZip::Entry> entries;
        for (int i = 1; i <= 9; ++i) {
            const std::string name = "patches/" + std::to_string(i) + ".json";
            entries.push_back({name, z.local(name, mb, crc), mbSize, crc});
        }
        z.finish(entries);
        check(!presetfile::read(z.bytes.data(), z.bytes.size(), out, error) && error.find("too big") != std::string::npos,
              "more than the total is turned down: " + error);
        std::vector<std::pair<std::string, std::vector<uint8_t>>> got;
        check(!presetfile::unzip(z.bytes.data(), z.bytes.size(), got, error) && error.find("too big") != std::string::npos,
              "unzip turns down more than 8 MB by default: " + error);
        got.clear();
        check(presetfile::unzip(z.bytes.data(), z.bytes.size(), got, error, 16u << 20) && got.size() == 9,
              "and takes it with a bigger maxTotal: " + error);
    }
}

void strings() {
    // A lone surrogate becomes U+FFFD; a pair becomes its character.
    Preset p = fixture();
    p.patches.resize(1);
    p.patches[0].settings = {{"text", "PLACEHOLDER"}};
    std::vector<std::pair<std::string, std::vector<uint8_t>>> entries;
    std::string error;
    std::vector<uint8_t> zip = presetfile::write(p);
    check(presetfile::unzip(zip.data(), zip.size(), entries, error), "unzip: " + error);
    std::string manifest, patch;
    for (auto &e : entries) {
        if (e.first == "preset.json") manifest.assign(e.second.begin(), e.second.end());
        else patch.assign(e.second.begin(), e.second.end());
    }
    const auto with = [&](const std::string &text, const std::string &machine) {
        std::string json = patch;
        json.replace(json.find("PLACEHOLDER"), 11, text);
        json.replace(json.find("\"Trinity\""), 9, machine);
        RawZip z;
        const std::vector<uint8_t> a(manifest.begin(), manifest.end()), b(json.begin(), json.end());
        const uint32_t ma = z.local("preset.json", a, crcOf(a));
        const uint32_t pa = z.local("patches/1.json", b, crcOf(b));
        z.finish({{"preset.json", ma, static_cast<uint32_t>(a.size()), crcOf(a)},
                  {"patches/1.json", pa, static_cast<uint32_t>(b.size()), crcOf(b)}});
        return z.bytes;
    };
    Preset got;
    std::vector<uint8_t> b = with("a\\ud800b \\ud83c\\udfb9", "\"Trinity\"");
    check(presetfile::read(b.data(), b.size(), got, error) && !got.patches.empty() &&
              got.patches[0].settings[0].second == "a\xef\xbf\xbd" "b \xf0\x9f\x8e\xb9",
          "surrogates: " + error);
    b = with("x", "7");
    check(!presetfile::read(b.data(), b.size(), got, error), "a machine that isn't a string is turned down");
}

int unzipList(const char *path) {
    const std::vector<uint8_t> bytes = load(path);
    std::vector<std::pair<std::string, std::vector<uint8_t>>> entries;
    std::string error;
    if (!presetfile::unzip(bytes.data(), bytes.size(), entries, error)) {
        std::printf("error %s\n", error.c_str());
        return 1;
    }
    for (const auto &e : entries) std::printf("%s %zu\n", e.first.c_str(), e.second.size());
    return 0;
}

} // namespace

int main(int argc, char **argv) {
    if (argc == 3 && std::string(argv[1]) == "--unzip") return unzipList(argv[2]);
    const char *root = std::getenv("ACIDULOUS_ROOT");
    fixtures(std::string(root != nullptr ? root : ".") + "/shared/src/desktopTest/resources/acpreset");
    refusals();
    hostile();
    strings();
    host();
    std::printf("preset file: %s (%d failures)\n", failures == 0 ? "ok" : "FAILED", failures);
    return failures == 0 ? 0 : 1;
}
