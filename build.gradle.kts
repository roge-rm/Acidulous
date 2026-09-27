// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}

// acidulous.buildRoot in ~/.gradle/gradle.properties moves every module's
// build folder there, one folder per checkout. The build VM uses it to build
// in RAM. Without it everything builds in the usual place. Android's CMake
// staging and the desktop engine builds follow it too (app/ and
// desktop/build.gradle.kts).
providers.gradleProperty("acidulous.buildRoot").orNull?.let { root ->
    val base = File(root, rootDir.name)
    allprojects {
        layout.buildDirectory.set(File(base, if (this == rootProject) "root" else path.removePrefix(":").replace(':', '/')))
    }
}

