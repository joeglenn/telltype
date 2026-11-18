plugins {
    `kotlin-dsl`
}

group = "net.jbglenn.telltype.buildlogic"

kotlin {
    jvmToolchain(libs.versions.javaToolchain.get().toInt())
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.spotless.gradlePlugin)
    compileOnly(libs.roborazzi.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "telltype.jvm.library"
            implementationClass = "net.jbglenn.telltype.buildlogic.JvmLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "telltype.android.library"
            implementationClass = "net.jbglenn.telltype.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "telltype.android.compose"
            implementationClass = "net.jbglenn.telltype.buildlogic.AndroidComposeConventionPlugin"
        }
        register("androidApplication") {
            id = "telltype.android.application"
            implementationClass = "net.jbglenn.telltype.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("hilt") {
            id = "telltype.hilt"
            implementationClass = "net.jbglenn.telltype.buildlogic.HiltConventionPlugin"
        }
        register("test") {
            id = "telltype.test"
            implementationClass = "net.jbglenn.telltype.buildlogic.TestConventionPlugin"
        }
    }
}
