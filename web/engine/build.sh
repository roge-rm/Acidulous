#!/bin/bash
# Builds the engine as WebAssembly: web/engine/out/acidulous.{js,wasm}.
set -e
cd "$(dirname "$0")"
. "${EMSDK:-$HOME/.local/share/emsdk}/emsdk_env.sh" >/dev/null 2>&1
# Builds where Gradle says (ACIDULOUS_ENGINE_BUILD), otherwise in build/.
# Uses ccache if it's installed.
B="${ACIDULOUS_ENGINE_BUILD:-build}"
command -v ccache >/dev/null && export EM_COMPILER_WRAPPER=ccache
emcmake cmake -S . -B "$B" -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$B" -j8
mkdir -p out
cp "$B/acidulous.js" "$B/acidulous.wasm" out/
ls -la out
