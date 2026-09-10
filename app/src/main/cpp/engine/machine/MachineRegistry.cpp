#include "MachineRegistry.h"
#include "hexbeat/Hexbeat.h"
#include "subvert/Subvert.h"

namespace acidulous {

namespace {
const char *const kNames[] = {"Subvert", "Hexbeat"};
constexpr int32_t kCount = sizeof(kNames) / sizeof(kNames[0]);
} // namespace

Machine *MachineRegistry::create(const char *typeName) {
    if (std::strcmp(typeName, "Subvert") == 0) return new machine::Subvert();
    if (std::strcmp(typeName, "Hexbeat") == 0) return new machine::Hexbeat();
    return nullptr;
}

const ParamDef *MachineRegistry::paramDefs(const char *typeName, int32_t &count) {
    if (std::strcmp(typeName, "Subvert") == 0) {
        static const machine::Subvert probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Hexbeat") == 0) {
        static const machine::Hexbeat probe;
        return probe.paramDefs(count);
    }
    count = 0;
    return nullptr;
}

int32_t MachineRegistry::count() { return kCount; }
const char *MachineRegistry::name(int32_t index) { return (index >= 0 && index < kCount) ? kNames[index] : ""; }

} // namespace acidulous
