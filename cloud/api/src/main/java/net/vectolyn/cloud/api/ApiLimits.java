package net.vectolyn.cloud.api;

/**
 * Limits of the API server. {@link #defaults()} fits one operator with a dashboard and a few
 * scripts; the node may pass other values.
 *
 * @param maxBodyBytes              largest request body; more is 413
 * @param requestsPerMinutePerIp    refill of the bucket per client address (IPv6 per /64)
 * @param burstPerIp                size of that bucket
 * @param requestsPerMinutePerToken refill of the bucket per token
 * @param burstPerToken             size of that bucket
 * @param authFailuresPerMinute     wrong tokens per address and minute before the address gets 429
 * @param maxConcurrentRequests     requests handled at once; more is 503
 * @param maxConnections            open connections in total
 * @param maxConnectionsPerIp       open connections per address
 * @param maxWebSockets             open event streams in total
 * @param idleTimeoutSeconds        a connection without traffic is closed after this time
 */
public record ApiLimits(int maxBodyBytes, int requestsPerMinutePerIp, int burstPerIp, int requestsPerMinutePerToken,
                        int burstPerToken, int authFailuresPerMinute, int maxConcurrentRequests, int maxConnections,
                        int maxConnectionsPerIp, int maxWebSockets, int idleTimeoutSeconds) {

    /** Checks the values. */
    public ApiLimits {
        if (maxBodyBytes < 1 || requestsPerMinutePerIp < 1 || burstPerIp < 1 || requestsPerMinutePerToken < 1
                || burstPerToken < 1 || authFailuresPerMinute < 1 || maxConcurrentRequests < 1 || maxConnections < 1
                || maxConnectionsPerIp < 1 || maxWebSockets < 0 || idleTimeoutSeconds < 1) {
            throw new IllegalArgumentException("every limit must be positive");
        }
    }

    /**
     * 256 KiB bodies; 600 requests a minute per address (burst 120); 300 per token (burst 60);
     * 10 wrong tokens a minute per address; 64 requests at once; 256 connections, 32 per address;
     * 32 event streams; 120 s idle timeout.
     *
     * @return the defaults
     */
    public static ApiLimits defaults() {
        return new ApiLimits(256 * 1024, 600, 120, 300, 60, 10, 64, 256, 32, 32, 120);
    }
}
