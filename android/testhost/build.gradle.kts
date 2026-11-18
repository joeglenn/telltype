plugins {
    alias(libs.plugins.telltype.android.application)
    alias(libs.plugins.telltype.android.compose)
}

android {
    namespace = "net.jbglenn.telltype.testhost"
    defaultConfig {
        applicationId = "net.jbglenn.telltype.testhost"
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
