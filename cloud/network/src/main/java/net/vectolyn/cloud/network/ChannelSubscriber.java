package net.vectolyn.cloud.network;

import net.vectolyn.protocol.ServiceId;
import net.vectolyn.protocol.message.ChannelMessage;

/** Receives channel messages inside the cloud. Called on a network thread; must not block. */
@FunctionalInterface
public interface ChannelSubscriber {

    /**
     * A message arrived.
     *
     * @param sender the service that published it
     */
    void received(ServiceId sender, ChannelMessage message);
}
