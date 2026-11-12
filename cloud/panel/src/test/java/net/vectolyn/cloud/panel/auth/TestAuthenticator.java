// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.auth;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A software passkey (ES256) for tests, with a tiny CBOR encoder. */
public final class TestAuthenticator {

    private final KeyPair keys;
    private final byte[] credentialId;
    private final String rpId;
    private long counter;
    /** Flags to send; tests clear bits to break a rule. */
    public int flags = 0x01 | 0x04;

    /**
     * Creates a passkey.
     *
     * @param rpId the RP ID it belongs to
     * @throws GeneralSecurityException never on a normal JDK
     */
    public TestAuthenticator(String rpId) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        this.keys = generator.generateKeyPair();
        this.credentialId = new byte[16];
        new java.security.SecureRandom().nextBytes(credentialId);
        this.rpId = rpId;
    }

    /** @return the credential id, Base64url */
    public String id() {
        return b64(credentialId);
    }

    /** The answer of {@code navigator.credentials.create}. */
    public record Created(byte[] clientDataJson, byte[] attestationObject) {
    }

    /** The answer of {@code navigator.credentials.get}. */
    public record Assertion(byte[] clientDataJson, byte[] authenticatorData, byte[] signature) {
    }

    /**
     * Registers.
     *
     * @param challenge the challenge, Base64url
     * @param origin    the origin
     * @return the answer
     */
    public Created create(String challenge, String origin) {
        byte[] client = clientData("webauthn.create", challenge, origin);
        ByteArrayOutputStream auth = new ByteArrayOutputStream();
        auth.writeBytes(WebAuthn.sha256(rpId.getBytes(StandardCharsets.UTF_8)));
        auth.write(flags | 0x40);
        auth.writeBytes(ByteBuffer.allocate(4).putInt((int) counter).array());
        auth.writeBytes(new byte[16]);
        auth.write(credentialId.length >> 8);
        auth.write(credentialId.length & 0xff);
        auth.writeBytes(credentialId);
        auth.writeBytes(coseKey());
        Map<Object, Object> attestation = new LinkedHashMap<>();
        attestation.put("fmt", "none");
        attestation.put("attStmt", new LinkedHashMap<>());
        attestation.put("authData", auth.toByteArray());
        return new Created(client, cbor(attestation));
    }

    /**
     * Signs in.
     *
     * @param challenge the challenge, Base64url
     * @param origin    the origin
     * @return the answer
     * @throws GeneralSecurityException never on a normal JDK
     */
    public Assertion get(String challenge, String origin) throws GeneralSecurityException {
        counter++;
        byte[] client = clientData("webauthn.get", challenge, origin);
        ByteArrayOutputStream auth = new ByteArrayOutputStream();
        auth.writeBytes(WebAuthn.sha256(rpId.getBytes(StandardCharsets.UTF_8)));
        auth.write(flags);
        auth.writeBytes(ByteBuffer.allocate(4).putInt((int) counter).array());
        byte[] data = auth.toByteArray();
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keys.getPrivate());
        signer.update(data);
        signer.update(WebAuthn.sha256(client));
        return new Assertion(client, data, signer.sign());
    }

    private static byte[] clientData(String type, String challenge, String origin) {
        return ("{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin
                + "\",\"crossOrigin\":false}").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] coseKey() {
        ECPublicKey pub = (ECPublicKey) keys.getPublic();
        Map<Object, Object> key = new LinkedHashMap<>();
        key.put(1L, 2L);
        key.put(3L, -7L);
        key.put(-1L, 1L);
        key.put(-2L, fixed(pub.getW().getAffineX()));
        key.put(-3L, fixed(pub.getW().getAffineY()));
        return cbor(key);
    }

    private static byte[] fixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    /**
     * Encodes integers, byte strings, text, lists and maps.
     *
     * @param value the value
     * @return CBOR
     */
    public static byte[] cbor(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, value);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, Object value) {
        switch (value) {
            case Long n when n >= 0 -> head(out, 0, n);
            case Long n -> head(out, 1, -1 - n);
            case byte[] b -> {
                head(out, 2, b.length);
                out.writeBytes(b);
            }
            case String s -> {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                head(out, 3, b.length);
                out.writeBytes(b);
            }
            case List<?> list -> {
                head(out, 4, list.size());
                list.forEach(item -> write(out, item));
            }
            case Map<?, ?> map -> {
                head(out, 5, map.size());
                map.forEach((k, v) -> {
                    write(out, k);
                    write(out, v);
                });
            }
            default -> throw new IllegalArgumentException("unsupported " + value.getClass());
        }
    }

    private static void head(ByteArrayOutputStream out, int major, long n) {
        int m = major << 5;
        if (n < 24) {
            out.write(m | (int) n);
        } else if (n < 256) {
            out.write(m | 24);
            out.write((int) n);
        } else if (n < 65_536) {
            out.write(m | 25);
            out.write((int) (n >> 8));
            out.write((int) n & 0xff);
        } else {
            out.write(m | 26);
            out.writeBytes(ByteBuffer.allocate(4).putInt((int) n).array());
        }
    }

    /**
     * Base64url without padding.
     *
     * @param bytes the bytes
     * @return the text
     */
    public static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
