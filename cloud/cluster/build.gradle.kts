// Cluster of several VectoraCloud nodes: own CA (made with the JDK's keytool), join tokens,
// mutual TLS 1.3 on a dedicated cluster port, NodeId in the certificate, revocation list. Plain
// JSSE and blocking sockets: a cluster has few nodes, so no Netty here. Reads frames and join
// requests from the network, so the payload decoders have Jazzer fuzz tests.
// Details: docs/cloud/cluster.md

plugins {
    id("vectora.java-conventions")
    id("vectora.fuzz-conventions")
}

vectora {
    artifactName = "cloud-cluster"
}

dependencies {
    // cluster.yml and the join tokens use the strict YAML layer, Secret, AtomicFileWriter and
    // OwnerOnlyPermissions of cloud-config; the head reads database.mode from the snapshot.
    api(project(":cloud:config"))
    implementation(libs.jackson.dataformat.yaml)
    // Frames, NodeId and the codecs of the cloud channel.
    api(project(":common:protocol"))
    // The "cluster" command is registered in the console's command registry.
    api(project(":cloud:console"))
    // AddressKey and ConnectionLimiter: the same /64 and unauthenticated limits as the service channel.
    implementation(project(":cloud:network"))
}

tasks.withType<Test>().configureEach {
    // cloud-console's terminal providers may load native code in the command tests.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
