package net.vectolyn.cloud.config.io;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serial;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.cfg.MapperBuilder;
import tools.jackson.databind.type.LogicalType;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;
import tools.jackson.dataformat.yaml.util.StringQuotingChecker;

/**
 * Jackson setup shared by the YAML files and, later, the JSON API, so both read the model with the
 * same names and the same strictness.
 *
 * <p>Strict on purpose, because people edit these files by hand:
 * <ul>
 *   <li>unknown fields fail (a typo must not be ignored silently),</li>
 *   <li>duplicate keys fail,</li>
 *   <li>no guessing between types: {@code port: "25565"}, {@code port: 1.5}, {@code eula-accepted: 1}
 *       and {@code minecraft-version: 1.20} (a number, would become "1.2") all fail,</li>
 *   <li>trailing content after the document fails,</li>
 *   <li>nesting depth, string length and token count are limited.</li>
 * </ul>
 * Field names are kebab-case ({@code network-name}), enum values lower case.
 */
public final class ConfigMappers {

    /** Upper limit for one configuration file, 1 MiB. */
    public static final int MAX_FILE_BYTES = 1024 * 1024;

    private static final YAMLMapper YAML = configure(YAMLMapper.builder(yamlFactory())).build();

    private ConfigMappers() {
    }

    /** The shared YAML mapper. Thread-safe. */
    public static YAMLMapper yaml() {
        return YAML;
    }

    /**
     * Applies naming, strictness and inclusion rules to any Jackson mapper builder, for example
     * {@code ConfigMappers.configure(JsonMapper.builder()).build()} for the API.
     */
    public static <M extends ObjectMapper, B extends MapperBuilder<M, B>> B configure(B builder) {
        return builder
                .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                        DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY,
                        DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,
                        MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES,
                        MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
                .withCoercionConfig(LogicalType.Textual, c -> c
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Integer, c -> c
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Boolean, c -> c
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Enum, c -> c
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.POJO, c -> c
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail));
    }

    private static YAMLFactory yamlFactory() {
        return YAMLFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(32)
                        .maxStringLength(64 * 1024)
                        .maxNameLength(256)
                        .maxNumberLength(32)
                        .maxDocumentLength(MAX_FILE_BYTES)
                        .maxTokenCount(200_000)
                        .build())
                .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
                .disable(YAMLWriteFeature.SPLIT_LINES)
                .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
                .enable(YAMLWriteFeature.INDENT_ARRAYS_WITH_INDICATOR)
                .stringQuotingChecker(new NumberQuotingChecker())
                .build();
    }

    /**
     * Quotes text that YAML would read back as a number, such as the Minecraft version {@code 26.3}.
     * Without this, minimized quoting writes {@code 26.3} bare and the strict reader rejects it.
     */
    private static final class NumberQuotingChecker extends StringQuotingChecker.Default {

        @Serial
        private static final long serialVersionUID = 1L;

        private static final Pattern NUMBER_LIKE = Pattern.compile("[-+.]?[0-9][0-9a-zA-Z._:+-]*|\\.(inf|nan|Inf|NaN|INF|NAN)");

        @Override
        public boolean needToQuoteValue(String value) {
            return super.needToQuoteValue(value) || value.isEmpty() || NUMBER_LIKE.matcher(value).matches();
        }
    }
}
