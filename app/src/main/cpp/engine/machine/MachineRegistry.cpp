#include "MachineRegistry.h"
#include "forage/Forage.h"
#include "hexbeat/Hexbeat.h"
#include "subvert/Subvert.h"
#include "ratio/Ratio.h"
#include "mosaic/Mosaic.h"
#include "trinity/Trinity.h"
#include "manual/Manual.h"
#include "cipher/Cipher.h"
#include "cumulus/Cumulus.h"
#include "formulate/Formulate.h"
#include "filament/Filament.h"
#include "nexus/Nexus.h"
#include "pollen/Pollen.h"
#include "dice/Dice.h"
#include "genesis/Genesis.h"
#include "resonance/Resonance.h"
#include "brazen/Brazen.h"
#include "timber/Timber.h"

namespace acidulous {

namespace {
const char *const kNames[] = {"Subvert", "Trinity", "Ratio", "Manual", "Cumulus", "Formulate", "Pollen", "Brazen", "Timber", "Cipher", "Filament", "Nexus", "Hexbeat", "Genesis", "Resonance", "Forage", "Dice", "Mosaic"};
constexpr int32_t kCount = sizeof(kNames) / sizeof(kNames[0]);
} // namespace

Machine *MachineRegistry::create(const char *typeName) {
    if (std::strcmp(typeName, "Subvert") == 0) return new machine::Subvert();
    if (std::strcmp(typeName, "Trinity") == 0) return new machine::Trinity();
    if (std::strcmp(typeName, "Ratio") == 0) return new machine::Ratio();
    if (std::strcmp(typeName, "Manual") == 0) return new machine::Manual();
    if (std::strcmp(typeName, "Cumulus") == 0) return new machine::Cumulus();
    if (std::strcmp(typeName, "Formulate") == 0) return new machine::Formulate();
    if (std::strcmp(typeName, "Pollen") == 0) return new machine::Pollen();
    if (std::strcmp(typeName, "Brazen") == 0) return new machine::Brazen();
    if (std::strcmp(typeName, "Timber") == 0) return new machine::Timber();
    if (std::strcmp(typeName, "Cipher") == 0) return new machine::Cipher();
    if (std::strcmp(typeName, "Filament") == 0) return new machine::Filament();
    if (std::strcmp(typeName, "Nexus") == 0) return new machine::Nexus();
    if (std::strcmp(typeName, "Hexbeat") == 0) return new machine::Hexbeat();
    if (std::strcmp(typeName, "Genesis") == 0) return new machine::Genesis();
    if (std::strcmp(typeName, "Resonance") == 0) return new machine::Resonance();
    if (std::strcmp(typeName, "Forage") == 0) return new machine::Forage();
    if (std::strcmp(typeName, "Dice") == 0) return new machine::Dice();
    if (std::strcmp(typeName, "Mosaic") == 0) return new machine::Mosaic();
    return nullptr;
}

const ParamDef *MachineRegistry::paramDefs(const char *typeName, int32_t &count) {
    if (std::strcmp(typeName, "Subvert") == 0) {
        static const machine::Subvert probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Trinity") == 0) {
        static const machine::Trinity probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Ratio") == 0) {
        static const machine::Ratio probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Manual") == 0) {
        static const machine::Manual probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Cumulus") == 0) {
        static const machine::Cumulus probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Formulate") == 0) {
        static const machine::Formulate probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Pollen") == 0) {
        static const machine::Pollen probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Brazen") == 0) {
        static const machine::Brazen probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Timber") == 0) {
        static const machine::Timber probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Cipher") == 0) {
        static const machine::Cipher probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Filament") == 0) {
        static const machine::Filament probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Nexus") == 0) {
        static const machine::Nexus probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Hexbeat") == 0) {
        static const machine::Hexbeat probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Genesis") == 0) {
        static const machine::Genesis probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Resonance") == 0) {
        static const machine::Resonance probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Forage") == 0) {
        static const machine::Forage probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Dice") == 0) {
        static const machine::Dice probe;
        return probe.paramDefs(count);
    }
    if (std::strcmp(typeName, "Mosaic") == 0) {
        static const machine::Mosaic probe;
        return probe.paramDefs(count);
    }
    count = 0;
    return nullptr;
}

int32_t MachineRegistry::count() { return kCount; }
const char *MachineRegistry::name(int32_t index) { return (index >= 0 && index < kCount) ? kNames[index] : ""; }

} // namespace acidulous
