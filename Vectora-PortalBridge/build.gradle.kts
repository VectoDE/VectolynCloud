// Java release target: 21 (project default, see root build.gradle.kts).
//
// paper-api 26.3 is compiled for Java 25 (class file version 69) and its Gradle module
// metadata demands a Java 25 consumer. javac 25 still compiles against those classes with
// --release 21, so this module keeps bytecode 21. disableAutoTargetJvm() only switches off
// Gradle's variant check; it does not change the bytecode level.
// Runtime still needs Java 25 because Paper 26.3 itself does. Details and the stage-3 plan
// (Java-21 core plus per-version adapters): docs/java-compatibility.md

java {
    disableAutoTargetJvm()
}

base {
    archivesName = "VectoraPortalBridge"
}

dependencies {
    compileOnly(libs.paper.api)

    testImplementation(libs.paper.api)
    testImplementation(libs.snakeyaml)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
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
