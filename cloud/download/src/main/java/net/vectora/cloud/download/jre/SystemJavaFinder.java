package net.vectora.cloud.download.jre;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import net.vectora.cloud.download.provider.TargetPlatform;

/**
 * Finds Java runtimes that are already installed: {@code JAVA_HOME} first, then every {@code java}
 * on the {@code PATH}.
 *
 * <p>For each candidate the {@code release} file in its Java home is read. Only if there is none
 * (for example a launcher stub such as Oracle's {@code javapath}) is the executable started with
 * {@code -XshowSettings:properties -version} ({@link JavaProbe}). A runtime counts only if its CPU
 * architecture fits the platform. Relative {@code PATH} entries are skipped, so a {@code java} in
 * the working directory is never started.
 */
public final class SystemJavaFinder {

    private static final System.Logger LOG = System.getLogger(SystemJavaFinder.class.getName());

    private final Map<String, String> environment;
    private final TargetPlatform platform;
    private final JavaProbe probe;

    /**
     * Creates a finder.
     *
     * @param environment the environment variables ({@code JAVA_HOME}, {@code PATH})
     * @param platform the platform the servers run on
     * @param probe how to ask an executable without a {@code release} file
     */
    public SystemJavaFinder(Map<String, String> environment, TargetPlatform platform, JavaProbe probe) {
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.platform = Objects.requireNonNull(platform, "platform");
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    /**
     * Creates a finder for this machine: the process environment, the current platform and a real
     * process probe.
     *
     * @return the finder
     */
    public static SystemJavaFinder createDefault() {
        return new SystemJavaFinder(System.getenv(), TargetPlatform.current(), JavaProbe.process());
    }

    /**
     * Returns the first installed runtime with this feature version.
     *
     * @param feature the Java feature version
     * @return the runtime, or empty
     * @throws InterruptedException if interrupted while probing
     */
    public Optional<JavaRuntime> find(int feature) throws InterruptedException {
        for (JavaRuntime runtime : runtimes()) {
            if (runtime.feature() == feature) {
                return Optional.of(runtime);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns every installed runtime that fits the platform, in search order, each Java home once.
     *
     * @return the runtimes
     * @throws InterruptedException if interrupted while probing
     */
    public List<JavaRuntime> runtimes() throws InterruptedException {
        Map<Path, JavaRuntime> byHome = new LinkedHashMap<>();
        for (Path executable : candidates()) {
            Optional<JavaRuntime> runtime = inspect(executable);
            if (runtime.isPresent()) {
                byHome.putIfAbsent(runtime.get().home(), runtime.get());
            }
        }
        return List.copyOf(byHome.values());
    }

    /** The executables to look at: JAVA_HOME/bin/java, then java in every absolute PATH entry. */
    List<Path> candidates() {
        String name = JavaRuntime.executableName(windows());
        List<Path> candidates = new ArrayList<>();
        String javaHome = variable("JAVA_HOME");
        if (javaHome != null) {
            path(javaHome).map(home -> home.resolve("bin").resolve(name)).ifPresent(candidates::add);
        }
        String pathVariable = variable("PATH");
        if (pathVariable != null) {
            // The environment is this machine's, so its separator counts (";" on Windows, ":" elsewhere).
            for (String entry : pathVariable.split(Pattern.quote(File.pathSeparator), -1)) {
                String trimmed = entry.strip();
                if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                    trimmed = trimmed.substring(1, trimmed.length() - 1);
                }
                path(trimmed).map(dir -> dir.resolve(name)).ifPresent(candidates::add);
            }
        }
        return candidates;
    }

    private Optional<JavaRuntime> inspect(Path executable) throws InterruptedException {
        if (!Files.isRegularFile(executable)) {
            return Optional.empty();
        }
        try {
            Path real = executable.toRealPath();
            Path home = real.getParent() == null ? null : real.getParent().getParent();
            if (home != null) {
                Optional<JavaReleaseFile> release = JavaReleaseFile.read(home);
                if (release.isPresent()) {
                    return fromRelease(home, release.get());
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Cannot read Java at {0}: {1}", executable, e.getMessage());
            return Optional.empty();
        }
        Optional<JavaSettings> settings = probe.probe(executable);
        if (settings.isEmpty()) {
            LOG.log(System.Logger.Level.DEBUG, "Java at {0} did not answer -XshowSettings", executable);
            return Optional.empty();
        }
        return fromSettings(settings.get());
    }

    private Optional<JavaRuntime> fromRelease(Path home, JavaReleaseFile release) {
        OptionalInt feature = release.feature();
        if (feature.isEmpty() || !archFits(release.osArch())) {
            return Optional.empty();
        }
        Path executable = home.resolve("bin").resolve(JavaRuntime.executableName(windows()));
        if (!Files.isRegularFile(executable)) {
            return Optional.empty();
        }
        return Optional.of(new JavaRuntime(home, executable, feature.getAsInt(),
                release.javaVersion().orElse(Integer.toString(feature.getAsInt())), JavaRuntime.Origin.SYSTEM));
    }

    private Optional<JavaRuntime> fromSettings(JavaSettings settings) {
        OptionalInt feature = settings.feature();
        Optional<Path> home = settings.home().flatMap(SystemJavaFinder::path);
        if (feature.isEmpty() || home.isEmpty() || !archFits(settings.arch())) {
            return Optional.empty();
        }
        Path executable = home.get().resolve("bin").resolve(JavaRuntime.executableName(windows()));
        if (!Files.isRegularFile(executable)) {
            return Optional.empty();
        }
        try {
            Path real = home.get().toRealPath();
            return Optional.of(new JavaRuntime(real, real.resolve("bin").resolve(JavaRuntime.executableName(windows())),
                    feature.getAsInt(), settings.version().orElse(Integer.toString(feature.getAsInt())),
                    JavaRuntime.Origin.SYSTEM));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private boolean archFits(Optional<String> arch) {
        // Without an architecture the runtime is assumed to fit; an unknown or different one does not.
        return arch.isEmpty() || TargetPlatform.detectArch(arch.get()) == platform.arch();
    }

    private boolean windows() {
        return platform.os() == TargetPlatform.Os.WINDOWS;
    }

    private String variable(String name) {
        if (windows()) {
            // Windows treats environment names case-insensitively ("Path").
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }
        return environment.get(name);
    }

    private static Optional<Path> path(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(text);
            return path.isAbsolute() ? Optional.of(path) : Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }
}
