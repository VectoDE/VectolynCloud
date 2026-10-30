package net.vectora.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import net.vectora.cloud.cluster.ClusterMessages.JoinReply;
import net.vectora.cloud.cluster.ClusterMessages.JoinRequest;
import net.vectora.cloud.cluster.ClusterMessages.NodeHello;
import net.vectora.cloud.cluster.ClusterMessages.NodeWelcome;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.ProtocolException;

/**
 * Fuzz targets for what the cluster port reads from the network before anyone is authenticated:
 * the four payloads and the frame reader. Stored inputs live in
 * {@code ClusterMessagesFuzzTestInputs/<method>/}; the nightly workflow fuzzes them on Linux.
 */
class ClusterMessagesFuzzTest {

    /** Any bytes as any payload: a value that decodes writes back to the same bytes, nothing else escapes. */
    @FuzzTest
    void payloadsDecodeOrThrowProtocolException(byte @NotNull [] input) {
        try {
            assertArrayEquals(input, JoinRequest.read(new PacketReader(input)).write());
        } catch (ProtocolException e) {
            // expected for most inputs
        }
        try {
            assertArrayEquals(input, JoinReply.read(new PacketReader(input)).write());
        } catch (ProtocolException e) {
            // expected
        }
        try {
            assertArrayEquals(input, NodeHello.read(new PacketReader(input)).write());
        } catch (ProtocolException e) {
            // expected
        }
        try {
            assertArrayEquals(input, NodeWelcome.read(new PacketReader(input)).write());
        } catch (ProtocolException e) {
            // expected
        }
    }

    /** Any bytes from the socket: the frame reader ends with an envelope, EOF or ProtocolException. */
    @FuzzTest
    void frameReaderThrowsOnlyEofOrProtocolException(byte @NotNull [] input) throws IOException {
        FrameStream stream = new FrameStream(new ByteArrayInputStream(input), new ByteArrayOutputStream());
        try {
            for (int i = 0; i < 16; i++) {
                stream.read(4096);
            }
        } catch (EOFException | ProtocolException e) {
            // expected
        }
    }
}
