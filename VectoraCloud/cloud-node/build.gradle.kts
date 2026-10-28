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
    implementation(project(":VectoraCloud:cloud-network"))
    implementation(project(":VectoraCloud:cloud-database"))
    // Console. The terminal provider is chosen with the console: jni on Java 21,
    // ffm (Java 22+, jline-terminal-ffm) only where available. See docs/java-compatibility.md.
    implementation(libs.jline.reader)
    implementation(libs.jline.terminal)
    runtimeOnly(libs.jline.terminal.jni)
}
