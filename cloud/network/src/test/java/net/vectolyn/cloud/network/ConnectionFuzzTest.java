package net.vectolyn.cloud.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.util.List;
import net.vectolyn.protocol.AuthToken;
import net.vectolyn.protocol.ServiceId;
import net.vectolyn.protocol.message.AuthResult;
import net.vectolyn.protocol.message.Disconnect;
import net.vectolyn.protocol.message.Envelope;
import net.vectolyn.protocol.message.Message;

/**
 * Fuzz target for the server side of a connection: arbitrary bytes from the network go through
 * the real pipeline (decoder, state machine) on an in-memory channel. Stored inputs live in
 * {@code ConnectionFuzzTestInputs/<method>/}; the nightly workflow fuzzes them on Linux.
 */
class ConnectionFuzzTest {

    /** Token of the service {@code fuzz-1}; the seed inputs contain a Hello with it. */
    static final AuthToken TOKEN = new AuthToken("fuzz-token-not-a-real-secret-0001");
    static final ServiceId SERVICE = new ServiceId("fuzz-1");

    /**
     * Whatever arrives: nothing escapes the pipeline, everything the server writes back is a
     * valid frame, and before a correct Hello the server says nothing but AuthResult or
     * Disconnect.
     */
    @FuzzTest
    void serverAnswersAnyInputWithValidFrames(byte @NotNull [] input) {
        ServiceTokens tokens = new ServiceTokens();
        tokens.register(SERVICE, TOKEN);
        RecordingConnected connected = new RecordingConnected();
        CloudNetworkServer server = CloudNetworkServer.builder(NetworkSettings.defaults(), tokens)
                .listener(connected).build();
        try (EmbeddedConnection connection = new EmbeddedConnection(server)) {
            // Split the input in two reads, so partial frames across reads are covered as well.
            int half = input.length / 2;
            connection.receive(java.util.Arrays.copyOfRange(input, 0, half));
            connection.receive(java.util.Arrays.copyOfRange(input, half, input.length));
            connection.runPendingTasks();

            List<Envelope> sent = connection.sent(); // throws if the server wrote an invalid frame
            if (!connected.happened) {
                for (Envelope envelope : sent) {
                    Message message = envelope.message();
                    assertTrue(message instanceof Disconnect || message instanceof AuthResult result
                            && result.outcome() != AuthResult.Outcome.ACCEPTED, message.toString());
                }
            } else {
                assertEquals(new AuthResult(AuthResult.Outcome.ACCEPTED, net.vectolyn.protocol.Protocol.PROTOCOL_VERSION),
                        sent.get(0).message());
            }
        }
    }

    private static final class RecordingConnected implements ChannelListener {

        private boolean happened;

        @Override
        public void onEvent(ServiceEvent event) {
            if (event instanceof ServiceEvent.Connected) {
                happened = true;
            }
        }
    }
}
