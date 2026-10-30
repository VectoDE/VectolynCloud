// Wire format of the cloud channel, shared by VectolynCloud, common/client and the bridges.
// Deliberately without any dependency: it ends up on the class path of every proxy and server.

plugins {
    id("vectolyn.java-conventions")
    id("vectolyn.fuzz-conventions")
}

vectolyn {
    // Jar and manifest name from before the repository layout change.
    artifactName = "Vectolyn-Protocol"
}
