// Convention plugins for every Java module of the main build (see build.gradle.kts here).

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
