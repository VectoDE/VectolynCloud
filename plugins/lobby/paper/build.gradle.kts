// Java release target: 21 (project default, see build-logic/).
//
// paper-api 26.3 is compiled for Java 25 (class file version 69) and its Gradle module
// metadata demands a Java 25 consumer. javac 25 still compiles against those classes with
// --release 21, so this module keeps bytecode 21. vectora.java25-api-conventions calls
// disableAutoTargetJvm(), which only switches off Gradle's variant check; it does not change
// the bytecode level.
// Runtime still needs Java 25 because Paper 26.3 itself does. Details and the stage-3 plan
// (Java-21 core plus per-version adapters): docs/java-compatibility.md

plugins {
    id("vectora.java25-api-conventions")
}

vectora {
    // Jar and manifest name from before the repository layout change.
    artifactName = "Vectora-Lobby"
    // The lobby still uses Paper APIs that 26.3 deprecates (GameRule constants, Sign#setLine).
    // They are replaced in stage 4; until then these warnings must not fail the build.
    warningsAsErrors = false
}

base {
    archivesName = "VectoraLobby"
}

dependencies {
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

tasks.test {
    systemProperty("vectora.expectedVersion", project.version.toString())
}
