// Client side of the cloud channel, used by the bridges and later by the Vectora plugin.
// Depends on Vectora-Protocol only and uses the JDK for networking (no Netty, neither shaded nor
// the server's own copy), so it adds nothing to the class path of the servers it runs on.
// Reasons in docs/cloud/network.md. Reads bytes from the network, so it has Jazzer fuzz tests.

plugins {
    id("vectora.java-conventions")
    id("vectora.fuzz-conventions")
}

dependencies {
    api(project(":Vectora-Protocol"))
}
