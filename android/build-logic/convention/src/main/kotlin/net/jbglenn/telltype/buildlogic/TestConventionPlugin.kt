package net.jbglenn.telltype.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/**
 * Test setup. Pure JVM modules: JUnit Jupiter. Android modules: JUnit 4 with Robolectric
 * (Android resources included). Applied by every telltype.* module convention.
 */
class TestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.withPlugin(libs.pluginId("kotlin-jvm")) { configureJupiter() }
        pluginManager.withPlugin("com.android.base") { configureRobolectric() }
        tasks.withType<Test>().configureEach {
            maxHeapSize = "2g"
            testLogging { events("failed", "skipped") }
        }
    }

    private fun Project.configureJupiter() {
        dependencies {
            add("testImplementation", platform(libs.library("junit-bom")))
            add("testImplementation", libs.library("junit-jupiter"))
            add("testRuntimeOnly", libs.library("junit-platform-launcher"))
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }

    private fun Project.configureRobolectric() {
        extensions.getByType<CommonExtension>().testOptions.unitTests.isIncludeAndroidResources = true
        dependencies {
            add("testImplementation", libs.library("junit4"))
            add("testImplementation", libs.library("robolectric"))
            add("testImplementation", libs.library("androidx-test-core"))
            add("testImplementation", libs.library("androidx-test-ext-junit"))
        }
    }
}
