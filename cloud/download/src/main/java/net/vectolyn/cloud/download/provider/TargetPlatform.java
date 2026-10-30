package net.vectolyn.cloud.download.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Operating system, CPU architecture and C library a native artifact must fit. Only sources
 * with native downloads look at it (MariaDB, Adoptium).
 *
 * @param os the operating system
 * @param arch the CPU architecture
 * @param libc the C library on Linux, {@link Libc#NONE} elsewhere
 */
public record TargetPlatform(Os os, Arch arch, Libc libc) {

    /** Operating systems. */
    public enum Os {
        /** Windows. */
        WINDOWS,
        /** Linux. */
        LINUX,
        /** macOS. */
        MAC,
        /** Anything else. */
        OTHER
    }

    /** CPU architectures. */
    public enum Arch {
        /** x86-64, also called amd64. */
        X64,
        /** 64-bit ARM. */
        AARCH64,
        /** Anything else. */
        OTHER
    }

    /** C libraries on Linux. */
    public enum Libc {
        /** GNU C library. */
        GLIBC,
        /** musl, for example Alpine. */
        MUSL,
        /** Not Linux. */
        NONE
    }

    /**
     * Checks the values.
     *
     * @param os the operating system
     * @param arch the CPU architecture
     * @param libc the C library
     */
    public TargetPlatform {
        Objects.requireNonNull(os, "os");
        Objects.requireNonNull(arch, "arch");
        Objects.requireNonNull(libc, "libc");
    }

    /**
     * Detects the platform this JVM runs on. On Linux, musl is assumed when a musl dynamic
     * loader exists under {@code /lib}, glibc otherwise.
     *
     * @return the current platform
     */
    public static TargetPlatform current() {
        Os os = detectOs(System.getProperty("os.name", ""));
        Arch arch = detectArch(System.getProperty("os.arch", ""));
        Libc libc = Libc.NONE;
        if (os == Os.LINUX) {
            libc = hasMuslLoader() ? Libc.MUSL : Libc.GLIBC;
        }
        return new TargetPlatform(os, arch, libc);
    }

    static Os detectOs(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("windows")) {
            return Os.WINDOWS;
        }
        if (n.startsWith("linux")) {
            return Os.LINUX;
        }
        if (n.startsWith("mac") || n.startsWith("darwin")) {
            return Os.MAC;
        }
        return Os.OTHER;
    }

    /**
     * Maps an {@code os.arch} value (also the {@code OS_ARCH} of a Java {@code release} file) to an
     * architecture.
     *
     * @param name for example {@code amd64}, {@code x86_64} or {@code aarch64}
     * @return the architecture, {@link Arch#OTHER} if unknown
     */
    public static Arch detectArch(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> Arch.X64;
            case "aarch64", "arm64" -> Arch.AARCH64;
            default -> Arch.OTHER;
        };
    }

    private static boolean hasMuslLoader() {
        try (var files = Files.list(Path.of("/lib"))) {
            return files.anyMatch(p -> p.getFileName().toString().startsWith("ld-musl-"));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String toString() {
        String base = os.name().toLowerCase(Locale.ROOT) + "/" + arch.name().toLowerCase(Locale.ROOT);
        return libc == Libc.NONE ? base : base + "/" + libc.name().toLowerCase(Locale.ROOT);
    }
}
