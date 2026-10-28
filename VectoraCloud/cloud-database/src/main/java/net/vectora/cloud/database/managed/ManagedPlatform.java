package net.vectora.cloud.database.managed;

import net.vectora.cloud.database.DatabaseException;
import net.vectora.cloud.download.provider.TargetPlatform;

/**
 * The platforms the managed mode runs on: those MariaDB publishes server archives for.
 */
public enum ManagedPlatform {

    /** Windows x64, zip archive, programs with {@code .exe}. */
    WINDOWS_X64,
    /** Linux x86_64 with glibc, tar.gz archive. */
    LINUX_X64_GLIBC;

    /**
     * Picks the managed platform for {@code system}.
     *
     * @param system the system the cloud runs on
     * @return the managed platform
     * @throws DatabaseException with reason {@code MANAGED_PLATFORM} on every other system; the
     *     message names the modes that work there
     */
    public static ManagedPlatform of(TargetPlatform system) throws DatabaseException {
        if (system.os() == TargetPlatform.Os.WINDOWS && system.arch() == TargetPlatform.Arch.X64) {
            return WINDOWS_X64;
        }
        if (system.os() == TargetPlatform.Os.LINUX && system.arch() == TargetPlatform.Arch.X64
                && system.libc() == TargetPlatform.Libc.GLIBC) {
            return LINUX_X64_GLIBC;
        }
        throw new DatabaseException(DatabaseException.Reason.MANAGED_PLATFORM, "The managed MariaDB uses the"
                + " server builds MariaDB publishes for Windows x64 and Linux x86_64 with glibc. This system is "
                + system + ", so switch to another database mode in cloud.yml: 'external' for your own"
                + " MariaDB or MySQL server, 'docker' to run MariaDB in a container (where Docker is available),"
                + " or 'h2' for embedded files per server (tests and trying things out).");
    }

    /**
     * Returns the file name of a program in {@code bin/}.
     *
     * @param name program name without extension
     * @return the name, with {@code .exe} on Windows
     */
    public String executable(String name) {
        return this == WINDOWS_X64 ? name + ".exe" : name;
    }
}
