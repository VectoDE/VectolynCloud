package net.vectora.cloud.config.io;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import net.vectora.cloud.config.error.ConfigError;
import net.vectora.cloud.config.error.ConfigException;
import net.vectora.cloud.config.error.ErrorCode;
import net.vectora.cloud.config.model.JavaSelection;
import net.vectora.cloud.config.model.MemorySize;
import net.vectora.cloud.config.model.TimeSpan;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.exc.ValueInstantiationException;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes one configuration file. Every failure becomes a {@link ConfigException} with
 * file, field path, line and a code; the raw Jackson message is never passed on, because it can
 * quote the offending value.
 */
public final class ConfigYaml {

    /** First lines of every file the cloud writes. */
    public static final String HEADER = """
            # Written by VectoraCloud. You may edit this file while the cloud runs; it reloads it.
            # Comments are not kept when the cloud saves the file. Reference: docs/cloud/config.md
            """;

    private ConfigYaml() {
    }

    /**
     * A decoded file.
     *
     * @param value       the model
     * @param fileVersion {@code schema-version} found in the file, before migration
     * @param <T>         model type
     */
    public record Decoded<T>(T value, int fileVersion) {
    }

    /**
     * Parses, migrates and binds {@code content}.
     *
     * @param file    file name for error messages, relative to the cloud home
     * @param content raw bytes (UTF-8)
     * @param format  what the file holds
     * @throws ConfigException with exactly one error describing the first problem
     */
    public static <T> Decoded<T> read(String file, byte[] content, ConfigFormat<T> format) throws ConfigException {
        if (content.length > ConfigMappers.MAX_FILE_BYTES) {
            throw new ConfigException(ConfigError.of(file, "", ErrorCode.FILE_TOO_LARGE, ConfigMappers.MAX_FILE_BYTES));
        }
        JsonNode tree;
        try {
            tree = ConfigMappers.yaml().readTree(content);
        } catch (JacksonException e) {
            throw new ConfigException(toError(file, e, content));
        }
        if (tree == null || !tree.isObject()) {
            throw new ConfigException(ConfigError.of(file, "", ErrorCode.NOT_A_MAPPING));
        }
        int version = schemaVersion(file, tree, format);
        try {
            T value;
            if (version < format.currentVersion()) {
                ObjectNode root = (ObjectNode) tree;
                format.migrate(root, version);
                value = ConfigMappers.yaml().treeToValue(root, format.type());
            } else {
                // Bind from the text, not the tree, so errors keep their line numbers.
                value = ConfigMappers.yaml().readValue(content, format.type());
            }
            if (value == null) {
                throw new ConfigException(ConfigError.of(file, "", ErrorCode.NOT_A_MAPPING));
            }
            return new Decoded<>(value, version);
        } catch (JacksonException e) {
            throw new ConfigException(toError(file, e, content));
        } catch (RuntimeException e) {
            // A migration that trips over unexpected content.
            throw new ConfigException(ConfigError.of(file, "", ErrorCode.INVALID_VALUE, "content of schema-version " + version));
        }
    }

    /** Serializes a model object as UTF-8 YAML, with {@link #HEADER} in front. */
    public static byte[] write(Object value) {
        String body = ConfigMappers.yaml().writeValueAsString(value);
        return (HEADER + body).getBytes(StandardCharsets.UTF_8);
    }

    private static int schemaVersion(String file, JsonNode tree, ConfigFormat<?> format) throws ConfigException {
        JsonNode node = tree.get(ConfigFormat.VERSION_FIELD);
        if (node == null || node.isNull()) {
            throw new ConfigException(ConfigError.of(file, ConfigFormat.VERSION_FIELD, ErrorCode.SCHEMA_VERSION_MISSING));
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()
                || node.intValue() < 1 || node.intValue() > format.currentVersion()) {
            throw new ConfigException(ConfigError.of(file, ConfigFormat.VERSION_FIELD,
                    ErrorCode.SCHEMA_VERSION_UNSUPPORTED, format.currentVersion()));
        }
        return node.intValue();
    }

