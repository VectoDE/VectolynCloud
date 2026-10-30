package net.vectolyn.cloud.api.http;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.vectolyn.cloud.config.error.ConfigError;
import net.vectolyn.cloud.config.error.ConfigException;
import net.vectolyn.cloud.config.io.ConfigFormat;
import net.vectolyn.cloud.config.io.ConfigMappers;
import net.vectolyn.cloud.config.io.ConfigYaml;
import net.vectolyn.cloud.config.model.Language;
import net.vectolyn.cloud.config.validation.ConfigNames;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON of the API. The mapper is {@link ConfigMappers#configure} on JSON, so request bodies are
 * read exactly as strictly as the YAML files: unknown fields, duplicate keys, wrong types
 * ({@code "port": "25565"}), trailing content and deep nesting all fail. Field names are
 * kebab-case, enum values lower case, like in the files.
 */
public final class ApiJson {

    /** Largest nesting depth of a request body. */
    public static final int MAX_DEPTH = 32;

    private static final JsonMapper MAPPER = ConfigMappers.configure(JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(MAX_DEPTH)
                            .maxStringLength(64 * 1024)
                            .maxNameLength(256)
                            .maxNumberLength(32)
                            .maxDocumentLength(ConfigMappers.MAX_FILE_BYTES)
                            .maxTokenCount(100_000)
                            .build())
                    .build()))
            .build();

    private ApiJson() {
    }

    /**
     * The shared mapper. Thread-safe.
     *
     * @return the mapper
     */
    public static JsonMapper mapper() {
        return MAPPER;
    }

    /**
     * Reads a request body.
     *
     * @param body the bytes, UTF-8
     * @param type the record to read
     * @param <T>  the type
     * @return the value, never null
     * @throws ApiException 400 {@code unknown-field}, {@code invalid-json} or {@code body-required};
     *                      the message names the field path, never a value
     */
    public static <T> T read(byte[] body, Class<T> type) throws ApiException {
        if (body == null || body.length == 0) {
            throw ApiException.badRequest("body-required", "A JSON body is required.");
        }
        T value;
        try {
            value = MAPPER.readValue(body, type);
        } catch (UnrecognizedPropertyException e) {
            throw ApiException.badRequest("unknown-field", "Unknown field \""
                    + ConfigNames.safeArgument(e.getPropertyName()) + "\" at " + path(e) + ".");
        } catch (DatabindException e) {
            throw ApiException.badRequest("invalid-json", "Invalid value at " + path(e) + ".");
        } catch (JacksonException e) {
            throw ApiException.badRequest("invalid-json", "The body is not valid JSON or breaks a limit.");
        } catch (RuntimeException e) {
            // a record constructor refused a value (for example null inside a list)
            throw ApiException.badRequest("invalid-json", "Invalid value in the body.");
        }
        if (value == null) {
            throw ApiException.badRequest("body-required", "A JSON body is required.");
        }
        return value;
    }

    /**
     * Reads a request body that is a whole configuration document, through the same reader as the
     * files ({@link ConfigYaml#read(String, byte[], ConfigFormat, tools.jackson.databind.ObjectMapper)}):
     * a body of an older {@code schema-version} is migrated like a file, and a missing required
     * field is reported as {@code required} at its path.
     *
     * @param body   the bytes, UTF-8
     * @param file   the file the document goes to, for the errors, for example {@code tasks/lobby.yml}
     * @param format the document type
     * @param <T>    the type
     * @return the value in the current format, never null
     * @throws ApiException 400 {@code body-required}, {@code invalid-json} (also for broken JSON) or
     *                      {@code unknown-field}, with {@code errors} for problems of the document
     */
    public static <T> T readDocument(byte[] body, String file, ConfigFormat<T> format) throws ApiException {
        // First the plain JSON rules, so broken JSON keeps the answers of every other endpoint.
        read(body, JsonNode.class);
        try {
            return ConfigYaml.read(file, body, format, MAPPER).value();
        } catch (ConfigException e) {
            throw ApiException.invalidBody(e.errors());
        }
    }

    /**
     * Writes a value.
     *
     * @param value any value of the model
     * @return UTF-8 JSON
     */
    public static byte[] write(Object value) {
        return MAPPER.writeValueAsBytes(value);
    }

    /**
     * Strong ETag of a document: the first 16 bytes of the SHA-256 of its JSON, quoted.
     *
     * @param json the JSON of the document
     * @return the ETag, for example {@code "3f2a..."}
     */
    public static String etag(byte[] json) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(json);
            return "\"" + HexFormat.of().formatHex(hash, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }

    /**
     * The JSON of an error answer.
     *
     * @param error the error
     * @return the body
     */
    public static byte[] error(ApiException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.code());
        body.put("message", error.getMessage());
        if (!error.errors().isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (ConfigError e : error.errors()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("file", e.file());
                item.put("path", e.path());
                if (e.line() > 0) {
                    item.put("line", e.line());
                }
                item.put("code", e.code().code());
                item.put("message", e.message(Language.EN));
                item.put("message-de", e.message(Language.DE));
                list.add(item);
            }
            body.put("errors", list);
        }
        return write(body);
    }

    private static String path(DatabindException e) {
        StringBuilder out = new StringBuilder();
        for (var ref : e.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!out.isEmpty()) {
                    out.append('.');
                }
                out.append(ConfigNames.safeArgument(ref.getPropertyName()));
            } else if (ref.getIndex() >= 0) {
                out.append('[').append(ref.getIndex()).append(']');
            }
        }
        return out.isEmpty() ? "the top level" : "\"" + out + "\"";
    }
}
