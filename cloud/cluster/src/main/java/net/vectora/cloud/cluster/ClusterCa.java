package net.vectora.cloud.cluster;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.vectora.cloud.config.secret.OwnerOnlyPermissions;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.protocol.NodeId;

/**
 * The cluster CA: an EC P-256 key with a self-signed certificate ({@code CA:true, pathlen:0}),
 * made by the JDK's {@code keytool} in an owner-only PKCS#12 keystore on the head. It signs the
 * node certificates and nothing else.
 *
 * <p>The node id of a certificate comes from the join token, not from the request: the CA writes
 * the subject and the one subject alternative name ({@code vectora-node:<id>}) itself and keytool
 * does not copy extensions out of the request. Every issued certificate is parsed and checked
 * again before it leaves the head.
 */
public final class ClusterCa {

    /** Names of the temporary request and certificate files. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Alias of the CA key in its keystore. */
    static final String ALIAS = "vectora-cluster-ca";
    /** How long the CA certificate is valid. */
    static final int CA_VALIDITY_DAYS = 3650;
    /** How long a node certificate is valid. */
    public static final Duration NODE_VALIDITY = Duration.ofDays(825);

    private final Path keystore;
    private final Secret password;
    private final X509Certificate certificate;

    private ClusterCa(Path keystore, Secret password, X509Certificate certificate) {
        this.keystore = keystore;
        this.password = password;
        this.certificate = certificate;
    }

    /**
     * Creates the CA. The keystore must not exist yet.
     *
     * @param keystore the PKCS#12 file to create
     * @param password its password
     * @param network  the network name, for the certificate's subject
     * @return the CA
     * @throws IOException if keytool fails or the file exists
     */
    public static ClusterCa create(Path keystore, Secret password, String network) throws IOException {
        if (Files.exists(keystore)) {
            throw new IOException("The cluster CA exists already.");
        }
        String name = network == null ? "" : network.replaceAll("[^A-Za-z0-9 ._-]", "");
        Keytool.run(List.of("-genkeypair", "-alias", ALIAS, "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-validity", Integer.toString(CA_VALIDITY_DAYS),
                "-dname", "CN=VectoraCloud cluster CA" + (name.isBlank() ? "" : " " + name.strip()),
                "-ext", "BC:c=ca:true,pathlen:0", "-ext", "KU:c=keyCertSign,cRLSign",
                "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass:env", Keytool.PASSWORD_ENV, "-keypass:env", Keytool.PASSWORD_ENV, "-noprompt"),
                Map.of(Keytool.PASSWORD_ENV, password));
        OwnerOnlyPermissions.restrictFile(keystore);
        return open(keystore, password);
    }

    /**
     * Opens the CA.
     *
     * @param keystore its PKCS#12 file
     * @param password its password
     * @return the CA
     * @throws IOException if the file cannot be read, the password is wrong or there is no CA in it
     */
    public static ClusterCa open(Path keystore, Secret password) throws IOException {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                store.load(in, password.reveal().toCharArray());
            }
            Certificate cert = store.getCertificate(ALIAS);
            if (!(cert instanceof X509Certificate x509) || !store.isKeyEntry(ALIAS) || x509.getBasicConstraints() < 0) {
                throw new IOException("secrets/cluster/" + keystore.getFileName() + " holds no cluster CA");
            }
            return new ClusterCa(keystore, password, x509);
        } catch (GeneralSecurityException e) {
            throw new IOException("The cluster CA cannot be read (wrong password or not PKCS#12).", e);
        }
    }

    /** The CA certificate. */
    public X509Certificate certificate() {
        return certificate;
    }

    /** The pin a joining node checks. */
    public CaPin pin() {
        return CaPin.of(certificate);
    }

    /**
     * Signs a node certificate for {@code node}.
     *
     * @param request the PKCS#10 certification request of the node (DER), at most 4 KiB
     * @param node    the node id, from the join token
     * @param work    a folder for the request and the certificate while keytool runs
     * @return the certificate, checked: issued by this CA, for {@code node} only, no CA, EC key of
     *         at least 256 bits
     * @throws IOException if the request is not valid (keytool checks its signature) or the result
     *                     fails a check
     */
    public synchronized X509Certificate issue(byte[] request, NodeId node, Path work) throws IOException {
        Objects.requireNonNull(node, "node");
        if (request == null || request.length == 0 || request.length > 4096) {
            throw new IOException("certification request missing or too large");
        }
        Files.createDirectories(work);
        OwnerOnlyPermissions.restrictDirectory(work);
        byte[] random = new byte[8];
        RANDOM.nextBytes(random);
        String name = HexFormat.of().formatHex(random);
        Path csr = work.resolve(name + ".csr");
        Path crt = work.resolve(name + ".crt");
        try {
            Files.write(csr, Pem.encode("NEW CERTIFICATE REQUEST", request));
            Keytool.run(List.of("-gencert", "-alias", ALIAS, "-keystore", keystore.toString(), "-storetype", "PKCS12",
                    "-storepass:env", Keytool.PASSWORD_ENV, "-infile", csr.toString(), "-outfile", crt.toString(),
                    "-rfc", "-sigalg", "SHA256withECDSA", "-dname", "CN=vectora-node " + node.value(),
                    "-validity", Long.toString(NODE_VALIDITY.toDays()),
                    "-ext", "SAN=uri:" + NodeCertificates.URI_PREFIX + node.value(),
                    "-ext", "BC:c=ca:false", "-ext", "KU:c=digitalSignature",
                    "-ext", "EKU=serverAuth,clientAuth"),
                    Map.of(Keytool.PASSWORD_ENV, password));
            X509Certificate issued = Pem.certificate(Pem.decode("CERTIFICATE", Files.readAllBytes(crt)));
            check(issued, node);
            return issued;
        } finally {
            Files.deleteIfExists(csr);
            Files.deleteIfExists(crt);
        }
    }

    /**
     * The checks every node certificate of this CA passes: signed by it, issued under its subject,
     * no CA, exactly the name of {@code node}, an acceptable key.
     */
    void check(X509Certificate issued, NodeId node) throws IOException {
        try {
            issued.verify(certificate.getPublicKey());
            issued.checkValidity();
        } catch (GeneralSecurityException e) {
            throw new IOException("the issued certificate does not verify", e);
        }
        if (!issued.getIssuerX500Principal().equals(certificate.getSubjectX500Principal())
                || issued.getBasicConstraints() >= 0
                || !NodeCertificates.nodeId(issued).map(node::equals).orElse(false)
                || !NodeCertificates.acceptableKey(issued.getPublicKey())) {
            throw new IOException("the issued certificate is not a valid node certificate");
        }
    }
}
