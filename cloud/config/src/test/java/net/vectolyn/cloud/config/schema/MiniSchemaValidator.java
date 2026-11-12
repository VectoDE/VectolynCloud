// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.config.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * A JSON Schema validator for exactly the keywords {@link ConfigSchema} writes, for the tests. It
 * refuses a schema with any other keyword, so the generator cannot start using one this validator
 * would silently ignore.
 */
final class MiniSchemaValidator {

    private static final Set<String> KEYWORDS = Set.of("$schema", "$id", "$defs", "$ref", "title", "description",
            "type", "properties", "required", "additionalProperties", "items", "enum", "const", "pattern", "minimum",
            "maximum", "anyOf");

    private final JsonNode root;

    MiniSchemaValidator(JsonNode root) {
        this.root = root;
        checkKeywords(root, "");
    }

    /** Every problem of {@code value}, as "path: problem"; empty if it is valid. */
    List<String> validate(JsonNode value) {
        List<String> errors = new ArrayList<>();
        validate(root, value, "", errors);
        return errors;
    }

    private void validate(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asString();
            if (!ref.startsWith("#/$defs/")) {
                throw new IllegalStateException("unsupported $ref " + ref);
            }
            validate(root.get("$defs").get(ref.substring("#/$defs/".length())), value, path, errors);
            return;
        }
        if (schema.has("anyOf")) {
            for (JsonNode option : schema.get("anyOf")) {
                List<String> inner = new ArrayList<>();
                validate(option, value, path, inner);
                if (inner.isEmpty()) {
                    return;
                }
            }
            errors.add(path + ": matches no option");
            return;
        }
        if (schema.has("const") && !schema.get("const").equals(value)) {
            errors.add(path + ": must be " + schema.get("const"));
            return;
        }
        if (schema.has("type") && !hasType(value, schema.get("type").asString())) {
            errors.add(path + ": must be of type " + schema.get("type").asString());
            return;
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.get("enum")) {
                found |= option.equals(value);
            }
            if (!found) {
                errors.add(path + ": not one of " + schema.get("enum"));
            }
        }
        if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").asString()).matcher(value.asString()).find()) {
            errors.add(path + ": does not match " + schema.get("pattern").asString());
        }
        if (schema.has("minimum") && value.isNumber() && value.asLong() < schema.get("minimum").asLong()) {
            errors.add(path + ": below " + schema.get("minimum"));
        }
        if (schema.has("maximum") && value.isNumber() && value.asLong() > schema.get("maximum").asLong()) {
            errors.add(path + ": above " + schema.get("maximum"));
        }
        if (value.isObject()) {
            JsonNode properties = schema.get("properties");
            if (schema.has("required")) {
                for (JsonNode name : schema.get("required")) {
                    if (!value.has(name.asString())) {
                        errors.add(path + "/" + name.asString() + ": required");
                    }
                }
            }
            for (Map.Entry<String, JsonNode> field : value.properties()) {
                JsonNode property = properties == null ? null : properties.get(field.getKey());
                if (property == null) {
                    if (schema.has("additionalProperties") && !schema.get("additionalProperties").asBoolean()) {
                        errors.add(path + "/" + field.getKey() + ": not allowed");
                    }
                    continue;
                }
                validate(property, field.getValue(), path + "/" + field.getKey(), errors);
            }
        }
        if (value.isArray() && schema.has("items")) {
            for (int i = 0; i < value.size(); i++) {
                validate(schema.get("items"), value.get(i), path + "/" + i, errors);
            }
        }
    }

    private static boolean hasType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isString();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            default -> throw new IllegalStateException("unsupported type " + type);
        };
    }

    private static void checkKeywords(JsonNode schema, String path) {
        for (Map.Entry<String, JsonNode> field : schema.properties()) {
            String key = field.getKey();
            if (!KEYWORDS.contains(key)) {
                throw new IllegalStateException("keyword " + key + " at " + path + " is not supported by the test validator");
            }
            JsonNode child = field.getValue();
            switch (key) {
                case "properties", "$defs" -> child.properties().forEach(e -> checkKeywords(e.getValue(), path + "/" + e.getKey()));
                case "items" -> checkKeywords(child, path + "/items");
                case "anyOf" -> child.forEach(option -> checkKeywords(option, path + "/anyOf"));
                default -> {
                    // a value, not a schema
                }
            }
        }
    }
}
