// Netty server of the cloud channel (services, later cluster nodes) and later the REST and
// WebSocket API. Reads bytes from the network, so it has Jazzer fuzz tests.

plugins {
    id("vectora.java-conventions")
    id("vectora.fuzz-conventions")
}

vectora {
    // Jar and manifest name from before the repository layout change.
    artifactName = "cloud-network"
}

dependencies {
    api(project(":common:protocol"))
    api(project(":cloud:config"))
    // Netty types appear in the public API only in PipelineCustomizer (the TLS hook).
    api(platform(libs.netty.bom))
    api(libs.netty.transport)
    implementation(libs.netty.handler)
    implementation(libs.netty.codec.http)

    // End-to-end tests against the real client: reconnect and messaging between two services.
    testImplementation(project(":common:client"))
}
