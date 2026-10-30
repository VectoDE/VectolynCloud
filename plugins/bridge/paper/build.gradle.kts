// Java release target: 21 (project default, see build-logic/).
//
// paper-api 26.3 is compiled for Java 25 (class file version 69). The module still builds
// bytecode 21; vectolyn.java25-api-conventions switches off Gradle's JVM variant check for it.
// Paper 26.3 itself needs Java 25, so the bridge only runs there. Tests that load API classes
// carry @Tag("requires-java25"). Details: docs/java-compatibility.md
//
// Thin adapter: the logic is in plugins/bridge/common, which is tested on Java 21 as well.

plugins {
    id("vectolyn.java25-api-conventions")
}

vectolyn {
    // Jar and manifest name from before the repository layout change.
    artifactName = "cloud-bridge-paper"
}

base {
    archivesName = "VectolynCloudBridgePaper"
}

dependencies {
    implementation(project(":plugins:bridge:common"))
    compileOnly(libs.paper.api)

    testImplementation(libs.paper.api)
    testImplementation(libs.snakeyaml)
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("plugin.yml") {
        expand("pluginVersion" to pluginVersion)
    }
}

// The server loads the plugin jar on its own, so the jar carries the bridge core, the client and
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
