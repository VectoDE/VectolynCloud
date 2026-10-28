// Wire format of the cloud channel, shared by VectoraCloud, Vectora-CloudClient and the bridges.
// Deliberately without any dependency: it ends up on the class path of every proxy and server.

plugins {
    id("vectora.java-conventions")
    id("vectora.fuzz-conventions")
}
