package net.jbglenn.telltype.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Android application: target SDK, the version from `telltype.version` ([AppVersion]), BuildConfig, and an R8-minified, resource-shrunk release build. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("android-application"))
        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)
            defaultConfig.targetSdk = libs.version("targetSdk").toInt()
            val version = AppVersion.of(target)
            defaultConfig.versionCode = version.code
            defaultConfig.versionName = version.name
            buildFeatures.buildConfig = true
            buildTypes.getByName("release") {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
        }
        pluginManager.apply(TestConventionPlugin::class.java)
        configureQuality()
    }
}
