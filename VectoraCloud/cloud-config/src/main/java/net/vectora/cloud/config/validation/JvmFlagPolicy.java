package net.vectora.cloud.config.validation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.GroupConfig;

/**
 * Allowlist for {@code process.jvm-flags} of a group.
 *
 * <p>The REST API can write groups, so a stolen token must not turn into code execution on the
 * node through a flag such as {@code -javaagent:} or {@code -XX:OnOutOfMemoryError=}. Every flag
 * falls into one of three classes ({@link #check}):
 * <ul>
 *   <li>{@link Verdict#ALLOWED}: on the allowlist (GC and sizing flags, boolean {@code -XX} flags,
 *       known system properties, module options, {@code -Xlog} to the console).</li>
 *   <li>{@link Verdict#FORBIDDEN}: loads code into the JVM, runs a command, opens remote access
 *       or bypasses a gate of the node (agents, {@code -Xbootclasspath}, {@code -XX:OnError=},
 *       JMX remote, {@code @argfiles}, {@code -Dcom.mojang.eula.agree}, ...).</li>
 *   <li>{@link Verdict#NOT_LISTED}: everything else.</li>
 * </ul>
 *
 * <p>Compatibility: files written by hand before the allowlist existed keep working. A flag that is
 * not allowed only becomes an error when a change through {@code ConfigService.apply} adds it
 * ({@link #refusedChanges}); a flag already in the file is reported as a warning ({@link #warnings}).
 * The pattern and reserved-flag checks of {@link ConfigValidator} apply in both cases.
 */
public final class JvmFlagPolicy {

    /** Result of {@link #check}. */
    public enum Verdict {
        /** On the allowlist. */
        ALLOWED,
        /** Not on the allowlist. */
        NOT_LISTED,
        /** Loads code, runs commands, opens remote access or bypasses the node. */
        FORBIDDEN
    }

    private static final Pattern XX_BOOLEAN = Pattern.compile("-XX:[+-][A-Za-z][A-Za-z0-9_]{0,79}");
    private static final Pattern XX_VALUE = Pattern.compile("-XX:([A-Za-z][A-Za-z0-9_]{0,79})=([A-Za-z0-9.+-]{1,64})");
    private static final Pattern PROPERTY = Pattern.compile("-D([A-Za-z0-9][A-Za-z0-9_.-]{0,127})(=.*)?");
    private static final Pattern SIZE = Pattern.compile("-X(ss|mn)[0-9]{1,9}[kKmMgG]?");
    private static final Pattern MODULE_OPTION = Pattern.compile(
            "--(add-opens|add-exports|add-reads|add-modules|enable-native-access|sun-misc-unsafe-memory-access"
                    + "|illegal-native-access)=[A-Za-z0-9_.,/=-]{1,255}");
    private static final Pattern ASSERTIONS = Pattern.compile("-(ea|da|esa|dsa|enableassertions|disableassertions"
            + "|enablesystemassertions|disablesystemassertions)(:[A-Za-z0-9_.]{1,200}(\\.\\.\\.)?)?");

    /** Boolean {@code -XX} flags that are refused although booleans are otherwise allowed. */
    private static final Set<String> FORBIDDEN_BOOLEANS = Set.of(
            "EnableDynamicAgentLoading", "EnableJVMCI", "UseJVMCICompiler", "EnableJVMCIProduct");

    /** {@code -XX:Name=value} flags that run commands or read files with options or code. */
    private static final Set<String> FORBIDDEN_VALUE_FLAGS = Set.of(
            "OnOutOfMemoryError", "OnError", "Flags", "VMOptionsFile", "CompileCommandFile", "CompileCommand",
            "JVMCILibPath", "JVMCIPrintProperties", "StartFlightRecording", "FlightRecorderOptions",
            "ErrorFile", "HeapDumpPath", "LogFile", "SharedArchiveFile", "AOTCache", "AOTConfiguration",
            "ArchiveClassesAtExit");

    /** {@code -XX:Name=value} flags that only size memory, threads, the GC or the JIT. */
    private static final Set<String> VALUE_FLAGS = Set.of(
            "MaxGCPauseMillis", "GCPauseIntervalMillis", "GCTimeRatio", "G1NewSizePercent", "G1MaxNewSizePercent",
            "G1HeapRegionSize", "G1ReservePercent", "G1HeapWastePercent", "G1MixedGCCountTarget",
            "G1MixedGCLiveThresholdPercent", "G1RSetUpdatingPauseTimePercent", "G1PeriodicGCInterval",
            "G1ConcRefinementThreads", "InitiatingHeapOccupancyPercent", "SurvivorRatio", "MaxTenuringThreshold",
            "InitialTenuringThreshold", "TargetSurvivorRatio", "NewRatio", "NewSize", "MaxNewSize",
            "ParallelGCThreads", "ConcGCThreads", "MaxRAMPercentage", "InitialRAMPercentage", "MinRAMPercentage",
            "MaxRAM", "MaxMetaspaceSize", "MetaspaceSize", "CompressedClassSpaceSize", "ReservedCodeCacheSize",
            "InitialCodeCacheSize", "MaxDirectMemorySize", "ThreadStackSize", "ActiveProcessorCount",
            "SoftMaxHeapSize", "MaxHeapFreeRatio", "MinHeapFreeRatio", "ZUncommitDelay", "ZCollectionInterval",
            "ZAllocationSpikeTolerance", "ShenandoahGCMode", "ShenandoahGCHeuristics", "CICompilerCount",
            "TieredStopAtLevel", "MaxInlineLevel", "MaxInlineSize", "FreqInlineSize", "InlineSmallCode",
            "LoopUnrollLimit", "AutoBoxCacheMax", "StringDeduplicationAgeThreshold", "AllocatePrefetchStyle",
            "LargePageSizeInBytes", "NativeMemoryTracking", "ObjectAlignmentInBytes", "MarkStackSize",
            "MaxJavaStackTraceDepth", "AdaptiveSizePolicyWeight");

