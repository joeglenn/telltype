plugins {
    alias(libs.plugins.telltype.android.library)
    alias(libs.plugins.telltype.android.compose)
}

dependencies {
    implementation(projects.core.model)
}
