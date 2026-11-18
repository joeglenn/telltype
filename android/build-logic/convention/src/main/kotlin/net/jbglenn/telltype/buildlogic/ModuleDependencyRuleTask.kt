package net.jbglenn.telltype.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/** Fails when this module declares a project dependency that breaks [ModuleDependencyRules]. */
@CacheableTask
internal abstract class ModuleDependencyRuleTask : DefaultTask() {
    @get:Input
    abstract val modulePath: Property<String>

    /** Each entry is `configurationName -> :project:path`, as declared in the module's build file. */
    @get:Input
    abstract val declaredProjectDependencies: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val from = modulePath.get()
        if (ModuleDependencyRules.layerOf(from) == null) {
            throw GradleException("$from is not classified: add it to ModuleDependencyRules.layerOf in build-logic.")
        }
        val violations = declaredProjectDependencies.get().mapNotNull { entry ->
            val (configuration, to) = entry.split(SEPARATOR)
            val reason = if (configuration.contains("test", ignoreCase = true)) {
                ModuleDependencyRules.testViolation(from, to)
            } else {
                ModuleDependencyRules.productionViolation(from, to)
            }
            reason?.let { "  $from --$configuration--> $to\n    rule: $it" }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Module dependency rule broken (docs/PLAN.md section 6.2):\n" +
                    violations.joinToString("\n") +
                    "\nRemove the dependency, or move the code to a layer that is allowed to use it.",
            )
        }
        report.get().asFile.writeText(declaredProjectDependencies.get().joinToString("\n", postfix = "\n"))
    }

    companion object {
        const val SEPARATOR = " -> "
    }
}

internal fun Project.registerModuleDependencyRuleCheck() {
    val projectPath = path
    val ruleCheck = tasks.register<ModuleDependencyRuleTask>("checkModuleDependencies") {
        group = "verification"
        description = "Fails if this module declares a project dependency that breaks the layer rule."
        modulePath.set(projectPath)
        declaredProjectDependencies.set(provider { declaredProjectDependencies(projectPath) })
        report.set(layout.buildDirectory.file("reports/module-dependencies.txt"))
    }
    tasks.matching { it.name == "check" }.configureEach { dependsOn(ruleCheck) }
    tasks.withType<KotlinCompilationTask<*>>().configureEach { dependsOn(ruleCheck) }
}

/**
 * Project dependencies written in build files, i.e. in declarable buckets such as `implementation` or
 * `testImplementation`. Resolvable classpaths are skipped: they repeat the same edges, and AGP adds the
 * module itself to its androidTest classpaths.
 */
private fun Project.declaredProjectDependencies(projectPath: String): List<String> = configurations
    .filter { !it.isCanBeResolved && !it.isCanBeConsumed }
    .flatMap { configuration ->
        configuration.dependencies.withType(ProjectDependency::class.java)
            .filter { it.path != projectPath }
            .map { "${configuration.name}${ModuleDependencyRuleTask.SEPARATOR}${it.path}" }
    }
    .sorted()
