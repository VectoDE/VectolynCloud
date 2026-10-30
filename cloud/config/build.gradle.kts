// Configuration layer of VectoraCloud: config/ (cloud.yml, nodes.yml, groups/, tasks/, ...) and secrets/vectora.env.
// Parses files an operator edits by hand, so its parsers have Jazzer fuzz tests (vectora.fuzz-conventions).
// Details: docs/cloud/config.md

plugins {
    id("vectora.java-conventions")
    id("vectora.fuzz-conventions")
}

vectora {
    // Jar and manifest name from before the repository layout change.
    artifactName = "cloud-config"
}

dependencies {
    api(platform(libs.jackson.bom))
    api(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
}
