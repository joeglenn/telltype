plugins {
    alias(libs.plugins.telltype.jvm.library)
}

// SharedStructureTest checks the enum ids against shared/ (guard names, tone and category ids).
val sharedDir: Directory = layout.projectDirectory.dir("../../../shared")

tasks.withType<Test>().configureEach {
    inputs.dir(sharedDir).withPropertyName("shared").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("telltype.sharedDir", sharedDir.asFile.absolutePath)
}
