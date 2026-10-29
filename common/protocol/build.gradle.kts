// Wire format of the cloud channel, shared by VectoraCloud, common/client and the bridges.
// Deliberately without any dependency: it ends up on the class path of every proxy and server.

plugins {
    id("vectora.java-conventions")
    id("vectora.fuzz-conventions")
}

vectora {
    // Jar and manifest name from before the repository layout change.
    artifactName = "Vectora-Protocol"
}
