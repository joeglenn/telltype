package net.jbglenn.telltype.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * The app's version, read from the Gradle property `telltype.version` (default in `android/gradle.properties`).
 * It must be `<major>.<minor>.<patch>` with minor and patch below 100; `versionCode` is
 * `major * 10000 + minor * 100 + patch`.
 */
internal data class AppVersion(val major: Int, val minor: Int, val patch: Int) {
    /** The `versionName`, e.g. `1.0.0`. */
    val name: String get() = "$major.$minor.$patch"

    /** The `versionCode`, e.g. `10000` for 1.0.0. */
    val code: Int get() = major * MAJOR_STEP + minor * MINOR_STEP + patch

    /** Parsing and the property name. */
    companion object {
        /** The Gradle property that holds the version. */
        const val PROPERTY = "telltype.version"
        private const val MAJOR_STEP = 10_000
        private const val MINOR_STEP = 100
        private const val MAX_MAJOR = 2_000
        private val pattern = Regex("""(0|[1-9]\d{0,3})\.(0|[1-9]\d?)\.(0|[1-9]\d?)""")

        /** Parses [text] or fails the build with a message naming the property. */
        fun parse(text: String): AppVersion {
            val match = pattern.matchEntire(text.trim())
                ?: throw GradleException("$PROPERTY must be <major>.<minor>.<patch> (minor, patch 0-99), got '$text'")
            val (major, minor, patch) = match.destructured
            if (major.toInt() > MAX_MAJOR) throw GradleException("$PROPERTY major must be at most $MAX_MAJOR: '$text'")
            return AppVersion(major.toInt(), minor.toInt(), patch.toInt())
        }

        /** Reads and parses the property of [project]; a missing property fails the build. */
        fun of(project: Project): AppVersion {
            val text = project.providers.gradleProperty(PROPERTY).orNull
                ?: throw GradleException("Gradle property $PROPERTY is not set (android/gradle.properties)")
            return parse(text)
        }
    }
}
