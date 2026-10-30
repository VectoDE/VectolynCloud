// Shared setup for every Java module.
//
// Java policy (see docs/java-compatibility.md): bytecode target is Java 21. The compiler always
// runs on a Java 25 toolchain. A module may raise its release target only when a dependency
// forces it, and must say why in its build file.
//
// Optional: -PtestJava=21 runs the unit tests on a Java 21 runtime instead of the toolchain JDK.
// Compilation still uses the Java 25 toolchain. Tests tagged "requires-java25" load classes of
// a Java 25 server API (class file version 69) and are skipped on older runtimes. CI uses this
// to prove that the bytecode-21 code paths really run on Java 21.

plugins {
    `java-library`
}

val defaultJavaRelease = 21
val toolchainVersion = 25
val testJava: Int? = providers.gradleProperty("testJava").orNull?.toInt()

val vectora = extensions.create<VectoraJavaExtension>("vectora")
vectora.warningsAsErrors.convention(true)
vectora.artifactName.convention(project.name)

// A build file that sets base.archivesName itself (the plugin jars) keeps its own name.
base.archivesName.convention(vectora.artifactName)

// Several modules share a project name (plugins/bridge/paper, plugins/lobby/paper, ...). Gradle
// tells project dependencies apart by group and name, so each folder gets its own group:
// net.vectora.cloud, net.vectora.common, net.vectora.plugins.bridge and so on.
group = "net.vectora" + project.path.substringBeforeLast(':').replace(':', '.')

java {
    toolchain.languageVersion = JavaLanguageVersion.of(toolchainVersion)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = defaultJavaRelease
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
    val werror = vectora.warningsAsErrors
    options.compilerArgumentProviders.add(CommandLineArgumentProvider {
        if (werror.get()) listOf("-Werror") else emptyList()
    })
}

val libs = the<VersionCatalogsExtension>().named("libs")

// Minimum versions for libraries that come in transitively through the server APIs (Paper,
// Velocity, PandaSpigot) and the Maven machinery inside paper-api. The APIs pin old releases with
// known advisories (OpenSSF Scorecard "Vulnerabilities" reads gradle/verification-metadata.xml).
// The APIs are compile only or test only here, so a newer patch release of the same line is safe.
// Reasons and the advisories: docs/dependency-verification.md.
val minimumVersions = mapOf(
    "com.google.guava:guava" to "33.7.2-jre",
    "com.google.code.gson:gson" to "2.14.0",
    "org.apache.commons:commons-lang3" to "3.18.0",
    "org.apache.logging.log4j:log4j-api" to "2.26.1",
    "org.codehaus.plexus:plexus-utils" to "3.6.1",
)
// Compares the numeric parts of two versions ("33.6.0-jre" against "33.7.2-jre").
fun compareVersions(a: String, b: String): Int {
    val left = a.split('.', '-').mapNotNull { it.toIntOrNull() }
    val right = b.split('.', '-').mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(left.size, right.size)) {
        val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}
configurations.configureEach {
    resolutionStrategy.eachDependency {
        val wanted = minimumVersions["${requested.group}:${requested.name}"] ?: return@eachDependency
        val current = requested.version ?: return@eachDependency
        if (compareVersions(current, wanted) < 0) {
            useVersion(wanted)
            because("known advisories in $current")
        }
    }
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

val javaToolchains = extensions.getByType<JavaToolchainService>()
tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        if (testJava != null && testJava < toolchainVersion) {
            excludeTags("requires-java25")
        }
    }
    if (testJava != null) {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(testJava)
        }
    }
}

// Implementation-Version lets the code read its own version through
// Package.getImplementationVersion(), for example for the User-Agent of cloud-download.
// Outside a jar (tests, IDE) it stays null and the code falls back to "dev".
val implementationTitle = vectora.artifactName
val implementationVersion = project.version.toString()
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest {
        attributes(
            "Implementation-Title" to implementationTitle,
            "Implementation-Version" to implementationVersion,
        )
    }
}
