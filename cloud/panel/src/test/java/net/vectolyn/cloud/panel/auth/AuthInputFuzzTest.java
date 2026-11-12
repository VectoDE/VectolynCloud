// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

/** Fuzz targets for every parser that reads sign-in input or the account file. */
class AuthInputFuzzTest {

    @FuzzTest
    void cborIsAValueOrRefused(FuzzedDataProvider data) {
        int offset = data.consumeInt(0, 8);
        byte[] bytes = data.consumeRemainingAsBytes();
        try {
            Cbor.decode(bytes);
        } catch (Cbor.CborException e) {
            // refused
        }
        try {
            Cbor.Decoded decoded = Cbor.decodeFirst(bytes, offset);
            assertTrue(decoded.end() > offset && decoded.end() <= bytes.length);
        } catch (Cbor.CborException e) {
            // refused
        }
    }

    @FuzzTest
    void authenticatorDataIsPartsOrRefused(byte[] bytes) {
        try {
            WebAuthn.AuthenticatorData parsed = WebAuthn.parseAuthenticatorData(bytes);
            assertEquals(32, parsed.rpIdHash().length);
        } catch (WebAuthn.WebAuthnException e) {
            // refused
        }
    }

    @FuzzTest
    void jwtIsPartsOrRefused(FuzzedDataProvider data) {
        try {
            Jwt jwt = Jwt.parse(data.consumeRemainingAsString());
            assertTrue(jwt.header() != null && jwt.claims() != null);
        } catch (Jwt.JwtException e) {
            // refused
        }
    }

    @FuzzTest
    void passwordHashesAndBase32AreValuesOrEmpty(FuzzedDataProvider data) {
        String text = data.consumeRemainingAsString();
        PasswordHash.parse(text).ifPresent(p -> assertTrue(p.iterations() > 0 && p.salt().length > 0));
        Totp.decodeBase32(text).ifPresent(b -> assertTrue(b.length <= 80));
    }

    @FuzzTest
    void accountFilesAreDataOrRefused(byte[] bytes) {
        try {
            PanelUsers.Data parsed = PanelUsers.parse(bytes);
            assertTrue(parsed.users().size() <= PanelUsers.MAX_USERS);
        } catch (PanelUsers.FormatException e) {
            // refused
        }
    }
}
