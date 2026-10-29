import java.security.MessageDigest

plugins {
    id("vectora.java-conventions")
}

dependencies {
    // Only so that cloud-node and its libraries end up in VectoraCloud.jar (see cloudJar below).
    // The launcher code itself never references a node class at compile time; it loads
    // net.vectora.cloud.node.NodeMain by name, after the Java, root and argument checks.
    implementation(project(":VectoraCloud:cloud-node"))
}

// Bootstrap source set: the Main-Class of VectoraCloud.jar, compiled with --release 8.
//
// Deliberate exception to the release-21 policy in docs/java-compatibility.md, and the only one:
// the first class the JVM loads must be readable by an old runtime, otherwise a user on Java 8 to
// 20 gets an UnsupportedClassVersionError instead of the "you need Java 21" message. The class only
// checks the version and then calls the release-21 launcher by reflection. Details in
// docs/cloud/launcher.md.
val bootstrap: SourceSet = sourceSets.create("bootstrap")

tasks.named<JavaCompile>(bootstrap.compileJavaTaskName) {
    options.release = 8
    // javac 25 warns that release 8 is obsolete; with -Werror that warning would fail the build.
    // All other lint checks stay on.
    options.compilerArgs.add("-Xlint:-options")
}

dependencies {
    // The tests check the bootstrap logic (version parsing, messages) directly.
    testImplementation(bootstrap.output)
}

/**
 * Writes the index of the libraries embedded in VectoraCloud.jar: one line per jar,
 * "<sha256 hex>  <file name>", the same layout as sha256sum. The launcher reads it at startup,
 * unpacks the jars to <home>/cache/libs/<version> and checks every hash before loading anything.
 */
abstract class EmbeddedLibrariesIndex : DefaultTask() {

    /** The jars that go into META-INF/vectora/libs/. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val libraries: ConfigurableFileCollection

    /** The index file; cloudJar stores it as META-INF/vectora/libs.index. */
    @get:OutputFile
    abstract val indexFile: RegularFileProperty

    @TaskAction
    fun write() {
        val jars = libraries.files.filter { it.isFile }.sortedBy { it.name }
        val duplicates = jars.groupBy { it.name }.filterValues { it.size > 1 }.keys
        check(duplicates.isEmpty()) { "Embedded libraries with the same file name: $duplicates" }
        val lines = jars.map { jar ->
            val digest = MessageDigest.getInstance("SHA-256")
            jar.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            "$hex  ${jar.name}"
        }
        val target = indexFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(
            "# VectoraCloud embedded libraries, format 1: <sha256>  <file name>\n" +
                lines.joinToString("") { "$it\n" },
            Charsets.UTF_8,
        )
    }
}

val embeddedLibraries: FileCollection = configurations.named("runtimeClasspath").get()
val embeddedLibrariesIndex = tasks.register<EmbeddedLibrariesIndex>("embeddedLibrariesIndex") {
    description = "Writes META-INF/vectora/libs.index for VectoraCloud.jar."
    libraries.from(embeddedLibraries)
    indexFile = layout.buildDirectory.file("generated/vectora-libs-index/libs.index")
}

// VectoraCloud.jar: bootstrap and launcher classes at the top level, cloud-node and every runtime
// library as plain jars under META-INF/vectora/libs/ plus their index. This replaces a shaded
// fat jar (Shadow plugin); the reasons are in docs/cloud/launcher.md.
val cloudJar = tasks.register<Jar>("cloudJar") {
    description = "Assembles VectoraCloud.jar with the embedded node and its libraries."
    group = BasePlugin.BUILD_GROUP
    archiveFileName = "VectoraCloud.jar"
    destinationDirectory = layout.buildDirectory.dir("cloud")
    from(sourceSets.main.map { it.output })
    from(bootstrap.output)
    from(embeddedLibrariesIndex) {
        into("META-INF/vectora")
    }
    from(embeddedLibraries) {
        into("META-INF/vectora/libs")
    }
    manifest {
        attributes(
            "Main-Class" to "net.vectora.cloud.launcher.Bootstrap",
            "Implementation-Title" to "VectoraCloud",
            "Implementation-Version" to project.version.toString(),
            // JLine's jni and ffm terminal providers call native code; Java 24+ warns unless it is
            // allowed. The node's class loader is an unnamed module too, so ALL-UNNAMED covers it.
            // Only the manifest can carry this: a --enable-native-access flag in the start scripts
            // would make Java 8 to 16 fail with "Unrecognized option" before the bootstrap can
            // print its "you need Java 21" message. Java 21 to 23 ignore the attribute.
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
}

tasks.named("assemble") {
    dependsOn(cloudJar)
}

// CloudJarTest opens the real VectoraCloud.jar (manifest and layout).
tasks.named<Test>("test") {
    val jar = cloudJar.flatMap { it.archiveFile }
    inputs.file(jar).withPathSensitivity(PathSensitivity.NONE).withPropertyName("cloudJar")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dvectora.cloudJar=${jar.get().asFile.absolutePath}")
    })
}
