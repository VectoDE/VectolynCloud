package net.vectora.cloud.cluster;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

/** PEM and DER helpers for certificates and certification requests. */
final class Pem {

    private Pem() {
    }

    /** Wraps DER bytes as PEM with the given label. */
    static byte[] encode(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return ("-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * The DER bytes of the first PEM block with the given label.
     *
     * @throws IOException if there is no such block or it is not Base64
     */
    static byte[] decode(String label, byte[] pem) throws IOException {
        String text = new String(pem, StandardCharsets.US_ASCII);
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        int from = text.indexOf(begin);
        int to = from < 0 ? -1 : text.indexOf(end, from);
        if (from < 0 || to < 0) {
            throw new IOException("no " + label + " in the keytool output");
        }
        try {
            return Base64.getMimeDecoder().decode(text.substring(from + begin.length(), to));
        } catch (IllegalArgumentException e) {
            throw new IOException("the " + label + " in the keytool output is not Base64", e);
        }
    }

    /**
     * Parses one DER certificate.
     *
     * @throws IOException if the bytes are not exactly one X.509 certificate
     */
    static X509Certificate certificate(byte[] der) throws IOException {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            ByteArrayInputStream in = new ByteArrayInputStream(der);
            X509Certificate certificate = (X509Certificate) factory.generateCertificate(in);
            if (in.available() > 0) {
                throw new IOException("bytes left after the certificate");
            }
            return certificate;
        } catch (CertificateException | ClassCastException e) {
            throw new IOException("not an X.509 certificate", e);
        }
    }
}
