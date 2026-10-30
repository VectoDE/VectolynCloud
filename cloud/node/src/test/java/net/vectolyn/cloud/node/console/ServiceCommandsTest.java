package net.vectolyn.cloud.node.console;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServiceCommandsTest {

    @Test
    void serverOutputCannotSendEscapeSequencesOrReorderTheLine() {
        assertEquals("a?[31mb?c?d\tz", ServiceCommands.printable("a\u001b[31mb‮c\u0007d\tz"));
        assertEquals("x?y?z", ServiceCommands.printable("x⁦y z"));
        assertEquals("plain line with tab\tand umlaut ä", ServiceCommands.printable("plain line with tab\tand umlaut ä"));
    }
}
