#!/bin/bash
# Turns the bank files into the Kotlin the app ships.
#
# tools/banks/<Unit>.bank is what you edit and what tools/audition.sh plays.
# This writes shared/src/commonMain/kotlin/com/rm/acidulous/model/
# FactoryBanks.kt, which the app reads. Run it after changing a bank.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
exec "$ROOT/tools/audition.sh" emit "$@"
