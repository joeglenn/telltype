plugins {
    alias(libs.plugins.telltype.android.library)
    alias(libs.plugins.telltype.android.compose)
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.domain)
    implementation(projects.ui.designsystem)

    testImplementation(projects.testing)
}
