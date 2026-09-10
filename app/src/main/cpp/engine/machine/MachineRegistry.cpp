#include "MachineRegistry.h"
#include "subvert/SubVert.h"

namespace acidulous {

namespace {
const char *const kNames[] = {"SubVert"};
constexpr int32_t kCount = sizeof(kNames) / sizeof(kNames[0]);
} // namespace

Machine *MachineRegistry::create(const char *typeName) {
    if (std::strcmp(typeName, "SubVert") == 0) return new machine::SubVert();
    return nullptr;
}

const ParamDef *MachineRegistry::paramDefs(const char *typeName, int32_t &count) {
    if (std::strcmp(typeName, "SubVert") == 0) {
        static const machine::SubVert probe;
        return probe.paramDefs(count);
    }
    count = 0;
    return nullptr;
}

int32_t MachineRegistry::count() { return kCount; }
const char *MachineRegistry::name(int32_t index) { return (index >= 0 && index < kCount) ? kNames[index] : ""; }

} // namespace acidulous
