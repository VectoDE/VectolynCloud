package net.vectora.cloud.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

/** The limit keys (IPv4, /64 of IPv6, IPv4 in IPv6) and the counting of the limiter. Documentation addresses only. */
class ConnectionLimiterTest {

    @Test
    void ipv4CountsPerAddress() throws Exception {
        assertEquals("v4:192.0.2.7", AddressKey.of(ip("192.0.2.7")).value());
        assertNotEquals(AddressKey.of(ip("192.0.2.7")), AddressKey.of(ip("192.0.2.8")));
    }

    @Test
    void ipv6CountsPerSlash64() throws Exception {
        AddressKey key = AddressKey.of(ip("2001:db8:1:2::1"));
        assertEquals("v6:20010db800010002::/64", key.value());
        assertEquals(key, AddressKey.of(ip("2001:db8:1:2:ffff:ffff:ffff:ffff")), "same /64");
        assertNotEquals(key, AddressKey.of(ip("2001:db8:1:3::1")), "neighbouring /64");
        assertEquals("v6:0000000000000000::/64", AddressKey.of(ip("::1")).value());
    }

    @Test
    void nat64AndMappedAddressesCountAsTheirIpv4Address() throws Exception {
        assertEquals("v4:192.0.2.33", AddressKey.of(ipv6("64:ff9b::c000:221")).value(), "NAT64 well-known prefix");
        assertEquals("v4:192.0.2.33", AddressKey.of(ipv6("::ffff:192.0.2.33")).value(), "IPv4-mapped");
        // Two IPv4 hosts behind one NAT64 gateway share the /64 but not the limit.
        assertNotEquals(AddressKey.of(ipv6("64:ff9b::c000:221")), AddressKey.of(ipv6("64:ff9b::c000:222")));
        // Counter-check: an address next to the NAT64 prefix is plain IPv6.
        assertEquals("v6:0064ff9b00000001::/64", AddressKey.of(ipv6("64:ff9b:0:1::c000:221")).value());
        assertEquals("v6:0064ff9b00000000::/64", AddressKey.of(ipv6("64:ff9b::1:c000:221")).value());
    }

    @Test
    void perAddressLimitAppliesToTheWholeSlash64() throws Exception {
        ConnectionLimiter limiter = new ConnectionLimiter(100, 2, 100, 100);
        assertNotNull(limiter.tryAcquire(ip("2001:db8::1")));
        assertNotNull(limiter.tryAcquire(ip("2001:db8::2")));
        assertNull(limiter.tryAcquire(ip("2001:db8::3")), "third address of the same /64");
        assertNotNull(limiter.tryAcquire(ip("2001:db8:0:1::3")), "another /64");
    }

    @Test
    void nat64SharesTheLimitOfItsIpv4Address() throws Exception {
        ConnectionLimiter limiter = new ConnectionLimiter(100, 1, 100, 100);
        assertNotNull(limiter.tryAcquire(ip("192.0.2.33")));
        assertNull(limiter.tryAcquire(ipv6("64:ff9b::c000:221")), "the same IPv4 host through NAT64");
        assertNotNull(limiter.tryAcquire(ipv6("64:ff9b::c000:222")), "another IPv4 host through NAT64");
    }

    @Test
    void unauthenticatedLimitsAreSeparateAndFreedByAuthentication() throws Exception {
        ConnectionLimiter limiter = new ConnectionLimiter(10, 10, 2, 1);
        ConnectionLimiter.Slot first = limiter.tryAcquire(ip("192.0.2.1"));
        assertNotNull(first);
        assertNull(limiter.tryAcquire(ip("192.0.2.1")), "second silent connection of one address");
        ConnectionLimiter.Slot other = limiter.tryAcquire(ip("192.0.2.2"));
        assertNotNull(other);
        assertNull(limiter.tryAcquire(ip("192.0.2.3")), "total of silent connections");
        assertEquals(2, limiter.unauthenticated());

        first.authenticated();
        first.authenticated(); // idempotent
        assertEquals(1, limiter.unauthenticated());
        assertNotNull(limiter.tryAcquire(ip("192.0.2.1")), "the authenticated one no longer counts as silent");
        assertEquals(3, limiter.open());

        other.release();
        other.release(); // idempotent
        assertEquals(2, limiter.open());
        assertEquals(1, limiter.unauthenticated());
        first.release();
        first.authenticated(); // after release: no effect
        assertEquals(1, limiter.open());
        assertEquals(1, limiter.unauthenticated());
    }

    @Test
    void totalLimitCountsAuthenticatedConnectionsToo() throws Exception {
        ConnectionLimiter limiter = new ConnectionLimiter(1, 10, 10, 10);
        ConnectionLimiter.Slot slot = limiter.tryAcquire(ip("192.0.2.1"));
        slot.authenticated();
        assertNull(limiter.tryAcquire(ip("192.0.2.2")));
        slot.release();
        assertNotNull(limiter.tryAcquire(ip("192.0.2.2")));
    }

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    /** An IPv6 address as given; the JDK would turn {@code ::ffff:a.b.c.d} into an Inet4Address. */
    private static InetAddress ipv6(String literal) throws UnknownHostException {
        InetAddress parsed = InetAddress.getByName(literal);
        byte[] bytes = parsed.getAddress();
        if (bytes.length == 4) {
            byte[] mapped = new byte[16];
            mapped[10] = (byte) 0xff;
            mapped[11] = (byte) 0xff;
            System.arraycopy(bytes, 0, mapped, 12, 4);
            bytes = mapped;
        }
        return Inet6Address.getByAddress(null, bytes, -1);
    }
}
