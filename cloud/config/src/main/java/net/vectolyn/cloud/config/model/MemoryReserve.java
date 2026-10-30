package net.vectolyn.cloud.config.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Memory of a node that its services must leave free, under {@code memory-reserve} of a node in
 * {@code nodes.yml}. Every field is optional; a missing one means its default. The heap of the node
 * JVM itself is not configured here, the node measures it.
 *
 * @param system       for the operating system, default {@link #DEFAULT_SYSTEM}
 * @param nodeOverhead for the node JVM on top of its heap (metaspace, threads, buffers), default
 *                     {@link #DEFAULT_NODE_OVERHEAD}
 * @param database     for the database server the cloud runs itself, default
 *                     {@link #DEFAULT_DATABASE}; counts only while {@link #countsDatabase} holds
 */
@JsonPropertyOrder({"system", "node-overhead", "database"})
public record MemoryReserve(MemorySize system, MemorySize nodeOverhead, MemorySize database) {

    /** {@code system} when the field is missing. */
    public static final MemorySize DEFAULT_SYSTEM = MemorySize.ofMegabytes(1024);
    /** {@code node-overhead} when the field is missing. */
    public static final MemorySize DEFAULT_NODE_OVERHEAD = MemorySize.ofMegabytes(256);
    /** {@code database} when the field is missing. */
    public static final MemorySize DEFAULT_DATABASE = MemorySize.ofMegabytes(512);
    /** Largest value of one field in MiB (64G). */
    public static final long MAX_MEGABYTES = 64L * 1024L;

    /** Every field at its default. */
    public static MemoryReserve defaults() {
        return new MemoryReserve(DEFAULT_SYSTEM, DEFAULT_NODE_OVERHEAD, DEFAULT_DATABASE);
    }

    /** A copy where every missing field has its default. */
    public MemoryReserve withDefaults() {
        return new MemoryReserve(system == null ? DEFAULT_SYSTEM : system,
                nodeOverhead == null ? DEFAULT_NODE_OVERHEAD : nodeOverhead,
                database == null ? DEFAULT_DATABASE : database);
    }

    /**
     * The fields of this reserve where it has one, the fields of {@code base} elsewhere.
     *
     * @param base the values to fall back to, may be null
     * @return the merged reserve
     */
    public MemoryReserve over(MemoryReserve base) {
        if (base == null) {
            return this;
        }
        return new MemoryReserve(system != null ? system : base.system(),
                nodeOverhead != null ? nodeOverhead : base.nodeOverhead(),
                database != null ? database : base.database());
    }

    /**
     * Whether no field is set, so the section says nothing and need not be written. Not named
     * {@code isEmpty}, which Jackson would write as a field.
     */
    public boolean noneSet() {
        return system == null && nodeOverhead == null && database == null;
    }

    /** A copy with another {@code system}; null means the default. */
    public MemoryReserve withSystem(MemorySize value) {
        return new MemoryReserve(value, nodeOverhead, database);
    }

    /** A copy with another {@code node-overhead}; null means the default. */
    public MemoryReserve withNodeOverhead(MemorySize value) {
        return new MemoryReserve(system, value, database);
    }

    /** A copy with another {@code database}; null means the default. */
    public MemoryReserve withDatabase(MemorySize value) {
        return new MemoryReserve(system, nodeOverhead, value);
    }

    /**
     * The whole reserve in MiB, missing fields at their default.
     *
     * @param withDatabase whether the database part counts, see {@link #countsDatabase}
     * @return {@code system + node-overhead (+ database)}
     */
    public long megabytes(boolean withDatabase) {
        MemoryReserve full = withDefaults();
        return full.system().megabytes() + full.nodeOverhead().megabytes()
                + (withDatabase ? full.database().megabytes() : 0);
    }

    /**
     * Whether the {@code database} part counts: the database server is switched on
     * ({@code servers.database}) and the cloud runs it itself ({@code database.mode} managed or
     * docker).
     *
     * @param cloud {@code cloud.yml}
     * @return whether to reserve memory for the database
     */
    public static boolean countsDatabase(CloudConfig cloud) {
        DatabaseConfig db = cloud.database();
        return cloud.servers() != null && cloud.servers().isWanted(ServerKind.DATABASE) && db != null
                && (db.mode() == DatabaseMode.MANAGED || db.mode() == DatabaseMode.DOCKER);
    }
}
