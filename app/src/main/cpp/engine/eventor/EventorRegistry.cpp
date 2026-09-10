#include "EventorRegistry.h"
#include "Eventors.h"
#include <cstring>

namespace acidulous {

namespace {
using Factory = Eventor *(*)();
struct Entry { const char *name; Factory make; };
template <typename T> Eventor *make() { return new T(); }
const Entry kEntries[] = {
    {"Scale", make<eventor::Scale>},
    {"Chord", make<eventor::Chord>},
    {"Arp", make<eventor::Arp>},
};
constexpr int32_t kCount = sizeof(kEntries) / sizeof(kEntries[0]);
} // namespace

Eventor *EventorRegistry::create(const char *typeName) {
    if (typeName == nullptr) return nullptr;
    for (const auto &e : kEntries) if (std::strcmp(e.name, typeName) == 0) return e.make();
    return nullptr;
}

const ParamDef *EventorRegistry::paramDefs(const char *typeName, int32_t &count) {
    static Eventor *probes[kCount] = {};
    for (int32_t i = 0; i < kCount; ++i) {
        if (std::strcmp(kEntries[i].name, typeName ? typeName : "") == 0) {
            if (probes[i] == nullptr) probes[i] = kEntries[i].make();
            return probes[i]->paramDefs(count);
        }
    }
    count = 0;
    return nullptr;
}

int32_t EventorRegistry::count() { return kCount; }
const char *EventorRegistry::name(int32_t index) { return (index >= 0 && index < kCount) ? kEntries[index].name : ""; }

} // namespace acidulous
