// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.auth;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.vectolyn.cloud.api.http.ApiJson;

/**
 * A signed JSON Web Token (RFC 7519, compact JWS of RFC 7515) as an OpenID Connect ID token.
 * Supported signatures: RS256 and ES256, the two that OpenID providers use; {@code none} and HMAC
 * are refused, so a token can never pick a weaker check. Keys come from the provider's JWKS
 * (RFC 7517) and are chosen by {@code kid} and {@code kty}.
 *
 * @param header    the decoded header
 * @param claims    the decoded payload
 * @param signed    the bytes the signature covers ({@code header.payload})
 * @param signature the signature bytes
 */
public record Jwt(Map<String, Object> header, Map<String, Object> claims, byte[] signed, byte[] signature) {

    private static final int MAX_LENGTH = 16 * 1024;

    /** The token breaks a rule; the message names it, never a value. */
    public static final class JwtException extends Exception {

        private static final long serialVersionUID = 1L;

        JwtException(String message) {
            super(message);
        }
    }

    /**
     * Splits and decodes a compact token without checking its signature.
     *
     * @param token the text
     * @return the parts
     * @throws JwtException if it is not three Base64url parts with a JSON header and payload
     */
    @SuppressWarnings("unchecked")
    public static Jwt parse(String token) throws JwtException {
        if (token == null || token.length() > MAX_LENGTH) {
            throw new JwtException("token missing or too long");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new JwtException("not a compact JWS");
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            Map<String, Object> header = ApiJson.mapper().readValue(decoder.decode(parts[0]), Map.class);
            Map<String, Object> claims = ApiJson.mapper().readValue(decoder.decode(parts[1]), Map.class);
            if (header == null || claims == null) {
                throw new JwtException("header or payload is not an object");
            }
            return new Jwt(header, claims, (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII),
                    decoder.decode(parts[2]));
        } catch (IllegalArgumentException e) {
            throw new JwtException("not Base64url");
        } catch (RuntimeException e) {
            throw new JwtException("header or payload is not JSON");
        }
    }

    /**
     * Checks the signature with the matching key of a JWKS.
     *
     * @param jwks the provider's key set ({@code {"keys": [...]}})
     * @throws JwtException if the algorithm is not RS256 or ES256, no key fits or the signature is wrong
     */
    public void verify(Map<String, Object> jwks) throws JwtException {
        Object alg = header.get("alg");
        if (!"RS256".equals(alg) && !"ES256".equals(alg)) {
            throw new JwtException("unsupported algorithm");
        }
        String kty = "RS256".equals(alg) ? "RSA" : "EC";
        Object kid = header.get("kid");
        if (!(jwks.get("keys") instanceof List<?> keys)) {
            throw new JwtException("key set without keys");
        }
        for (Object entry : keys) {
            if (!(entry instanceof Map<?, ?> key) || !kty.equals(key.get("kty"))) {
                continue;
            }
            if (kid != null && !kid.equals(key.get("kid"))) {
                continue;
            }
            if (key.get("use") != null && !"sig".equals(key.get("use"))) {
                continue;
            }
            try {
                PublicKey publicKey = publicKey(key);
                Signature verifier = Signature.getInstance("RS256".equals(alg) ? "SHA256withRSA" : "SHA256withECDSAinP1363Format");
                verifier.initVerify(publicKey);
                verifier.update(signed);
                if (verifier.verify(signature)) {
                    return;
                }
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                // try the next key
            }
        }
        throw new JwtException("no key of the provider verifies the signature");
    }

    private static PublicKey publicKey(Map<?, ?> jwk) throws GeneralSecurityException {
        Base64.Decoder decoder = Base64.getUrlDecoder();
        if ("RSA".equals(jwk.get("kty"))) {
            BigInteger n = new BigInteger(1, decoder.decode((String) jwk.get("n")));
            BigInteger e = new BigInteger(1, decoder.decode((String) jwk.get("e")));
            if (n.bitLength() < 2048) {
                throw new GeneralSecurityException("RSA key below 2048 bits");
            }
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
        }
        if (!"P-256".equals(jwk.get("crv"))) {
            throw new GeneralSecurityException("unsupported curve");
        }
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECPoint point = new ECPoint(new BigInteger(1, decoder.decode((String) jwk.get("x"))),
                new BigInteger(1, decoder.decode((String) jwk.get("y"))));
        return KeyFactory.getInstance("EC").generatePublic(
                new ECPublicKeySpec(point, params.getParameterSpec(ECParameterSpec.class)));
    }

    /**
     * Checks the claims of an ID token (OpenID Connect Core 1.0, 3.1.3.7).
     *
     * @param issuer   the expected {@code iss}
     * @param clientId the expected audience
     * @param nonce    the nonce sent with the authorization request
     * @param nowSec   now, seconds since 1970
     * @throws JwtException if a claim is missing or wrong; 60 seconds of clock skew are allowed
     */
    public void checkIdToken(String issuer, String clientId, String nonce, long nowSec) throws JwtException {
        if (!issuer.equals(claims.get("iss"))) {
            throw new JwtException("wrong issuer");
        }
        Object aud = claims.get("aud");
        boolean audience = aud instanceof String text ? text.equals(clientId)
                : aud instanceof List<?> list && list.contains(clientId);
        if (!audience) {
            throw new JwtException("wrong audience");
        }
        if (aud instanceof List<?> list && list.size() > 1 && !clientId.equals(claims.get("azp"))) {
            throw new JwtException("wrong authorized party");
        }
        Optional<Long> exp = number("exp");
        Optional<Long> iat = number("iat");
        if (exp.isEmpty() || exp.get() + 60 < nowSec) {
            throw new JwtException("expired");
        }
        if (iat.isEmpty() || iat.get() - 60 > nowSec) {
            throw new JwtException("issued in the future");
        }
        if (nonce == null || !nonce.equals(claims.get("nonce"))) {
            throw new JwtException("wrong nonce");
        }
        if (!(claims.get("sub") instanceof String sub) || sub.isEmpty() || sub.length() > 255) {
            throw new JwtException("missing subject");
        }
    }

    private Optional<Long> number(String name) {
        return claims.get(name) instanceof Number n ? Optional.of(n.longValue()) : Optional.empty();
    }
}
