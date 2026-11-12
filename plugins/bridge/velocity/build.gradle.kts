// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
// Java release target: 21 (project default, see build-logic/).
//
// velocity-api 4.3 is compiled for Java 25 (class file version 69). The module still builds
// bytecode 21; vectolyn.java25-api-conventions switches off Gradle's JVM variant check for it.
// Velocity 4.3 itself needs Java 25, so the bridge only runs there. Tests that load API classes
// carry @Tag("requires-java25"). Details: docs/java-compatibility.md
//
// Runs in the main proxy and in the legacy proxy. Thin adapter: the logic (server registry, player
// state, send queue) is in plugins/bridge/common, which is tested on Java 21 as well.
// velocity-plugin.json is written by hand instead of by velocity-api's annotation processor, so the
// build runs no processor from a compile-only dependency.

plugins {
    id("vectolyn.java25-api-conventions")
}

vectolyn {
    // Jar and manifest name from before the repository layout change.
    artifactName = "cloud-bridge-velocity"
    // Line coverage of the unit tests: 0.230 measured (issue #223). Lower than the default of
    // 0.70 because most code needs a running Velocity proxy; unit tests cover the logic that does not.
    minimumLineCoverage = 0.2
}

base {
    archivesName = "VectolynCloudBridgeVelocity"
}

dependencies {
    implementation(project(":plugins:bridge:common"))
    compileOnly(libs.velocity.api)

    testImplementation(libs.velocity.api)

    // velocity-api pulls in the moving snapshot velocity-brigadier:1.0.0-SNAPSHOT (every released
    // velocity-api does). Without a pin each new PaperMC build breaks dependency verification, so
    // the constraint fixes one unique snapshot build. Reason and version: gradle/libs.versions.toml.
    constraints {
        compileOnly(libs.velocity.brigadier) {
            version { strictly(libs.versions.velocity.brigadier.get()) }
            because("velocity-brigadier is only published as a moving snapshot; pin one build")
        }
        testImplementation(libs.velocity.brigadier) {
            version { strictly(libs.versions.velocity.brigadier.get()) }
            because("velocity-brigadier is only published as a moving snapshot; pin one build")
        }
    }
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("velocity-plugin.json") {
        expand("pluginVersion" to pluginVersion)
    }
}

// The proxy loads the plugin jar on its own, so the jar carries the bridge core, the client and
// the protocol (all Vectolyn code, no third-party library).
tasks.jar {
    val bundled = configurations.runtimeClasspath
    dependsOn(bundled)
    from(bundled.map { files -> files.map { if (it.isDirectory) it else zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/MANIFEST.MF")
}

tasks.test {
    systemProperty("vectolyn.expectedVersion", project.version.toString())
}
