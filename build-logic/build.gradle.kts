// Included build with the shared Gradle setup of all Java modules.
//
// Why an included build instead of the old subprojects {} block in the root build file:
// with the VectolynCloud modules the build has three kinds of Java modules (plain release-21
// libraries, modules that compile against a Java 25 server API, modules with fuzz tests).
// Precompiled convention plugins let each module state what it is in one line, keep the
// shared JUnit and toolchain setup in one place, and avoid cross-project configuration,
// which Gradle discourages and which does not work with project isolation.
// build-logic is preferred over buildSrc because changing it does not invalidate the whole
// build and it can be tested on its own.

plugins {
    `kotlin-dsl`
}
