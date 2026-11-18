pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "telltype"

include(
    ":app",
    ":core:model",
    ":core:common",
    ":domain",
    ":data",
    ":network",
    ":audio",
    ":platform",
    ":ui:designsystem",
    ":ui:overlay",
    ":ui:screens",
    ":testing",
    ":testhost",
)
