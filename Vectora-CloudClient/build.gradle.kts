// Client side of the cloud channel, used by the bridges and later by the Vectora plugin.
// Depends on Vectora-Protocol only and uses the JDK for networking, so it adds nothing to the
// class path of the servers it runs on.

plugins {
    id("vectora.java-conventions")
}

dependencies {
    api(project(":Vectora-Protocol"))
}
