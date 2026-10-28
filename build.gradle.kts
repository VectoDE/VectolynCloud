// Shared setup for all plugin modules.
//
// Java policy (see docs/java-compatibility.md): bytecode target is Java 21 by default.
// The compiler itself always runs on a Java 25 toolchain. A module may raise its
// release target only when a dependency forces it, and must say why in its build file.

val defaultJavaRelease = 21
val toolchainVersion = 25

// Optional: -PtestJava=21 runs the unit tests on a Java 21 runtime instead of the toolchain JDK.
// Compilation still uses the Java 25 toolchain. Tests tagged "requires-java25" load Paper
// classes (paper-api 26.3 is class file version 69) and are skipped on older runtimes.
// CI uses this to prove that the bytecode-21 code paths really run on Java 21.
val testJava: Int? = providers.gradleProperty("testJava").orNull?.toInt()

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(toolchainVersion)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = defaultJavaRelease
        // -Werror stays off for now: the existing lobby code still uses Paper APIs that 26.3
        // deprecates (GameRule constants, Sign#setLine). They are replaced in stage 4.
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
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

    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
