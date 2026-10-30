// Console of VectolynCloud: terminal (JLine), command framework, built-in commands and the
// question API that the setup assistant builds on. Changes go through cloud-config, the same layer
// that files, the setup assistant and later the API use.
// The command line parser reads what an operator types, so it has a Jazzer fuzz test.
// Details: docs/cloud/console.md

plugins {
    id("vectolyn.java-conventions")
    id("vectolyn.fuzz-conventions")
}

vectolyn {
    // Jar and manifest name from before the repository layout change.
    artifactName = "cloud-console"
}

dependencies {
    api(project(":cloud:config"))
    implementation(libs.jline.reader)
    implementation(libs.jline.terminal)
    // Terminal providers. jni works on Java 21 (native helper for Windows, Linux and macOS).
    // ffm needs Java 22+ (Foreign Function & Memory API); TerminalSelector only loads it there,
    // checked by the running version, never by class-path order. See docs/java-compatibility.md.
    runtimeOnly(libs.jline.terminal.jni)
    runtimeOnly(libs.jline.terminal.ffm)
}

tasks.withType<Test>().configureEach {
    // jni loads a native library and ffm calls native code; Java 24+ warns about both unless allowed.
    // VectolynCloud.jar declares the same in its manifest (Enable-Native-Access).
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
