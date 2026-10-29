// Monitoring of VectoraCloud: metrics per service and node, history, alert rules with hysteresis,
// notifications (webhook) and an optional Prometheus text endpoint. The node plugs it in through
// ServiceDirectory and Monitor.statusReported/serviceStateChanged; it does not know cloud-node.
// Alert rules and the history file are read from disk, so both parsers have Jazzer fuzz tests.
// Details: docs/cloud/monitor.md

plugins {
    id("vectora.java-conventions")
    id("vectora.fuzz-conventions")
}

vectora {
    // Jar and manifest name from before the repository layout change.
    artifactName = "cloud-monitor"
}

dependencies {
    // monitor.yml is read with the shared strict YAML mapper; TimeSpan, Secret, SecretStore and
    // AtomicFileWriter come from there as well.
    api(project(":cloud:config"))
    // ServiceId and ServiceStatus are part of the monitor's API.
    api(project(":common:protocol"))
    // monitor and alerts commands; MonitorCommands hands out console Command objects.
    api(project(":cloud:console"))
}

tasks.withType<Test>().configureEach {
    // cloud-console's terminal providers may load native code in the command tests.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
