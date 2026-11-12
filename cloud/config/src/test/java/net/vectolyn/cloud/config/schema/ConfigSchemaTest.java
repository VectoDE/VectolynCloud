// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.ConfigService;
import net.vectolyn.cloud.config.Samples;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.io.ConfigFormat;
import net.vectolyn.cloud.config.io.ConfigMappers;
import net.vectolyn.cloud.config.io.ConfigStore;
import net.vectolyn.cloud.config.io.ConfigYaml;
import net.vectolyn.cloud.config.model.ConfigSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON Schemas against the model, the shipped copies, the examples of docs/cloud/config.md and
 * the strict reader.
 */
class ConfigSchemaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, ConfigFormat<?>> FORMATS = Map.of(
            "cloud.schema.json", ConfigFormat.CLOUD,
            "nodes.schema.json", ConfigFormat.NODES,
            "group.schema.json", ConfigFormat.GROUP,
            "task.schema.json", ConfigFormat.TASK);

    @TempDir
    Path temp;

    @Test
    void shippedCopiesMatchTheModel() throws IOException {
        Path dir = Path.of(System.getProperty("vectolyn.schemaDir"));
        Map<String, byte[]> generated = ConfigSchema.all();
        if (Boolean.getBoolean("vectolyn.updateSchemas")) {
            Files.createDirectories(dir);
            for (Map.Entry<String, byte[]> entry : generated.entrySet()) {
                Files.write(dir.resolve(entry.getKey()), entry.getValue());
            }
        }
        Set<String> shipped = new TreeSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(file -> shipped.add(file.getFileName().toString()));
        }
        assertEquals(new TreeSet<>(generated.keySet()), shipped, "files in cloud/dist/schema/");
        for (Map.Entry<String, byte[]> entry : generated.entrySet()) {
            String expected = new String(entry.getValue(), StandardCharsets.UTF_8);
            String actual = Files.readString(dir.resolve(entry.getKey()), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals(expected, actual, "cloud/dist/schema/" + entry.getKey()
                    + " is out of date; run ./gradlew :cloud:config:test -PupdateSchemas");
        }
    }

    @Test
    void schemasAreValidJsonWithTheDialect() {
        ConfigSchema.all().forEach((name, content) -> {
            JsonNode schema = JSON.readTree(content);
            assertEquals(ConfigSchema.DIALECT, schema.get("$schema").asString());
            assertEquals(ConfigSchema.ID_BASE + name, schema.get("$id").asString());
            assertFalse(schema.get("additionalProperties").asBoolean());
            new MiniSchemaValidator(schema); // only known keywords
        });
    }

    @Test
    void samplesAndDefaultsValidate() {
        List<ConfigSnapshot> snapshots = List.of(Samples.snapshot(), ConfigSnapshot.defaults());
        for (ConfigSnapshot snapshot : snapshots) {
            ConfigStore.files(snapshot).forEach((file, value) -> {
                JsonNode tree = ConfigMappers.yaml().valueToTree(value);
                List<String> errors = validator(ConfigSchema.schemaFile(file)).validate(tree);
                assertTrue(errors.isEmpty(), file + ": " + errors);
            });
        }
    }

    @Test
    void docExamplesValidateAndLoad() throws Exception {
        Map<String, String> examples = docExamples();
        assertEquals(Set.of("cloud.schema.json", "group.schema.json", "task.schema.json", "nodes.schema.json"),
                examples.keySet(), "a yaml example per file in docs/cloud/config.md");
        for (Map.Entry<String, String> example : examples.entrySet()) {
            byte[] yaml = example.getValue().getBytes(StandardCharsets.UTF_8);
            JsonNode tree = ConfigMappers.yaml().readTree(yaml);
            List<String> errors = validator(example.getKey()).validate(tree);
            assertTrue(errors.isEmpty(), example.getKey() + ": " + errors);
            ConfigYaml.read("example.yml", yaml, FORMATS.get(example.getKey()));
        }
    }

    /**
     * For every field of the doc examples (which set nearly every field): without it, the strict
     * reader fails exactly when the schema lists it as required.
     */
    @Test
    void requiredFieldsMatchTheReader() throws Exception {
        int checked = 0;
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, String> example : docExamples().entrySet()) {
            ObjectNode tree = (ObjectNode) ConfigMappers.yaml().readTree(example.getValue());
            MiniSchemaValidator validator = validator(example.getKey());
            ConfigFormat<?> format = FORMATS.get(example.getKey());
            for (List<Object> path : objectFieldPaths(tree, new ArrayList<>())) {
                ObjectNode copy = tree.deepCopy();
                remove(copy, path);
                boolean schemaRefuses = !validator.validate(copy).isEmpty();
                boolean readerRefuses;
                try {
                    ConfigYaml.read("example.yml", ConfigMappers.yaml().writeValueAsBytes(copy), format);
                    readerRefuses = false;
                } catch (ConfigException e) {
                    readerRefuses = true;
                }
                if (readerRefuses != schemaRefuses) {
                    mismatches.add(example.getKey() + " without " + path + ": reader refuses " + readerRefuses);
                }
                checked++;
            }
        }
        assertEquals(List.of(), mismatches);
        assertTrue(checked > 60, "fields checked: " + checked);
    }

    @Test
    void wrongValuesAreRefusedByBoth() throws Exception {
        String cloud = docExamples().get("cloud.schema.json");
        Map<String, String> wrong = new LinkedHashMap<>();
        wrong.put("unknown field", cloud.replace("network-name:", "network-nam:"));
        wrong.put("enum value", cloud.replace("language: en", "language: fr"));
        wrong.put("type", cloud.replace("port: 25565", "port: \"25565\""));
        wrong.put("boolean", cloud.replace("eula-accepted: false", "eula-accepted: 1"));
        wrong.put("schema-version", cloud.replace("schema-version: 1", "schema-version: 9"));
        MiniSchemaValidator validator = validator("cloud.schema.json");
        for (Map.Entry<String, String> entry : wrong.entrySet()) {
            byte[] yaml = entry.getValue().getBytes(StandardCharsets.UTF_8);
            assertFalse(validator.validate(ConfigMappers.yaml().readTree(yaml)).isEmpty(), entry.getKey());
            assertThrows(ConfigException.class, () -> ConfigYaml.read("cloud.yml", yaml, ConfigFormat.CLOUD), entry.getKey());
        }
        String group = docExamples().get("group.schema.json");
        MiniSchemaValidator groupValidator = validator("group.schema.json");
        for (String bad : List.of(group.replace("min: 256M", "min: 256K"), group.replace("java: auto", "java: newest"),
                group.replace("drain-timeout: 30s", "drain-timeout: 30d"))) {
            byte[] yaml = bad.getBytes(StandardCharsets.UTF_8);
            assertFalse(groupValidator.validate(ConfigMappers.yaml().readTree(yaml)).isEmpty(), bad);
            assertThrows(ConfigException.class, () -> ConfigYaml.read("g.yml", yaml, ConfigFormat.GROUP));
        }
    }

    @Test
    void writtenFilesNameTheirSchemaAndTheHomeHasIt() throws Exception {
        assertEquals("schema/cloud.schema.json", ConfigSchema.reference("cloud.yml"));
        assertEquals("../schema/group.schema.json", ConfigSchema.reference("groups/lobby.yml"));
        assertEquals("../schema/task.schema.json", ConfigSchema.reference("tasks/lobby.yml"));
        assertNull(ConfigSchema.reference("monitor.yml"));

        CloudHome home = CloudHome.of(temp);
        try (ConfigService service = ConfigService.open(home)) {
            assertTrue(service.snapshot().nodes().nodes().size() == 1);
        }
        String cloud = Files.readString(home.cloudFile());
        assertTrue(cloud.startsWith(ConfigYaml.HEADER + ConfigYaml.SCHEMA_PREFIX + "schema/cloud.schema.json\n"), cloud);
        for (Map.Entry<String, byte[]> entry : ConfigSchema.all().entrySet()) {
            Path file = home.configDir().resolve(ConfigSchema.DIR).resolve(entry.getKey());
            assertTrue(java.util.Arrays.equals(entry.getValue(), Files.readAllBytes(file)), entry.getKey());
        }
        // Read back unchanged: the comment line does not disturb the reader.
        ConfigYaml.read("cloud.yml", Files.readAllBytes(home.cloudFile()), ConfigFormat.CLOUD);
    }

    @Test
    void kebabCaseMatchesJackson() {
        assertEquals("schema-version", ConfigSchema.kebab("schemaVersion"));
        assertEquals("players-per-service", ConfigSchema.kebab("playersPerService"));
        assertEquals("enabled", ConfigSchema.kebab("enabled"));
    }

    private static MiniSchemaValidator validator(String schemaFile) {
        return new MiniSchemaValidator(JSON.readTree(ConfigSchema.all().get(schemaFile)));
    }

    /** The YAML example below each file heading of docs/cloud/config.md, by schema file. */
    static Map<String, String> docExamples() throws IOException {
        String doc = Files.readString(Path.of(System.getProperty("vectolyn.configDoc")), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        Map<String, String> headings = Map.of("### `cloud.yml`", "cloud.schema.json",
                "### `groups/<name>.yml`", "group.schema.json",
                "### `tasks/<name>.yml`", "task.schema.json",
                "### `nodes.yml`", "nodes.schema.json");
        Map<String, String> examples = new LinkedHashMap<>();
        Matcher block = Pattern.compile("(?m)^### `[^`]+`$|^```yaml\n(.*?)^```$", Pattern.DOTALL).matcher(doc);
        String current = null;
        while (block.find()) {
            if (block.group().startsWith("###")) {
                current = headings.get(block.group());
            } else if (current != null && !examples.containsKey(current)) {
                examples.put(current, block.group(1));
            }
        }
        return examples;
    }

    /** Every path to a field inside an object, depth first; list indexes as Integer. */
    private static List<List<Object>> objectFieldPaths(JsonNode node, List<Object> prefix) {
        List<List<Object>> paths = new ArrayList<>();
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                List<Object> path = new ArrayList<>(prefix);
                path.add(field.getKey());
                paths.add(path);
                paths.addAll(objectFieldPaths(field.getValue(), path));
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                List<Object> path = new ArrayList<>(prefix);
                path.add(i);
                paths.addAll(objectFieldPaths(node.get(i), path));
            }
        }
        return paths;
    }

    private static void remove(JsonNode root, List<Object> path) {
        JsonNode parent = root;
        for (Object step : path.subList(0, path.size() - 1)) {
            parent = step instanceof Integer index ? ((ArrayNode) parent).get(index) : parent.get((String) step);
        }
        Object last = path.getLast();
        if (!(parent instanceof ObjectNode object)) {
            fail("not an object at " + path);
            return;
        }
        object.remove((String) last);
    }
}
