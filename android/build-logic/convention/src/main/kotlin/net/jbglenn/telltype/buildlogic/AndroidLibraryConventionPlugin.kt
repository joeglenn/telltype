package net.jbglenn.telltype.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Android library with built-in Kotlin; namespace derived from the module path. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("android-library"))
        extensions.configure<LibraryExtension> {
            namespace = derivedNamespace()
            configureAndroidCommon(this)
        }
        pluginManager.apply(TestConventionPlugin::class.java)
        configureQuality()
    }
}
