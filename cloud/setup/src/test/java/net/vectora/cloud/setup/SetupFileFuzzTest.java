package net.vectora.cloud.setup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.Language;

/**
 * Fuzz targets for the setup file and the password file: whatever the bytes, the only outcomes are
 * answers, a {@link ConfigException} or a {@link SetupException} with readable problems. Regression
 * mode in a normal test run; the nightly workflow fuzzes on Linux.
 */
class SetupFileFuzzTest {

    private static final SetupEnvironment ENV = new SetupEnvironment(Language.EN, false, Optional.empty(), false,
            false, false, true, OptionalLong.empty(), OptionalInt.empty(), OptionalLong.empty());

    @FuzzTest
    void setupFileFailsOnlyWithReadableProblems(byte @NotNull [] input) {
        SetupFile file;
        try {
            file = SetupFile.parse(input);
        } catch (ConfigException expected) {
            assertFalse(expected.describe(Language.EN).isBlank());
            return;
        }
        ConfigSnapshot empty = ConfigSnapshot.defaults();
        SetupAnswers defaults = SetupPlan.defaults(empty, ENV);
        SetupAnswers answers;
        try {
            answers = file.toAnswers(defaults, name -> new PasswordFile.Result(null, SetupMessages.FILE_PASSWORD_UNREADABLE));
        } catch (SetupException expected) {
            assertFalse(expected.problems().isEmpty());
            for (Language language : Language.values()) {
                assertFalse(expected.describe(language).isBlank());
            }
            return;
        }
        assertNotNull(answers);
        assertTrue(answers.eulaAccepted(), "answers only come back with the EULA accepted");
        for (var problem : SetupChecks.check(empty, answers, ENV, false)) {
            assertFalse(problem.get(Language.EN).isBlank());
        }
    }

    @FuzzTest
    void passwordFileGivesASecretOrAProblem(byte @NotNull [] input) {
        PasswordFile.Result result = PasswordFile.parse(input);
        if (result.secret() != null) {
            String value = result.secret().reveal();
            assertFalse(value.isEmpty());
            assertTrue(value.indexOf('\n') < 0 && value.indexOf('\r') < 0, "one line");
            assertFalse(result.toString().contains(value) && value.length() > 3, "toString never shows it");
        }
    }
}
