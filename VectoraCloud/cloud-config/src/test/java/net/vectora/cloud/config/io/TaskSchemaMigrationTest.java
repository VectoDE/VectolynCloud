package net.vectora.cloud.config.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.vectora.cloud.config.Samples;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.model.CloudConfig;
import net.vectora.cloud.config.model.ConfigSnapshot;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.NodeConfig;
import net.vectora.cloud.config.model.NodesConfig;
import net.vectora.cloud.config.model.ScalingConfig;
import net.vectora.cloud.config.model.TaskConfig;
import net.vectora.cloud.config.model.TimeSpan;
import net.vectora.cloud.config.validation.ConfigValidator;
import org.junit.jupiter.api.Test;

/** Task files of schema-version 1 ({@code mode}) and 2 ({@code static-services}), and {@code max-memory}. */
class TaskSchemaMigrationTest {

    private static final String V1_STATIC = """
            schema-version: 1
            group: survival
            mode: static
            scaling:
              min-services: 2
              max-services: 5
              players-per-service: 50
              scale-up-at: 80
              scale-down-after: 5m
              cooldown: 60s
            """;

    private static final String V1_DYNAMIC = V1_STATIC.replace("mode: static", "mode: dynamic");

    private static final String V2 = """
            schema-version: 2
            group: lobby
            scaling:
              static-services: 1
              min-services: 1
              max-services: 4
              players-per-service: 50
              scale-up-at: 75
              scale-down-below: 40
              scale-down-after: 5m
              cooldown: 60s
            """;

    @Test
    void staticV1BecomesAFixedNumberOfStaticServices() throws ConfigException {
        ConfigYaml.Decoded<TaskConfig> decoded = read(V1_STATIC);
        assertEquals(1, decoded.fileVersion());
        TaskConfig task = decoded.value();
        assertEquals(TaskConfig.SCHEMA_VERSION, task.schemaVersion());
        assertEquals(2, task.scaling().staticServices());
        assertEquals(2, task.scaling().minServices());
        assertEquals(2, task.scaling().maxServices(), "a static task never scaled, so max follows min");
        assertFalse(task.scaling().allowsDynamic());
        assertEquals(80, task.scaling().scaleUpAt(), "values of the file stay");
        assertEquals(ScalingConfig.DEFAULT_SCALE_DOWN_BELOW, task.scaling().scaleDownBelow());
        assertEquals(List.of(), ConfigValidator.validate(withTask(task)));
    }

    @Test
    void dynamicV1BecomesZeroStaticServicesWithTheSameLimits() throws ConfigException {
        TaskConfig task = read(V1_DYNAMIC).value();
        assertEquals(0, task.scaling().staticServices());
        assertEquals(2, task.scaling().minServices());
        assertEquals(5, task.scaling().maxServices());
        assertTrue(task.scaling().allowsDynamic());
        assertEquals(List.of(), ConfigValidator.validate(withTask(task)));
    }

    @Test
    void migratedFileIsWrittenAsVersion2WithoutMode() throws ConfigException {
        TaskConfig task = read(V1_STATIC).value();
        String written = new String(ConfigYaml.write(task), StandardCharsets.UTF_8);
        assertTrue(written.contains("schema-version: 2"), written);
        assertTrue(written.contains("static-services: 2"), written);
        assertTrue(written.contains("scale-down-below: 50"), written);
        assertFalse(written.contains("mode"), written);
        assertEquals(task, read(written).value());
    }

    @Test
    void invalidModeInV1IsReportedAtTheField() {
        ConfigError error = single(V1_STATIC.replace("mode: static", "mode: elastic"));
        assertEquals(ErrorCode.INVALID_VALUE, error.code());
        assertEquals("mode", error.path());
        assertEquals(List.of("static or dynamic"), error.args());
        assertEquals(3, error.line());
        assertFalse(error.describe(net.vectora.cloud.config.model.Language.EN).contains("elastic"), "the value is not quoted");
        assertEquals(ErrorCode.INVALID_VALUE, single(V1_STATIC.replace("mode: static", "mode: 1")).code());
    }

    @Test
    void v1WithoutModeIsNotGuessed() {
        ConfigError error = single(V1_STATIC.replace("mode: static\n", ""));
        assertEquals(ErrorCode.REQUIRED, error.code(), error.toString());
        assertEquals("mode", error.path(), error.toString());
    }

    @Test
    void staticV1WithoutMinServicesIsReportedNotGuessed() {
        ConfigError error = single(V1_STATIC.replace("  min-services: 2\n", ""));
        assertTrue(error.path().startsWith("scaling."), error.toString());
    }

