// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
// Web panel of VectolynCloud: an embedded Netty server that serves the Next.js frontend from the jar
// and talks to the API in-process. The frontend lives in web/ (Next.js, TypeScript, Tailwind) and is
// exported to static files at build time; at runtime there is no Node.js.
// Request targets and panel.yml come from outside, so they have Jazzer fuzz tests.
// Details: docs/cloud/panel.md

import org.gradle.internal.os.OperatingSystem

plugins {
    id("vectolyn.java-conventions")
    id("vectolyn.fuzz-conventions")
}

vectolyn {
    artifactName = "cloud-panel"
}

dependencies {
    // The panel runs the API's routes in-process with the rights of the signed-in user, and uses
    // the API's TLS material, rate limiter and JSON rules.
    api(project(":cloud:api"))
    api(project(":cloud:config"))
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.transport)
    implementation(libs.netty.handler)
    implementation(libs.netty.codec.http)
}

// --- Frontend --------------------------------------------------------------------------------
// -Pvectolyn.panel.web=true builds the frontend with npm (Node.js 20.9 or newer) and puts the
// static export into the jar. Without it the jar carries a placeholder page that explains how to
// build the frontend; the panel server and its tests work the same either way. Release builds and
// the Build job of CI set it.

val buildWeb: Boolean = providers.gradleProperty("vectolyn.panel.web").map { it.toBoolean() }.getOrElse(false)
val webDir = layout.projectDirectory.dir("web")
val npm: List<String> = if (OperatingSystem.current().isWindows) listOf("cmd", "/c", "npm") else listOf("npm")

val panelWebInstall = tasks.register<Exec>("panelWebInstall") {
    description = "Installs the frontend's packages from package-lock.json (npm ci, no install scripts)."
    workingDir(webDir)
    commandLine(npm + listOf("ci", "--ignore-scripts", "--no-audit", "--no-fund"))
    inputs.files(webDir.file("package.json"), webDir.file("package-lock.json"))
    outputs.dir(webDir.dir("node_modules"))
}

val panelWebBuild = tasks.register<Exec>("panelWebBuild") {
    description = "Builds and statically exports the panel frontend to web/out."
    dependsOn(panelWebInstall)
    workingDir(webDir)
    environment("NEXT_TELEMETRY_DISABLED", "1")
    commandLine(npm + listOf("run", "build"))
    inputs.files(fileTree(webDir) {
        include("app/**", "components/**", "lib/**", "messages/**", "scripts/**", "public/**")
        include("package.json", "package-lock.json", "next.config.ts", "tsconfig.json", "postcss.config.mjs")
        exclude("app/brand-tokens.css", "public/fonts/**", "public/mark-on-*.svg")
    })
    inputs.files(rootProject.layout.projectDirectory.dir("branding").asFileTree.matching {
        include("tokens.css", "mark-on-*.svg", "fonts/**/*.ttf")
    })
    outputs.dir(webDir.dir("out"))
}

/**
 * Copies the frontend into the panel's resources (net/vectolyn/cloud/panel/web/) and writes
 * files.index with one path per line: a class loader cannot list a folder.
 */
abstract class PanelWebIndex : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val files: ConfigurableFileCollection

    @get:Internal
    abstract val root: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val base = root.get().asFile
        val target = outputDirectory.get().asFile.resolve("net/vectolyn/cloud/panel/web")
        target.deleteRecursively()
        target.mkdirs()
        val paths = files.files.filter { it.isFile }.map { file ->
            val path = file.relativeTo(base).invariantSeparatorsPath
            check(Regex("[A-Za-z0-9_.~@()+-]+(/[A-Za-z0-9_.~@()+-]+)*").matches(path) && !path.contains("..")) {
                "The panel file $path has characters the panel server does not serve"
            }
            val copy = target.resolve(path)
            copy.parentFile.mkdirs()
            file.copyTo(copy, overwrite = true)
            path
        }.sorted()
        check(paths.contains("index.html")) { "The panel frontend has no index.html in ${base.path}" }
        target.resolve("files.index").writeText(paths.joinToString("") { "$it\n" }, Charsets.UTF_8)
    }
}

val webSource = if (buildWeb) webDir.dir("out") else layout.projectDirectory.dir("placeholder")
val panelWebResources = tasks.register<PanelWebIndex>("panelWebResources") {
    description = "Puts the panel frontend (or the placeholder page) and its index into the resources."
    if (buildWeb) {
        dependsOn(panelWebBuild)
    }
    root = webSource
    files.from(fileTree(webSource))
    outputDirectory = layout.buildDirectory.dir("generated/panel-web")
}

sourceSets.main {
    resources.srcDir(panelWebResources)
}

// The panel with the frontend from the build on 127.0.0.1, for the browser tests and development:
// ./gradlew :cloud:panel:panelPreview -Pvectolyn.panel.web=true [-PpanelPort=8090]
tasks.register<JavaExec>("panelPreview") {
    description = "Starts the panel server on 127.0.0.1 with the built frontend."
    group = "application"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "net.vectolyn.cloud.panel.PanelPreview"
    args(providers.gradleProperty("panelPort").getOrElse("8090"))
}
