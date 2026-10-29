package net.vectora.cloud.network;

import java.util.concurrent.CompletionStage;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.Request;
import net.vectora.protocol.message.Response;

/**
 * Answers requests that services send to the cloud on one channel. Called on a network thread;
 * return a stage that completes later for anything that takes time. A handler that throws or
 * completes exceptionally produces {@link Response.Status#ERROR} with an empty payload.
 */
@FunctionalInterface
public interface RequestHandler {

    /** Handles {@code request} from {@code service}. */
    CompletionStage<Response> handle(ServiceId service, Request request);
}
