package net.vectora.cloud.backup;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One static service whose folder can be backed up and restored. cloud-node lists them through
 * {@link BackupTargets}; dynamic services never appear, because their folders are thrown away
 * after every stop.
 *
 * @param serviceId the service id ({@code lobby-1})
 * @param group the group name
 * @param minecraftVersion the group's Minecraft version, for the manifest; empty if the group has none
 * @param directory the absolute service folder ({@code <home>/Lobby-Server/runtime})
 * @param worldSaving whether the server saves worlds and understands {@code save-off},
 *     {@code save-all flush} and {@code save-on} (Paper, Fabric, PandaSpigot); false for proxies
 */
public record BackupTarget(String serviceId, String group, String minecraftVersion, Path directory,
                           boolean worldSaving) {

    /** Pattern of a service id, the same as {@code ServiceId} of the protocol. */
    public static final String SERVICE_ID = "[a-z0-9][a-z0-9_-]{0,63}";
    private static final Pattern SERVICE_ID_PATTERN = Pattern.compile(SERVICE_ID);
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z._+-]{0,64}");

    /** Checks the values. */
    public BackupTarget {
        Objects.requireNonNull(serviceId, "serviceId");
        if (!isServiceId(serviceId)) {
            throw new IllegalArgumentException("service id must match " + SERVICE_ID);
        }
        Objects.requireNonNull(group, "group");
        minecraftVersion = minecraftVersion == null ? "" : minecraftVersion;
        if (!VERSION.matcher(minecraftVersion).matches()) {
            throw new IllegalArgumentException("invalid minecraft version");
        }
        Objects.requireNonNull(directory, "directory");
        if (!directory.isAbsolute()) {
            throw new IllegalArgumentException("directory must be absolute");
        }
        directory = directory.normalize();
    }

    /** Whether {@code value} is a valid service id. */
    public static boolean isServiceId(String value) {
        return value != null && SERVICE_ID_PATTERN.matcher(value).matches();
    }
}
