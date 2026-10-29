plugins {
    id("vectora.java-conventions")
    // state/services.json is read back on every start and may have been edited or damaged.
    id("vectora.fuzz-conventions")
}

dependencies {
    api(project(":VectoraCloud:cloud-config"))
    // ServiceId of the protocol is part of the service API (console, API, cloud channel).
    api(project(":Vectora-Protocol"))
    implementation(project(":VectoraCloud:cloud-template"))
    implementation(project(":VectoraCloud:cloud-download"))
    // ServiceManager.authenticator() hands the network module's ServiceAuthenticator out.
    api(project(":VectoraCloud:cloud-network"))
    implementation(project(":VectoraCloud:cloud-database"))
    // Console with its JLine terminal; cloud-console brings JLine and the terminal providers
    // (jni on Java 21, ffm on 22+), see docs/java-compatibility.md. CloudNode.console() is part of
    // the API that the setup assistant (PR 11) uses.
    api(project(":VectoraCloud:cloud-console"))

    // End-to-end test: the fake server connects with the real client.
    testImplementation(project(":Vectora-CloudClient"))
}

tasks.withType<Test>().configureEach {
    // The console's terminal providers call native code; Java 24+ warns unless it is allowed.
    // VectoraCloud.jar declares the same in its manifest (Enable-Native-Access, PR 14).
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // NodeDatabaseProcessesTest checks that no VECTORA_ variable of the node reaches MariaDB.
    environment("VECTORA_TEST_ENV_MARKER", "leak")
}
