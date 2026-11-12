// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class AuthPrimitivesTest {

    @Test
    void passwordsHashVerifyAndRefuseWeakOnes() {
        String hash = PasswordHash.hash("correct horse battery".toCharArray());
        assertTrue(hash.startsWith("pbkdf2-sha256$600000$"), hash);
        assertTrue(PasswordHash.verify("correct horse battery".toCharArray(), hash));
        assertFalse(PasswordHash.verify("correct horse batterz".toCharArray(), hash));
        assertFalse(PasswordHash.verify("x".toCharArray(), null), "a missing user is a normal failure");
        assertFalse(PasswordHash.verify("x".toCharArray(), "garbage"));
        assertFalse(PasswordHash.outdated(hash));
        assertTrue(PasswordHash.problem("short", "a").isPresent());
        assertTrue(PasswordHash.problem("alice-the-admin", "alice-the-admin").isPresent(), "not the name");
        assertTrue(PasswordHash.problem("a".repeat(257), "b").isPresent());
        assertTrue(PasswordHash.problem("a long enough phrase", "bob").isEmpty());
        assertTrue(PasswordHash.parse("pbkdf2-sha256$1$AA$AA").isEmpty() || PasswordHash.outdated("pbkdf2-sha256$1$AA$AA"));
    }

    @Test
    void totpMatchesRfc6238AndRefusesReuse() {
        // RFC 6238 appendix B, SHA-1 secret "12345678901234567890": 8-digit 94287082 at T=59 -> 6 digits 287082.
        byte[] key = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertEquals(287_082, Totp.code(key, 59 / 30));
        assertEquals(81_804, Totp.code(key, 1_111_111_109L / 30)); // 07081804
        String secret = Totp.base32(key);
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", secret);
        assertArrayEquals(key, Totp.decodeBase32(secret.toLowerCase(java.util.Locale.ROOT)).orElseThrow());
        OptionalLong step = Totp.verify(secret, "287 082", 59, -1);
        assertEquals(1, step.orElseThrow());
        assertTrue(Totp.verify(secret, "287082", 59, 1).isEmpty(), "a used code does not work twice");
        assertTrue(Totp.verify(secret, "287082", 59 + 90, -1).isEmpty(), "older than one step");
        assertTrue(Totp.verify(secret, "28708", 59, -1).isEmpty());
        assertTrue(Totp.decodeBase32("not*base32").isEmpty());
        assertTrue(Totp.uri(Totp.newSecret(), "alice", "VectolynCloud").startsWith("otpauth://totp/VectolynCloud%3Aalice?secret="));
    }

    @Test
    void cborDecodesTheSubsetAndRefusesTheRest() throws Exception {
        Object decoded = Cbor.decode(TestAuthenticator.cbor(Map.of("a", List.of(1L, -2L, new byte[] {7}))));
        assertTrue(decoded instanceof Map<?, ?>);
        assertThrows(Cbor.CborException.class, () -> Cbor.decode(new byte[] {(byte) 0xa2, 0x01, 0x01, 0x01, 0x02}), "duplicate key");
        assertThrows(Cbor.CborException.class, () -> Cbor.decode(new byte[] {(byte) 0x9f, (byte) 0xff}), "indefinite");
        assertThrows(Cbor.CborException.class, () -> Cbor.decode(new byte[] {(byte) 0xc0, 0x01}), "tag");
        assertThrows(Cbor.CborException.class, () -> Cbor.decode(new byte[] {(byte) 0xf9, 0x00, 0x00}), "float");
        assertThrows(Cbor.CborException.class, () -> Cbor.decode(new byte[] {0x5a, 0x7f, 0x00, 0x00, 0x00}), "length");
        assertThrows(Cbor.CborException.class, () -> Cbor.decode(new byte[] {0x01, 0x02}), "trailing");
        byte[] deep = new byte[40];
        java.util.Arrays.fill(deep, (byte) 0x81);
        assertThrows(Cbor.CborException.class, () -> Cbor.decode(deep), "too deep");
    }

    @Test
    void passkeysRegisterAndSignInWithEveryCheck() throws Exception {
        TestAuthenticator authenticator = new TestAuthenticator("localhost");
        byte[] challenge = new byte[32];
        challenge[0] = 9;
        WebAuthn.Expected expected = new WebAuthn.Expected(challenge, "http://localhost:8090", "localhost");
        TestAuthenticator.Created created = authenticator.create(TestAuthenticator.b64(challenge), "http://localhost:8090");
        WebAuthn.Credential credential = WebAuthn.register(created.clientDataJson(), created.attestationObject(), expected);
        assertEquals(WebAuthn.ES256, credential.algorithm());
        assertEquals(authenticator.id(), TestAuthenticator.b64(credential.id()));

        TestAuthenticator.Assertion assertion = authenticator.get(TestAuthenticator.b64(challenge), "http://localhost:8090");
        long count = WebAuthn.verifyAssertion(credential, assertion.clientDataJson(), assertion.authenticatorData(),
                assertion.signature(), expected);
        assertEquals(1, count);
        WebAuthn.Credential used = new WebAuthn.Credential(credential.id(), credential.publicKey(), credential.algorithm(), count);
        assertThrows(WebAuthn.WebAuthnException.class, () -> WebAuthn.verifyAssertion(used, assertion.clientDataJson(),
                assertion.authenticatorData(), assertion.signature(), expected), "a replayed counter");

        TestAuthenticator.Assertion other = authenticator.get(TestAuthenticator.b64(challenge), "https://evil.example");
        assertThrows(WebAuthn.WebAuthnException.class, () -> WebAuthn.verifyAssertion(used, other.clientDataJson(),
                other.authenticatorData(), other.signature(), expected), "wrong origin");
        TestAuthenticator.Assertion wrongChallenge = authenticator.get(TestAuthenticator.b64(new byte[32]), "http://localhost:8090");
        assertThrows(WebAuthn.WebAuthnException.class, () -> WebAuthn.verifyAssertion(used, wrongChallenge.clientDataJson(),
                wrongChallenge.authenticatorData(), wrongChallenge.signature(), expected), "wrong challenge");
        authenticator.flags = 0x01;
        TestAuthenticator.Assertion unverified = authenticator.get(TestAuthenticator.b64(challenge), "http://localhost:8090");
        assertThrows(WebAuthn.WebAuthnException.class, () -> WebAuthn.verifyAssertion(used, unverified.clientDataJson(),
                unverified.authenticatorData(), unverified.signature(), expected), "user verification is required");
        WebAuthn.Expected otherRp = new WebAuthn.Expected(challenge, "http://localhost:8090", "example.org");
        assertThrows(WebAuthn.WebAuthnException.class,
                () -> WebAuthn.register(created.clientDataJson(), created.attestationObject(), otherRp), "wrong RP ID");
    }

    @Test
    void idTokensAreVerifiedWithTheKeySetAndEveryClaim() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keys = generator.generateKeyPair();
        Map<String, Object> jwks = jwks(keys, "k1");
        long now = 1_800_000_000L;
        String token = sign(keys, "k1", "{\"iss\":\"https://id.example\",\"aud\":\"panel\",\"sub\":\"u-1\",\"nonce\":\"n\","
                + "\"exp\":" + (now + 300) + ",\"iat\":" + now + "}");
        Jwt jwt = Jwt.parse(token);
        jwt.verify(jwks);
        jwt.checkIdToken("https://id.example", "panel", "n", now);
        assertThrows(Jwt.JwtException.class, () -> jwt.checkIdToken("https://id.example", "panel", "other", now));
        assertThrows(Jwt.JwtException.class, () -> jwt.checkIdToken("https://id.example", "other", "n", now));
        assertThrows(Jwt.JwtException.class, () -> jwt.checkIdToken("https://other", "panel", "n", now));
        assertThrows(Jwt.JwtException.class, () -> jwt.checkIdToken("https://id.example", "panel", "n", now + 400));
        KeyPair stranger = generator.generateKeyPair();
        assertThrows(Jwt.JwtException.class, () -> Jwt.parse(token).verify(jwks(stranger, "k1")));
        String none = b64("{\"alg\":\"none\"}") + "." + b64("{}") + ".";
        assertThrows(Jwt.JwtException.class, () -> Jwt.parse(none).verify(jwks));
        assertThrows(Jwt.JwtException.class, () -> Jwt.parse("a.b"));
    }

    @Test
    void sessionsEndAndAccountsLock() {
        Instant[] now = {Instant.parse("2026-11-12T10:00:00Z")};
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        Sessions sessions = new Sessions(clock);
        Sessions.Session session = sessions.create("alice", "password");
        assertTrue(sessions.touch(session.id()).isPresent());
        assertTrue(Sessions.csrfMatches(session, session.csrf()));
        assertFalse(Sessions.csrfMatches(session, "x"));
        now[0] = now[0].plus(Sessions.IDLE).plusSeconds(1);
        assertTrue(sessions.touch(session.id()).isEmpty(), "idle timeout");
        for (int i = 0; i < Sessions.MAX_FAILURES - 1; i++) {
            assertFalse(sessions.fail("Bob"));
        }
        assertTrue(sessions.fail("bob"));
        assertTrue(sessions.lockedSeconds("BOB") > 0);
        now[0] = now[0].plus(Sessions.LOCKOUT);
        assertEquals(0, sessions.lockedSeconds("bob"));
        String ticket = sessions.issue("totp", "alice");
        assertTrue(sessions.take(ticket, "passkey-login", String.class).isEmpty(), "wrong kind consumes it");
        assertTrue(sessions.take(ticket, "totp", String.class).isEmpty(), "one time");
        String late = sessions.issue("totp", "alice");
        now[0] = now[0].plus(Sessions.TICKET);
        assertTrue(sessions.take(late, "totp", String.class).isEmpty(), "expired");
        Sessions.Session absolute = sessions.create("carol", "password");
        for (int i = 0; i < 30; i++) {
            now[0] = now[0].plus(Duration.ofMinutes(25));
            sessions.touch(absolute.id());
        }
        assertTrue(sessions.touch(absolute.id()).isEmpty(), "absolute timeout");
    }

    static Map<String, Object> jwks(KeyPair keys, String kid) {
        ECPublicKey pub = (ECPublicKey) keys.getPublic();
        return Map.of("keys", List.of(Map.of("kty", "EC", "crv", "P-256", "kid", kid, "use", "sig",
                "x", TestAuthenticator.b64(fixed(pub.getW().getAffineX())),
                "y", TestAuthenticator.b64(fixed(pub.getW().getAffineY())))));
    }

    static String sign(KeyPair keys, String kid, String claims) throws Exception {
        String signed = b64("{\"alg\":\"ES256\",\"kid\":\"" + kid + "\"}") + "." + b64(claims);
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(keys.getPrivate());
        signer.update(signed.getBytes(StandardCharsets.US_ASCII));
        return signed + "." + TestAuthenticator.b64(signer.sign());
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] fixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }
}
