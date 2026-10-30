package net.vectolyn.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.nio.charset.StandardCharsets;
import net.vectolyn.cloud.cluster.MirrorMessages.Changed;
import net.vectolyn.cloud.cluster.MirrorMessages.ChunkRequest;
import net.vectolyn.cloud.cluster.MirrorMessages.Manifest;
import net.vectolyn.cloud.cluster.MirrorMessages.ManifestRequest;
import net.vectolyn.cloud.cluster.MirrorMessages.TemplateList;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.ProtocolException;

/**
 * Fuzz targets for what a cluster session reads from its peer: the mirror's payloads, the code of
 * an error answer and the path rules. Stored inputs live in
 * {@code MirrorMessagesFuzzTestInputs/<method>/}; the nightly workflow fuzzes them on Linux.
 */
class MirrorMessagesFuzzTest {

    /** Any bytes as any payload: a value that decodes writes back to the same bytes, nothing else escapes. */
    @FuzzTest
    void payloadsDecodeOrThrowProtocolException(byte @NotNull [] input) {
        try {
            assertArrayEquals(input, Changed.read(new PacketReader(input)).write());
        } catch (ProtocolException e) {
            // expected for most inputs
        }
        try {
            assertArrayEquals(input, ManifestRequest.read(new PacketReader(input)).write());
        } catch (ProtocolException e) {
            // expected
        }
        try {
            Manifest manifest = Manifest.read(new PacketReader(input));
            assertArrayEquals(input, manifest.write());
            manifest.entries().forEach(entry -> assertTrue(MirrorPaths.isSafePath(entry.path())));
        } catch (ProtocolException e) {
            // expected
        }
        try {
            ChunkRequest request = ChunkRequest.read(new PacketReader(input));
            assertArrayEquals(input, request.write());
            assertTrue(MirrorPaths.isSafePath(request.path()));
        } catch (ProtocolException e) {
            // expected
        }
        try {
            assertArrayEquals(input, TemplateList.read(new PacketReader(input)).write());
        } catch (ProtocolException e) {
            // expected
        }
    }

    /** Any bytes as the payload of an error answer: a valid code or "error", never anything else. */
    @FuzzTest
    void errorCodesAreValidOrUnknown(byte @NotNull [] input) {
        String code = ClusterRpcException.code(input);
        assertTrue(code.equals(ClusterRpcException.UNKNOWN) || new ClusterRpcException(code).code().equals(code));
    }

    /** Any text as a path: a path the rules accept has no way out of the folder it is resolved in. */
    @FuzzTest
    void safePathsStayInside(byte @NotNull [] input) {
        String path = new String(input, StandardCharsets.UTF_8);
        if (MirrorPaths.isSafePath(path)) {
            for (String segment : path.split("/", -1)) {
                assertTrue(!segment.isEmpty() && !segment.equals("..") && !segment.equals(".")
                        && segment.indexOf('\\') < 0 && segment.indexOf(':') < 0 && segment.indexOf('\0') < 0);
            }
            assertTrue(!path.startsWith("/"));
        }
    }
}
