#!/bin/bash
# The bank files, as the Kotlin that ships.
#
# tools/banks/<Unit>.bank is what a person edits and what tools/audition.sh
# plays; this turns it into app/src/main/java/com/rm/acidulous/model/
# FactoryBanks.kt, which is what the app reads. Run it after touching a bank.
#
# The same shape as tools/gen_param_labels.py: a generated Kotlin file with a
# header naming the command that regenerates it.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
exec "$ROOT/tools/audition.sh" emit "$@"
