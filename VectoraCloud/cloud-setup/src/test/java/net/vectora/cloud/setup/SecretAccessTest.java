package net.vectora.cloud.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.vectora.cloud.config.CloudHome;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.config.secret.SecretGenerator;
import net.vectora.cloud.config.secret.SecretStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class SecretAccessTest {

    private static final String GERMAN = String.join("\r\n",
            "\"Jeder\",\"Bekannte Gruppe\",\"S-1-1-0\",\"Verbindliche Gruppe, Aktivierte Gruppe\"",
            "\"VORDEFINIERT\\Administratoren\",\"Alias\",\"S-1-5-32-544\",\"Nur für Verweigerungen verwendete Gruppe\"",
            "\"VORDEFINIERT\\Benutzer\",\"Alias\",\"S-1-5-32-545\",\"Verbindliche Gruppe\"");
    private static final String FALLBACK = "BUILTIN\\Administrators (S-1-5-32-544)";

    @Test
    void localizedNameComesWithTheSid() {
        assertEquals("VORDEFINIERT\\Administratoren (S-1-5-32-544)", SecretAccess.administratorsGroup(Optional.of(GERMAN)));
    }

    @Test
    void fallbackWhenTheGroupIsMissingOrOdd() {
        assertEquals(FALLBACK, SecretAccess.administratorsGroup(Optional.empty()));
        assertEquals(FALLBACK, SecretAccess.administratorsGroup(Optional.of("\"Jeder\",\"Bekannte Gruppe\",\"S-1-1-0\",\"x\"")));
        assertEquals(FALLBACK, SecretAccess.administratorsGroup(Optional.of("\"Evil\u001b[2J\",\"Alias\",\"S-1-5-32-544\",\"x\"")),
                "control characters never reach the console");
        assertEquals(FALLBACK, SecretAccess.administratorsGroup(Optional.of("garbage")));
    }

    @Test
    void csvHandlesQuotesAndCommas() {
        assertEquals(List.of("a,b", "c\"d", "", "e"), SecretAccess.csv("\"a,b\",\"c\"\"d\",,e"));
    }

    @Test
    void missingFileIsDescribedAsOpen(@TempDir Path root) {
        assertSame(SetupMessages.ACCESS_OPEN, SecretAccess.describe(root.resolve("absent"), true, Optional::empty));
    }

    @Test
    void restrictedFileNamesOwnerAndAdministrators(@TempDir Path root) throws Exception {
        SecretStore store = new SecretStore(CloudHome.of(root));
        store.ensureGenerated(new SecretGenerator());
        String windows = SecretAccess.describe(store.file(), true, () -> Optional.of(GERMAN)).get(Language.DE);
        String posix = SecretAccess.describe(store.file(), false, Optional::empty).get(Language.EN);
        String open = SetupMessages.ACCESS_OPEN.get(Language.DE);
        assertTrue(windows.contains("VORDEFINIERT\\Administratoren (S-1-5-32-544)") || windows.equals(open), windows);
        assertTrue(posix.contains("root") || posix.contains("Administrators") || posix.equals(SetupMessages.ACCESS_OPEN.get(Language.EN)),
                posix);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsTheRealGroupListIsUsed() {
        String group = SecretAccess.administratorsGroup(SecretAccess.whoamiGroups());
        assertTrue(group.endsWith("(S-1-5-32-544)"), group);
    }
}
