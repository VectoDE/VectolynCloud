package net.vectolyn.cloud.cluster;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import net.vectolyn.protocol.NodeId;

/**
 * The trust decisions of the cluster port, as JSSE trust managers.
 *
 * <ul>
 *   <li>{@link #peers}: between members. The chain must lead to the cluster CA (PKIX, with the CA
 *       as the only trust anchor; the JDK checks validity and the extended key usage for TLS
 *       client and server), the leaf must be a node certificate ({@link NodeCertificates}), and
 *       {@link PeerCheck} decides about the node id and the fingerprint (member, not revoked,
 *       the expected head).</li>
 *   <li>{@link #pinned}: for a node that joins and does not have the CA yet. The head's chain must
 *       end in a self-signed CA certificate whose key has the pin from the join command, and the
 *       leaf must be a node certificate that this CA signed. Only then does the joining node send
 *       its token.</li>
 * </ul>
 * Host names are not checked: a node is known by the node id in its certificate, signed by a CA
 * that exists only in this cluster.
 */
public final class ClusterTrust {

    private ClusterTrust() {
    }

    /** The decision about a peer's node id and certificate, after the chain checked out. */
    @FunctionalInterface
    public interface PeerCheck {
        /**
         * Accepts or refuses a peer.
         *
         * @param node        node id from the certificate
         * @param fingerprint SHA-256 of the certificate ({@link NodeCertificates#fingerprint})
         * @throws CertificateException to refuse it; the handshake fails
         */
        void check(NodeId node, String fingerprint) throws CertificateException;
    }

    /**
     * Trust between members of the cluster.
     *
     * @param ca    the cluster CA certificate
     * @param check the membership and revocation check
     * @return the trust manager, for both client and server side
     */
    public static X509ExtendedTrustManager peers(X509Certificate ca, PeerCheck check) {
        Objects.requireNonNull(check, "check");
        X509TrustManager pkix = pkix(ca);
        return new Base() {
            @Override
            void verify(X509Certificate[] chain, String authType, boolean client) throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("no certificate");
                }
                if (client) {
                    pkix.checkClientTrusted(chain, authType);
                } else {
                    pkix.checkServerTrusted(chain, authType);
                }
                NodeId node = NodeCertificates.nodeId(chain[0])
                        .orElseThrow(() -> new CertificateException("not a node certificate"));
                check.check(node, NodeCertificates.fingerprint(chain[0]));
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[] {ca};
            }
        };
    }

    /**
     * Trust of a joining node in the head, by the pin of the CA's key and the head's node id. Every
     * member holds a certificate of the same CA, so the pin alone would let a member that sits in
     * the path play the head and take the token; the node id of the head's certificate has to be
     * the one of the join command as well. The CA it found is kept in {@code found} for the node to
     * install.
     *
     * @param pin   the pin from the join command
     * @param head  the head's node id from the join command
     * @param found receives the CA certificate once the chain checks out
     * @return the trust manager, for the client side only
     */
    public static X509ExtendedTrustManager pinned(CaPin pin, NodeId head, AtomicReference<X509Certificate> found) {
        Objects.requireNonNull(pin, "pin");
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(found, "found");
        return new Base() {
            @Override
            void verify(X509Certificate[] chain, String authType, boolean client) throws CertificateException {
                if (client) {
                    throw new CertificateException("a joining node does not accept connections");
                }
                if (chain == null || chain.length < 2) {
                    throw new CertificateException("the head sent no CA certificate");
                }
                X509Certificate root = chain[chain.length - 1];
                if (!pin.matches(root)) {
                    throw new CertificateException("the CA of this head does not have the pinned key");
                }
                try {
                    root.verify(root.getPublicKey());
                } catch (GeneralSecurityException e) {
                    throw new CertificateException("the pinned CA certificate is not self-signed", e);
                }
                if (root.getBasicConstraints() < 0) {
                    throw new CertificateException("the pinned certificate is no CA");
                }
                pkix(root).checkServerTrusted(chain, authType);
                Optional<NodeId> node = NodeCertificates.nodeId(chain[0]);
                if (node.isEmpty()) {
                    throw new CertificateException("the head has no node certificate");
                }
                if (!node.get().equals(head)) {
                    throw new CertificateException("the peer is node " + node.get() + ", not the head " + head);
                }
                found.set(root);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private static X509TrustManager pkix(X509Certificate ca) {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setCertificateEntry("cluster-ca", ca);
            TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
            factory.init(store);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    return x509;
                }
            }
            throw new IllegalStateException("no X.509 trust manager");
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalStateException("the trust store for the cluster CA cannot be built", e);
        }
    }

    /** Routes every JSSE entry point to one check. */
    private abstract static class Base extends X509ExtendedTrustManager {

        abstract void verify(X509Certificate[] chain, String authType, boolean client) throws CertificateException;

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            verify(chain, authType, true);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            verify(chain, authType, false);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            verify(chain, authType, true);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            verify(chain, authType, false);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            verify(chain, authType, true);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            verify(chain, authType, false);
        }
    }
}
