plugins {
    alias(libs.plugins.telltype.android.library)
    alias(libs.plugins.telltype.hilt)
    alias(libs.plugins.room)
}

android {
    // The exported schemas double as test assets, so MigrationTestHelper can open every version on the JVM.
    sourceSets.getByName("test").assets.directories.add("$projectDir/schemas")
}

room {
    schemaDirectory("$projectDir/schemas")
    generateKotlin = true
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.domain)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(projects.testing)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(projects.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
