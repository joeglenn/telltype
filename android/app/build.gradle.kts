import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.telltype.android.application)
    alias(libs.plugins.telltype.android.compose)
    alias(libs.plugins.telltype.hilt)
}

/**
 * Release signing is read from a properties file outside the repository (storeFile, storePassword, keyAlias,
 * keyPassword). Override the location with -PtelltypeSigningProperties=/path. Without the file the
 * release APK is built unsigned.
 */
val signingProperties: File = providers.gradleProperty("telltypeSigningProperties")
    .map(::File)
    .getOrElse(File(System.getProperty("user.home"), ".config/telltype/release-signing.properties"))

android {
    namespace = "net.jbglenn.telltype"
    defaultConfig {
        applicationId = "net.jbglenn.telltype"
    }
    if (signingProperties.isFile) {
        val signing = Properties().apply { signingProperties.inputStream().use(::load) }
        signingConfigs.create("release") {
            storeFile = File(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    buildTypes.getByName("debug") {
        applicationIdSuffix = ".debug"
    }
    // F-Droid: no Google-encrypted dependency block in the APK or bundle.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

/**
 * Copies `shared/guards.json` and the prompt files of `shared/prompts/` into the APK's assets under `shared/`
 * (decision D11: `shared/` stays the only copy in the repository).
 */
abstract class CopySharedAssets : DefaultTask() {
    /** The repository's `shared/` directory. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedDirectory: DirectoryProperty

    /** The generated assets directory. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val shared = sharedDirectory.get().asFile
        val target = outputDirectory.get().asFile.resolve("shared")
        target.resolve("prompts").mkdirs()
        shared.resolve("guards.json").copyTo(target.resolve("guards.json"), overwrite = true)
        shared.resolve("prompts").listFiles().orEmpty()
            .filter { it.isFile && it.name != "README.md" }
            .forEach { it.copyTo(target.resolve("prompts/${it.name}"), overwrite = true) }
    }
}

/** Fails when the merged manifest asks for a permission that is not on [allowed] (Appendix F, decision D26). */
abstract class CheckManifestPermissions : DefaultTask() {
    /** The variant's merged manifest. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    /** The permissions the app may request. */
    @get:Input
    abstract val allowed: SetProperty<String>

    /** The permissions found, one per line. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.get().asFile)
        val found = listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ANDROID_NAMESPACE, "name") }
        }.toSortedSet()
        val unexpected = found - allowed.get()
        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "Permissions outside docs/PLAN.md Appendix F in ${manifest.get().asFile}: $unexpected",
            )
        }
        report.get().asFile.writeText(found.joinToString("\n", postfix = "\n"))
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}

/** docs/PLAN.md Appendix F plus QUERY_ADVANCED_PROTECTION_MODE (decision D26). */
val appendixFPermissions = listOf(
    "INTERNET",
    "ACCESS_NETWORK_STATE",
    "ACCESS_LOCAL_NETWORK",
    "RECORD_AUDIO",
    "FOREGROUND_SERVICE",
    "FOREGROUND_SERVICE_MICROPHONE",
    "POST_NOTIFICATIONS",
    "VIBRATE",
    "QUERY_ADVANCED_PROTECTION_MODE",
).map { "android.permission.$it" }

val copySharedAssets = tasks.register<CopySharedAssets>("copySharedAssets") {
    sharedDirectory.set(layout.projectDirectory.dir("../../shared"))
    outputDirectory.set(layout.buildDirectory.dir("generated/sharedAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copySharedAssets, CopySharedAssets::outputDirectory)
        val name = variant.name.replaceFirstChar(Char::uppercaseChar)
        val checkPermissions = tasks.register<CheckManifestPermissions>("check${name}ManifestPermissions") {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            allowed.set(appendixFPermissions)
            report.set(layout.buildDirectory.file("reports/permissions/${variant.name}.txt"))
        }
        tasks.named("check").configure { dependsOn(checkPermissions) }
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.domain)
    implementation(projects.data)
    implementation(projects.network)
    implementation(projects.audio)
    implementation(projects.platform)
    implementation(projects.ui.designsystem)
    implementation(projects.ui.overlay)
    implementation(projects.ui.screens)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.leakcanary.android)

    testImplementation(projects.testing)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}

// The connected tests dictate into the test host app, so it must be on the device first.
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(":testhost:installDebug")
}
