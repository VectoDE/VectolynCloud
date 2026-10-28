plugins {
    id("vectora.java-conventions")
    id("vectora.fuzz-conventions")
}

dependencies {
    api(project(":VectoraCloud:cloud-config"))
    // Same Jackson as cloud-config (version from the BOM there). cloud-config keeps the YAML
    // module internal, so the lockfile and the catalog need it here as well.
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
}

// Tests tagged "network" talk to the real download APIs. They never run in the normal build;
// run them on purpose with ./gradlew :VectoraCloud:cloud-download:networkTest
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
