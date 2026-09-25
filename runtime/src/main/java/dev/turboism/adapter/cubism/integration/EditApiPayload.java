package dev.turboism.adapter.cubism.integration;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Typed readers over a request's {@code Data} object.
 *
 * <p>Mirrors the official {@code PayloadValidator} behaviour: a required field that is absent
 * or wrongly typed fails the request with {@link EditApiErrorCode#INVALID_DATA}; optional
 * fields surface as {@link Optional} and are absent when the JSON member is missing or null.
 * Numeric fields accept any JSON number; a non-finite value (NaN/Infinity, reachable through
 * JSONIC-style literals) is rejected as invalid data.</p>
 */
final class EditApiPayload {

    private final JsonNode data;

    private EditApiPayload(final JsonNode data) {
        this.data = data;
    }

    /**
     * Wraps the {@code Data} member.
     *
     * @throws EditApiFailure {@code InvalidData} when {@code data} is present but not an object
     */
    static EditApiPayload of(final JsonNode data) throws EditApiFailure {
        if (data == null || data.isNull()) {
            return new EditApiPayload(null);
        }
        if (!data.isObject()) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
        return new EditApiPayload(data);
    }

    /** {@return whether {@code field} is present and not null} */
    boolean has(final String field) {
        return data != null && data.has(field) && !data.get(field).isNull();
    }

    /** {@return the required textual field} */
    String requiredString(final String field) throws EditApiFailure {
        final JsonNode node = node(field);
        if (node == null || !node.isTextual() || node.asText().isEmpty()) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
        return node.asText();
    }

    /** {@return the optional textual field; empty when absent, null, or blank} */
    Optional<String> optionalString(final String field) {
        final JsonNode node = node(field);
        if (node == null || !node.isTextual()) {
            return Optional.empty();
        }
        final String text = node.asText();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /** {@return the required numeric field as a finite double} */
    double requiredNumber(final String field) throws EditApiFailure {
        final JsonNode node = node(field);
        if (node == null || !node.isNumber() || !Double.isFinite(node.asDouble())) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
        return node.asDouble();
    }

    /** {@return the optional numeric field; empty when absent or not numeric} */
    Optional<Double> optionalNumber(final String field) {
        final JsonNode node = node(field);
        if (node == null || !node.isNumber() || !Double.isFinite(node.asDouble())) {
            return Optional.empty();
        }
        return Optional.of(node.asDouble());
    }

    /** {@return the optional integral field; empty when absent or not integral} */
    Optional<Integer> optionalInt(final String field) {
        final JsonNode node = node(field);
        if (node == null || !node.isNumber() || !node.canConvertToInt()) {
            return Optional.empty();
        }
        return Optional.of(node.asInt());
    }

    /** {@return the required boolean field} */
    boolean requiredBoolean(final String field) throws EditApiFailure {
        final JsonNode node = node(field);
        if (node == null || !node.isBoolean()) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
        return node.asBoolean();
    }

    /** {@return the optional boolean field, or {@code fallback} when absent/not boolean} */
    boolean optionalBoolean(final String field, final boolean fallback) {
        final JsonNode node = node(field);
        return node != null && node.isBoolean() ? node.asBoolean() : fallback;
    }

    /** {@return the optional string array; empty when absent, {@code InvalidData} on non-array} */
    Optional<List<String>> optionalStringList(final String field) throws EditApiFailure {
        final JsonNode node = node(field);
        if (node == null) {
            return Optional.empty();
        }
        if (!node.isArray()) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
        final List<String> values = new ArrayList<>(node.size());
        for (final JsonNode element : node) {
            if (!element.isTextual() || element.asText().isEmpty()) {
                throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
            }
            values.add(element.asText());
        }
        return Optional.of(values);
    }

    /**
     * {@return the optional {@code Parameters} array as {@code [{Id?, Value?}]} pairs}
     *
     * <p>Each entry is a raw {@link JsonNode} pair converted lazily by the handler into an
     * {@code EditParameterKeyCondition}; malformed entries fail {@code InvalidData}.</p>
     */
    Optional<List<JsonNode>> optionalArray(final String field) throws EditApiFailure {
        final JsonNode node = node(field);
        if (node == null) {
            return Optional.empty();
        }
        if (!node.isArray()) {
            throw new EditApiFailure(EditApiErrorCode.INVALID_DATA);
        }
        final List<JsonNode> entries = new ArrayList<>(node.size());
        node.forEach(entries::add);
        return Optional.of(entries);
    }

    private JsonNode node(final String field) {
        if (data == null) {
            return null;
        }
        final JsonNode node = data.get(field);
        return node == null || node.isNull() ? null : node;
    }
}
