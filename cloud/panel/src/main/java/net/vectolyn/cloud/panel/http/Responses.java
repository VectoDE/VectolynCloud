// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.http;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import net.vectolyn.cloud.api.http.ApiJson;

/**
 * Builds the answers of the panel server with the headers every answer carries.
 *
 * <p>Every answer: {@code X-Content-Type-Options: nosniff}, {@code Referrer-Policy: no-referrer},
 * {@code X-Frame-Options: DENY}, {@code Cross-Origin-Opener-Policy: same-origin},
 * {@code Cross-Origin-Resource-Policy: same-origin}, a {@code Permissions-Policy} that switches off
 * every device API except passkeys for this origin, and with TLS {@code Strict-Transport-Security}.
 * JSON answers are never cached and have {@code Content-Security-Policy: default-src 'none'}.
 */
public final class Responses {

    /** Permissions of the page: nothing but passkeys of this origin. */
    static final String PERMISSIONS = "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(),"
            + " microphone=(), payment=(), usb=(),"
            + " publickey-credentials-create=(self), publickey-credentials-get=(self)";

    private Responses() {
    }

    /**
     * A JSON answer.
     *
     * @param status the status
     * @param value  a value Jackson can write, or null for no body
     * @param secure whether the server runs with TLS
     * @return the answer
     */
    public static FullHttpResponse json(int status, Object value, boolean secure) {
        byte[] body = value == null ? new byte[0] : ApiJson.write(value);
        return jsonBytes(status, body, secure);
    }

    /**
     * A JSON answer from bytes that are JSON already.
     *
     * @param status the status
     * @param body   the body, may be empty
     * @param secure whether the server runs with TLS
     * @return the answer
     */
    public static FullHttpResponse jsonBytes(int status, byte[] body, boolean secure) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status),
                Unpooled.wrappedBuffer(body));
        HttpHeaders h = response.headers();
        if (body.length > 0) {
            h.set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        }
        h.set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        h.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        common(h, secure);
        h.setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        return response;
    }

    /**
     * The error body of the API ({@code code}, {@code message}).
     *
     * @param status  the status
     * @param code    the code
     * @param message the message, without values from the request
     * @param secure  whether the server runs with TLS
     * @return the answer
     */
    public static FullHttpResponse error(int status, String code, String message, boolean secure) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return json(status, body, secure);
    }

    /**
     * A plain text answer (for answers before any routing, such as a bad request line).
     *
     * @param status the status
     * @param text   the text
     * @param secure whether the server runs with TLS
     * @return the answer
     */
    public static FullHttpResponse text(int status, String text, boolean secure) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status),
                Unpooled.wrappedBuffer(body));
        HttpHeaders h = response.headers();
        h.set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        h.set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        h.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        common(h, secure);
        h.setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        return response;
    }

    /**
     * Sets the headers every answer carries.
     *
     * @param h      the headers
     * @param secure whether the server runs with TLS
     */
    public static void common(HttpHeaders h, boolean secure) {
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Frame-Options", "DENY");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Cross-Origin-Resource-Policy", "same-origin");
        h.set("Permissions-Policy", PERMISSIONS);
        if (secure) {
            h.set("Strict-Transport-Security", "max-age=31536000");
        }
    }
}
