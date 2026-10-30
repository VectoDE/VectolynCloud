package net.vectolyn.cloud.cluster;

import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import net.vectolyn.protocol.NodeId;

/**
 * What a node certificate says: the node it belongs to and its fingerprint.
 *
 * <p>A node certificate carries exactly one subject alternative name, the URI
 * {@code vectolyn-node:<node id>}. The cluster CA writes it from the join token, never from the
 * request of the joining node, so a node cannot pick another node's id. A certificate with any
 * other or any further name is no node certificate.
 */
public final class NodeCertificates {

    /** Scheme of the URI name. */
    public static final String URI_PREFIX = "vectolyn-node:";

    /** GeneralName type of a URI (RFC 5280). */
    private static final int URI_NAME = 6;

    private NodeCertificates() {
    }

    /**
     * The node id of a node certificate.
     *
     * @param certificate the certificate
     * @return the id, or empty if the certificate does not have exactly one name, a valid
     *         {@code vectolyn-node:} URI
     */
    public static Optional<NodeId> nodeId(X509Certificate certificate) {
        Collection<List<?>> names;
        try {
            names = certificate.getSubjectAlternativeNames();
        } catch (CertificateParsingException e) {
            return Optional.empty();
        }
        if (names == null || names.size() != 1) {
            return Optional.empty();
        }
        List<?> name = names.iterator().next();
        if (name.size() < 2 || !(name.get(0) instanceof Integer type) || type != URI_NAME
                || !(name.get(1) instanceof String uri) || !uri.startsWith(URI_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new NodeId(uri.substring(URI_PREFIX.length())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * SHA-256 of the certificate (DER), 64 lower-case hex digits. The revocation list and the member
     * list of {@code cluster.yml} name certificates by it.
     *
     * @param certificate the certificate
     * @return the fingerprint
     */
    public static String fingerprint(X509Certificate certificate) {
        try {
            return HexFormat.of().formatHex(CaPin.sha256(certificate.getEncoded()));
        } catch (CertificateEncodingException e) {
            throw new IllegalArgumentException("certificate cannot be encoded", e);
        }
    }

    /** Whether a fingerprint has the written form of {@link #fingerprint}. */
    public static boolean isFingerprint(String text) {
        return text != null && text.matches("[0-9a-f]{64}");
    }

    /**
     * Whether a key is fit for a node: EC with at least 256 bits. The node generates P-256 keys;
     * anything weaker in a request is refused before the CA signs it.
     */
    static boolean acceptableKey(java.security.PublicKey key) {
        return key instanceof ECPublicKey ec && ec.getParams().getCurve().getField().getFieldSize() >= 256;
    }
}
