// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
// REST and WebSocket API of VectolynCloud: services, groups, tasks, configuration, node status,
// backups and live events, for scripts and later the dashboard (stage 8). Every change goes through
// ConfigService.apply of cloud-config, the same layer the console and the files use.
// Reads requests from the network, so routing, JSON parsing and GraphQL documents have Jazzer fuzz tests.
// cloud-node implements ApiBackend and starts the ApiServer. Details: docs/cloud/api.md

plugins {
    id("vectolyn.java-conventions")
    id("vectolyn.fuzz-conventions")
}

vectolyn {
    // Jar and manifest name from before the repository layout change.
    artifactName = "cloud-api"
}

dependencies {
    api(project(":cloud:config"))
    // The "api token" commands are registered in the console's command registry.
    api(project(":cloud:console"))
    // BackupServiceAccess adapts the backup service to the backup endpoints.
    api(project(":cloud:backup"))
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.transport)
    implementation(libs.netty.handler)
    implementation(libs.netty.codec.http)
    // POST /api/v1/graphql and the graphql-transport-ws subscriptions (GraphQlEndpoint).
    implementation(libs.graphql.java)
}

dependencies {
    // OpenApiContractTest reads docs/cloud/api.openapi.yaml (already a runtime library of cloud/config).
    testImplementation(platform(libs.jackson.bom))
    testImplementation(libs.jackson.dataformat.yaml)
}

// The OpenAPI file is part of the contract: OpenApiContractTest compares it with the routing table,
// so a route without its entry (or an entry without its route) fails the build.
tasks.test {
    val openApi = layout.settingsDirectory.file("docs/cloud/api.openapi.yaml")
    inputs.file(openApi).withPropertyName("openApi")
    systemProperty("vectolyn.openapi", openApi.asFile.absolutePath)
}
