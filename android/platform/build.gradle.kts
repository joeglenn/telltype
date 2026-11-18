plugins {
    alias(libs.plugins.telltype.android.library)
    alias(libs.plugins.telltype.android.compose)
    alias(libs.plugins.telltype.hilt)
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.domain)

    testImplementation(projects.testing)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    // The instrumented tests host the real accessibility service in this module's test APK and drive the
    // :testhost app (installed by the task dependency below) with UI Automator.
    androidTestImplementation(projects.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}

// The connected tests type into the test host app, so it must be on the device first.
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(":testhost:installDebug")
}
