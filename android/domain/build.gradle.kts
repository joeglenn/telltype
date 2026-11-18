import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    alias(libs.plugins.telltype.jvm.library)
    alias(libs.plugins.kover)
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    // parsePromptSet and parseGuardRules read the bundled shared/ JSON files (tree API only, no plugin).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(projects.testing)
}

// The parser and conformance tests read the real shared/ files (prompts and guards).
val sharedDir: Directory = layout.projectDirectory.dir("../../shared")

tasks.withType<Test>().configureEach {
    inputs.dir(sharedDir).withPropertyName("shared").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("telltype.sharedDir", sharedDir.asFile.absolutePath)
}

// Line coverage of :domain is a gate (brief A2b): at least 90 %, verified by `check`.
kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 90
                    coverageUnits = CoverageUnit.LINE
                    aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn("koverVerify")
}
