// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.auth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import net.vectolyn.cloud.api.http.ApiJson;

/**
 * Server side of passkeys (W3C Web Authentication Level 3), verified with the JDK only.
 *
 * <p>Registration ({@link #register}): {@code clientDataJSON} must be of type
 * {@code webauthn.create}, carry the challenge this server issued and the panel's origin, and must
 * not be cross-origin. The attestation object's authenticator data must name the panel's RP ID (by
 * SHA-256), have the user-present and user-verified flags and an attested credential. The panel asks
 * for {@code attestation: "none"}, so the attestation statement is not checked: a passkey is trusted
 * because a signed-in user registered it, not because of its maker. Supported keys: ES256 (P-256),
 * RS256 and EdDSA (Ed25519).
 *
 * <p>Sign-in ({@link #verifyAssertion}): the same checks for {@code webauthn.get}, then the
 * signature over {@code authenticatorData || SHA-256(clientDataJSON)} with the stored key, and the
 * signature counter must grow unless both counters are zero (a counter that does not grow points
 * to a cloned authenticator).
 */
public final class WebAuthn {

    /** COSE algorithm ids. */
    public static final int ES256 = -7;
    /** RSASSA-PKCS1-v1_5 with SHA-256. */
    public static final int RS256 = -257;
    /** EdDSA (Ed25519). */
    public static final int EDDSA = -8;

    private static final int FLAG_UP = 0x01;
    private static final int FLAG_UV = 0x04;
    private static final int FLAG_AT = 0x40;
    private static final int MAX_INPUT = 16 * 1024;

    private WebAuthn() {
    }

    /** A check failed; the message names the rule, never a value. */
    public static final class WebAuthnException extends Exception {

        private static final long serialVersionUID = 1L;

        WebAuthnException(String message) {
            super(message);
        }
    }

    /**
     * What the server expects of a ceremony.
     *
     * @param challenge the challenge it issued (raw bytes)
     * @param origin    the panel's origin, for example {@code https://panel.example.org}
     * @param rpId      the RP ID, the host of the origin
     */
    public record Expected(byte[] challenge, String origin, String rpId) {
    }

    /**
     * A registered credential.
     *
     * @param id        credential id
     * @param publicKey the COSE key as stored
     * @param algorithm COSE algorithm
     * @param signCount the counter at registration
     */
    public record Credential(byte[] id, byte[] publicKey, int algorithm, long signCount) {
    }

    /**
     * Parsed authenticator data.
     *
     * @param rpIdHash     SHA-256 of the RP ID
     * @param flags        the flag byte
     * @param signCount    the signature counter
     * @param credentialId the attested credential id, or null
     * @param publicKey    the attested COSE key bytes, or null
     */
    public record AuthenticatorData(byte[] rpIdHash, int flags, long signCount, byte[] credentialId, byte[] publicKey) {
    }

    /**
     * Verifies a registration.
     *
     * @param clientDataJson    {@code response.clientDataJSON}
     * @param attestationObject {@code response.attestationObject}
     * @param expected          challenge, origin and RP ID
     * @return the new credential
     * @throws WebAuthnException if any check fails
     */
    public static Credential register(byte[] clientDataJson, byte[] attestationObject, Expected expected)
            throws WebAuthnException {
        checkClientData(clientDataJson, "webauthn.create", expected);
        if (attestationObject == null || attestationObject.length > MAX_INPUT) {
            throw new WebAuthnException("attestation object missing or too large");
        }
        Object decoded;
        try {
            decoded = Cbor.decode(attestationObject);
        } catch (Cbor.CborException e) {
            throw new WebAuthnException("attestation object is not valid CBOR");
        }
        if (!(decoded instanceof Map<?, ?> map) || !(map.get("authData") instanceof byte[] authData)
                || !(map.get("fmt") instanceof String)) {
            throw new WebAuthnException("attestation object without fmt or authData");
        }
        AuthenticatorData data = parseAuthenticatorData(authData);
        checkAuthenticatorData(data, expected.rpId());
        if ((data.flags() & FLAG_AT) == 0 || data.credentialId() == null) {
            throw new WebAuthnException("no attested credential");
        }
        int algorithm = algorithm(data.publicKey());
        publicKey(data.publicKey()); // refuses keys the JDK cannot use
        return new Credential(data.credentialId(), data.publicKey(), algorithm, data.signCount());
    }

