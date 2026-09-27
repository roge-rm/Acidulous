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

// **Build output where this machine says, when it says.** `acidulous.buildRoot`
// in ~/.gradle/gradle.properties moves every module's build folder there, one
// folder per checkout so a worktree and the main checkout never share one.
// The build VM puts it in RAM (/tmp is tmpfs there) because /home is on a
// slow disk; the finished pieces are copied out to the downloads folder as
// they always were. Unset - any other machine - everything builds where it
// always has. Android's CMake staging and the desktop's engine builds follow
// it too (app/ and desktop/build.gradle.kts).
providers.gradleProperty("acidulous.buildRoot").orNull?.let { root ->
    val base = File(root, rootDir.name)
    allprojects {
        layout.buildDirectory.set(File(base, if (this == rootProject) "root" else path.removePrefix(":").replace(':', '/')))
    }
}

