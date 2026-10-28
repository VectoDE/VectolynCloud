plugins {
    id("vectora.java-conventions")
    // The placeholder renderer reads template files, which are untrusted input for the cloud.
    id("vectora.fuzz-conventions")
}

dependencies {
    api(project(":VectoraCloud:cloud-config"))

    // Only to check that every rendered YAML file of the real templates still parses.
    // The version comes from the Jackson BOM that cloud-config exports.
    testImplementation(libs.jackson.dataformat.yaml)
}
