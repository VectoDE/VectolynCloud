// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/**
 * The key under which rate limits and connection limits count a client: the IPv4 address, or the
 * /64 prefix of an IPv6 address (one customer usually gets a whole /64, so counting single IPv6
 * addresses would let one client spread over billions of keys).
 *
 * <p>A client behind NAT64 reaches the node from the well-known prefix {@code 64:ff9b::/96}
 * (RFC 6052) with its IPv4 address in the last 32 bits. Such an address is counted under that
 * IPv4 address, so the clients behind one NAT64 gateway are not lumped into one /64, and the same
 * client counts the same over IPv4 and NAT64.
 */
final class ClientAddress {

    private static final byte[] NAT64_PREFIX = {0, 0x64, (byte) 0xff, (byte) 0x9b, 0, 0, 0, 0, 0, 0, 0, 0};

    private ClientAddress() {
    }

    /**
     * The limit key of a remote address.
     *
     * @param remote the remote address of a channel
     * @return the key, {@code unknown} if the address is not an IP address
     */
    static String key(@Nullable SocketAddress remote) {
        if (!(remote instanceof InetSocketAddress socket) || socket.getAddress() == null) {
            return "unknown";
        }
        return key(socket.getAddress());
    }

    /**
     * The limit key of an IP address.
     *
     * @param address the address
     * @return the key
     */
    static String key(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address && isNat64(bytes)) {
            return "v4:" + (bytes[12] & 0xff) + "." + (bytes[13] & 0xff) + "." + (bytes[14] & 0xff) + "."
                    + (bytes[15] & 0xff);
        }
        if (address instanceof Inet6Address) {
            return "v6:" + HexFormat.of().formatHex(Arrays.copyOf(bytes, 8)) + "::/64";
        }
        return "v4:" + address.getHostAddress();
    }

    /** Whether an IPv6 address lies in the well-known NAT64 prefix {@code 64:ff9b::/96}. */
    private static boolean isNat64(byte[] bytes) {
        return bytes.length == 16 && Arrays.equals(bytes, 0, 12, NAT64_PREFIX, 0, 12);
    }
}
