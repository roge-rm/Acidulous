# Sourced by the tools, after ROOT is set to the checkout. Build output goes
# under acidulous.buildRoot in ~/.gradle/gradle.properties if it's set (see
# the root build.gradle.kts), otherwise in the checkout.
BUILD_ROOT=$(sed -n 's/^acidulous\.buildRoot=//p' "$HOME/.gradle/gradle.properties" 2>/dev/null | tail -1)

# build_dir PROJECT FOLDER prints a Gradle project's build folder. PROJECT is
# its path without the colon (webApp), or root; FOLDER is where it sits in the
# checkout (web/app, or . for root).
build_dir() {
    if [ -n "$BUILD_ROOT" ]; then echo "$BUILD_ROOT/$(basename "$ROOT")/$1"
    else echo "$ROOT/$2/build"; fi
}

# The root project's build folder, where the host engine objects go.
BUILD=$(build_dir root .)
