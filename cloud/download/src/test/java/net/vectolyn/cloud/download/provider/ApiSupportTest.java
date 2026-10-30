package net.vectolyn.cloud.download.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.vectolyn.cloud.download.MalformedResponseException;
import org.junit.jupiter.api.Test;

class ApiSupportTest {

    @Test
    void fileNameFromAnApiMustBeABaseName() throws Exception {
        assertEquals("paper-26.3-227.jar", ApiSupport.fileName("paper-26.3-227.jar", "papermc"));
        for (String bad : new String[] {"../paper.jar", "plugins/x.jar", "..", "nul", "com1.jar", "x\r\n.jar", null}) {
            MalformedResponseException e = assertThrows(MalformedResponseException.class,
                    () -> ApiSupport.fileName(bad, "papermc"));
            assertTrue(e.getMessage().startsWith("papermc: file name is not a plain base name"), e.getMessage());
            assertTrue(e.getMessage().chars().noneMatch(c -> c == '\n' || c == '\r'), "no line breaks in the message");
        }
    }
}
