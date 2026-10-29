package net.vectora.cloud.monitor.notify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * A self-signed certificate for 127.0.0.1, made with the test JDK's keytool, with a server context
 * that presents it and a client context that trusts only it.
 */
final class TestTls {

    private static final char[] PASSWORD = "changeit".toCharArray();

    final SSLContext server;
    final SSLContext client;

    private TestTls(SSLContext server, SSLContext client) {
        this.server = server;
        this.client = client;
    }

    static TestTls create(Path dir) throws Exception {
        Path keystore = dir.resolve("server.p12");
        String exe = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows") ? "keytool.exe" : "keytool";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", exe);
        Process process = new ProcessBuilder(List.of(keytool.toString(), "-genkeypair", "-alias", "test",
                "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=127.0.0.1",
                "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-validity", "2",
                "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD)))
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("keytool failed: " + new String(output, java.nio.charset.StandardCharsets.UTF_8));
        }
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            keys.load(in, PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, PASSWORD);
        SSLContext server = SSLContext.getInstance("TLS");
        server.init(kmf.getKeyManagers(), null, null);

        Certificate cert = keys.getCertificate("test");
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("test", cert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, tmf.getTrustManagers(), null);
        return new TestTls(server, client);
    }
}
