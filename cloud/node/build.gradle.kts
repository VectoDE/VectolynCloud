import java.security.MessageDigest

plugins {
    id("vectora.java-conventions")
    // state/services.json is read back on every start and may have been edited or damaged.
    id("vectora.fuzz-conventions")
}

vectora {
    // Jar and manifest name from before the repository layout change.
    artifactName = "cloud-node"
}

dependencies {
    api(project(":cloud:config"))
    // ServiceId of the protocol is part of the service API (console, API, cloud channel).
    api(project(":common:protocol"))
    implementation(project(":cloud:template"))
    implementation(project(":cloud:download"))
    // ServiceManager.authenticator() hands the network module's ServiceAuthenticator out.
    api(project(":cloud:network"))
    implementation(project(":cloud:database"))
    // Console with its JLine terminal; cloud-console brings JLine and the terminal providers
    // (jni on Java 21, ffm on 22+), see docs/java-compatibility.md. CloudNode.console() is part of
    // the API that the setup assistant (PR 11) uses.
    api(project(":cloud:console"))
    // The setup assistant runs before the first server starts, and "setup" is a console command.
    implementation(project(":cloud:setup"))
    // Wired in CloudNode (docs/cloud/backup.md, monitor.md, api.md): none of them knows the node.
    implementation(project(":cloud:backup"))
    implementation(project(":cloud:monitor"))
    implementation(project(":cloud:api"))

    // End-to-end test: the fake server connects with the real client.
    testImplementation(project(":common:client"))
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
    bridgeJars(project(":plugins:bridge:velocity"))
    bridgeJars(project(":plugins:bridge:paper"))
    bridgeJars(project(":plugins:bridge:legacy"))
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
 * The repository keeps a template in templates/<Server>/; in the jar it is <Server>/template/<path>
 * (index format 1). In the cloud home the node writes it to local/templates/<Server>/default/, only
 * for the servers a group wants.
 */
abstract class TemplateIndex : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val templates: ConfigurableFileCollection

    @get:Internal
    abstract val templatesRoot: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val root = templatesRoot.get().asFile
        val target = outputDirectory.get().asFile.resolve("net/vectora/cloud/node/templates")
        target.deleteRecursively()
        target.mkdirs()
        val lines = templates.files.filter { it.isFile }
            .map { it to it.relativeTo(root).invariantSeparatorsPath.replaceFirst("/", "/template/") }
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

val templatesDir = rootProject.layout.projectDirectory.dir("templates")
val templateResources = tasks.register<TemplateIndex>("templateResources") {
    description = "Puts the server templates and their index into the node's resources."
    templatesRoot = templatesDir
    // Only what the repository tracks belongs in the jar: never a plugin jar or a key someone put
    // into a template folder of their checkout, and never runtime data or secrets of a cloud home
    // that was started inside a template folder. Files directly in templates/ (placeholders.md)
    // are documentation, not a template.
    templates.from(templatesDir.asFileTree.matching {
        include("*/**")
        exclude("*.md")
        exclude("**/runtime/**", "**/runtime-*/**", "**/secrets/**")
        exclude("**/*.jar", "**/key.pem", "**/*.key", "**/*.secret", "**/*.p12", "**/*.jks", "**/.*", "**/.*/**")
    })
    outputDirectory = layout.buildDirectory.dir("generated/template-resources")
}

sourceSets.main {
    resources.srcDir(bridgeResources)
    resources.srcDir(templateResources)
}

// The end-to-end test starts the real VectoraCloud.jar as a process in an empty folder, with fake
// server software and without network (CloudJarEndToEndTest, docs/cloud/node.md#end-to-end-test).
// It takes a few minutes and starts about ten JVMs, so it is not part of the normal build; the
// Integration job of ci.yml runs it with ./gradlew :cloud:node:e2eTest.
val cloudJar: Configuration = configurations.create("cloudJar") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    cloudJar(project(mapOf("path" to ":cloud:launcher", "configuration" to "cloudJarElements")))
}

tasks.test {
    useJUnitPlatform {
        excludeTags("e2e")
    }
}

