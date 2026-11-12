// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.privacy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/** Canonical text of IP address literals. Never does a DNS lookup. */
public final class IpAddresses {

    private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private IpAddresses() {
    }

    /**
     * Canonical form of an IPv4 or IPv6 literal: IPv4 as dotted decimal, IPv6 lower case with
     * leading zeros removed and no brackets or zone.
     *
     * @param text the literal
     * @return the canonical form
     * @throws IllegalArgumentException if {@code text} is not an IP address literal
     */
    public static String normalize(String text) {
        if (text == null) {
            throw new IllegalArgumentException("not an IP address");
        }
        String value = text.strip();
        boolean v4 = IPV4.matcher(value).matches();
        boolean v6 = !v4 && value.indexOf(':') >= 0 && IPV6.matcher(value).matches();
        if (!v4 && !v6) {
            throw new IllegalArgumentException("not an IP address");
        }
        if (v4) {
            for (String part : value.split("\\.", -1)) {
                if (Integer.parseInt(part) > 255) {
                    throw new IllegalArgumentException("not an IP address");
                }
            }
        }
        try {
            // Only digits, dots, colons and hex letters reach this point, so no lookup happens.
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("not an IP address", e);
        }
    }
}
