pluginManagement {
    // Convention plugins shared by all Java modules (vectora.java-conventions and friends).
    includeBuild("build-logic")
}

plugins {
    // Downloads a missing JDK for a requested toolchain (Foojay Disco API).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "vectora-network"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
            content {
                includeGroupAndSubgroups("io.papermc")
                includeGroup("com.mojang")
                includeGroup("net.md-5")
                includeGroupAndSubgroups("com.velocitypowered")
            }
        }
    }
}

// Plugin modules (stage 1, merged into Vectora-Paper/Velocity/Fabric.jar in stage 3).
include("Vectora-Lobby", "Vectora-PortalBridge")

// Shared between the cloud and the servers it manages.
include("Vectora-Protocol", "Vectora-CloudClient")

// VectoraCloud, module graph in docs/cloud/architecture.md.
include(
    "VectoraCloud:cloud-launcher",
    "VectoraCloud:cloud-config",
    "VectoraCloud:cloud-template",
    "VectoraCloud:cloud-download",
    "VectoraCloud:cloud-network",
    "VectoraCloud:cloud-database",
    "VectoraCloud:cloud-node",
    "VectoraCloud:cloud-bridge-velocity",
    "VectoraCloud:cloud-bridge-paper",
)
