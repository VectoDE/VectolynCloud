package net.vectolyn.cloud.download.provider;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.vectolyn.cloud.download.MalformedResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict accessors for API JSON. Every shape error ends as {@link MalformedResponseException}
 * with the source and the field in the message, never as a runtime exception.
 */
final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    static JsonNode parse(byte[] body, String source) throws MalformedResponseException {
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw new MalformedResponseException(source + ": invalid JSON (" + e.getOriginalMessage() + ")", e);
        }
        if (node == null || node.isMissingNode() || node.isNull()) {
            throw new MalformedResponseException(source + ": empty response");
        }
        return node;
    }

    static JsonNode object(JsonNode node, String context) throws MalformedResponseException {
        if (node == null || !node.isObject()) {
            throw new MalformedResponseException(context + ": expected an object");
        }
        return node;
    }

    static JsonNode object(JsonNode parent, String field, String context) throws MalformedResponseException {
        return object(parent.get(field), context + "." + field);
    }

    static List<JsonNode> array(JsonNode node, String context) throws MalformedResponseException {
        if (node == null || !node.isArray()) {
            throw new MalformedResponseException(context + ": expected an array");
        }
        List<JsonNode> items = new ArrayList<>(node.size());
        for (JsonNode item : node.values()) {
            items.add(item);
        }
        return items;
    }

    static List<JsonNode> array(JsonNode parent, String field, String context) throws MalformedResponseException {
        return array(parent.get(field), context + "." + field);
    }

    static String string(JsonNode parent, String field, String context) throws MalformedResponseException {
        JsonNode value = parent.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new MalformedResponseException(context + "." + field + ": expected a non-empty string");
        }
        return value.stringValue();
    }

    static String string(JsonNode value, String context) throws MalformedResponseException {
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new MalformedResponseException(context + ": expected a non-empty string");
        }
        return value.stringValue();
    }

    static Optional<String> optString(JsonNode parent, String field, String context) throws MalformedResponseException {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isString()) {
            throw new MalformedResponseException(context + "." + field + ": expected a string");
        }
        return value.stringValue().isBlank() ? Optional.empty() : Optional.of(value.stringValue());
    }

    static long integer(JsonNode value, String context) throws MalformedResponseException {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new MalformedResponseException(context + ": expected an integer");
        }
        return value.longValue();
    }

    static long integer(JsonNode parent, String field, String context) throws MalformedResponseException {
        return integer(parent.get(field), context + "." + field);
    }

    static int smallInt(JsonNode value, String context) throws MalformedResponseException {
        long number = integer(value, context);
        if (number < 0 || number > Integer.MAX_VALUE) {
            throw new MalformedResponseException(context + ": out of range");
        }
        return (int) number;
    }

    static OptionalInt optSmallInt(JsonNode parent, String field, String context) throws MalformedResponseException {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(smallInt(value, context + "." + field));
    }

    static boolean bool(JsonNode parent, String field, boolean fallback, String context)
            throws MalformedResponseException {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw new MalformedResponseException(context + "." + field + ": expected true or false");
        }
        return value.booleanValue();
    }

    static URI url(String raw, String context) throws MalformedResponseException {
        try {
            URI uri = new URI(raw);
            if (!uri.isAbsolute() || uri.getHost() == null) {
                throw new MalformedResponseException(context + ": not an absolute URL");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new MalformedResponseException(context + ": malformed URL", e);
        }
    }
}