    /**
     * Verifies a sign-in.
     *
     * @param credential        the stored credential
     * @param clientDataJson    {@code response.clientDataJSON}
     * @param authenticatorData {@code response.authenticatorData}
     * @param signature         {@code response.signature}
     * @param expected          challenge, origin and RP ID
     * @return the new signature counter
     * @throws WebAuthnException if any check fails
     */
    public static long verifyAssertion(Credential credential, byte[] clientDataJson, byte[] authenticatorData,
                                       byte[] signature, Expected expected) throws WebAuthnException {
        checkClientData(clientDataJson, "webauthn.get", expected);
        AuthenticatorData data = parseAuthenticatorData(authenticatorData);
        checkAuthenticatorData(data, expected.rpId());
        if (signature == null || signature.length == 0 || signature.length > 1024) {
            throw new WebAuthnException("signature missing");
        }
        byte[] signed = concat(authenticatorData, sha256(clientDataJson));
        try {
            Signature verifier = Signature.getInstance(switch (credential.algorithm()) {
                case ES256 -> "SHA256withECDSA";
                case RS256 -> "SHA256withRSA";
                case EDDSA -> "Ed25519";
                default -> throw new WebAuthnException("unsupported algorithm");
            });
            verifier.initVerify(publicKey(credential.publicKey()));
            verifier.update(signed);
            if (!verifier.verify(signature)) {
                throw new WebAuthnException("signature does not verify");
            }
        } catch (GeneralSecurityException e) {
            throw new WebAuthnException("signature does not verify");
        }
        if ((data.signCount() != 0 || credential.signCount() != 0) && data.signCount() <= credential.signCount()) {
            throw new WebAuthnException("signature counter did not grow");
        }
        return data.signCount();
    }

    private static void checkClientData(byte[] json, String type, Expected expected) throws WebAuthnException {
        if (json == null || json.length == 0 || json.length > MAX_INPUT) {
            throw new WebAuthnException("client data missing or too large");
        }
        Map<?, ?> client;
        try {
            client = ApiJson.mapper().readValue(json, Map.class);
        } catch (RuntimeException e) {
            throw new WebAuthnException("client data is not JSON");
        }
        if (client == null || !type.equals(client.get("type"))) {
            throw new WebAuthnException("wrong ceremony type");
        }
        Object challenge = client.get("challenge");
        byte[] presented;
        try {
            presented = challenge instanceof String text ? Base64.getUrlDecoder().decode(text) : null;
        } catch (IllegalArgumentException e) {
            presented = null;
        }
        if (presented == null || !MessageDigest.isEqual(presented, expected.challenge())) {
            throw new WebAuthnException("wrong challenge");
        }
        if (!expected.origin().equals(client.get("origin"))) {
            throw new WebAuthnException("wrong origin");
        }
        if (Boolean.TRUE.equals(client.get("crossOrigin"))) {
            throw new WebAuthnException("cross-origin ceremony");
        }
    }

    private static void checkAuthenticatorData(AuthenticatorData data, String rpId) throws WebAuthnException {
        if (!MessageDigest.isEqual(data.rpIdHash(), sha256(rpId.getBytes(StandardCharsets.UTF_8)))) {
            throw new WebAuthnException("wrong RP ID");
        }
        if ((data.flags() & FLAG_UP) == 0) {
            throw new WebAuthnException("user not present");
        }
        if ((data.flags() & FLAG_UV) == 0) {
            throw new WebAuthnException("user not verified");
        }
    }

