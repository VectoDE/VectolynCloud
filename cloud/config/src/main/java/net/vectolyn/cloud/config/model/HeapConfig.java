package net.vectolyn.cloud.config.model;

/**
 * JVM heap of each service of a group ({@code -Xms}/{@code -Xmx}).
 *
 * @param min initial heap ({@code VECTOLYN_XMS})
 * @param max maximum heap ({@code VECTOLYN_XMX}), at least {@code min}
 */
public record HeapConfig(MemorySize min, MemorySize max) {
}
