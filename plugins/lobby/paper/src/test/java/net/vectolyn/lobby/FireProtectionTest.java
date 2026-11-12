// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockIgniteEvent.IgniteCause;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** No ignition and no burning in the lobby, whatever the cause. */
class FireProtectionTest {

    // Object, not Block: no method signature may name a paper-api type (class file version 69), or
    // JUnit fails to discover the test on Java 21.
    private static Object block() {
        return Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getX" -> 10;
                    case "getY" -> 64;
                    case "getZ" -> -3;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    @Tag("requires-java25") // loads paper-api 26.3 classes (class file version 69)
    void everyCauseOfIgnitionIsCancelledAndLogged() {
        List<String> messages = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {
                // Nothing is buffered.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        });
        FireProtection protection = new FireProtection(logger);
        for (IgniteCause cause : IgniteCause.values()) {
            BlockIgniteEvent event = new BlockIgniteEvent((Block) block(), cause, (Block) null);
            protection.onIgnite(event);
            assertTrue(event.isCancelled(), cause.name());
        }
        // Spreading is cancelled without a log line.
        assertEquals(IgniteCause.values().length - 1, messages.size());
        assertTrue(messages.contains("Fire blocked: LIGHTNING at 10 64 -3"));
    }

    @Test
    @Tag("requires-java25")
    void noBlockBurnsAway() {
        BlockBurnEvent event = new BlockBurnEvent((Block) block(), null);
        new FireProtection(Logger.getAnonymousLogger()).onBurn(event);
        assertTrue(event.isCancelled());
    }
}
