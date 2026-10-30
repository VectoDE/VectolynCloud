pluginManagement {
    // Convention plugins shared by all Java modules (vectolyn.java-conventions and friends).
    includeBuild("build-logic")
}

plugins {
    // Downloads a missing JDK for a requested toolchain (Foojay Disco API).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "vectolyncloud"

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
        // PandaSpigot API (1.8.8 fork, GPL-3.0) for plugins/bridge/legacy, compile only. The repository
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

// Repository layout (docs/cloud/architecture.md): the cloud in cloud/, code shared by the cloud
// and its servers in common/, one module per plugin feature and platform in plugins/.
// The jar names stay those of the old module names (archivesName in each build file).

// Shared between the cloud and the servers it manages.
include("common:protocol", "common:client")

// VectolynCloud, module graph in docs/cloud/architecture.md.
include(
    "cloud:launcher",
    "cloud:config",
    "cloud:template",
    "cloud:download",
    "cloud:network",
    "cloud:console",
    "cloud:database",
    "cloud:setup",
    "cloud:api",
    "cloud:node",
    "cloud:backup",
    "cloud:monitor",
    "cloud:cluster",
)

// Plugins: plugins/<feature>/<platform>, shared logic of a feature in plugins/<feature>/common.
include(
    "plugins:bridge:common",
    "plugins:bridge:velocity",
    "plugins:bridge:paper",
    "plugins:bridge:legacy",
    "plugins:lobby:paper",
    "plugins:portal:paper",
)
