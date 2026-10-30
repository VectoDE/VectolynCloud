package net.vectora.cloud.cluster;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import net.vectora.cloud.cluster.ClusterMessages.JoinReply;
import net.vectora.cloud.cluster.ClusterMessages.JoinRequest;
import net.vectora.protocol.NodeId;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.Protocol;
import net.vectora.protocol.ProtocolException;
import net.vectora.protocol.message.Envelope;
import net.vectora.protocol.message.Request;
import net.vectora.protocol.message.Response;

/**
 * The joining side: one TLS 1.3 connection to the head without a client certificate, the head's
 * CA checked against the pin before the token is sent, one join request, one answer.
 */
public final class ClusterJoin {

    /** Connect and TLS. */
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    /**
     * The answer, after the request is sent: longer than the head's own limit for a join
     * ({@link ClusterServer#JOIN_TIMEOUT}), so a slow keytool on the head does not leave a member
     * there that the node gave up on.
     */
    static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(90);

    private ClusterJoin() {
    }

    /**
     * The head's answer and what its TLS certificate said.
     *
     * @param reply the answer
     * @param ca    the CA certificate, checked against the pin
     * @param head  the node id in the head's certificate
     */
    public record Result(JoinReply reply, X509Certificate ca, NodeId head) {
    }

    /**
     * Sends a join request.
     *
     * @param head    address of the head's cluster port
     * @param headId  node id of the head from the join command
     * @param pin     pin of the cluster CA from the join command
     * @param request the request
     * @return the answer
     * @throws IOException if the head cannot be reached, its CA does not match the pin or its
     *                     certificate is not the one of {@code headId} (then the message says so
     *                     and the token was not sent), or the answer is invalid
     */
    public static Result join(InetSocketAddress head, NodeId headId, CaPin pin, JoinRequest request) throws IOException {
        AtomicReference<X509Certificate> found = new AtomicReference<>();
        SSLContext context = ClusterTls.context(null, ClusterTrust.pinned(pin, headId, found));
        int timeout = (int) TIMEOUT.toMillis();
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
            // TLS 1.3 only, endpoint identification on: the pinned trust manager identifies the
            // head by the node id in its certificate (ClusterTrust.pinned).
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setProtocols(new String[] {ClusterTls.PROTOCOL});
            parameters.setEndpointIdentificationAlgorithm(ClusterTls.ENDPOINT_IDENTIFICATION);
            socket.setSSLParameters(parameters);
            socket.connect(head, timeout);
            socket.setSoTimeout(timeout);
            try {
                socket.startHandshake();
            } catch (SSLHandshakeException e) {
                throw new IOException("TLS with the head failed; the CA pin or the head's node id may not match "
                        + "this head (" + e.getMessage() + ")", e);
            }
            if (found.get() == null || !ClusterTls.PROTOCOL.equals(socket.getSession().getProtocol())) {
                throw new IOException("the head's certificate was not checked");
            }
            java.security.cert.Certificate[] chain = socket.getSession().getPeerCertificates();
            NodeId certified = chain.length > 0 && chain[0] instanceof X509Certificate leaf
                    ? NodeCertificates.nodeId(leaf).orElse(null) : null;
            if (!headId.equals(certified)) {
                throw new IOException("the peer is not the head " + headId);
            }
            FrameStream stream = new FrameStream(socket.getInputStream(), socket.getOutputStream());
            stream.write(new Envelope(1, new Request(ClusterMessages.JOIN, request.write())));
            socket.setSoTimeout((int) ANSWER_TIMEOUT.toMillis());
            Envelope answer = stream.read(Protocol.MAX_HANDSHAKE_FRAME_SIZE * 4);
            if (!(answer.message() instanceof Response response) || answer.requestId() != 1
                    || response.status() != Response.Status.OK) {
                throw new IOException("the head did not answer the join request");
            }
            return new Result(JoinReply.read(new PacketReader(response.payload())), found.get(), certified);
        } catch (ProtocolException e) {
            throw new IOException("the head's answer is invalid: " + e.getMessage(), e);
        }
    }
}