    /** System properties that load classes or libraries, open remote access or bypass the node. */
    private static final List<String> FORBIDDEN_PROPERTIES = List.of(
            "java.system.class.loader", "java.security.manager", "java.security.policy", "java.security.properties",
            "java.library.path", "sun.boot.library.path", "java.class.path", "java.home", "java.ext.dirs",
            "java.endorsed.dirs", "jdk.attach.allowattachself", "java.rmi.server.codebase", "com.mojang.eula.agree",
            "log4j.configurationfile", "log4j2.configurationfile", "log4j.configuration");
    /** Prefixes of system properties with the same problem. */
    private static final List<String> FORBIDDEN_PROPERTY_PREFIXES = List.of(
            "com.sun.management.jmxremote", "jdk.module.", "log4j2.script", "jdk.internal.", "java.security.");

    /** Exact system property names that are allowed. */
    private static final Set<String> PROPERTIES = Set.of(
            "file.encoding", "stdout.encoding", "stderr.encoding", "sun.stdout.encoding", "sun.stderr.encoding",
            "user.language", "user.country", "user.timezone", "java.awt.headless", "java.net.preferipv4stack",
            "java.net.preferipv6addresses", "log4j2.formatmsgnolookups", "using.aikars.flags", "aikars.new.flags",
            "ireallyknowwhatiamdoingiswear", "disable.watchdog");
    /** Prefixes of allowed system properties (server software, libraries the servers ship). */
    private static final List<String> PROPERTY_PREFIXES = List.of(
            "paper.", "velocity.", "fabric.", "vectora.", "terminal.", "jline.", "io.netty.", "net.kyori.",
            "com.velocitypowered.", "geyser.", "floodgate.", "jdk.virtualthreadscheduler.", "spark.");
    /**
     * Namespaces of the JDK, logging, native loading, scripting and networking. A property in one of
     * them is only allowed when it is listed above; any other name (a plugin's own switch such as
     * {@code -Dmyplugin.debug=true}) is allowed, because the JVM and the server ignore it.
     */
    private static final List<String> RESERVED_PROPERTY_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "oracle.", "com.ibm.", "os.", "user.", "file.",
            "path.", "line.", "native.", "jna.", "jnr.", "org.lwjgl.", "log4j", "org.apache.logging.", "logback.",
            "org.slf4j.", "graal.", "polyglot.", "truffle.", "nashorn.", "http.", "https.", "ftp.", "socks",
            "networkaddress.", "jndi.", "javafx.", "com.mojang.");

    private static final Set<String> SIMPLE = Set.of("-Xrs", "-Xnoclassgc", "-Xint", "-Xmixed", "-Xbatch",
            "-Xshare:auto", "-Xshare:on", "-Xshare:off", "-server", "--enable-preview", "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UnlockDiagnosticVMOptions");

    private JvmFlagPolicy() {
    }

    /**
     * Classifies one entry of {@code jvm-flags}.
     *
     * @param flag the flag as written in the file
     * @return its class
     */
    public static Verdict check(String flag) {
        if (flag == null || flag.isEmpty()) {
            return Verdict.NOT_LISTED;
        }
        if (isForbidden(flag)) {
            return Verdict.FORBIDDEN;
        }
        if (SIMPLE.contains(flag) || XX_BOOLEAN.matcher(flag).matches() || SIZE.matcher(flag).matches()
                || MODULE_OPTION.matcher(flag).matches() || ASSERTIONS.matcher(flag).matches()) {
            return Verdict.ALLOWED;
        }
        var value = XX_VALUE.matcher(flag);
        if (value.matches()) {
            return VALUE_FLAGS.contains(value.group(1)) ? Verdict.ALLOWED : Verdict.NOT_LISTED;
        }
        var property = PROPERTY.matcher(flag);
        if (property.matches()) {
            String name = property.group(1).toLowerCase(Locale.ROOT);
            if (PROPERTIES.contains(name) || PROPERTY_PREFIXES.stream().anyMatch(name::startsWith)) {
                return Verdict.ALLOWED;
            }
            return RESERVED_PROPERTY_PREFIXES.stream().anyMatch(name::startsWith) ? Verdict.NOT_LISTED : Verdict.ALLOWED;
        }
        if (flag.equals("-Xlog") || flag.startsWith("-Xlog:")) {
            return consoleLog(flag) ? Verdict.ALLOWED : Verdict.NOT_LISTED;
        }
        return Verdict.NOT_LISTED;
    }

    /**
     * The flags a change adds and the allowlist does not allow. A flag counts as added when the
     * group is new or the flag was not in the group's {@code jvm-flags} before (a reordered list
     * adds nothing). Flags a file already had stay accepted, see {@link #warnings}.
     *
     * @param before the snapshot before the change
     * @param after  the snapshot after the change
     * @return one error per refused flag, empty if the change is fine
     */
    public static List<ConfigError> refusedChanges(ConfigSnapshot before, ConfigSnapshot after) {
        List<ConfigError> errors = new ArrayList<>();
        after.groups().forEach((name, group) -> {
            GroupConfig old = before.groups().get(name);
            Map<String, Integer> previous = new HashMap<>();
            if (old != null) {
                old.processOrDefaults().jvmFlags().forEach(f -> previous.merge(f, 1, Integer::sum));
            }
            List<String> flags = group.processOrDefaults().jvmFlags();
            for (int i = 0; i < flags.size(); i++) {
                String flag = flags.get(i);
                Integer left = previous.get(flag);
                if (left != null && left > 0) {
                    previous.put(flag, left - 1);
                    continue;
                }
                Verdict verdict = check(flag);
                if (verdict != Verdict.ALLOWED) {
                    errors.add(error(name, i, verdict));
                }
            }
        });
        return errors;
    }

    /**
     * Every flag in {@code snapshot} that the allowlist does not allow. The cloud logs these on
     * start and reload; they are not errors, so files from before the allowlist keep working.
     *
     * @param snapshot the configuration
     * @return one warning per flag
     */
    public static List<ConfigError> warnings(ConfigSnapshot snapshot) {
        List<ConfigError> warnings = new ArrayList<>();
        snapshot.groups().forEach((name, group) -> {
            List<String> flags = group.processOrDefaults().jvmFlags();
            for (int i = 0; i < flags.size(); i++) {
                Verdict verdict = check(flags.get(i));
                if (verdict != Verdict.ALLOWED) {
                    warnings.add(error(name, i, verdict));
                }
            }
        });
        return warnings;
    }

    private static ConfigError error(String group, int index, Verdict verdict) {
        return ConfigError.of(CloudHome.groupFileName(group), "process.jvm-flags[" + index + "]",
                verdict == Verdict.FORBIDDEN ? ErrorCode.JVM_FLAG_FORBIDDEN : ErrorCode.JVM_FLAG_NOT_LISTED);
    }

    private static boolean isForbidden(String flag) {
        if (flag.startsWith("@")) {
            return true; // argument file: would read more options from a file
        }
        String lower = flag.toLowerCase(Locale.ROOT);
        if (lower.startsWith("-javaagent") || lower.startsWith("-agentpath") || lower.startsWith("-agentlib")
                || lower.startsWith("-xrun") || lower.startsWith("-xdebug") || lower.startsWith("-xbootclasspath")
                || lower.startsWith("--patch-module") || lower.startsWith("--module-path")
                || lower.startsWith("--upgrade-module-path") || lower.equals("-p")
                || lower.startsWith("--system") || lower.startsWith("-xplugin") || lower.startsWith("--limit-modules")) {
            return true;
        }
        if (flag.startsWith("-XX:")) {
            String body = flag.substring(4);
            if (body.startsWith("+")) {
                return FORBIDDEN_BOOLEANS.contains(body.substring(1));
            }
            if (body.startsWith("-")) {
                return false; // switching a feature off is never a way in
            }
            int eq = body.indexOf('=');
            String name = eq < 0 ? body : body.substring(0, eq);
            return FORBIDDEN_VALUE_FLAGS.contains(name);
        }
        if (flag.startsWith("-D")) {
            int eq = flag.indexOf('=');
            String name = (eq < 0 ? flag.substring(2) : flag.substring(2, eq)).toLowerCase(Locale.ROOT);
            return FORBIDDEN_PROPERTIES.contains(name) || FORBIDDEN_PROPERTY_PREFIXES.stream().anyMatch(name::startsWith);
        }
        return false;
    }

    /** {@code -Xlog} that writes to stdout or stderr only, never to a file. */
    private static boolean consoleLog(String flag) {
        if (flag.contains("file=")) {
            return false;
        }
        String[] parts = flag.split(":", -1);
        if (parts.length < 3) {
            return true; // -Xlog or -Xlog:<selectors>: the default output is stdout
        }
        String output = parts[2];
        return output.isEmpty() || output.equals("stdout") || output.equals("stderr");
    }
}
