package net.vectolyn.cloud.config.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.vectolyn.cloud.config.Samples;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.model.CloudConfig;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import net.vectolyn.cloud.config.model.GroupConfig;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.config.model.NodesConfig;
import net.vectolyn.cloud.config.model.TaskConfig;
import net.vectolyn.cloud.config.validation.ConfigValidator;

/**
 * Fuzz target for loading and validating a configuration file. The first byte picks the file kind
 * (modulo 4: cloud, group, task, nodes), the rest is the file content. Stored inputs therefore
 * start with {@code 0}, {@code 1}, {@code 2} or {@code 3}.
 */
class ConfigYamlFuzzTest {

    private static final List<ConfigFormat<?>> FORMATS =
            List.of(ConfigFormat.CLOUD, ConfigFormat.GROUP, ConfigFormat.TASK, ConfigFormat.NODES);

    @FuzzTest
    void loadAndValidateFailOnlyWithConfigErrors(byte @NotNull [] input) {
        if (input.length == 0) {
            return;
        }
        ConfigFormat<?> format = FORMATS.get(Math.floorMod(input[0], FORMATS.size()));
        byte[] content = Arrays.copyOfRange(input, 1, input.length);
        Object value;
        try {
            value = ConfigYaml.read("fuzz.yml", content, format).value();
        } catch (ConfigException expected) {
            for (ConfigError error : expected.errors()) {
                assertFalse(error.describe(Language.DE).isEmpty());
                assertFalse(error.describe(Language.EN).isEmpty());
            }
            return;
        }
        // Whatever loads must survive validation without exceptions ...
        List<ConfigError> errors = ConfigValidator.validate(snapshotWith(value));
        errors.forEach(error -> assertFalse(error.describe(Language.EN).isEmpty()));
        // ... and be written back to an equal value.
        try {
            assertEquals(value, ConfigYaml.read("fuzz.yml", ConfigYaml.write(value), format).value());
        } catch (ConfigException e) {
            throw new AssertionError("written file does not load again: " + e.getMessage(), e);
        }
    }

    private static ConfigSnapshot snapshotWith(Object value) {
        ConfigSnapshot base = Samples.snapshot();
        return switch (value) {
            case CloudConfig cloud -> base.withCloud(cloud);
            case NodesConfig nodes -> base.withNodes(nodes);
            case GroupConfig group -> base.withGroup("fuzz", group);
            case TaskConfig task -> base.withTask("fuzz", task);
            default -> ConfigSnapshot.of(base.cloud(), base.nodes(), Map.of(), Map.of());
        };
    }
}