    @Test
    void modeIsUnknownInVersion2() {
        ConfigError error = single(V2.replace("group: lobby\n", "group: lobby\nmode: static\n"));
        assertEquals(ErrorCode.UNKNOWN_FIELD, error.code());
    }

    @Test
    void version2WithoutScaleDownBelowUsesTheDefault() throws ConfigException {
        TaskConfig task = read(V2.replace("  scale-down-below: 40\n", "")).value();
        assertEquals(ScalingConfig.DEFAULT_SCALE_DOWN_BELOW, task.scaling().scaleDownBelow());
        assertEquals(40, read(V2).value().scaling().scaleDownBelow(), "counter-check: a given value stays");
    }

    @Test
    void version2WithoutStaticServicesIsAnError() {
        ConfigError error = single(V2.replace("  static-services: 1\n", ""));
        // Same code as every other required field the model marks (process.restart.*).
        assertEquals(ErrorCode.INVALID_VALUE, error.code(), error.toString());
        assertEquals("scaling.static-services", error.path(), error.toString());
    }

    @Test
    void version2RejectsWrongTypes() {
        assertEquals("scaling.scale-down-below", single(V2.replace("scale-down-below: 40", "scale-down-below: \"40\"")).path());
        assertEquals("scaling.static-services", single(V2.replace("static-services: 1", "static-services: 1.5")).path());
    }

    @Test
    void version3IsNotSupportedYet() {
        assertEquals(ErrorCode.SCHEMA_VERSION_UNSUPPORTED, single(V2.replace("schema-version: 2", "schema-version: 3")).code());
    }

    @Test
    void defaultsUseTheNewThresholds() {
        ScalingConfig single = ScalingConfig.single();
        assertEquals(75, single.scaleUpAt());
        assertEquals(50, single.scaleDownBelow());
        assertEquals(1, single.staticServices());
        assertEquals(1, single.minServices());
        assertEquals(1, single.maxServices());
        assertEquals(new ScalingConfig(0, 1, 8, 50, 75, 50, TimeSpan.ofSeconds(300), TimeSpan.ofSeconds(60)),
                ScalingConfig.counts(0, 1, 8));
        assertEquals(50, new ScalingConfig(0, 1, 8, 50, 75, null, TimeSpan.ofSeconds(300), TimeSpan.ofSeconds(60))
                .scaleDownBelow(), "null becomes the default");
    }

    @Test
    void nodeMaxMemoryIsOptionalInNodesYml() throws ConfigException {
        String plain = new String(ConfigYaml.write(NodesConfig.defaults()), StandardCharsets.UTF_8);
        assertFalse(plain.contains("max-memory"), "automatic is not written: " + plain);
        NodesConfig limited = new NodesConfig(1, List.of(NodeConfig.defaults().withMaxMemory(MemorySize.parse("8G"))));
        byte[] written = ConfigYaml.write(limited);
        assertTrue(new String(written, StandardCharsets.UTF_8).contains("max-memory: \"8G\""),
                new String(written, StandardCharsets.UTF_8));
        assertEquals(limited, ConfigYaml.read("nodes.yml", written, ConfigFormat.NODES).value());
        assertEquals(null, ConfigYaml.read("nodes.yml", plain.getBytes(StandardCharsets.UTF_8), ConfigFormat.NODES)
                .value().nodes().getFirst().maxMemory());
        ConfigException bad = assertThrows(ConfigException.class, () -> ConfigYaml.read("nodes.yml",
                plain.replace("    service-ports:", "    max-memory: 8 GB\n    service-ports:").getBytes(StandardCharsets.UTF_8),
                ConfigFormat.NODES));
        assertEquals("nodes[0].max-memory", bad.errors().getFirst().path());
        assertEquals(List.of(MemorySize.FORMAT), bad.errors().getFirst().args());
    }

    private static ConfigSnapshot withTask(TaskConfig task) {
        return ConfigSnapshot.of(CloudConfig.defaults(), NodesConfig.defaults(),
                Map.of(task.group(), Samples.lobbyGroup()), Map.of("survival", task));
    }

    private static ConfigYaml.Decoded<TaskConfig> read(String yaml) throws ConfigException {
        return ConfigYaml.read("tasks/survival.yml", yaml.getBytes(StandardCharsets.UTF_8), ConfigFormat.TASK);
    }

    private static ConfigError single(String yaml) {
        ConfigException e = assertThrows(ConfigException.class, () -> read(yaml));
        assertEquals(1, e.errors().size(), e.getMessage());
        return e.errors().getFirst();
    }
}
