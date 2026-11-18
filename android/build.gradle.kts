// Plugins are put on the classpath here once; modules apply them through the telltype.* conventions.
plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.spotless)
}

// ktlint for the build itself: root build files and the convention plugins in build-logic.
spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    val editorConfig = file(".editorconfig")
    kotlin {
        target("build-logic/**/*.kt")
        targetExclude("**/build/**")
        ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
    }
    kotlinGradle {
        target("*.gradle.kts", "build-logic/**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
    }
}
