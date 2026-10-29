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
        // PandaSpigot API (1.8.8 fork, GPL-3.0) for cloud-bridge-legacy, compile only. The repository
        // is used for this group only and the group is never looked up anywhere else.
        exclusiveContent {
            forRepository {
                maven("https://repo.hpfxd.com/releases/") { name = "hpfxd" }
            }
            filter {
                includeGroup("com.hpfxd.pandaspigot")
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
    "VectoraCloud:cloud-console",
    "VectoraCloud:cloud-database",
    "VectoraCloud:cloud-setup",
    "VectoraCloud:cloud-api",
    "VectoraCloud:cloud-node",
    "VectoraCloud:cloud-backup",
    "VectoraCloud:cloud-monitor",
    "VectoraCloud:cloud-bridge-common",
    "VectoraCloud:cloud-bridge-velocity",
    "VectoraCloud:cloud-bridge-paper",
    "VectoraCloud:cloud-bridge-legacy",
)
