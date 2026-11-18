package net.jbglenn.telltype.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalArgumentException("No library '$alias' in libs.versions.toml") }

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).orElseThrow {
    IllegalArgumentException("No version '$alias' in libs.versions.toml")
}.requiredVersion

internal fun VersionCatalog.pluginId(alias: String): String = findPlugin(alias).orElseThrow {
    IllegalArgumentException("No plugin '$alias' in libs.versions.toml")
}.get().pluginId

/** `:ui:designsystem` becomes `net.jbglenn.telltype.ui.designsystem`. */
internal fun Project.derivedNamespace(): String = BASE_NAMESPACE + path.replace(':', '.')

internal const val BASE_NAMESPACE = "net.jbglenn.telltype"
