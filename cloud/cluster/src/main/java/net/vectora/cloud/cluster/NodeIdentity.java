package net.vectora.cloud.cluster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.KeyManagerFactory;
import net.vectora.cloud.config.io.AtomicFileWriter;
import net.vectora.cloud.config.secret.OwnerOnlyPermissions;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.protocol.NodeId;

/**
 * This node's key and certificate in {@code secrets/cluster/node.p12}. The key is made on the node
 * itself (EC P-256, by keytool) and never leaves it: a joining node sends a certification request,
 * the head sends back the certificate its CA signed, and the node puts it in front of its key.
 */
public final class NodeIdentity {

    /** Names of the temporary request files. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Alias of the key in the keystore. */
    static final String ALIAS = "vectora-node";

    private final Path keystore;
    private final Secret password;

    private NodeIdentity(Path keystore, Secret password) {
        this.keystore = keystore;
        this.password = password;
    }

    /**
     * Makes a new key, replacing an earlier keystore (of a join that did not finish).
     *
     * @param keystore the PKCS#12 file
     * @param password its password
     * @return the identity, without a cluster certificate yet
     * @throws IOException if keytool fails
     */
    public static NodeIdentity generate(Path keystore, Secret password) throws IOException {
        Files.deleteIfExists(keystore);
        // The self-signed certificate keytool makes with the key is a placeholder until install().
        Keytool.run(List.of("-genkeypair", "-alias", ALIAS, "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-validity", "2", "-dname", "CN=vectora-node",
                "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass:env", Keytool.PASSWORD_ENV, "-keypass:env", Keytool.PASSWORD_ENV, "-noprompt"),
                Map.of(Keytool.PASSWORD_ENV, password));
        OwnerOnlyPermissions.restrictFile(keystore);
        return new NodeIdentity(keystore, password);
    }

    /**
     * Opens an existing identity.
     *
     * @param keystore the PKCS#12 file
     * @param password its password
     * @return the identity
     */
    public static NodeIdentity open(Path keystore, Secret password) {
        return new NodeIdentity(keystore, password);
    }

    /**
     * The PKCS#10 certification request for the key (DER).
     *
     * @param work a folder for keytool's output file
     * @return the request
     * @throws IOException if keytool fails
     */
    public byte[] certificationRequest(Path work) throws IOException {
        Files.createDirectories(work);
        OwnerOnlyPermissions.restrictDirectory(work);
        byte[] random = new byte[8];
        RANDOM.nextBytes(random);
        Path csr = work.resolve(HexFormat.of().formatHex(random) + ".csr");
        try {
            Keytool.run(List.of("-certreq", "-alias", ALIAS, "-keystore", keystore.toString(), "-storetype", "PKCS12",
                    "-storepass:env", Keytool.PASSWORD_ENV, "-sigalg", "SHA256withECDSA", "-file", csr.toString()),
                    Map.of(Keytool.PASSWORD_ENV, password));
            return Pem.decode("NEW CERTIFICATE REQUEST", Files.readAllBytes(csr));
        } finally {
            Files.deleteIfExists(csr);
        }
    }

    /**
     * Puts the certificate the head sent in front of the key, with the CA certificate behind it.
     *
     * @param certificate the node certificate; must carry this node's public key
     * @param ca          the CA certificate that signed it
     * @throws IOException if the certificate is for another key or the keystore cannot be written
     */
    public void install(X509Certificate certificate, X509Certificate ca) throws IOException {
        try {
            KeyStore store = load();
            Key key = store.getKey(ALIAS, password.reveal().toCharArray());
            Certificate current = store.getCertificate(ALIAS);
            if (!(key instanceof PrivateKey privateKey) || current == null
                    || !current.getPublicKey().equals(certificate.getPublicKey())) {
                throw new IOException("the certificate from the head is not for this node's key");
            }
            store.setKeyEntry(ALIAS, privateKey, password.reveal().toCharArray(), new Certificate[] {certificate, ca});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            store.store(out, password.reveal().toCharArray());
            new AtomicFileWriter().write(keystore, out.toByteArray(), OwnerOnlyPermissions::restrictFile);
            OwnerOnlyPermissions.restrictFile(keystore);
        } catch (GeneralSecurityException e) {
            throw new IOException("secrets/cluster/" + keystore.getFileName() + " cannot be updated", e);
        }
    }

    /**
     * The key managers for TLS, with the node certificate and the CA behind it.
     *
     * @return the factory
     * @throws IOException if the keystore cannot be read
     */
    public KeyManagerFactory keyManagers() throws IOException {
        try {
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(load(), password.reveal().toCharArray());
            return factory;
        } catch (GeneralSecurityException e) {
            throw new IOException("secrets/cluster/" + keystore.getFileName() + " cannot be used", e);
        }
    }

    /** The current certificate of the key (the placeholder before the join). */
    public X509Certificate certificate() throws IOException {
        try {
            return (X509Certificate) load().getCertificate(ALIAS);
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IOException("secrets/cluster/" + keystore.getFileName() + " cannot be read", e);
        }
    }

    /** The CA certificate behind the node certificate, once installed. */
    public Optional<X509Certificate> caCertificate() throws IOException {
        try {
            Certificate[] chain = load().getCertificateChain(ALIAS);
            return chain != null && chain.length == 2 && chain[1] instanceof X509Certificate ca
                    ? Optional.of(ca) : Optional.empty();
        } catch (GeneralSecurityException e) {
            throw new IOException("secrets/cluster/" + keystore.getFileName() + " cannot be read", e);
        }
    }

    /** The node id of the installed certificate, if there is one. */
    public Optional<NodeId> nodeId() throws IOException {
        return NodeCertificates.nodeId(certificate());
    }

    private KeyStore load() throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, password.reveal().toCharArray());
        }
        return store;
    }
}
