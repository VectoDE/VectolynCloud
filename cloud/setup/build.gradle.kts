// Setup assistant of VectoraCloud: the questions on the first start, the same answers from a setup
// file for Docker, Pterodactyl and CI, and the "setup" command to change them later. Every change
// goes through cloud-config, the layer that the console commands, hand-edited files and later the
// dashboard use as well.
// The setup file and the password file come from outside, so their readers have Jazzer fuzz tests.
// Details: docs/cloud/setup.md

plugins {
    id("vectora.java-conventions")
    id("vectora.fuzz-conventions")
}

vectora {
    // Jar and manifest name from before the repository layout change.
    artifactName = "cloud-setup"
}

dependencies {
    api(project(":cloud:console"))
    // Only to tell whether the managed MariaDB runs on this system (ManagedPlatform).
    implementation(project(":cloud:database"))
}
