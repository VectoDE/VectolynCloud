// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
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
    id("net.ltgt.errorprone")
    id("com.github.spotbugs")
    id("checkstyle")
    id("pmd")
    id("jacoco")
}

val defaultJavaRelease = 21
val toolchainVersion = 25
val testJava: Int? = providers.gradleProperty("testJava").orNull?.toInt()

val vectolyn = extensions.create<VectolynJavaExtension>("vectolyn")
vectolyn.warningsAsErrors.convention(true)
vectolyn.minimumLineCoverage.convention(0.70)
vectolyn.artifactName.convention(project.name)

// A build file that sets base.archivesName itself (the plugin jars) keeps its own name.
base.archivesName.convention(vectolyn.artifactName)

// Several modules share a project name (plugins/bridge/paper, plugins/lobby/paper, ...). Gradle
// tells project dependencies apart by group and name, so each folder gets its own group:
// net.vectolyn.cloud, net.vectolyn.common, net.vectolyn.plugins.bridge and so on.
group = "net.vectolyn" + project.path.substringBeforeLast(':').replace(':', '.')

java {
    toolchain.languageVersion = JavaLanguageVersion.of(toolchainVersion)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = defaultJavaRelease
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
    val werror = vectolyn.warningsAsErrors
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
    // A test that hangs fails after five minutes and names itself. The database tests that start
    // a real MariaDB (mariadbTest) raise the limit in their build file.
    systemProperty("junit.jupiter.execution.timeout.default", "5m")
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
val implementationTitle = vectolyn.artifactName
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

// ---------------------------------------------------------------------------------------------
// Static analysis (issue #223). The versions live here because only the build logic uses them.
val errorProneVersion = "2.52.0"
val nullAwayVersion = "0.14.3"
val findSecBugsVersion = "1.14.0"

dependencies {
    add("errorprone", "com.google.errorprone:error_prone_core:$errorProneVersion")
    add("errorprone", "com.uber.nullaway:nullaway:$nullAwayVersion")
    compileOnly("org.jspecify:jspecify:1.0.0")
    testCompileOnly("org.jspecify:jspecify:1.0.0")
    add("spotbugsPlugins", "com.h3xstream.findsecbugs:findsecbugs-plugin:$findSecBugsVersion")
}

// Developer aid: -PerrorproneOnly=:cloud:backup,:cloud:api runs Error Prone and NullAway only in
// those modules, so one module can be fixed without its upstream modules blocking the build.
// CI never sets it.
val errorproneOnly: List<String>? = providers.gradleProperty("errorproneOnly").orNull?.split(',')
tasks.withType<JavaCompile>().configureEach {
    options.errorprone.enabled.set(errorproneOnly == null || project.path in errorproneOnly)
    options.errorprone {
        // Every Error Prone check at its default severity; warnings fail the build with -Werror.
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:AnnotatedPackages", "net.vectolyn")
        option("NullAway:JSpecifyMode", "true")
    }
    // Test code: Error Prone runs, NullAway does not (fixtures pass null on purpose).
    if (name == "compileTestJava") {
        options.errorprone.check("NullAway", CheckSeverity.OFF)
    }
}

spotbugs {
    effort = com.github.spotbugs.snom.Effort.MAX
    reportLevel = com.github.spotbugs.snom.Confidence.MEDIUM
    ignoreFailures = false
    // Excluded bug patterns, each with its reason: gradle/spotbugs/exclude.xml.
    excludeFilter = rootProject.file("gradle/spotbugs/exclude.xml")
}
tasks.named("spotbugsTest") { enabled = false }

// Checkstyle and PMD run on production code with curated rule sets (gradle/checkstyle, gradle/pmd).
// Any violation fails the build.
checkstyle {
    toolVersion = "11.1.0"
    configFile = rootProject.file("gradle/checkstyle/checkstyle.xml")
    maxWarnings = 0
}
tasks.named("checkstyleTest") { enabled = false }

pmd {
    toolVersion = "7.22.0"
    ruleSets = emptyList()
    ruleSetFiles = files(rootProject.file("gradle/pmd/ruleset.xml"))
    isConsoleOutput = true
    isIgnoreFailures = false
}
tasks.named("pmdTest") { enabled = false }

// PMD pulls Apache HttpComponents 5.1.3 for its remote rule set loading; that line has advisories
// (GHSA-hjcp-jmpx-g3qm, GHSA-v3jc-474w-2wm6, GHSA-hf6x-8p5f-cgmf). The tool does not use the
// affected features here (rule sets are local files); the fixed releases are a drop-in.
dependencies {
    constraints {
        add("pmd", "org.apache.httpcomponents.client5:httpclient5:5.6.3")
        add("pmd", "org.apache.httpcomponents.core5:httpcore5:5.4.3")
        add("pmd", "org.apache.httpcomponents.core5:httpcore5-h2:5.4.3")
    }
}

// JaCoCo: every module reports line coverage, and `check` fails below the module's minimum.
jacoco {
    toolVersion = "0.8.14"
}
tasks.withType<Test>().configureEach {
    finalizedBy(tasks.named("jacocoTestReport"))
}
tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required = true
        html.required = true
    }
}
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = vectolyn.minimumLineCoverage.get().toBigDecimal()
            }
        }
    }
}
tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}
