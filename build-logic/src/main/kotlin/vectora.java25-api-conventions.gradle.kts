// For modules that compile against a server API published for Java 25
// (paper-api 26.3, velocity-api 4.3: class file version 69).
//
// The bytecode stays at release 21. disableAutoTargetJvm() only switches off Gradle's variant
// check, which would otherwise refuse a Java 25 library for a release-21 consumer.
// Tests that load classes of such an API carry @Tag("requires-java25"); they are skipped when the
// tests run on Java 21 (-PtestJava=21). Details: docs/java-compatibility.md

plugins {
    id("vectora.java-conventions")
}

java {
    disableAutoTargetJvm()
}
