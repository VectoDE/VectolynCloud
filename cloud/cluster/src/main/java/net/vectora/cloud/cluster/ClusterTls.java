package net.vectora.cloud.cluster;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * TLS settings of the cluster port: TLS 1.3 only (no fallback to 1.2 or older), the JDK's default
 * TLS 1.3 cipher suites, no ALPN, no host name check (see {@link ClusterTrust}). The server asks
 * for a client certificate but does not require one, because a joining node has none yet; what
 * such a connection may do is limited to one join request ({@link ClusterServer}).
 */
public final class ClusterTls {

    /** The only protocol the cluster port speaks. */
    public static final String PROTOCOL = "TLSv1.3";

    private ClusterTls() {
    }

    /**
     * An SSL context for TLS 1.3.
     *
     * @param keys  key managers (this node's certificate), or null for a joining node
     * @param trust the trust decision
     * @return the context
     */
    public static SSLContext context(KeyManager[] keys, X509ExtendedTrustManager trust) {
        try {
            SSLContext context = SSLContext.getInstance(PROTOCOL);
            context.init(keys, new javax.net.ssl.TrustManager[] {trust}, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS 1.3 is not available in this Java", e);
        }
    }

    /** Limits a client socket to TLS 1.3. */
    public static void configure(SSLSocket socket) {
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setProtocols(new String[] {PROTOCOL});
        parameters.setEndpointIdentificationAlgorithm(null);
        socket.setSSLParameters(parameters);
    }

    /** Limits a server socket to TLS 1.3 and asks for (does not require) a client certificate. */
    public static void configure(SSLServerSocket socket) {
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setProtocols(new String[] {PROTOCOL});
        parameters.setWantClientAuth(true);
        socket.setSSLParameters(parameters);
    }
}
