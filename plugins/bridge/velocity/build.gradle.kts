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
}

base {
    archivesName = "VectolynCloudBridgeVelocity"
}

dependencies {
    implementation(project(":plugins:bridge:common"))
    compileOnly(libs.velocity.api)

    testImplementation(libs.velocity.api)
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
