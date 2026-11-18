package net.jbglenn.telltype.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * ktlint (formatting, through Spotless), detekt (with the Compose rules) and the module dependency rule;
 * all three run as part of `check`.
 */
internal fun Project.configureQuality() {
    configureSpotless()
    configureDetekt()
    registerModuleDependencyRuleCheck()
}

private fun Project.configureSpotless() {
    pluginManager.apply(libs.pluginId("spotless"))
    val ktlintVersion = libs.version("ktlint")
    val editorConfig = rootProject.file(".editorconfig")
    extensions.configure<SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
        }
    }
}

private fun Project.configureDetekt() {
    pluginManager.apply(libs.pluginId("detekt"))
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        source.setFrom(
            "src/main/kotlin",
            "src/test/kotlin",
            "src/androidTest/kotlin",
        )
    }
    tasks.withType<Detekt>().configureEach {
        jvmTarget = libs.version("javaToolchain")
        reports {
            html.required.set(true)
            xml.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
            txt.required.set(false)
        }
    }
    dependencies {
        add("detektPlugins", libs.library("compose-rules-detekt"))
    }
}
