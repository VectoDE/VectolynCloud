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
