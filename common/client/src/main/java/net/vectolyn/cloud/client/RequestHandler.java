package net.vectolyn.cloud.client;

import java.util.concurrent.CompletionStage;
import net.vectolyn.protocol.message.Request;
import net.vectolyn.protocol.message.Response;

/**
 * Answers requests the cloud sends to this service on one channel. Runs on the client's event
 * thread; return a stage that completes later for anything that needs the server thread. A
 * handler that throws or fails produces {@link Response.Status#ERROR}.
 */
@FunctionalInterface
public interface RequestHandler {

    /** Handles {@code request}. */
    CompletionStage<Response> handle(Request request);
}
