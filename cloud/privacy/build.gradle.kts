// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
// Data protection of VectolynCloud: privacy.yml, the IP ban list with a retention period and its
// automatic deletion. The node plugs it in through PrivacyRuntime; it does not know cloud-node.
// The ban list file is read from disk, so its parser has a Jazzer fuzz test.
// Details: docs/cloud/privacy.md

plugins {
    id("vectolyn.java-conventions")
    id("vectolyn.fuzz-conventions")
}

vectolyn {
    artifactName = "cloud-privacy"
}

dependencies {
    // privacy.yml is read with the shared strict YAML mapper; AtomicFileWriter comes from there too.
    api(project(":cloud:config"))
    // The ban list is a small JSON file.
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}
