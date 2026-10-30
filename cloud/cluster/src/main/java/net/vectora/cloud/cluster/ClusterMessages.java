package net.vectora.cloud.cluster;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.protocol.ChannelName;
import net.vectora.protocol.NodeId;
import net.vectora.protocol.PacketReader;
import net.vectora.protocol.PacketWriter;
import net.vectora.protocol.ProtocolException;

/**
 * Payloads of the cluster port. They travel in the frames of the cloud channel
 * ({@code common/protocol}): a {@code Request} on {@link #JOIN} or {@link #HELLO} and the
 * {@code Response} to it. Every reader throws only {@link ProtocolException} and checks every
 * length before it allocates.
 */
public final class ClusterMessages {

    /** Channel of the join request, the only request a connection without a certificate may make. */
    public static final ChannelName JOIN = new ChannelName("cluster.join");
    /** Channel of the first request of a member, with its certificate. */
    public static final ChannelName HELLO = new ChannelName("cluster.hello");

    /** Largest version string, for example {@code 0.4.0-beta.2+abc}. */
    static final int MAX_VERSION_BYTES = 64;
    /** Largest certification request or certificate (DER). */
    static final int MAX_DER_BYTES = 4096;
    /** {@code <6 letters or digits>.<32 letters or digits>}, lower case. */
    static final Pattern TOKEN = Pattern.compile("([a-z0-9]{6})\\.([a-z0-9]{32})");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z.+_-]{1,64}");

    private ClusterMessages() {
    }

    /** Checks a version string: 1 to 64 characters of {@code [0-9A-Za-z.+_-]}. */
    static String checkVersion(String value) {
        if (value == null || !VERSION.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid version");
        }
        return value;
    }

    private static NodeId readNode(PacketReader in) {
        return in.readValidated(NodeId.MAX_LENGTH, NodeId::new);
    }

    /** Why the head refused a join, or that it accepted it. */
    public enum JoinOutcome {
        /** Joined; the reply carries the certificate. */
        ACCEPTED(0),
        /** Unknown, expired, used or wrong token, or a token for another node id. One answer for all. */
        INVALID_TOKEN(1),
        /** The joining node runs another VectoraCloud version than the head. */
        VERSION_MISMATCH(2),
        /** The head runs the embedded H2 database, which serves one node only. */
        DATABASE_SINGLE_NODE(3),
        /** The database is on a loopback address the joining node cannot reach. */
        DATABASE_UNREACHABLE(4),
        /** A member with this node id exists already. */
        NODE_EXISTS(5),
        /** The certification request was not valid. */
        BAD_REQUEST(6),
        /** The head could not finish the join (keytool, files); the log of the head has the reason. */
        FAILED(7);

        private final int code;

        JoinOutcome(int code) {
            this.code = code;
        }

        static JoinOutcome read(PacketReader in) {
            int code = in.readVarInt();
            for (JoinOutcome outcome : values()) {
                if (outcome.code == code) {
                    return outcome;
                }
            }
            throw new ProtocolException("unknown join outcome " + code);
        }

        void write(PacketWriter out) {
            out.writeVarInt(code);
        }
    }

    /** What the head answers a member's hello. */
    public enum HelloOutcome {
        /** Accepted; the connection is a member session now. */
        ACCEPTED(0),
        /** The member runs another VectoraCloud version than the head. */
        VERSION_MISMATCH(1),
        /** The node is no member, or its certificate is not the one the head has for it. */
        UNKNOWN_NODE(2),
        /** The node id of the hello is not the one in the certificate. */
        NODE_MISMATCH(3),
        /** The certificate was revoked. */
        REVOKED(4),
        /** The node this connection reached is not the head. */
        NOT_HEAD(5),
        /**
         * The head's database serves one node only (H2) or listens where this member cannot reach
         * it; the member stays out until the head runs a MariaDB server on the cluster network.
         */
        DATABASE_SINGLE_NODE(6);

        private final int code;

        HelloOutcome(int code) {
            this.code = code;
        }

        static HelloOutcome read(PacketReader in) {
            int code = in.readVarInt();
            for (HelloOutcome outcome : values()) {
                if (outcome.code == code) {
                    return outcome;
                }
            }
            throw new ProtocolException("unknown hello outcome " + code);
        }

        void write(PacketWriter out) {
            out.writeVarInt(code);
        }
    }

    /**
     * A node asks to join. {@code toString()} does not show the secret.
     *
     * @param node    node id of the joining node ({@code node-id} of its {@code cloud.yml})
     * @param tokenId the public part of the join token
     * @param secret  the secret part of the join token
     * @param version VectoraCloud version of the joining node
     * @param request PKCS#10 certification request for the node's key (DER)
     */
    public record JoinRequest(NodeId node, String tokenId, Secret secret, String version, byte[] request) {

        /** Checks the values. */
        public JoinRequest {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(secret, "secret");
            if (tokenId == null || !TOKEN.matcher(tokenId + "." + secret.reveal()).matches()) {
                throw new IllegalArgumentException("invalid join token");
            }
            checkVersion(version);
            Objects.requireNonNull(request, "request");
            if (request.length == 0 || request.length > MAX_DER_BYTES) {
                throw new IllegalArgumentException("certification request missing or too large");
            }
            request = request.clone();
        }

        @Override
        public byte[] request() {
            return request.clone();
        }

        /** Reads the payload. */
        public static JoinRequest read(PacketReader in) {
            NodeId node = readNode(in);
            String id = in.readString(6);
            String secret = in.readString(32);
            String version = in.readString(MAX_VERSION_BYTES);
            byte[] request = in.readByteArray(MAX_DER_BYTES);
            in.expectEnd();
            try {
                return new JoinRequest(node, id, Secret.of(secret), version, request);
            } catch (IllegalArgumentException e) {
                throw new ProtocolException(e.getMessage(), e);
            }
        }

        /** Writes the payload. */
        public byte[] write() {
            PacketWriter out = new PacketWriter();
            out.writeString(node.value(), NodeId.MAX_LENGTH);
            out.writeString(tokenId, 6);
            out.writeString(secret.reveal(), 32);
            out.writeString(version, MAX_VERSION_BYTES);
            out.writeByteArray(request, MAX_DER_BYTES);
            return out.toByteArray();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof JoinRequest r && node.equals(r.node) && tokenId.equals(r.tokenId)
                    && secret.equals(r.secret) && version.equals(r.version) && Arrays.equals(request, r.request);
        }

        @Override
        public int hashCode() {
            return Objects.hash(node, tokenId, version, Arrays.hashCode(request));
        }

        @Override
        public String toString() {
            return "JoinRequest[node=" + node + ", token=" + tokenId + ".[redacted], version=" + version
                    + ", request=" + request.length + " bytes]";
        }
    }

    /**
     * The head's answer to a join.
     *
     * @param outcome     accepted or why not
     * @param headVersion VectoraCloud version of the head
     * @param head        node id of the head
     * @param certificate the signed node certificate (DER), empty unless accepted
     * @param ca          the CA certificate (DER), empty unless accepted
     */
    public record JoinReply(JoinOutcome outcome, String headVersion, NodeId head, byte[] certificate, byte[] ca) {

        /** Checks the values. */
        public JoinReply {
            Objects.requireNonNull(outcome, "outcome");
            checkVersion(headVersion);
            Objects.requireNonNull(head, "head");
            Objects.requireNonNull(certificate, "certificate");
            Objects.requireNonNull(ca, "ca");
            boolean accepted = outcome == JoinOutcome.ACCEPTED;
            if (accepted != (certificate.length > 0) || accepted != (ca.length > 0)
                    || certificate.length > MAX_DER_BYTES || ca.length > MAX_DER_BYTES) {
                throw new IllegalArgumentException("certificates only with an accepted join");
            }
            certificate = certificate.clone();
            ca = ca.clone();
        }

        /** A refusal. */
        public static JoinReply refused(JoinOutcome outcome, String headVersion, NodeId head) {
            return new JoinReply(outcome, headVersion, head, new byte[0], new byte[0]);
        }

        @Override
        public byte[] certificate() {
            return certificate.clone();
        }

        @Override
        public byte[] ca() {
            return ca.clone();
        }

        /** Reads the payload. */
        public static JoinReply read(PacketReader in) {
            JoinOutcome outcome = JoinOutcome.read(in);
            String version = in.readString(MAX_VERSION_BYTES);
            NodeId head = readNode(in);
            byte[] certificate = in.readByteArray(MAX_DER_BYTES);
            byte[] ca = in.readByteArray(MAX_DER_BYTES);
            in.expectEnd();
            try {
                return new JoinReply(outcome, version, head, certificate, ca);
            } catch (IllegalArgumentException e) {
                throw new ProtocolException(e.getMessage(), e);
            }
        }

        /** Writes the payload. */
        public byte[] write() {
            PacketWriter out = new PacketWriter();
            outcome.write(out);
            out.writeString(headVersion, MAX_VERSION_BYTES);
            out.writeString(head.value(), NodeId.MAX_LENGTH);
            out.writeByteArray(certificate, MAX_DER_BYTES);
            out.writeByteArray(ca, MAX_DER_BYTES);
            return out.toByteArray();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof JoinReply r && outcome == r.outcome && headVersion.equals(r.headVersion)
                    && head.equals(r.head) && Arrays.equals(certificate, r.certificate) && Arrays.equals(ca, r.ca);
        }

        @Override
        public int hashCode() {
            return Objects.hash(outcome, headVersion, head, Arrays.hashCode(certificate), Arrays.hashCode(ca));
        }

        @Override
        public String toString() {
            return "JoinReply[" + outcome + ", head=" + head + ", version=" + headVersion + "]";
        }
    }

    /**
     * A member's first request on a connection with its certificate.
     *
     * @param node    its node id; must be the one in its certificate
     * @param version its VectoraCloud version
     */
    public record NodeHello(NodeId node, String version) {

        /** Checks the values. */
        public NodeHello {
            Objects.requireNonNull(node, "node");
            checkVersion(version);
        }

        /** Reads the payload. */
        public static NodeHello read(PacketReader in) {
            NodeId node = readNode(in);
            String version = in.readString(MAX_VERSION_BYTES);
            in.expectEnd();
            try {
                return new NodeHello(node, version);
            } catch (IllegalArgumentException e) {
                throw new ProtocolException(e.getMessage(), e);
            }
        }

        /** Writes the payload. */
        public byte[] write() {
            return new PacketWriter().writeString(node.value(), NodeId.MAX_LENGTH)
                    .writeString(version, MAX_VERSION_BYTES).toByteArray();
        }
    }

    /**
     * The head's answer to a hello.
     *
     * @param outcome     accepted or why not
     * @param head        node id of the head
     * @param headVersion VectoraCloud version of the head; a member of another version updates to it
     */
    public record NodeWelcome(HelloOutcome outcome, NodeId head, String headVersion) {

        /** Checks the values. */
        public NodeWelcome {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(head, "head");
            checkVersion(headVersion);
        }

        /** Reads the payload. */
        public static NodeWelcome read(PacketReader in) {
            HelloOutcome outcome = HelloOutcome.read(in);
            NodeId head = readNode(in);
            String version = in.readString(MAX_VERSION_BYTES);
            in.expectEnd();
            try {
                return new NodeWelcome(outcome, head, version);
            } catch (IllegalArgumentException e) {
                throw new ProtocolException(e.getMessage(), e);
            }
        }

        /** Writes the payload. */
        public byte[] write() {
            PacketWriter out = new PacketWriter();
            outcome.write(out);
            out.writeString(head.value(), NodeId.MAX_LENGTH);
            out.writeString(headVersion, MAX_VERSION_BYTES);
            return out.toByteArray();
        }
    }
}
