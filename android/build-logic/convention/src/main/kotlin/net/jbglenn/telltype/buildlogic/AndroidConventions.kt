package net.jbglenn.telltype.buildlogic

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.Lint
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** SDK levels, Java level, Kotlin flags and Lint gates shared by libraries and applications. */
internal fun Project.configureAndroidCommon(android: CommonExtension) {
    val javaVersion = JavaVersion.toVersion(libs.version("javaToolchain"))
    android.compileSdk = libs.version("compileSdk").toInt()
    android.defaultConfig.minSdk = libs.version("minSdk").toInt()
    android.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    android.compileOptions.sourceCompatibility = javaVersion
    android.compileOptions.targetCompatibility = javaVersion
    configureLint(android.lint)
    configureKotlin(extensions.getByType<KotlinAndroidProjectExtension>())
    disableReleaseHostTests()
}

private fun Project.configureLint(lint: Lint) {
    lint.abortOnError = true
    lint.warningsAsErrors = true
    lint.lintConfig = rootProject.file("config/lint/lint.xml")
}

/**
 * Unit tests and screenshot tests run against the debug variant only: release differs in R8 and signing,
 * which host tests cannot observe, and running both doubles the Robolectric time.
 */
private fun Project.disableReleaseHostTests() {
    val components = extensions.getByType(AndroidComponentsExtension::class.java)
    components.beforeVariants(components.selector().withBuildType("release")) { variant ->
        (variant as? HasHostTestsBuilder)?.hostTests?.get(HostTestBuilder.UNIT_TEST_TYPE)?.enable = false
    }
}
