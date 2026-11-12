// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class ClientAddressTest {

    @Test
    void ipv4IsCountedPerAddress() throws Exception {
        assertEquals("v4:203.0.113.7", ClientAddress.key(InetAddress.getByName("203.0.113.7")));
    }

    @Test
    void ipv6IsCountedPerSlash64() throws Exception {
        String a = ClientAddress.key(InetAddress.getByName("2001:db8:1:2::1"));
        String b = ClientAddress.key(InetAddress.getByName("2001:db8:1:2:ffff::9"));
        assertEquals("v6:20010db800010002::/64", a);
        assertEquals(a, b);
        assertNotEquals(a, ClientAddress.key(InetAddress.getByName("2001:db8:1:3::1")));
    }

    @Test
    void nat64IsCountedUnderTheEmbeddedIpv4Address() throws Exception {
        assertEquals("v4:192.0.2.33", ClientAddress.key(InetAddress.getByName("64:ff9b::c000:221")));
        assertEquals("v4:192.0.2.33", ClientAddress.key(InetAddress.getByName("64:ff9b::192.0.2.33")));
        // Two clients behind one NAT64 gateway share its /64, but not their limit key.
        assertNotEquals(ClientAddress.key(InetAddress.getByName("64:ff9b::192.0.2.33")),
                ClientAddress.key(InetAddress.getByName("64:ff9b::192.0.2.34")));
        // Only the well-known /96: another address in 64:ff9b:1::/48 stays an IPv6 /64.
        assertEquals("v6:0064ff9b00010000::/64", ClientAddress.key(InetAddress.getByName("64:ff9b:1::c000:221")));
    }

    @Test
    void aSocketAddressWithoutAnIpIsUnknown() {
        assertEquals("unknown", ClientAddress.key(InetSocketAddress.createUnresolved("example.org", 80)));
        assertEquals("unknown", ClientAddress.key((java.net.SocketAddress) null));
    }
}
