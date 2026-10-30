package net.vectolyn.cloud.client;

import net.vectolyn.protocol.message.ChannelMessage;

/** Receives channel messages other services or the cloud published. Runs on the client's event thread. */
@FunctionalInterface
public interface ChannelSubscriber {

    /** A message arrived on the subscribed channel. */
    void received(ChannelMessage message);
}
