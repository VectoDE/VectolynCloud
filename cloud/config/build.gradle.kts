// Configuration layer of VectolynCloud: config/ (cloud.yml, nodes.yml, groups/, tasks/, ...) and secrets/vectolyn.env.
// Parses files an operator edits by hand, so its parsers have Jazzer fuzz tests (vectolyn.fuzz-conventions).
// Details: docs/cloud/config.md

plugins {
    id("vectolyn.java-conventions")
    id("vectolyn.fuzz-conventions")
}

vectolyn {
    // Jar and manifest name from before the repository layout change.
    artifactName = "cloud-config"
}

dependencies {
    api(platform(libs.jackson.bom))
    api(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
}
