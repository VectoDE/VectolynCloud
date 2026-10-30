// Client side of the cloud channel, used by the bridges and later by the Vectolyn plugin.
// Depends on common/protocol only and uses the JDK for networking (no Netty, neither shaded nor
// the server's own copy), so it adds nothing to the class path of the servers it runs on.
// Reasons in docs/cloud/network.md. Reads bytes from the network, so it has Jazzer fuzz tests.

plugins {
    id("vectolyn.java-conventions")
    id("vectolyn.fuzz-conventions")
}

vectolyn {
    // Jar and manifest name from before the repository layout change.
    artifactName = "Vectolyn-CloudClient"
}

dependencies {
    api(project(":common:protocol"))
}
