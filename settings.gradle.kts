pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    // Settings' repositories win over a project's: the Kotlin/Wasm plugin adds
    // its own for Node, Yarn and Binaryen, which are declared below instead.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // The browser build's tools (web/app): Node.js, Yarn and Binaryen, fetched
        // by the Kotlin/Wasm plugin from their own release pages.
        ivy("https://nodejs.org/dist") {
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            patternLayout { artifact("version_[revision]/[module]-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

rootProject.name = "Acidulous"
include(":app")
include(":shared")
include(":desktop")
// The browser build's app: web/app, beside the engine's WebAssembly build in web/engine.
include(":webApp")
project(":webApp").projectDir = file("web/app")
 