// Configuration layer of VectoraCloud: cloud.yml, groups/, tasks/, nodes.yml and secrets/vectora.env.
// Parses files an operator edits by hand, so its parsers have Jazzer fuzz tests (vectora.fuzz-conventions).
// Details: docs/cloud/config.md

plugins {
    id("vectora.java-conventions")
    id("vectora.fuzz-conventions")
}

dependencies {
    api(platform(libs.jackson.bom))
    api(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
}
