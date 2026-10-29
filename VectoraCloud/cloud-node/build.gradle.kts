import java.security.MessageDigest

plugins {
    id("vectora.java-conventions")
    // state/services.json is read back on every start and may have been edited or damaged.
    id("vectora.fuzz-conventions")
}

dependencies {
    api(project(":VectoraCloud:cloud-config"))
    // ServiceId of the protocol is part of the service API (console, API, cloud channel).
    api(project(":Vectora-Protocol"))
    implementation(project(":VectoraCloud:cloud-template"))
    implementation(project(":VectoraCloud:cloud-download"))
    // ServiceManager.authenticator() hands the network module's ServiceAuthenticator out.
    api(project(":VectoraCloud:cloud-network"))
    implementation(project(":VectoraCloud:cloud-database"))
    // Console with its JLine terminal; cloud-console brings JLine and the terminal providers
    // (jni on Java 21, ffm on 22+), see docs/java-compatibility.md. CloudNode.console() is part of
    // the API that the setup assistant (PR 11) uses.
    api(project(":VectoraCloud:cloud-console"))
    // The setup assistant runs before the first server starts, and "setup" is a console command.
    implementation(project(":VectoraCloud:cloud-setup"))
    // Wired in CloudNode (docs/cloud/backup.md, monitor.md, api.md): none of them knows the node.
    implementation(project(":VectoraCloud:cloud-backup"))
    implementation(project(":VectoraCloud:cloud-monitor"))
    implementation(project(":VectoraCloud:cloud-api"))

    // End-to-end test: the fake server connects with the real client.
    testImplementation(project(":Vectora-CloudClient"))
}

// The bridge plugins the node copies into the plugin folder of each service (docs/cloud/bridges.md).
// They travel inside the node's own jar, so VectoraCloud.jar carries them and the launcher's hash
// index covers them. Only the plugin jars themselves: each one already bundles its Vectora classes.
val bridgeJars: Configuration = configurations.create("bridgeJars") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    bridgeJars(project(":VectoraCloud:cloud-bridge-velocity"))
    bridgeJars(project(":VectoraCloud:cloud-bridge-paper"))
    bridgeJars(project(":VectoraCloud:cloud-bridge-legacy"))
}

/**
 * Writes bridges.index next to the bridge jars: one line per jar, "<sha256 hex>  <file name>", the
 * layout of sha256sum. The node reads it to find the jars (a class loader cannot list resources)
 * and checks every copy against it.
 */
abstract class BridgeIndex : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val target = outputDirectory.get().asFile.resolve("net/vectora/cloud/node/bridge")
        target.deleteRecursively()
        target.mkdirs()
        val lines = jars.files.filter { it.isFile }.sortedBy { it.name }.map { jar ->
            val digest = MessageDigest.getInstance("SHA-256")
            jar.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            jar.copyTo(target.resolve(jar.name))
            digest.digest().joinToString("") { "%02x".format(it) } + "  " + jar.name
        }
        check(lines.size == 3) { "Expected the velocity, paper and legacy bridge jars, got $lines" }
        target.resolve("bridges.index").writeText(
            "# VectoraCloud bridge plugins, format 1: <sha256>  <file name>\n" + lines.joinToString("") { "$it\n" },
            Charsets.UTF_8,
        )
    }
}

val bridgeResources = tasks.register<BridgeIndex>("bridgeResources") {
    description = "Puts the bridge plugin jars and their index into the node's resources."
    jars.from(bridgeJars)
    outputDirectory = layout.buildDirectory.dir("generated/bridge-resources")
}

/**
 * Copies the server templates into the node's resources and writes templates.index: one line per
 * file, "<sha256 hex>  <Server>/template/<path>". BuiltInTemplates writes a missing template folder
 * from them, so VectoraCloud.jar in an empty folder has every template (docs/cloud/node.md).
 */
abstract class TemplateIndex : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val templates: ConfigurableFileCollection

    @get:Internal
    abstract val serverNetwork: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val root = serverNetwork.get().asFile
        val target = outputDirectory.get().asFile.resolve("net/vectora/cloud/node/templates")
        target.deleteRecursively()
        target.mkdirs()
        val lines = templates.files.filter { it.isFile }
            .map { it to it.relativeTo(root).invariantSeparatorsPath }
            .sortedBy { it.second }
            .map { (file, path) ->
                val bytes = file.readBytes()
                val copy = target.resolve(path)
                copy.parentFile.mkdirs()
                copy.writeBytes(bytes)
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + "  " + path
            }
        check(lines.isNotEmpty()) { "No template found under ${root.path}" }
        target.resolve("templates.index").writeText(
            "# VectoraCloud server templates, format 1: <sha256>  <Server>/template/<path>\n" +
                lines.joinToString("") { "$it\n" },
            Charsets.UTF_8,
        )
    }
}

val serverNetworkDir = rootProject.layout.projectDirectory.dir("Server-Network")
val templateResources = tasks.register<TemplateIndex>("templateResources") {
    description = "Puts the server templates and their index into the node's resources."
    serverNetwork = serverNetworkDir
    // Only what the repository tracks belongs in the jar: never a plugin jar or a key someone put
    // into a template folder of their checkout.
    templates.from(serverNetworkDir.asFileTree.matching {
        include("*/template/**")
        exclude("**/*.jar", "**/key.pem", "**/*.key", "**/*.secret", "**/*.p12", "**/*.jks", "**/.*", "**/.*/**")
    })
    outputDirectory = layout.buildDirectory.dir("generated/template-resources")
}

sourceSets.main {
    resources.srcDir(bridgeResources)
    resources.srcDir(templateResources)
}

tasks.withType<Test>().configureEach {
    // The console's terminal providers call native code; Java 24+ warns unless it is allowed.
    // VectoraCloud.jar declares the same in its manifest (Enable-Native-Access, PR 14).
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // NodeDatabaseProcessesTest checks that no VECTORA_ variable of the node reaches MariaDB.
    environment("VECTORA_TEST_ENV_MARKER", "leak")
}
