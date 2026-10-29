package net.vectora.cloud.config.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.ConfigService;
import net.vectora.cloud.config.Samples;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.GroupConfig;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.ProcessConfig;
import net.vectora.cloud.config.operation.PutGroup;
import net.vectora.cloud.config.operation.SetGroupHeap;
import net.vectora.cloud.config.validation.JvmFlagPolicy.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmFlagPolicyTest {

    @TempDir
    Path root;

    @Test
    void commonTuningFlagsAreAllowed() {
        for (String flag : List.of("-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
                "-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch",
                "-XX:G1NewSizePercent=30", "-XX:G1HeapRegionSize=8M", "-XX:InitiatingHeapOccupancyPercent=15",
                "-XX:MaxRAMPercentage=75.0", "-XX:-EnableDynamicAgentLoading", "-XX:ShenandoahGCMode=generational",
                "-Xss1M", "-Xmn256m", "-Dfile.encoding=UTF-8", "-Dusing.aikars.flags=https://mcflags.emc.gs",
                "-Daikars.new.flags=true", "-Dpaper.disableChannelLimit=true", "-Dvectora.flag=a,b",
                "-Dterminal.jline=false", "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--enable-native-access=ALL-UNNAMED", "-Xlog:gc*", "-Xlog:gc*:stdout:time", "-Xshare:off", "-ea")) {
            assertEquals(Verdict.ALLOWED, JvmFlagPolicy.check(flag), flag);
        }
    }

    @Test
    void flagsThatLoadCodeOrRunCommandsAreForbidden() {
        for (String flag : List.of("-javaagent:spark.jar", "-javaagent:/tmp/x.jar=opts", "-agentpath:/tmp/lib.so",
                "-agentlib:jdwp=transport=dt_socket,server=y,address=*:5005", "-Xrunjdwp:transport=dt_socket",
                "-Xdebug", "-Xbootclasspath/a:/tmp/x.jar", "--patch-module=java.base=/tmp", "--module-path=/tmp",
                "-XX:OnOutOfMemoryError=/bin/sh", "-XX:OnError=calc.exe", "-XX:Flags=/tmp/flags",
                "-XX:VMOptionsFile=/tmp/x", "-XX:CompileCommandFile=/tmp/x", "-XX:+EnableDynamicAgentLoading",
                "-XX:StartFlightRecording=filename=/tmp/x.jfr", "-XX:HeapDumpPath=/etc", "@argfile",
                "-Djava.system.class.loader=evil.Loader", "-Djava.library.path=/tmp",
                "-Dcom.sun.management.jmxremote.port=9010", "-Dcom.mojang.eula.agree=true",
                "-DLog4j.configurationFile=http://example.org/x.xml", "-Djava.security.manager=allow",
                "-Djdk.attach.allowAttachSelf=true")) {
            assertEquals(Verdict.FORBIDDEN, JvmFlagPolicy.check(flag), flag);
        }
    }

    @Test
    void everythingElseIsNotListed() {
        for (String flag : List.of("-XX:SomeNewFlag=1", "-Djavax.net.ssl.trustStore=x", "-Djna.library.path=/tmp",
                "-Dhttp.proxyHost=evil", "-Dorg.apache.logging.log4j.level=ALL", "-Dsun.misc.unsafe=1", "-Xlog:gc*:file=gc.log",
                "-Xlog:gc*:gc.log", "-Xlog:gc:C:\\gc.log", "-XX:G1HeapRegionSize=8M,x", "-verbose:gc", "", "-Xverify:none")) {
            assertEquals(Verdict.NOT_LISTED, JvmFlagPolicy.check(flag), flag);
        }
    }

    @Test
    void propertiesOfUnknownLibrariesAreNotListed() {
        // An unknown name may be read by any library on the class path, so the API cannot add it.
        for (String flag : List.of("-Dmyplugin.debug=true", "-Dnet.luckperms.storage=h2",
                "-Dcom.example.loader.repository=https://evil.example/maven",
                "-Dh2.javaObjectSerializer=evil.Serializer", "-Djdk.myplugin.debug=true", "-DJava.Foo=1")) {
            assertEquals(Verdict.NOT_LISTED, JvmFlagPolicy.check(flag), flag);
        }
        // Counter-check: an exact name keeps its value, a known prefix with a plain value is allowed.
        for (String flag : List.of("-DIReallyKnowWhatIAmDoingISwear", "-Duser.timezone=Europe/Berlin",
                "-DPaper.IgnoreJavaVersion=true", "-Dnet.kyori.ansi.colorLevel=truecolor", "-Dfabric.debug.x=1")) {
            assertEquals(Verdict.ALLOWED, JvmFlagPolicy.check(flag), flag);
        }
    }

    @Test
    void propertiesUnderAllowedPrefixesThatLoadCodeAreRefused() {
        // Fabric loads extra mods from any path (a UNC path on Windows fetches it from another host),
        // JLine 2 runs the programs these name, Netty and Velocity load native libraries from these folders.
        for (String flag : List.of("-Dfabric.addMods=//evil.example/share/mod.jar", "-Dfabric.addMods=mod.jar",
                "-DFabric.GameJarPath=x.jar", "-Djline.stty=x", "-Djline.sh=x", "-Dio.netty.native.workdir=x",
                "-Dvelocity.natives-tmpdir=x", "-Dorg.bukkit.plugin.java.LibraryLoader.centralURL=x")) {
            assertEquals(Verdict.FORBIDDEN, JvmFlagPolicy.check(flag), flag);
        }
        // A path, a URL, a drive or a parent folder as the value of a prefixed property is not listed.
        for (String flag : List.of("-Dpaper.x=/tmp/y", "-Dvelocity.x=C:y", "-Dfabric.x=https://evil.example",
                "-Dvectora.x=..", "-Dspark.x=a@b", "-Dgeyser.x=%41")) {
            assertEquals(Verdict.NOT_LISTED, JvmFlagPolicy.check(flag), flag);
        }
    }

    @Test
    void aChangeMayNotAddARefusedFlag() {
        ConfigSnapshot before = Samples.snapshot();
        ConfigSnapshot after = before.withGroup("lobby", withFlags(Samples.lobbyGroup(),
                "-XX:+UseG1GC", "-javaagent:x.jar", "-Djavax.net.ssl.keyStore=x"));
        List<ConfigError> errors = JvmFlagPolicy.refusedChanges(before, after);
        assertEquals(List.of("groups/lobby.yml process.jvm-flags[1] JVM_FLAG_FORBIDDEN",
                "groups/lobby.yml process.jvm-flags[2] JVM_FLAG_NOT_LISTED"), errors.stream()
                .map(e -> e.file() + " " + e.path() + " " + e.code().name()).toList());
        // The message never repeats the flag, so a value pasted there does not reach a log.
        assertTrue(errors.stream().noneMatch(e -> e.message(Language.EN).contains("javaagent")));
    }

    @Test
    void flagsAlreadyInTheFileStayButAreReportedAsWarnings() {
        GroupConfig legacy = withFlags(Samples.lobbyGroup(), "-javaagent:spark.jar", "-XX:+UseG1GC");
        ConfigSnapshot before = Samples.snapshot().withGroup("lobby", legacy);
        // Same flags in another order and a changed heap: nothing is added.
        ConfigSnapshot reordered = before.withGroup("lobby", withFlags(legacy, "-XX:+UseG1GC", "-javaagent:spark.jar"));
        assertEquals(List.of(), JvmFlagPolicy.refusedChanges(before, reordered));
        assertEquals(1, JvmFlagPolicy.warnings(before).size());
        // Copying the flag into a new group counts as adding it.
        ConfigSnapshot copied = before.withGroup("lobby2", legacy);
        assertEquals(1, JvmFlagPolicy.refusedChanges(before, copied).size());
        // Two copies where the file had one: the second is new.
        ConfigSnapshot doubled = before.withGroup("lobby", withFlags(legacy, "-javaagent:spark.jar", "-javaagent:spark.jar"));
        assertEquals(1, JvmFlagPolicy.refusedChanges(before, doubled).size());
    }

    @Test
    void serviceKeepsTheOperatorsFlagsAndReportsThemAsWarnings() throws Exception {
        Files.createDirectories(root.resolve("groups"));
        Files.writeString(root.resolve("groups/lobby.yml"), """
                schema-version: 1
                template: Lobby-Server
                software: paper
                minecraft-version: "26.3"
                java: auto
                heap:
                  min: 256M
                  max: 768M
                combat: modern
                process:
                  jvm-flags: [-javaagent:spark.jar]
                """);
        try (ConfigService service = ConfigService.open(CloudHome.of(root))) {
            assertEquals(List.of(ErrorCode.JVM_FLAG_FORBIDDEN), service.warnings().stream().map(ConfigError::code).toList());
            // Changing something else in the group keeps the old flag.
            service.apply(new SetGroupHeap("lobby", MemorySize.parse("512M"), MemorySize.parse("1G")));
            assertTrue(Files.readString(root.resolve("groups/lobby.yml")).contains("-javaagent:spark.jar"));
            // The console is the operator's own: ConfigService itself does not refuse; the REST API does
            // (ApiServerTest), and the flag shows up as a warning.
            service.apply(new PutGroup("lobby", withFlags(Samples.lobbyGroup(), "-javaagent:spark.jar", "-Dmyplugin.x=1")));
            assertEquals(List.of(ErrorCode.JVM_FLAG_FORBIDDEN, ErrorCode.JVM_FLAG_NOT_LISTED),
                    service.warnings().stream().map(ConfigError::code).toList());
            // The pattern and reserved-flag rules still apply to everyone.
            assertThrows(ConfigException.class, () -> service.apply(new PutGroup("lobby",
                    withFlags(Samples.lobbyGroup(), "-jar"))));
            assertFalse(service.snapshot().groups().get("lobby").processOrDefaults().jvmFlags().contains("-jar"));
        }
    }

    private static GroupConfig withFlags(GroupConfig group, String... flags) {
        return group.withProcess(new ProcessConfig(List.of(flags), null, null, null));
    }
}
