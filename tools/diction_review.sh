#!/bin/bash
# How Diction sings a recorded voice, in numbers: every vowel at three notes
# against the singer's own takes, and every consonant, starting, ending and
# inside words, checked by what makes it that consonant. See
# tools/diction_review/.
#   diction_review.sh <voice folder> [the three notes, e.g. 45,52,57]
# A voice folder is a bank.json with its takes, as the voice tab shares it.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
VOICE=$(cd "${1:?voice folder}" && pwd)
NOTES="${2:-45,52,57}"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
LIB=$("$ROOT/tools/host_engine.sh") || exit 1
g++ -O2 -std=c++17 -I "$ROOT/app/src/main/cpp" "$ROOT/tools/diction_words.cpp" "$LIB" -o "$DIR/diction_words" || exit 1
python3 "$ROOT/tools/diction_review/spec.py" "$VOICE" > "$DIR/voice.spec"
for n in ${NOTES//,/ }; do
    for v in IY IH EH AE AA AO AH UH UW ER; do echo "v-$v-$n|60|$n:2:$v"; done
done > "$DIR/vowels.txt"
python3 "$ROOT/tools/diction_review/consonants.py" phrases > "$DIR/consonants.txt"
# The consonants are sung a note up from the first vowel note's octave, as
# the phrases are written at 57.
FIRST=${NOTES%%,*}
OCTAVE=$(( (FIRST - 57) / 12 ))
mkdir -p "$DIR/vowels" "$DIR/consonants"
DICTION_PHRASES="$DIR/vowels.txt" "$DIR/diction_words" "$DIR/vowels" 0 "$DIR/voice.spec" 0 > /dev/null || exit 1
DICTION_PHRASES="$DIR/consonants.txt" "$DIR/diction_words" "$DIR/consonants" 0 "$DIR/voice.spec" "$OCTAVE" > /dev/null || exit 1
echo "--- vowels"
python3 "$ROOT/tools/diction_review/vowels.py" "$VOICE" "$DIR/vowels" "$NOTES"
echo "--- consonants"
python3 "$ROOT/tools/diction_review/consonants.py" grade "$VOICE" "$DIR/consonants"