val e2eToolchains = extensions.getByType<JavaToolchainService>()
val e2eJava21 = e2eToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
val e2eJava25 = e2eToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }

/** Common setup of the two tasks that start VectoraCloud.jar as a process. */
fun Test.startsTheCloudJar() {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    val jar: FileCollection = cloudJar
    val java21 = e2eJava21
    val java25 = e2eJava25
    inputs.files(jar).withPropertyName("cloudJar").withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dvectora.e2e.cloudJar=${jar.singleFile.absolutePath}",
            // The servers get exactly Java 21 (PandaSpigot) and 25 (everything else).
            "-Dvectora.e2e.java21=${java21.get().executablePath.asFile.absolutePath}",
            "-Dvectora.e2e.java25=${java25.get().executablePath.asFile.absolutePath}",
        )
    })
    // -Pe2eAllowRoot=true for a developer shell that runs as administrator or root.
    providers.gradleProperty("e2eAllowRoot").orNull?.let { systemProperty("vectora.e2e.allowRoot", it) }
    // -Pe2eDatabase=managed: the cloud downloads and runs its own MariaDB (managed-mariadb.yml).
    providers.gradleProperty("e2eDatabase").orNull?.let { systemProperty("vectora.e2e.database", it) }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

val e2eTest by tasks.registering(Test::class) {
    description = "Starts VectoraCloud.jar in an empty folder with fake servers and checks the whole network."
    startsTheCloudJar()
    useJUnitPlatform {
        includeTags("e2e")
        excludeTags("e2e-live")
    }
}

// The same with real downloads and real Velocity, Paper and PandaSpigot: needs internet access
// and a few GB of memory, so only on purpose and in the weekly cloud-e2e-live.yml.
val e2eLiveTest by tasks.registering(Test::class) {
    description = "Starts VectoraCloud.jar with real downloads and real servers (needs internet access)."
    startsTheCloudJar()
    useJUnitPlatform {
        includeTags("e2e-live")
    }
}

// Local development start: the freshly built VectoraCloud.jar with run/ in the repository root as
// its cloud home. run/ is fully ignored by .gitignore, so worlds, logs, downloads and secrets the
// cloud writes there never reach a commit. Operators use their own home outside any checkout.
// The setup assistant asks on the console, so stdin is passed through.
val localHome: Directory = rootProject.layout.projectDirectory.dir("run")
tasks.register<JavaExec>("runLocal") {
    description = "Starts the built VectoraCloud.jar with run/ in the repository root as cloud home."
    group = ApplicationPlugin.APPLICATION_GROUP
    val jar: FileCollection = cloudJar
    val home = localHome
    inputs.files(jar).withPropertyName("cloudJar").withPathSensitivity(PathSensitivity.NONE)
    classpath = jar
    // The Main-Class of the jar; the launcher finds libs.index on the class path just as with -jar.
    mainClass = "net.vectora.cloud.launcher.Bootstrap"
    // With -jar the manifest attribute Enable-Native-Access does this.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Under Gradle the process has no console, so Java would fall back to the platform encoding
    // (cp1252 on Windows) and German messages would arrive garbled in a UTF-8 terminal.
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    workingDir = home.asFile
    // -PallowRoot=true for a developer shell that runs as administrator or root (passes --allow-root).
    val allowRoot = providers.gradleProperty("allowRoot").map { it.toBoolean() }.orElse(false)
    // An argument provider, not args(): --args="--lang en" replaces args, but adds to these.
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--home", home.asFile.absolutePath) + (if (allowRoot.get()) listOf("--allow-root") else emptyList())
    })
    standardInput = System.`in`
    doFirst { home.asFile.mkdirs() }
    outputs.upToDateWhen { false }
}

tasks.withType<Test>().configureEach {
    // The console's terminal providers call native code; Java 24+ warns unless it is allowed.
    // VectoraCloud.jar declares the same in its manifest (Enable-Native-Access, PR 14).
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // NodeDatabaseProcessesTest checks that no VECTORA_ variable of the node reaches MariaDB.
    environment("VECTORA_TEST_ENV_MARKER", "leak")
}
