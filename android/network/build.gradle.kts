plugins {
    alias(libs.plugins.telltype.android.library)
    alias(libs.plugins.telltype.hilt)
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.domain)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(projects.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}

// The live check against the real gateway (LiveGateTest) is opt-in: it runs only when OWK_LIVE_GATE_URL is set,
// and is excluded from `check` otherwise (brief A3, "Live check").
val liveGateUrl: Provider<String> = providers.environmentVariable("OWK_LIVE_GATE_URL")
val liveGateKeyFile: Provider<String> = providers.environmentVariable("OWK_LIVE_GATE_KEY_FILE")
val liveGateCertFile: Provider<String> = providers.environmentVariable("OWK_LIVE_GATE_CERT_FILE")
val liveGateWav: Provider<String> = providers.environmentVariable("OWK_LIVE_GATE_WAV")

tasks.withType<Test>().configureEach {
    val url = liveGateUrl.orNull
    if (url == null) {
        filter { excludeTestsMatching("*.LiveGateTest") }
    } else {
        environment("OWK_LIVE_GATE_URL", url)
        liveGateKeyFile.orNull?.let { environment("OWK_LIVE_GATE_KEY_FILE", it) }
        liveGateCertFile.orNull?.let { environment("OWK_LIVE_GATE_CERT_FILE", it) }
        liveGateWav.orNull?.let { environment("OWK_LIVE_GATE_WAV", it) }
        outputs.upToDateWhen { false }
    }
}
