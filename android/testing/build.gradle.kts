plugins {
    alias(libs.plugins.telltype.jvm.library)
}

dependencies {
    api(projects.domain)
    api(projects.core.common)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
}
