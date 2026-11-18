plugins {
    alias(libs.plugins.telltype.android.library)
    alias(libs.plugins.telltype.android.compose)
    alias(libs.plugins.telltype.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.domain)
    implementation(projects.ui.designsystem)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(projects.testing)
}
