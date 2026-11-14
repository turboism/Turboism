package dev.turboism.plugin.mcp;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Schema draft 2020-12 construction DSL shared by every MCP tool and resource definition.
 *
 * <p>The schema fragments are the wire contract advertised to clients, so the shape and the key set
 * of every fragment stay fixed: callers compose them, and no caller rewrites them. Object schemas
 * are closed — {@code required} lists every declared property and {@code additionalProperties} is
 * {@code false} — because the tool input preflight and the output validator both reject unknown
 * members.</p>
 */
final class McpSchemas {

    private McpSchemas() {}

    /** Closed object schema whose {@code required} list mirrors the declaration order of {@code fields}. */
    static Map<String, Object> object(final Map<String, ?> fields) {
        return Map.of(
                "type",
                "object",
                "properties",
                fields,
                "required",
                List.copyOf(fields.keySet()),
                "additionalProperties",
                false);
    }

    /** Closed object schema with an explicit {@code required} subset of {@code properties}. */
    static Map<String, Object> object(final Map<String, Object> properties, final List<String> required) {
        return linked(
                entry("type", "object"),
                entry("properties", properties),
                entry("required", required),
                entry("additionalProperties", false));
    }

    /** Array schema without item-count bounds. */
    static Map<String, Object> array(final Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    /** Array schema bounded by {@code minItems} and {@code maxItems}. */
    static Map<String, Object> array(final Object items, final int minimumItems, final int maximumItems) {
        return Map.of("type", "array", "items", items, "minItems", minimumItems, "maxItems", maximumItems);
    }

    /** Array schema bounded by {@code maxItems} only. */
    static Map<String, Object> boundedArray(final Map<String, Object> items, final int maximumItems) {
        return Map.of("type", "array", "items", items, "maxItems", maximumItems);
    }

    /** Array schema that requires at least one item and is bounded by {@code maxItems}. */
    static Map<String, Object> nonEmptyBoundedArray(final Map<String, Object> items, final int maximumItems) {
        return Map.of("type", "array", "minItems", 1, "maxItems", maximumItems, "items", items);
    }

    /** Non-blank string schema bounded by {@code maxLength}. */
    static Map<String, Object> text(final int maximumLength) {
        return Map.of("type", "string", "minLength", 1, "maxLength", maximumLength);
    }

    /** String schema with an explicit length range. */
    static Map<String, Object> text(final int minimumLength, final int maximumLength) {
        return Map.of("type", "string", "minLength", minimumLength, "maxLength", maximumLength);
    }

    /** Non-blank string schema for the 4096-character identifier budget. */
    static Map<String, Object> id() {
        return text(4096);
    }

    /** Non-blank string schema for the 128-character short identifier budget. */
    static Map<String, Object> shortId() {
        return text(128);
    }

    /** Unbounded string schema. */
    static Map<String, Object> stringSchema() {
        return Map.of("type", "string");
    }

    /** Non-blank string schema without an upper bound. */
    static Map<String, Object> nonEmptyString() {
        return Map.of("type", "string", "minLength", 1);
    }

    /** String schema that also admits JSON {@code null}. */
    static Map<String, Object> nullableString() {
        return Map.of("type", List.of("string", "null"));
    }

    /** Non-blank 4096-character string schema that also admits JSON {@code null}. */
    static Map<String, Object> nullableText() {
        return Map.of("type", List.of("string", "null"), "minLength", 1, "maxLength", 4096);
    }

    /** Boolean schema. */
    static Map<String, Object> booleanSchema() {
        return Map.of("type", "boolean");
    }

    /** Number schema. */
    static Map<String, Object> numberSchema() {
        return Map.of("type", "number");
    }

    /** Non-negative integer schema. */
    static Map<String, Object> nonNegativeInteger() {
        return Map.of("type", "integer", "minimum", 0);
    }

    /** Non-negative integer schema that also admits JSON {@code null}. */
    static Map<String, Object> nullableNonNegativeInteger() {
        return Map.of("type", List.of("integer", "null"), "minimum", 0);
    }

    /** Integer schema with an inclusive range. */
    static Map<String, Object> integer(final long minimum, final long maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }

    /** Schema pinning the single JSON value {@code value}. */
    static Map<String, Object> constant(final Object value) {
        return Map.of("const", value);
    }

    /** String schema restricted to {@code values}. */
    static Map<String, Object> enumSchema(final List<String> values) {
        return Map.of("type", "string", "enum", values);
    }

    /** Schema admitting {@code schema} or JSON {@code null}. */
    static Map<String, Object> nullable(final Map<String, Object> schema) {
        return Map.of("oneOf", List.of(schema, Map.of("type", "null")));
    }

    /** Schema admitting exactly one of {@code alternatives}. */
    @SafeVarargs
    static Map<String, Object> oneOf(final Map<String, Object>... alternatives) {
        return Map.of("oneOf", List.of(alternatives));
    }

    /** Ordered property map built from {@code entries}. */
    @SafeVarargs
    static Map<String, Object> properties(final Map.Entry<String, Object>... entries) {
        return linked(entries);
    }

    /** Ordered map built from {@code entries}; declaration order is the wire order. */
    @SafeVarargs
    static LinkedHashMap<String, Object> linked(final Map.Entry<String, Object>... entries) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) result.put(entry.getKey(), entry.getValue());
        return result;
    }

    /** Ordered, unmodifiable map built from {@code entries}. */
    @SafeVarargs
    static Map<String, Object> immutableMap(final Map.Entry<String, Object>... entries) {
        return Collections.unmodifiableMap(linked(entries));
    }

    /** Map entry for {@link #properties} and {@link #linked}. */
    static Map.Entry<String, Object> entry(final String key, final Object value) {
        return new AbstractMap.SimpleImmutableEntry<>(key, value);
    }
}
