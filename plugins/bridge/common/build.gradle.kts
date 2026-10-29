// Platform-neutral part of the bridges: send queue, proxy state, server registry, tick meter and
// the BungeeGuard check for the 1.8.8 backends. No platform API, so everything here runs and is
// tested on Java 21. The platform modules (velocity, paper, legacy) are thin adapters on top.
// From stage 3 on this logic moves into the Vectora plugin. Parses forwarded handshakes, so it has
// Jazzer fuzz tests. Details: docs/cloud/bridges.md

plugins {
    id("vectora.java-conventions")
    id("vectora.fuzz-conventions")
}

vectora {
    // Jar and manifest name from before the repository layout change.
    artifactName = "cloud-bridge-common"
}

dependencies {
    api(project(":common:client"))
}
