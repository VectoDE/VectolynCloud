// Fuzz tests with Jazzer (methods annotated with @FuzzTest).
//
// Default: regression mode. Every normal test run executes each @FuzzTest once per stored input
// in src/test/resources/<package>/<TestClass>Inputs/<method>/ (plus an empty input). This is a
// plain JUnit test and runs on every OS and on Java 21.
//
// Fuzzing mode: ./gradlew :<module>:test --tests '<TestClass>.<method>' -Pfuzz -PfuzzMaxDuration=5m
// Jazzer fuzzes only the first @FuzzTest it meets per run, so always select a single method.
// Crashing inputs land in the inputs directory above, coverage-increasing inputs in
// .cifuzz-corpus/. The nightly workflow .github/workflows/fuzz-nightly.yml does this on Linux.

plugins {
    id("vectolyn.java-conventions")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    testImplementation(libs.findLibrary("jazzer-junit").get())
}

val fuzz = providers.gradleProperty("fuzz").isPresent
val fuzzMaxDuration = providers.gradleProperty("fuzzMaxDuration").orElse("5m")

tasks.withType<Test>().configureEach {
    // The JUnit integration takes the mode from the JAZZER_FUZZ environment variable only.
    // Drop an inherited value so a normal test run is always a regression run.
    environment.remove("JAZZER_FUZZ")
    // Only Vectolyn code is coverage-instrumented; libraries are left alone.
    systemProperty("jazzer.instrument", "net.vectolyn.**")
    if (fuzz) {
        environment("JAZZER_FUZZ", "1")
        systemProperty("jazzer.max_duration", fuzzMaxDuration.get())
        // Jazzer attaches its agent at runtime; Java 21+ warns about that unless allowed.
        jvmArgs("-XX:+EnableDynamicAgentLoading")
        // A fuzz run must never be skipped as up to date or taken from the build cache.
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}
