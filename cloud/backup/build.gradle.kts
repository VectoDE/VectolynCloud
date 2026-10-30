// Backups of VectolynCloud: archives of the static services (worlds and configuration from
// local/services/<id>/), database dumps, manifest with hashes, retention, schedule, restore and the console
// commands. cloud-node implements the small interfaces (BackupTargets, ServiceControl,
// DatabaseSource) and starts the scheduler. The manifest is read back from archives that may have
// been edited or damaged, so its parser has a Jazzer fuzz test.
// Details: docs/cloud/backup.md

plugins {
    id("vectolyn.java-conventions")
    id("vectolyn.fuzz-conventions")
}

vectolyn {
    // Jar and manifest name from before the repository layout change.
    artifactName = "cloud-backup"
}

dependencies {
    // backup.yml uses the strict reader, error model and TimeSpan of the configuration layer.
    api(project(":cloud:config"))
    // The command definitions (backup create|list|restore|delete) use the console's command API.
    api(project(":cloud:console"))
    // Restore unpacks with the zip-slip-safe extractor of the download module.
    implementation(project(":cloud:download"))

    // Only the MariaDB integration test: it sets up a throwaway managed server through it.
    testImplementation(project(":cloud:database"))
}

// Tests tagged "mariadb" download and start a real MariaDB server in a temp folder and dump it.
// They never run in the normal build; run them on purpose with
// ./gradlew :cloud:backup:mariadbTest (.github/workflows/managed-mariadb.yml does).
tasks.test {
    useJUnitPlatform {
        excludeTags("mariadb")
    }
}

val mariadbTest by tasks.registering(Test::class) {
    description = "Dumps a throwaway managed MariaDB with mariadb-dump (needs internet access)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("mariadb")
    }
    systemProperty("vectolyn.mariadb.cache", layout.buildDirectory.dir("mariadb-cache").get().asFile.absolutePath)
    providers.gradleProperty("mariadbVersion").orNull?.let { systemProperty("vectolyn.mariadb.version", it) }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
