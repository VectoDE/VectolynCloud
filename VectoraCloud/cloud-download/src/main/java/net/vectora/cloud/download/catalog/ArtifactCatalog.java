package net.vectora.cloud.download.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The catalog of standard artifacts the cloud downloads. The data lives in
 * {@code net/vectora/cloud/download/catalog/catalog.yml} on the class path; this class only reads it.
 */
public final class ArtifactCatalog {

    /** Class-path location of the built-in catalog. */
    public static final String RESOURCE = "/net/vectora/cloud/download/catalog/catalog.yml";

    private static final Set<String> ENTRY_KEYS = Set.of("id", "kind", "type", "provider", "project", "platform",
            "game-version", "targets", "replaced-by", "alternative");

    private static final YAMLMapper MAPPER = YAMLMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final Map<String, CatalogEntry> entries;

    private ArtifactCatalog(Map<String, CatalogEntry> entries) {
        this.entries = entries;
    }

    /**
     * Loads the built-in catalog.
     *
     * @return the catalog
     * @throws IllegalStateException if the resource is missing or invalid (a build error)
     */
    public static ArtifactCatalog builtIn() {
        try (InputStream in = ArtifactCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the class path");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses catalog YAML.
     *
     * @param yaml the content
     * @return the catalog
     * @throws IOException if the content is invalid
     */
    public static ArtifactCatalog parse(String yaml) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(yaml);
        } catch (JacksonException e) {
            throw new IOException("catalog: invalid YAML (" + e.getOriginalMessage() + ")", e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("catalog: expected a mapping");
        }
        JsonNode schema = root.get("schema-version");
        if (schema == null || !schema.isIntegralNumber() || schema.intValue() != 1) {
            throw new IOException("catalog: schema-version must be 1");
        }
        JsonNode list = root.get("artifacts");
        if (list == null || !list.isArray()) {
            throw new IOException("catalog: artifacts must be a list");
        }
        Map<String, CatalogEntry> entries = new LinkedHashMap<>();
        for (JsonNode node : list.values()) {
            CatalogEntry entry = toEntry(node);
            if (entries.putIfAbsent(entry.id(), entry) != null) {
                throw new IOException("catalog: duplicate id " + entry.id());
            }
        }
        return new ArtifactCatalog(entries);
    }

    private static CatalogEntry toEntry(JsonNode node) throws IOException {
        if (node == null || !node.isObject()) {
            throw new IOException("catalog: every artifact must be a mapping");
        }
        String id = text(node, "id", "catalog entry", true);
        String context = "catalog entry " + id;
        for (String key : node.propertyNames()) {
            if (!ENTRY_KEYS.contains(key)) {
                throw new IOException(context + ": unknown key '" + key + "'");
            }
        }
        List<String> targets = new ArrayList<>();
        JsonNode targetNode = node.get("targets");
        if (targetNode != null && !targetNode.isNull()) {
            if (!targetNode.isArray()) {
                throw new IOException(context + ": targets must be a list");
            }
            for (JsonNode target : targetNode.values()) {
                if (!target.isString()) {
                    throw new IOException(context + ": targets must be strings");
                }
                targets.add(target.stringValue());
            }
        }
        try {
            return new CatalogEntry(id,
                    enumValue(CatalogEntry.Kind.class, text(node, "kind", context, true), context),
                    enumValue(CatalogEntry.Type.class, text(node, "type", context, true), context),
                    text(node, "provider", context, true),
                    text(node, "project", context, true),
                    text(node, "platform", context, false),
                    enumValue(CatalogEntry.GameVersionUse.class, text(node, "game-version", context, true), context),
                    targets,
                    text(node, "replaced-by", context, false),
                    text(node, "alternative", context, false));
        } catch (IllegalArgumentException e) {
            throw new IOException(context + ": " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode node, String field, String context, boolean required) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                throw new IOException(context + ": " + field + " is missing");
            }
            return null;
        }
        if (!value.isString() || value.stringValue().isBlank()) {
            throw new IOException(context + ": " + field + " must be a non-empty string");
        }
        return value.stringValue();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String context) throws IOException {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IOException(context + ": unknown value " + value, e);
        }
    }

    /**
     * Returns all entries in file order.
     *
     * @return the entries
     */
    public List<CatalogEntry> entries() {
        return List.copyOf(entries.values());
    }

    /**
     * Looks up an entry.
     *
     * @param id the id
     * @return the entry, or empty
     */
    public Optional<CatalogEntry> get(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    /**
     * Returns the transitional entries, the ones to remove once the Vectora replacements exist.
     *
     * @return the transitional entries
     */
    public List<CatalogEntry> transitional() {
        return entries.values().stream().filter(CatalogEntry::transitional).toList();
    }
}
