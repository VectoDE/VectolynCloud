package net.vectora.cloud.setup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.model.Language;
import net.vectora.cloud.console.Text;
import org.junit.jupiter.api.Test;

/** House rules for the texts: both languages filled, no line that opens with "No" or "Not". */
class SetupMessagesTest {

    static List<Text> allTexts() throws IllegalAccessException {
        List<Text> texts = new ArrayList<>();
        for (Field field : SetupMessages.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Text.class) {
                texts.add((Text) field.get(null));
            }
        }
        for (NetworkServer server : NetworkServer.values()) {
            texts.add(server.label());
            texts.add(server.shortName());
        }
        // The description of the setup command, as the console shows it in "help".
        texts.add(SetupWizard.command(null, null, null).description());
        // Problems of cloud-config that the setup passes on word for word.
        for (ErrorCode code : ErrorCode.values()) {
            ConfigError error = ConfigError.of("cloud.yml", "servers.lobby", code, "a", "b", "c", "d");
            texts.addAll(SetupException.of(new ConfigException(error)).problems());
        }
        return texts;
    }

    @Test
    void everyTextHasBothLanguagesAndNoNegativeOpening() throws Exception {
        List<Text> texts = allTexts();
        assertTrue(texts.size() > 80 + ErrorCode.values().length, "the reflection found the texts");
        assertTrue(texts.contains(SetupMessages.PORT_SERVICES) && texts.contains(SetupMessages.LANGUAGE_DE),
                "labels of the port hint and the language question are covered");
        for (Text text : texts) {
            for (Language language : Language.values()) {
                String value = text.get(language);
                assertFalse(value.isBlank(), text.toString());
                for (String line : value.split("\\R")) {
                    String word = line.strip().split("[\\s,.:;!?]", 2)[0].toLowerCase(Locale.ROOT);
                    assertFalse(word.equals("no") || word.equals("not") || word.equals("none"),
                            "opens with a negation: " + line);
                }
            }
        }
    }

    @Test
    void eulaIsLinkedInBothLanguages() {
        for (Language language : Language.values()) {
            String eula = SetupMessages.EULA.get(language) + SetupMessages.EULA_HELP.get(language);
            assertTrue(eula.contains(SetupMessages.EULA_URL), eula);
        }
    }
}
