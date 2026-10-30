package net.vectolyn.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedTrustManager;
import net.vectolyn.cloud.cluster.ClusterMessages.HelloOutcome;
import net.vectolyn.cloud.cluster.ClusterMessages.NodeHello;
import net.vectolyn.cloud.cluster.ClusterMessages.NodeWelcome;
import net.vectolyn.protocol.NodeId;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.message.Disconnect;
import net.vectolyn.protocol.message.Envelope;
import net.vectolyn.protocol.message.Ping;
import net.vectolyn.protocol.message.Request;
import net.vectolyn.protocol.message.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cluster port against hand-made peers: what the TLS layer and the first frame allow.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ClusterPortTest {

    @TempDir
    Path dir;

    private TestNode head;
    private TestNode member;
    private int port;
    private X509Certificate ca;

    @BeforeEach
    void setUp() throws Exception {
        head = new TestNode(dir.resolve("head"), "node-1", "1.0.0");
        member = new TestNode(dir.resolve("member"), "node-2", "1.0.0");
        port = TestNode.freePort();
        head.cluster.init("127.0.0.1", port, null);
        String token = head.cluster.createToken(new NodeId("node-2"), Duration.ofMinutes(5)).token().reveal();
        member.cluster.join("127.0.0.1", port, new NodeId("node-1"), token,
                head.cluster.status().pin().orElseThrow().toString());
        // Only the hand-made peers below talk to the head.
        member.cluster.close();
        ca = memberIdentity().caCertificate().orElseThrow();
    }

    @AfterEach
    void tearDown() {
        member.close();
        head.close();
    }

    private NodeIdentity memberIdentity() throws IOException {
        return NodeIdentity.open(member.paths.nodeKeystore(), ClusterPaths.readPassword(member.paths.nodePassword()));
    }

    /** Trusts the cluster CA, no further checks: the peer the tests play is not the head's concern. */
    private X509ExtendedTrustManager trustCa() {
        return ClusterTrust.peers(ca, (node, fingerprint) -> { });
    }

    private SSLSocket connect(SSLContext context, String... protocols) throws IOException {
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setProtocols(protocols.length == 0 ? new String[] {ClusterTls.PROTOCOL} : protocols);
        socket.setSSLParameters(parameters);
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket.setSoTimeout(15_000);
        return socket;
    }

    @Test
    void tls12IsRefused() throws Exception {
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(memberIdentity().keyManagers().getKeyManagers(), new javax.net.ssl.TrustManager[] {trustCa()}, null);
        try (SSLSocket socket = connect(context, "TLSv1.2")) {
            assertThrows(SSLException.class, socket::startHandshake);
        }
    }

    @Test
    void aMemberSessionRunsOnTls13() throws Exception {
        SSLContext context = ClusterTls.context(memberIdentity().keyManagers().getKeyManagers(), trustCa());
        try (SSLSocket socket = connect(context)) {
            socket.startHandshake();
            assertEquals("TLSv1.3", socket.getSession().getProtocol());
            FrameStream stream = new FrameStream(socket.getInputStream(), socket.getOutputStream());
            stream.write(new Envelope(1, new Request(ClusterMessages.HELLO, new NodeHello(new NodeId("node-2"), "1.0.0").write())));
            assertEquals(HelloOutcome.ACCEPTED, welcome(stream).outcome());
            stream.write(Envelope.of(new Ping(7)));
            Envelope next = stream.read(1 << 20);
            assertTrue(next.message() instanceof net.vectolyn.protocol.message.Pong
                    || next.message() instanceof Ping, next.toString());
        }
    }

    private static NodeWelcome welcome(FrameStream stream) throws IOException {
        Envelope answer = stream.read(4096);
        Response response = assertInstanceOf(Response.class, answer.message());
        return NodeWelcome.read(new PacketReader(response.payload()));
    }

    @Test
    void aHelloWithAnotherNodeIdThanTheCertificateIsRefused() throws Exception {
        SSLContext context = ClusterTls.context(memberIdentity().keyManagers().getKeyManagers(), trustCa());
        try (SSLSocket socket = connect(context)) {
            FrameStream stream = new FrameStream(socket.getInputStream(), socket.getOutputStream());
            stream.write(new Envelope(1, new Request(ClusterMessages.HELLO, new NodeHello(new NodeId("node-1"), "1.0.0").write())));
            assertEquals(HelloOutcome.NODE_MISMATCH, welcome(stream).outcome());
            assertClosed(socket.getInputStream());
        }
    }

    @Test
    void aHelloWithoutACertificateIsRefused() throws Exception {
        SSLContext context = ClusterTls.context(null, trustCa());
        try (SSLSocket socket = connect(context)) {
            FrameStream stream = new FrameStream(socket.getInputStream(), socket.getOutputStream());
            stream.write(new Envelope(1, new Request(ClusterMessages.HELLO, new NodeHello(new NodeId("node-2"), "1.0.0").write())));
            Envelope answer = stream.read(4096);
            assertEquals(new Disconnect(Disconnect.Reason.AUTH_FAILED), answer.message());
        }
    }

    @Test
    void aJoinRequestWithACertificateIsAProtocolError() throws Exception {
        SSLContext context = ClusterTls.context(memberIdentity().keyManagers().getKeyManagers(), trustCa());
        try (SSLSocket socket = connect(context)) {
            FrameStream stream = new FrameStream(socket.getInputStream(), socket.getOutputStream());
            stream.write(new Envelope(1, new Request(ClusterMessages.JOIN, new byte[] {1})));
            assertEquals(new Disconnect(Disconnect.Reason.PROTOCOL_ERROR), stream.read(4096).message());
        }
    }

    @Test
    void aCertificateOfAnotherCaIsRefusedInTheHandshake() throws Exception {
        try (TestNode other = new TestNode(dir.resolve("other"), "node-2", "1.0.0")) {
            other.cluster.init("127.0.0.1", TestNode.freePort(), null);
            NodeIdentity foreign = NodeIdentity.open(other.paths.nodeKeystore(),
                    ClusterPaths.readPassword(other.paths.nodePassword()));
            SSLContext context = ClusterTls.context(foreign.keyManagers().getKeyManagers(), trustCa());
            try (SSLSocket socket = connect(context)) {
                // TLS 1.3: the server checks the client certificate after the client finished, so
                // the refusal shows on the first read at the latest.
                assertThrows(IOException.class, () -> {
                    socket.startHandshake();
                    FrameStream stream = new FrameStream(socket.getInputStream(), socket.getOutputStream());
                    stream.write(new Envelope(1, new Request(ClusterMessages.HELLO,
                            new NodeHello(new NodeId("node-2"), "1.0.0").write())));
                    stream.read(4096);
                });
            }
        }
    }

    @Test
    void theMemberSideRefusesAPeerThatIsNotItsHead() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        X509ExtendedTrustManager trust = ClusterTrust.peers(ca, (node, fingerprint) -> {
            seen.set(node.value());
            if (!node.equals(new NodeId("node-1"))) {
                throw new CertificateException("not the head");
            }
        });
        X509Certificate[] memberChain = {memberIdentity().certificate(), ca};
        assertThrows(CertificateException.class, () -> trust.checkServerTrusted(memberChain, "ECDHE_ECDSA"));
        assertEquals("node-2", seen.get());
    }

    @Test
    void aJoiningNodeRefusesAMemberThatPlaysTheHead() throws Exception {
        CaPin pin = CaPin.of(ca);
        X509Certificate[] memberChain = {memberIdentity().certificate(), ca};
        AtomicReference<X509Certificate> found = new AtomicReference<>();
        X509ExtendedTrustManager joining = ClusterTrust.pinned(pin, new NodeId("node-1"), found);
        CertificateException e = assertThrows(CertificateException.class,
                () -> joining.checkServerTrusted(memberChain, "ECDHE_ECDSA"));
        assertTrue(e.getMessage().contains("node-2"), e.getMessage());
        assertEquals(null, found.get(), "nothing is trusted");
        NodeIdentity headIdentity = NodeIdentity.open(head.paths.nodeKeystore(),
                ClusterPaths.readPassword(head.paths.nodePassword()));
        joining.checkServerTrusted(new X509Certificate[] {headIdentity.certificate(), ca}, "ECDHE_ECDSA");
        assertEquals(ca, found.get());
    }

    private FrameStream session(SSLSocket socket) throws IOException {
        socket.startHandshake();
        FrameStream stream = new FrameStream(socket.getInputStream(), socket.getOutputStream());
        stream.write(new Envelope(1, new Request(ClusterMessages.HELLO, new NodeHello(new NodeId("node-2"), "1.0.0").write())));
        assertEquals(HelloOutcome.ACCEPTED, welcome(stream).outcome());
        return stream;
    }

    @Test
    void aMemberThatFloodsPingsLosesItsSession() throws Exception {
        SSLContext context = ClusterTls.context(memberIdentity().keyManagers().getKeyManagers(), trustCa());
        try (SSLSocket socket = connect(context)) {
            FrameStream stream = session(socket);
            for (int i = 0; i <= ClusterServer.PINGS_PER_INTERVAL; i++) {
                stream.write(Envelope.of(new Ping(i)));
            }
            Disconnect.Reason reason = null;
            try {
                while (reason == null) {
                    if (stream.read(1 << 20).message() instanceof Disconnect d) {
                        reason = d.reason();
                    }
                }
            } catch (IOException e) {
                // closed right after the reason
            }
            assertEquals(Disconnect.Reason.PROTOCOL_ERROR, reason);
        }
    }

    @Test
    void removingAMemberThatDoesNotReadDoesNotWait() throws Exception {
        SSLContext context = ClusterTls.context(memberIdentity().keyManagers().getKeyManagers(), trustCa());
        try (SSLSocket socket = connect(context)) {
            session(socket);
            // From here the peer reads nothing.
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> head.cluster.remove(new NodeId("node-2")));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!head.disconnected.contains(new NodeId("node-2"))) {
                assertTrue(System.nanoTime() < end, "the session did not end");
                Thread.sleep(50);
            }
        }
    }

    @Test
    void aSilentConnectionIsClosedAfterTheHandshakeTimeout() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(20_000);
            long start = System.nanoTime();
            assertClosed(socket.getInputStream());
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
            assertTrue(seconds >= 8 && seconds <= 15, "closed after " + seconds + " s");
        }
    }

    @Test
    void garbageEndsTheConnection() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            socket.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            assertClosed(socket.getInputStream());
        }
    }

    private static void assertClosed(InputStream in) throws IOException {
        try {
            while (in.read() >= 0) {
                // drain until the server closes
            }
        } catch (EOFException | SSLException | java.net.SocketException e) {
            // closed
        }
    }
}
