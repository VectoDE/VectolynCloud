// Java release target: 21 (project default, see build-logic/).
//
// velocity-api 4.3 is compiled for Java 25 (class file version 69). The module still builds
// bytecode 21; vectora.java25-api-conventions switches off Gradle's JVM variant check for it.
// Velocity 4.3 itself needs Java 25, so the bridge only runs there. Tests that load API classes
// carry @Tag("requires-java25"). Details: docs/java-compatibility.md

plugins {
    id("vectora.java25-api-conventions")
}

dependencies {
    implementation(project(":Vectora-CloudClient"))
    compileOnly(libs.velocity.api)

    testImplementation(libs.velocity.api)
}
