// Checks the engine lines reader (platform/lines/EngineLines.cpp) on crafted
// lines: escapes, null, handles, the floats Kotlin writes, arrays and lines
// that must be refused. The calls here are stand-ins that report what they
// read, in place of the generated ones.

#include <platform/lines/EngineLines.h>

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using acidulous::lines::Reader;
using acidulous::lines::Replay;

namespace {
std::vector<std::string> got;

std::string show(jstring s) {
    if (s == nullptr) return "<null>";
    std::string out;
    for (unsigned char c : s->text) {
        if (c < 0x20 || c >= 0x7f || c == '\\') {
            char hex[8];
            std::snprintf(hex, sizeof hex, "\\%02X", c);
            out += hex;
        } else {
            out += static_cast<char>(c);
        }
    }
    return out;
}
std::string bits(float f) {
    uint32_t b;
    std::memcpy(&b, &f, 4);
    char t[16];
    std::snprintf(t, sizeof t, "%08X", b);
    return t;
}

bool str(Reader &in, Replay &) {
    jstring a = in.str();
    if (!in.finish()) return false;
    got.push_back(show(a));
    return true;
}
bool path(Reader &in, Replay &) {
    jstring a = in.path();
    if (!in.finish()) return false;
    got.push_back(show(a));
    return true;
}
bool spec(Reader &in, Replay &) {
    jstring a = in.spec({1});
    if (!in.finish()) return false;
    got.push_back(show(a));
    return true;
}
bool f(Reader &in, Replay &) {
    std::string out;
    for (int k = 0; k < 6; ++k) out += bits(in.f32()) + " ";
    if (!in.finish()) return false;
    got.push_back(out);
    return true;
}
bool ints(Reader &in, Replay &) {
    const jint a = in.i32();
    const jlong b = in.i64();
    const jboolean c = in.flag();
    if (!in.finish()) return false;
    got.push_back(std::to_string(a) + " " + std::to_string(b) + " " + std::to_string(c));
    return true;
}
bool begin(Reader &in, Replay &r) {
    if (!in.finish()) return false;
    r.handles.push_back(1000 + static_cast<jlong>(r.handles.size()));
    return true;
}
bool use(Reader &in, Replay &) {
    const jlong h = in.handle();
    if (!in.finish()) return false;
    got.push_back(std::to_string(h));
    return true;
}
bool arrays(Reader &in, Replay &) {
    jintArray a = in.ints();
    jfloatArray b = in.floats();
    jobjectArray c = in.strings();
    if (!in.finish()) return false;
    std::string out;
    out += a == nullptr ? "null" : std::to_string(a->items.size()) + (a->items.empty() ? "" : ":" + std::to_string(a->items.back()));
    out += " ";
    out += b == nullptr ? "null" : std::to_string(b->items.size());
    out += " ";
    if (c == nullptr) out += "null";
    else for (auto *o : c->items) out += show(static_cast<jstring>(o)) + ",";
    got.push_back(out);
    return true;
}
} // namespace

namespace acidulous::lines {
const Call kCalls[] = {{"Str", str},   {"Path", path}, {"Spec", spec}, {"F", f},
                       {"Ints", ints}, {"Begin", begin}, {"Use", use}, {"Arrays", arrays}};
const int kCallCount = sizeof kCalls / sizeof kCalls[0];
} // namespace acidulous::lines

