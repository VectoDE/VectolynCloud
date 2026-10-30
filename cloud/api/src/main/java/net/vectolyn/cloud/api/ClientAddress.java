package net.vectolyn.cloud.api;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * The key under which rate limits and connection limits count a client: the IPv4 address, or the
 * /64 prefix of an IPv6 address (one customer usually gets a whole /64, so counting single IPv6
 * addresses would let one client spread over billions of keys).
 */
final class ClientAddress {

    private ClientAddress() {
    }

    /**
     * The limit key of a remote address.
     *
     * @param remote the remote address of a channel
     * @return the key, {@code unknown} if the address is not an IP address
     */
    static String key(SocketAddress remote) {
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
        if (address instanceof Inet6Address) {
            return "v6:" + HexFormat.of().formatHex(Arrays.copyOf(bytes, 8)) + "::/64";
        }
        return "v4:" + address.getHostAddress();
    }
}
