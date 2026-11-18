package net.jbglenn.telltype.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/** Toolchain and compiler flags shared by every Kotlin module, JVM or Android. */
internal fun Project.configureKotlin(kotlin: KotlinProjectExtension) {
    kotlin.jvmToolchain(libs.version("javaToolchain").toInt())
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            allWarningsAsErrors.set(true)
            progressiveMode.set(true)
        }
    }
}
