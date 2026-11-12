// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("vectolyn.java-conventions")
    // ServerVersion parses text the database server sends; SqlQuoting guards every setup statement.
    id("vectolyn.fuzz-conventions")
}

vectolyn {
    // Jar and manifest name from before the repository layout change.
    artifactName = "cloud-database"
}

dependencies {
    api(project(":cloud:config"))
    // Managed mode downloads the MariaDB server through the shared downloader.
    api(project(":cloud:download"))
    implementation(libs.mariadb.java.client)
    // Mode postgresql: an operator's PostgreSQL server (BSD-2-Clause).
    implementation(libs.postgresql)
    // pgjdbc's package-info carries Checker Framework annotations (@DefaultQualifier). NullAway reads
    // them, and without the annotation classes javac warns [classfile], which -Werror turns into an
    // error. Compile only: pgjdbc brings the same jar at runtime.
    compileOnly(libs.checker.qual)

    // Only to check that the generated compose file is valid YAML.
    testImplementation(platform(libs.jackson.bom))
    testImplementation(libs.jackson.dataformat.yaml)
}

// Tests tagged "mariadb" download and start a real MariaDB server in a temp folder. They never run
// in the normal build; run them on purpose with ./gradlew :cloud:database:mariadbTest
// (the weekly workflow .github/workflows/managed-mariadb.yml does that on Linux and Windows).
tasks.test {
    useJUnitPlatform {
        excludeTags("mariadb")
    }
}

val mariadbTest by tasks.registering(Test::class) {
    description = "Downloads, starts and sets up a throwaway managed MariaDB (needs internet access)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("mariadb")
    }
    // Keeps the downloaded archive between local runs; CI starts empty.
    systemProperty("vectolyn.mariadb.cache", layout.buildDirectory.dir("mariadb-cache").get().asFile.absolutePath)
    providers.gradleProperty("mariadbVersion").orNull?.let { systemProperty("vectolyn.mariadb.version", it) }
    // Downloading and starting a real MariaDB takes longer than the default limit of five minutes.
    systemProperty("junit.jupiter.execution.timeout.default", "20m")
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