    /** Maps a Jackson failure to an error without quoting the value. */
    static ConfigError toError(String file, JacksonException e, byte[] content) {
        TokenStreamLocation location = e.getLocation();
        int line = location == null ? 0 : Math.max(0, location.getLineNr());
        String path = path(e.getPath());
        if (line == 0 && !e.getPath().isEmpty()) {
            // Records are bound through a token buffer, which loses positions; find the field again.
            line = locate(content, pointer(e.getPath()));
        }
        String original = e.getOriginalMessage() == null ? "" : e.getOriginalMessage();
        if (e instanceof UnrecognizedPropertyException unknown) {
            return ConfigError.at(file, path, line, ErrorCode.UNKNOWN_FIELD, unknown.getPropertyName());
        }
        if (original.toLowerCase(Locale.ROOT).contains("duplicate")) {
            return ConfigError.at(file, path, line, ErrorCode.DUPLICATE_FIELD);
        }
        if (e instanceof StreamReadException) {
            return ConfigError.at(file, path, line, ErrorCode.SYNTAX);
        }
        if (e instanceof MismatchedInputException mismatch) {
            return ConfigError.at(file, path, line, ErrorCode.INVALID_VALUE, expected(mismatch.getTargetType()));
        }
        if (e instanceof ValueInstantiationException instantiation) {
            JavaType type = instantiation.getType();
            return ConfigError.at(file, path, line, ErrorCode.INVALID_VALUE, expected(type == null ? null : type.getRawClass()));
        }
        return ConfigError.at(file, path, line, ErrorCode.INVALID_VALUE, "a valid value");
    }

    /** Dotted field path with list indexes, for example {@code catalog[1].kind}. */
    static String path(List<JacksonException.Reference> references) {
        StringBuilder out = new StringBuilder();
        for (JacksonException.Reference ref : references) {
            if (ref.getPropertyName() != null) {
                if (!out.isEmpty()) {
                    out.append('.');
                }
                out.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                out.append('[').append(ref.getIndex()).append(']');
            }
        }
        return out.toString();
    }

    /** JSON Pointer of a reference chain, for example {@code /catalog/1/kind}. */
    static String pointer(List<JacksonException.Reference> references) {
        StringBuilder out = new StringBuilder();
        for (JacksonException.Reference ref : references) {
            if (ref.getPropertyName() != null) {
                out.append('/').append(ref.getPropertyName().replace("~", "~0").replace("/", "~1"));
            } else if (ref.getIndex() >= 0) {
                out.append('/').append(ref.getIndex());
            }
        }
        return out.toString();
    }

    /** Line of the token at {@code pointer}, or 0 if it cannot be found. */
    static int locate(byte[] content, String pointer) {
        try (JsonParser parser = ConfigMappers.yaml().createParser(content)) {
            while (parser.nextToken() != null) {
                if (pointer.equals(parser.streamReadContext().pathAsPointer().toString())) {
                    return Math.max(0, parser.currentTokenLocation().getLineNr());
                }
            }
        } catch (JacksonException e) {
            // The content was readable a moment ago; give up on the line rather than fail.
        }
        return 0;
    }

    private static String expected(Class<?> type) {
        if (type == null) {
            return "a valid value";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "a whole number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "true or false";
        }
        if (type == String.class) {
            return "text (quote values like \"1.20\")";
        }
        if (type == MemorySize.class) {
            return MemorySize.FORMAT;
        }
        if (type == TimeSpan.class) {
            return TimeSpan.FORMAT;
        }
        if (type == JavaSelection.class) {
            return JavaSelection.FORMAT;
        }
        if (type.isEnum()) {
            StringBuilder values = new StringBuilder("one of ");
            Object[] constants = type.getEnumConstants();
            for (int i = 0; i < constants.length; i++) {
                values.append(i == 0 ? "" : ", ").append(((Enum<?>) constants[i]).name().toLowerCase(Locale.ROOT));
            }
            return values.toString();
        }
        if (List.class.isAssignableFrom(type)) {
            return "a list";
        }
        return "a mapping (key: value)";
    }
}
