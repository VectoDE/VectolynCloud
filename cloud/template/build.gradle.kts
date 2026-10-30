plugins {
    id("vectolyn.java-conventions")
    // The placeholder renderer reads template files, which are untrusted input for the cloud.
    id("vectolyn.fuzz-conventions")
}

vectolyn {
    // Jar and manifest name from before the repository layout change.
    artifactName = "cloud-template"
}

dependencies {
    // Only to check that every rendered YAML and JSON file of the real templates still parses.
    testImplementation(platform(libs.jackson.bom))
    testImplementation(libs.jackson.dataformat.yaml)
}
