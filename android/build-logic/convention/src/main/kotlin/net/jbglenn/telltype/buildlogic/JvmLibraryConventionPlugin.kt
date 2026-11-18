package net.jbglenn.telltype.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Pure Kotlin/JVM module: no Android plugin, so Android imports cannot compile. Explicit API mode is on. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("kotlin-jvm"))
        extensions.configure<KotlinJvmProjectExtension> {
            explicitApi()
            configureKotlin(this)
        }
        pluginManager.apply(TestConventionPlugin::class.java)
        configureQuality()
    }
}
