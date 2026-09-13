#!/bin/bash
# The send delay under the address sanitiser. See tools/delay_test.cpp.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CPP="$ROOT/app/src/main/cpp"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
g++ -O1 -g -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I "$CPP" "$ROOT/tools/delay_test.cpp" -o "$DIR/delay_test" || exit 1
"$DIR/delay_test"
