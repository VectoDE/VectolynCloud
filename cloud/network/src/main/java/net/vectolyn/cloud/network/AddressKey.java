package net.vectolyn.cloud.network;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The key under which the connection limits count a peer.
 *
 * <ul>
 *   <li>IPv4: the address itself.</li>
 *   <li>IPv4 in IPv6: an IPv4-mapped address ({@code ::ffff:a.b.c.d}) and an address of the
 *       well-known NAT64 prefix {@code 64:ff9b::/96} (RFC 6052) count as the IPv4 address they
 *       carry. Behind a NAT64 gateway every IPv4 host would otherwise share one /64 and so one
 *       limit.</li>
 *   <li>Any other IPv6 address: its /64 prefix. One host usually gets a whole /64, so counting
 *       single IPv6 addresses would let it spread over billions of keys.</li>
 * </ul>
 *
 * @param value printable form, for example {@code v4:192.0.2.7} or {@code v6:20010db800000001::/64}
 */
public record AddressKey(String value) {

    private static final byte[] NAT64_PREFIX = {0x00, 0x64, (byte) 0xff, (byte) 0x9b, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final byte[] MAPPED_PREFIX = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff};

    /** Checks the value. */
    public AddressKey {
        Objects.requireNonNull(value, "value");
    }

    /** Returns the key of {@code address}. */
    public static AddressKey of(InetAddress address) {
        Objects.requireNonNull(address, "address");
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return v4(bytes);
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            byte[] prefix = Arrays.copyOf(bytes, 12);
            if (Arrays.equals(prefix, NAT64_PREFIX) || Arrays.equals(prefix, MAPPED_PREFIX)) {
                return v4(Arrays.copyOfRange(bytes, 12, 16));
            }
            return new AddressKey("v6:" + HexFormat.of().formatHex(bytes, 0, 8) + "::/64");
        }
        throw new IllegalArgumentException("not an IP address: " + address);
    }

    private static AddressKey v4(byte[] bytes) {
        return new AddressKey("v4:" + (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff) + "."
                + (bytes[3] & 0xff));
    }

    @Override
    public String toString() {
        return value;
    }
}
