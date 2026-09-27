#!/bin/bash
# The engine as WebAssembly: web/engine/out/acidulous.{js,wasm}.
set -e
cd "$(dirname "$0")"
. "${EMSDK:-$HOME/.local/share/emsdk}/emsdk_env.sh" >/dev/null 2>&1
# The build folder where Gradle says (ACIDULOUS_ENGINE_BUILD), build/ otherwise;
# and every compile through the compiler cache where this machine has one.
B="${ACIDULOUS_ENGINE_BUILD:-build}"
command -v ccache >/dev/null && export EM_COMPILER_WRAPPER=ccache
emcmake cmake -S . -B "$B" -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$B" -j8
mkdir -p out
cp "$B/acidulous.js" "$B/acidulous.wasm" out/
ls -la out
