plugins {
    id("vectora.java-conventions")
}

dependencies {
    api(project(":VectoraCloud:cloud-config"))
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