    /**
     * Reads authenticator data: RP ID hash (32), flags (1), counter (4), and with the AT flag the
     * attested credential (AAGUID 16, id length 2, id, COSE key).
     *
     * @param data the bytes
     * @return the parts
     * @throws WebAuthnException if it is too short or the key is not CBOR
     */
    public static AuthenticatorData parseAuthenticatorData(byte[] data) throws WebAuthnException {
        if (data == null || data.length < 37 || data.length > MAX_INPUT) {
            throw new WebAuthnException("authenticator data too short or too large");
        }
        byte[] rpIdHash = Arrays.copyOfRange(data, 0, 32);
        int flags = data[32] & 0xff;
        long count = ((long) (data[33] & 0xff) << 24) | ((data[34] & 0xff) << 16) | ((data[35] & 0xff) << 8)
                | (data[36] & 0xff);
        if ((flags & FLAG_AT) == 0) {
            return new AuthenticatorData(rpIdHash, flags, count, null, null);
        }
        if (data.length < 37 + 18) {
            throw new WebAuthnException("attested credential data too short");
        }
        int idLength = ((data[53] & 0xff) << 8) | (data[54] & 0xff);
        int idStart = 55;
        if (idLength == 0 || idLength > 1023 || idStart + idLength > data.length) {
            throw new WebAuthnException("credential id length out of range");
        }
        byte[] id = Arrays.copyOfRange(data, idStart, idStart + idLength);
        Cbor.Decoded key;
        try {
            key = Cbor.decodeFirst(data, idStart + idLength);
        } catch (Cbor.CborException e) {
            throw new WebAuthnException("credential public key is not valid CBOR");
        }
        byte[] keyBytes = Arrays.copyOfRange(data, idStart + idLength, key.end());
        return new AuthenticatorData(rpIdHash, flags, count, id, keyBytes);
    }

    /**
     * The COSE algorithm of a key.
     *
     * @param cose the key bytes
     * @return ES256, RS256 or EdDSA
     * @throws WebAuthnException for any other key
     */
    public static int algorithm(byte[] cose) throws WebAuthnException {
        Map<?, ?> key = coseMap(cose);
        if (!(key.get(3L) instanceof Long alg) || (alg != ES256 && alg != RS256 && alg != EDDSA)) {
            throw new WebAuthnException("unsupported algorithm");
        }
        return alg.intValue();
    }

    /**
     * Turns a COSE key into a JDK public key.
     *
     * @param cose the key bytes
     * @return the key
     * @throws WebAuthnException if the key is not a supported, valid key
     */
    public static PublicKey publicKey(byte[] cose) throws WebAuthnException {
        Map<?, ?> key = coseMap(cose);
        int algorithm = algorithm(cose);
        try {
            switch (algorithm) {
                case ES256 -> {
                    if (!Long.valueOf(2).equals(key.get(1L)) || !Long.valueOf(1).equals(key.get(-1L))
                            || !(key.get(-2L) instanceof byte[] x) || !(key.get(-3L) instanceof byte[] y)
                            || x.length != 32 || y.length != 32) {
                        throw new WebAuthnException("invalid P-256 key");
                    }
                    AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
                    params.init(new ECGenParameterSpec("secp256r1"));
                    ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
                    ECPoint point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
                    return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
                }
                case RS256 -> {
                    if (!Long.valueOf(3).equals(key.get(1L)) || !(key.get(-1L) instanceof byte[] n)
                            || !(key.get(-2L) instanceof byte[] e) || n.length < 256 || n.length > 512 || e.length > 8) {
                        throw new WebAuthnException("invalid RSA key");
                    }
                    return KeyFactory.getInstance("RSA").generatePublic(
                            new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e)));
                }
                default -> {
                    if (!Long.valueOf(1).equals(key.get(1L)) || !Long.valueOf(6).equals(key.get(-1L))
                            || !(key.get(-2L) instanceof byte[] x) || x.length != 32) {
                        throw new WebAuthnException("invalid Ed25519 key");
                    }
                    byte[] reversed = new byte[32];
                    for (int i = 0; i < 32; i++) {
                        reversed[i] = x[31 - i];
                    }
                    boolean odd = (reversed[0] & 0x80) != 0;
                    reversed[0] &= 0x7f;
                    EdECPoint point = new EdECPoint(odd, new BigInteger(1, reversed));
                    return KeyFactory.getInstance("Ed25519").generatePublic(
                            new EdECPublicKeySpec(NamedParameterSpec.ED25519, point));
                }
            }
        } catch (GeneralSecurityException e) {
            throw new WebAuthnException("the key cannot be used");
        }
    }

    private static Map<?, ?> coseMap(byte[] cose) throws WebAuthnException {
        try {
            if (cose != null && Cbor.decode(cose) instanceof Map<?, ?> map) {
                return map;
            }
        } catch (Cbor.CborException e) {
            // reported below
        }
        throw new WebAuthnException("the key is not a COSE map");
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
