// Java release target: 21 (project default, see build-logic/).
//
// Bridge for the PandaSpigot 1.8.8 backends behind the legacy proxy. PandaSpigot runs on Java 21
// (docs/cloud/legacy-1.8.8.md) and its API is compiled for Java 8, so unlike the Paper and Velocity
// bridges this module needs no Java 25 API and all of its tests also run with -PtestJava=21.
//
// pandaspigot-api comes from the project's own repository (repo.hpfxd.com, GPL-3.0 like Vectora);
// settings.gradle.kts limits that repository to this one group. It is compile only and not
// transitive: the server provides the API at runtime, and the bridge uses none of the libraries
// the API declares (Guava, Gson, ebean, ...). Only adventure-api is added, in the version the API
// declares, because Bukkit's Player and event classes extend or overload with Adventure types and
// javac needs those to compile calls on them. Reasons in docs/cloud/bridges.md.

plugins {
    id("vectora.java-conventions")
}

base {
    archivesName = "VectoraCloudBridgeLegacy"
}

dependencies {
    implementation(project(":VectoraCloud:cloud-bridge-common"))
    compileOnly(libs.pandaspigot.api) { isTransitive = false }
    compileOnly(libs.pandaspigot.adventure.api)

    // Tests load Bukkit classes, which need the API's own libraries (commons-lang, bungeecord-chat).
    testImplementation(libs.pandaspigot.api)
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
// the protocol (all Vectora code, no third-party library).
tasks.jar {
    val bundled = configurations.runtimeClasspath
    dependsOn(bundled)
    from(bundled.map { files -> files.map { if (it.isDirectory) it else zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/MANIFEST.MF")
}

tasks.test {
    systemProperty("vectora.expectedVersion", project.version.toString())
}
