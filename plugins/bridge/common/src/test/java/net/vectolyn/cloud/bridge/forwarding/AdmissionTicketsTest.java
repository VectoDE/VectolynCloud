package net.vectolyn.cloud.bridge.forwarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AdmissionTicketsTest {

    private final AtomicLong now = new AtomicLong();
    private final AdmissionTickets tickets = new AdmissionTickets(now::get);

    @Test
    void aTicketAdmitsExactlyOneLogin() {
        UUID uuid = UUID.randomUUID();
        assertFalse(tickets.consume(uuid), "without a checked handshake there is no ticket");
        tickets.issue(uuid);
        assertTrue(tickets.consume(uuid));
        assertFalse(tickets.consume(uuid), "a ticket is used up");
        assertFalse(tickets.consume(null));
    }

    @Test
    void ticketsExpire() {
        UUID early = UUID.randomUUID();
        UUID late = UUID.randomUUID();
        tickets.issue(early);
        now.addAndGet(AdmissionTickets.TTL_NANOS / 2);
        tickets.issue(late);
        now.addAndGet(AdmissionTickets.TTL_NANOS / 2);
        assertFalse(tickets.consume(early));
        assertTrue(tickets.consume(late));
    }

    @Test
    void theOldestTicketsGoFirstAboveTheLimit() {
        UUID first = UUID.randomUUID();
        tickets.issue(first);
        for (int i = 0; i < AdmissionTickets.MAX_TICKETS; i++) {
            tickets.issue(UUID.randomUUID());
        }
        assertEquals(AdmissionTickets.MAX_TICKETS, tickets.size());
        assertFalse(tickets.consume(first));
    }
}
