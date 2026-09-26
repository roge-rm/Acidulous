#!/bin/bash
# The engine as WebAssembly: web/engine/out/acidulous.{js,wasm}.
set -e
cd "$(dirname "$0")"
. "${EMSDK:-$HOME/.local/share/emsdk}/emsdk_env.sh" >/dev/null 2>&1
emcmake cmake -S . -B build -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build build -j8
mkdir -p out
cp build/acidulous.js build/acidulous.wasm out/
ls -la out
