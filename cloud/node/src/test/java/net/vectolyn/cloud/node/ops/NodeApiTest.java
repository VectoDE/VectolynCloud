package net.vectolyn.cloud.node.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.vectolyn.cloud.api.backend.ApiBackendException;
import net.vectolyn.cloud.node.service.ServiceException;
import org.junit.jupiter.api.Test;

/** What a refusal of the service core shows an API client: its own short reasons, never a path. */
class NodeApiTest {

    @Test
    void ownShortReasonsReachTheClient() {
        ApiBackendException wanted = NodeApi.translate("start lobby",
                new ServiceException("group lobby is not wanted (cloud.yml servers.lobby)"));
        assertEquals(ApiBackendException.Reason.CONFLICT, wanted.reason());
        assertEquals("group lobby is not wanted (cloud.yml servers.lobby)", wanted.getMessage());

        ApiBackendException setup = NodeApi.translate("start lobby", new ServiceException(
                "the setup is not complete (cloud.yml: setup-complete and eula-accepted must be true), so no server may start"));
        assertEquals(ApiBackendException.Reason.UNAVAILABLE, setup.reason());
    }

    @Test
    void aMessageWithAPathStaysInTheNodeLog() {
        for (String message : new String[] {
                "cannot start lobby-1: /opt/vectolyn/Lobby-Server/runtime is already in use",
                "cannot start lobby-1: C:\\vectolyn\\secrets\\vectolyn.env is already locked",
                "secrets/vectolyn.env is malformed (line 3)",
                "cannot start lobby-1: java.io.IOException: disk full"}) {
            ApiBackendException e = NodeApi.translate("start lobby", new ServiceException(message));
            assertFalse(e.getMessage().contains("/"), e.getMessage());
            assertFalse(e.getMessage().contains("\\"), e.getMessage());
            assertFalse(e.getMessage().contains("vectolyn.env"), e.getMessage());
            assertEquals("The node could not start lobby. The node log has the details.", e.getMessage());
        }
        // Counter-check: the reason is kept where it is known, only the text is replaced.
        assertEquals(ApiBackendException.Reason.CONFLICT, NodeApi.translate("start lobby",
                new ServiceException("cannot start lobby-1: /opt/x is already in use")).reason());
        assertEquals(ApiBackendException.Reason.UNAVAILABLE, NodeApi.translate("start lobby",
                new ServiceException("cannot start lobby-1: /opt/x")).reason());
    }

    @Test
    void controlCharactersAndLongTextsStayInTheNodeLog() {
        ApiBackendException newline = NodeApi.translate("stop lobby-1",
                new ServiceException("lobby-1 is not running\nInjected: line"));
        assertEquals("The node could not stop lobby-1. The node log has the details.", newline.getMessage());
        ApiBackendException longText = NodeApi.translate("stop lobby-1",
                new ServiceException("lobby-1 is not running " + "x".repeat(400)));
        assertEquals("The node could not stop lobby-1. The node log has the details.", longText.getMessage());
    }
}