int main() {
    const std::string text = R"(acidulous-engine 3
# comment
Str "a \"q\" \\ \n\t\x01\x00z"
Str null
Str "é😀"
Path "zip:samples/a b.wav"
Path "/abs/x.wav"
Spec "zip:no|zip:yes|zip:no\nzip:no|zip:yes"
F 0.1 -0.0 NaN Infinity -Infinity 1.0E-5
Ints -2147483648 9007199254740993 true
Begin
Begin
Use @1
Use @0
Arrays i[3: 1 2 3] f[0:] s[2: "x" null]
Arrays null null null
Str nullx
Str "unterminated
Str "bad \q escape"
Str "bad \x4"
Ints 2147483648 0 true
Ints 1 2 maybe
Ints 1 2 true extra
Use @2
Use 0
Arrays i[3: 1 2] f[0:] null
Arrays i[2: 1 2 3] f[0:] null
Arrays i[-1:] f[0:] null
Arrays i[99999999: 1] f[0:] null
Arrays i[2: 1 2 f[0:] null
Nope 1
F 1 2 3 4 5
)";
    static JNIEnv env;
    Replay r;
    r.env = &env;
    r.root = "/root";
    std::vector<std::string> problems;
    r.log = [&](const std::string &p) { problems.push_back(p); };
    const bool read = acidulous::lines::replay(text, r);

    const std::vector<std::string> want = {
        "a \"q\" \\5C \\0A\\09\\01\\C0\\80z",
        "<null>",
        "\\C3\\A9\\ED\\A0\\BD\\ED\\B8\\80",
        "/root/samples/a b.wav",
        "/abs/x.wav",
        "zip:no|/root/yes|zip:no\\0Azip:no|/root/yes",
        "3DCCCCCD 80000000 7FC00000 7F800000 FF800000 3727C5AC ",
        "-2147483648 9007199254740993 1",
        "1001",
        "1000",
        "3:3 0 x,<null>,",
        "null null null",
    };
    int fail = 0;
    auto check = [&](bool ok, const std::string &what) {
        std::printf("  %s %s\n", ok ? "ok  " : "FAIL", what.c_str());
        if (!ok) fail = 1;
    };
    check(read, "the header is accepted");
    for (size_t k = 0; k < want.size() || k < got.size(); ++k) {
        const std::string g = k < got.size() ? got[k] : "(nothing)";
        const std::string w = k < want.size() ? want[k] : "(nothing)";
        check(g == w, "call " + std::to_string(k + 1) + ": " + g + (g == w ? "" : "   wanted " + w));
    }
    check(r.calls == 14, "14 calls made (" + std::to_string(r.calls) + ")");
    check(r.skipped == 16, "16 bad lines skipped (" + std::to_string(r.skipped) + ")");
    check(problems.size() == 16, "each bad line reported (" + std::to_string(problems.size()) + ")");

    Replay other;
    other.env = &env;
    other.log = [](const std::string &) {};
    check(!acidulous::lines::replay("acidulous-engine 4\nStr \"x\"\n", other), "another version is refused");
    Replay two;
    two.env = &env;
    two.log = [](const std::string &) {};
    check(acidulous::lines::replay("acidulous-engine 2\n", two) && two.version == 2, "version 2 is read");

    // Version 1 lines: Trinity's waves were over 12 steps and Nexus's wtosc
    // tables over 8. Each must land on the same wave or table.
    Replay u;
    u.version = 1;
    u.noteTables("LoadNexusPatch 3 \"v|1\\nm|01|wtosc|poly\\nm|02|osc|poly\"");
    u.upgradeLine("MountMachine 2 \"Trinity\"");
    u.upgradeLine("MountMachine 3 \"Nexus\"");
    auto f9 = [](double v) { char b[32]; std::snprintf(b, sizeof b, "%.9g", static_cast<double>(static_cast<float>(v))); return std::string(b); };
    const std::pair<std::string, std::string> upgrades[] = {
        {"SetParam 2 \"machine\" \"o1_wave\" 0.6363636 false 0", "SetParam 2 \"machine\" \"o1_wave\" " + f9(7.0 / 35.0) + " false 0"},
        {"SetParam 2 \"machine\" \"o3_wave\" 1.0 false 0", "SetParam 2 \"machine\" \"o3_wave\" " + f9(11.0 / 35.0) + " false 0"},
        {"SetParam 2 \"machine\" \"o1_pos\" 0.5 false 0", "SetParam 2 \"machine\" \"o1_pos\" 0.5 false 0"},
        {"SetParam 2 \"effect1\" \"o1_wave\" 0.5 false 0", "SetParam 2 \"effect1\" \"o1_wave\" 0.5 false 0"},
        {"SetParam 3 \"machine\" \"s01_p1\" 0.3 false 0", "SetParam 3 \"machine\" \"s01_p1\" " + f9(2.5 / 32.0) + " false 0"},
        {"SetParam 3 \"machine\" \"s02_p1\" 0.3 false 0", "SetParam 3 \"machine\" \"s02_p1\" 0.3 false 0"},
        {"SetParam 3 \"machine\" \"s01_p2\" 0.3 false 0", "SetParam 3 \"machine\" \"s01_p2\" 0.3 false 0"},
        {"SnapshotSetLane @0 2 1 \"Trinity\" \"machine\" \"o2_wave\" false f[4: 0.0 0.27272728 1536.0 -1.0]",
         "SnapshotSetLane @0 2 1 \"Trinity\" \"machine\" \"o2_wave\" false f[4: 0.0 " + f9(3.0 / 35.0) + " 1536.0 -1.0]"},
        {"SnapshotSetLane @0 3 1 \"Nexus\" \"machine\" \"s01_p1\" true f[2: 0.0 0.99]",
         "SnapshotSetLane @0 3 1 \"Nexus\" \"machine\" \"s01_p1\" true f[2: 0.0 " + f9(7.5 / 32.0) + "]"},
        {"SnapshotSetLane @0 2 1 \"Trinity\" \"machine\" \"o2_wave\" false f[0:]",
         "SnapshotSetLane @0 2 1 \"Trinity\" \"machine\" \"o2_wave\" false f[0:]"},
    };
    for (const auto &[in, want] : upgrades) {
        const std::string got = u.upgradeLine(in);
        check(got == want, "upgrade " + in + (got == want ? "" : "   got " + got));
    }
    // Versions 1 and 2: Trinity's destinations were over 34 steps.
    {
        const std::string in = "SetParam 2 \"machine\" \"mac3_d1\" " + f9(31.0 / 33.0) + " false 0";
        const std::string want = "SetParam 2 \"machine\" \"mac3_d1\" " + f9(31.0 / 36.0) + " false 0";
        check(u.upgradeLine(in) == want, "upgrade a version 1 destination");
        Replay v2;
        v2.version = 2;
        v2.upgradeLine("MountMachine 2 \"Trinity\"");
        check(v2.upgradeLine(in) == want, "upgrade a version 2 destination");
        const std::string lane = "SnapshotSetLane @0 2 1 \"Trinity\" \"machine\" \"m04_dest\" false f[2: 0.0 1.0]";
        check(v2.upgradeLine(lane) == "SnapshotSetLane @0 2 1 \"Trinity\" \"machine\" \"m04_dest\" false f[2: 0.0 " + f9(33.0 / 36.0) + "]",
              "upgrade a version 2 destination lane");
        const std::string wave = "SetParam 2 \"machine\" \"o1_wave\" 0.6363636 false 0";
        check(v2.upgradeLine(wave) == wave, "a version 2 wave is left alone");
        const std::string depth = "SetParam 2 \"machine\" \"mac3_a1\" 0.6 false 0";
        check(v2.upgradeLine(depth) == depth, "a depth is left alone");
    }
    u.upgradeLine("UnmountMachine 2");
    check(u.upgradeLine("SetParam 2 \"machine\" \"o1_wave\" 0.5 false 0") == "SetParam 2 \"machine\" \"o1_wave\" 0.5 false 0",
          "an unmounted rack is left alone");
    return fail;
}
