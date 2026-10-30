plugins {
    id("vectolyn.java-conventions")
    id("vectolyn.fuzz-conventions")
}

vectolyn {
    // Jar and manifest name from before the repository layout change.
    artifactName = "cloud-download"
}

dependencies {
    // Same Jackson as cloud-config (version from the shared BOM), for the lockfile, the catalog
    // and the API responses.
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
}

// Tests tagged "network" talk to the real download APIs. They never run in the normal build;
// run them on purpose with ./gradlew :cloud:download:networkTest
tasks.test {
    useJUnitPlatform {
        excludeTags("network")
    }
}

val networkTest by tasks.registering(Test::class) {
    description = "Live smoke test against the real download APIs (needs internet access)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("network")
    }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
