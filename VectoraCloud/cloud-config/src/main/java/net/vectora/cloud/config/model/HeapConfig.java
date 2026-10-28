package net.vectora.cloud.config.model;

/**
 * JVM heap of each service of a group ({@code -Xms}/{@code -Xmx}).
 *
 * @param min initial heap ({@code VECTORA_XMS})
 * @param max maximum heap ({@code VECTORA_XMX}), at least {@code min}
 */
public record HeapConfig(MemorySize min, MemorySize max) {
}
