// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
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

// ConfigSchemaTest compares the generated JSON Schemas with the shipped copies in cloud/dist/schema/
// and validates the YAML examples of docs/cloud/config.md against them. After a model change,
// "./gradlew :cloud:config:test -PupdateSchemas" writes the new copies.
tasks.test {
    val schemaDir = layout.settingsDirectory.dir("cloud/dist/schema")
    val configDoc = layout.settingsDirectory.file("docs/cloud/config.md")
    inputs.dir(schemaDir).withPropertyName("schemaDir")
    inputs.file(configDoc).withPropertyName("configDoc")
    systemProperty("vectolyn.schemaDir", schemaDir.asFile.absolutePath)
    systemProperty("vectolyn.configDoc", configDoc.asFile.absolutePath)
    if (providers.gradleProperty("updateSchemas").isPresent) {
        systemProperty("vectolyn.updateSchemas", "true")
        outputs.upToDateWhen { false }
    }
}
