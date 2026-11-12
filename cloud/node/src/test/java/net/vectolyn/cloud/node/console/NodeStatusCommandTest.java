// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.node.console;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NodeStatusCommandTest {

    @Test
    void uptimeLeavesOutLeadingZeroParts() {
        assertEquals("0s", NodeStatusCommand.uptime(0));
        assertEquals("0s", NodeStatusCommand.uptime(-5));
        assertEquals("59s", NodeStatusCommand.uptime(59));
        assertEquals("1m 0s", NodeStatusCommand.uptime(60));
        assertEquals("1h 0m 5s", NodeStatusCommand.uptime(3605));
        assertEquals("2d 0h 0m 1s", NodeStatusCommand.uptime(2 * 86_400 + 1));
    }
}
