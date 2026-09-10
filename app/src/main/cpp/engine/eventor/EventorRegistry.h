#pragma once
#include <cstdint>
#include <engine/core/Params.h>
#include <engine/eventor/Eventor.h>

namespace acidulous {

class EventorRegistry {
  public:
    static Eventor *create(const char *typeName);
    static const ParamDef *paramDefs(const char *typeName, int32_t &count);
    static int32_t count();
    static const char *name(int32_t index);
};

} // namespace acidulous
