package net.vectora.cloud.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.vectora.cloud.cluster.ClusterMessages.JoinOutcome;
import net.vectora.cloud.config.model.DatabaseConfig;
import net.vectora.cloud.config.model.DatabaseMode;
import net.vectora.cloud.config.secret.Secret;
import net.vectora.protocol.NodeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The CA made by keytool and the certificates it signs. */
class ClusterCaTest {

    private static final Secret PASSWORD = Secret.of("test-password-made-at-test-time");

    @TempDir
    Path dir;

    private ClusterCa ca() throws IOException {
        return ClusterCa.create(dir.resolve("ca.p12"), PASSWORD, "Test");
    }

    @Test
    void theCaSignsANodeCertificateWithExactlyTheTokensNodeId() throws Exception {
        ClusterCa ca = ca();
        assertTrue(ca.certificate().getBasicConstraints() >= 0);
        NodeIdentity node = NodeIdentity.generate(dir.resolve("node.p12"), PASSWORD);
        X509Certificate issued = ca.issue(node.certificationRequest(dir.resolve("work")), new NodeId("node-2"),
                dir.resolve("work"));
        assertEquals(Optional.of(new NodeId("node-2")), NodeCertificates.nodeId(issued));
        assertEquals(-1, issued.getBasicConstraints());
        assertTrue(issued.getExtendedKeyUsage().containsAll(List.of("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2")));
        node.install(issued, ca.certificate());
        assertEquals(Optional.of(new NodeId("node-2")), node.nodeId());
        assertEquals(Optional.of(ca.certificate()), node.caCertificate());
        assertTrue(CaPin.of(ca.certificate()).matches(node.caCertificate().orElseThrow()));
        try (var files = Files.list(dir.resolve("work"))) {
            assertEquals(0, files.count(), "no request or certificate is left in the work folder");
        }
    }

    @Test
    void namesInTheRequestAreIgnored() throws Exception {
        ClusterCa ca = ca();
        NodeIdentity.generate(dir.resolve("node.p12"), PASSWORD);
        Path csr = dir.resolve("evil.csr");
        // A request that asks for the head's name, a DNS name and CA rights.
        Keytool.run(List.of("-certreq", "-alias", NodeIdentity.ALIAS, "-keystore", dir.resolve("node.p12").toString(),
                "-storetype", "PKCS12", "-storepass:env", Keytool.PASSWORD_ENV, "-file", csr.toString(),
                "-ext", "SAN=uri:vectora-node:node-1,dns:evil.example", "-ext", "BC=ca:true"),
                Map.of(Keytool.PASSWORD_ENV, PASSWORD));
        byte[] request = Pem.decode("NEW CERTIFICATE REQUEST", Files.readAllBytes(csr));
        X509Certificate issued = ca.issue(request, new NodeId("node-2"), dir.resolve("work"));
        assertEquals(Optional.of(new NodeId("node-2")), NodeCertificates.nodeId(issued));
        assertEquals(1, issued.getSubjectAlternativeNames().size());
        assertEquals(-1, issued.getBasicConstraints());
    }

    @Test
    void aTamperedRequestIsRefused() throws Exception {
        ClusterCa ca = ca();
        NodeIdentity node = NodeIdentity.generate(dir.resolve("node.p12"), PASSWORD);
        byte[] request = node.certificationRequest(dir.resolve("work"));
        request[request.length / 2] ^= 0x01;
        assertThrows(IOException.class, () -> ca.issue(request, new NodeId("node-2"), dir.resolve("work")));
        assertThrows(IOException.class, () -> ca.issue(new byte[0], new NodeId("node-2"), dir.resolve("work")));
        assertThrows(IOException.class, () -> ca.issue(new byte[5000], new NodeId("node-2"), dir.resolve("work")));
    }

    @Test
    void aCertificateOfAnotherCaIsNotInstalledOverAnotherKey() throws Exception {
        ClusterCa ca = ca();
        NodeIdentity a = NodeIdentity.generate(dir.resolve("a.p12"), PASSWORD);
        NodeIdentity b = NodeIdentity.generate(dir.resolve("b.p12"), PASSWORD);
        X509Certificate forA = ca.issue(a.certificationRequest(dir.resolve("work")), new NodeId("node-2"), dir.resolve("work"));
        assertThrows(IOException.class, () -> b.install(forA, ca.certificate()));
    }

    @Test
    void theCaKeystoreIsNotCreatedTwice() throws Exception {
        ca();
        assertThrows(IOException.class, this::ca);
        assertThrows(IOException.class, () -> ClusterCa.open(dir.resolve("ca.p12"), Secret.of("wrong")));
    }

    @Test
    void theDatabaseDecidesWhoMayJoin() throws Exception {
        InetAddress remote = InetAddress.getByName("192.0.2.10");
        InetAddress loopback = InetAddress.getLoopbackAddress();
        DatabaseConfig h2 = new DatabaseConfig(DatabaseMode.H2, "127.0.0.1", 3307, "lp", "lp", null);
        DatabaseConfig local = new DatabaseConfig(DatabaseMode.MANAGED, "127.0.0.1", 3307, "lp", "lp", null);
        DatabaseConfig localhost = new DatabaseConfig(DatabaseMode.EXTERNAL, "localhost", 3306, "lp", "lp", null);
        DatabaseConfig shared = new DatabaseConfig(DatabaseMode.EXTERNAL, "10.0.0.5", 3306, "lp", "lp", null);
        assertEquals(Optional.of(JoinOutcome.DATABASE_SINGLE_NODE), ClusterService.databaseRefusal(h2, loopback));
        assertEquals(Optional.of(JoinOutcome.DATABASE_UNREACHABLE), ClusterService.databaseRefusal(local, remote));
        assertEquals(Optional.of(JoinOutcome.DATABASE_UNREACHABLE), ClusterService.databaseRefusal(localhost, remote));
        assertEquals(Optional.empty(), ClusterService.databaseRefusal(local, loopback), "same machine: reachable");
        assertEquals(Optional.empty(), ClusterService.databaseRefusal(shared, remote));
        // Managed and Docker MariaDB listen on 127.0.0.1 whatever host the config names.
        DatabaseConfig managedNamed = new DatabaseConfig(DatabaseMode.MANAGED, "10.0.0.5", 3307, "lp", "lp", null);
        DatabaseConfig dockerNamed = new DatabaseConfig(DatabaseMode.DOCKER, "10.0.0.5", 3307, "lp", "lp", null);
        assertEquals(Optional.of(JoinOutcome.DATABASE_UNREACHABLE), ClusterService.databaseRefusal(managedNamed, remote));
        assertEquals(Optional.of(JoinOutcome.DATABASE_UNREACHABLE), ClusterService.databaseRefusal(dockerNamed, remote));
        assertEquals(Optional.empty(), ClusterService.databaseRefusal(dockerNamed, loopback), "same machine: reachable");
        assertFalse(ClusterService.databaseRefusal(null, loopback).isEmpty());
    }
}
