package net.vectolyn.cloud.network;

import io.netty.channel.Channel;

/**
 * Hook that runs first for every accepted connection, before the cloud adds its own handlers.
 * It exists for transport security: the cluster (stage 2, PR 20) adds an {@code SslHandler} here,
 * in front of the handler named {@link CloudNetworkServer#FRAME_DECODER}. Without a customizer
 * ({@link CloudNetworkServer.Builder#pipelineCustomizer} not called) the channel is plain TCP.
 */
@FunctionalInterface
public interface PipelineCustomizer {

    /** Adds handlers to the pipeline of {@code channel}. */
    void customize(Channel channel);
}
