// REST and WebSocket API of VectoraCloud: services, groups, tasks, configuration, node status,
// backups and live events, for scripts and later the dashboard (stage 8). Every change goes through
// ConfigService.apply of cloud-config, the same layer the console and the files use.
// Reads requests from the network, so routing and JSON parsing have Jazzer fuzz tests.
// cloud-node implements ApiBackend and starts the ApiServer. Details: docs/cloud/api.md

plugins {
    id("vectora.java-conventions")
    id("vectora.fuzz-conventions")
}

dependencies {
    api(project(":VectoraCloud:cloud-config"))
    // The "api token" commands are registered in the console's command registry.
    api(project(":VectoraCloud:cloud-console"))
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.transport)
    implementation(libs.netty.handler)
    implementation(libs.netty.codec.http)
}
