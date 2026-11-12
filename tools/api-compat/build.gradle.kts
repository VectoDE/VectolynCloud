// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
// Checks the public API of common/protocol and common/client against the newest release tag with
// japicmp (build tool, Apache-2.0, not shipped). Details: docs/api-compatibility.md
//
//   ./gradlew :tools:api-compat:apiCompat -PapiBaselineDir=<dir> -PapiBaselineTag=v0.1.1-dev
//
// <dir> holds protocol.jar and client.jar built from that tag (ci.yml, job "API compatibility").

plugins {
    id("vectolyn.java-conventions")
    // The baseline tag and the arguments are parsed (ApiCompatFuzzTest).
    id("vectolyn.fuzz-conventions")
}

vectolyn {
    artifactName = "api-compat"
}

// The command-line build of japicmp with its dependencies inside, one artifact to verify.
val japicmp: Configuration = configurations.create("japicmp") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

fun apiJar(name: String): Configuration = configurations.create(name) {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
val protocolJar = apiJar("apiProtocol")
val clientJar = apiJar("apiClient")

dependencies {
    japicmp(variantOf(libs.japicmp) { classifier("jar-with-dependencies") })
    protocolJar(project(":common:protocol"))
    clientJar(project(":common:client"))
}

// JapicmpTest runs the real japicmp against two small jars it compiles itself.
tasks.test {
    val jar: FileCollection = japicmp
    inputs.files(jar).withPropertyName("japicmp").withNormalizer(ClasspathNormalizer::class)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dvectolyn.japicmp=${jar.singleFile.absolutePath}")
    })
}

tasks.register<JavaExec>("apiCompat") {
    group = "verification"
    description = "Compares the API of common/protocol and common/client with the newest release " +
        "(-PapiBaselineDir, -PapiBaselineTag)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "net.vectolyn.tools.apicompat.ApiCompat"

    val tool: FileCollection = japicmp
    val protocol: FileCollection = protocolJar
    val client: FileCollection = clientJar
    inputs.files(tool, protocol, client).withPropertyName("jars")
    val baselineDir = providers.gradleProperty("apiBaselineDir")
    val baselineTag = providers.gradleProperty("apiBaselineTag")
    val next = project.version.toString()
    val report = layout.buildDirectory.file("reports/api-compat/report.md")
    outputs.file(report).withPropertyName("report")
    // The baseline comes from outside the build; never take the result from the cache.
    outputs.upToDateWhen { false }

    argumentProviders.add(CommandLineArgumentProvider {
        val dir = File(baselineDir.orNull
            ?: throw GradleException("set -PapiBaselineDir=<folder with protocol.jar and client.jar of the baseline>"))
        val tag = baselineTag.orNull ?: throw GradleException("set -PapiBaselineTag=<release tag, e.g. v0.1.1-dev>")
        val oldProtocol = dir.resolve("protocol.jar").absolutePath
        val newProtocol = protocol.singleFile.absolutePath
        listOf(
            "--japicmp", tool.singleFile.absolutePath,
            "--baseline", tag,
            "--next", next,
            "--report", report.get().asFile.absolutePath,
            "--module", "common/protocol", oldProtocol, newProtocol, "-", "-",
            "--module", "common/client", dir.resolve("client.jar").absolutePath, client.singleFile.absolutePath,
            oldProtocol, newProtocol,
        )
    })
}
