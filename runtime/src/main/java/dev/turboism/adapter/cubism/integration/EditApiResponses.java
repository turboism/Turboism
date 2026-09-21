package dev.turboism.adapter.cubism.integration;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Serializes response frames in the exact shape the host's {@code a.f()} produces.
 *
 * <p>Observed wire recipe ({@code host-evidence/integration-54compat/dispatcher-internals.md}
 * §6): each present field is emitted as {@code "Name" : value} separated by commas inside a
 * single object; {@code Version}, {@code Type} and {@code Method} are JSON strings,
 * {@code Timestamp} is a number, {@code RequestId} keeps the request's scalar shape, and
 * {@code Data} is concatenated as a raw JSON fragment rather than a quoted string. Absent
 * fields are omitted entirely.</p>
 */
final class EditApiResponses {

    private EditApiResponses() {
    }

    /**
     * Builds a {@code Type="Error"} envelope with the official error body
     * {@code { "ErrorType" : "<code>" }}.
     *
     * @param version   the version string to echo back; omitted when null
     * @param requestId the request's {@code RequestId} scalar; omitted when null
     * @param method    the request method being answered; omitted when null
     * @param code      the typed error
     * @param timestamp response timestamp (epoch millis)
     * @return the serialized frame
     */
    static String error(
        final String version,
        final JsonNode requestId,
        final String method,
        final EditApiErrorCode code,
        final long timestamp
    ) {
        return envelope(
            version,
            requestId,
            "Error",
            method,
            "{ \"ErrorType\" : \"" + escape(code.wireName()) + "\"}",
            timestamp
        );
    }

    /**
     * Builds a {@code Type="Response"} envelope.
     *
     * @param version   the version string to report; omitted when null
     * @param requestId the request's {@code RequestId} scalar; omitted when null
     * @param method    the request method being answered; omitted when null
     * @param dataJson  the response {@code Data} fragment, already serialized
     * @param timestamp response timestamp (epoch millis)
     * @return the serialized frame
     */
    static String response(
        final String version,
        final JsonNode requestId,
        final String method,
        final String dataJson,
        final long timestamp
    ) {
        return envelope(version, requestId, "Response", method, dataJson, timestamp);
    }

    private static String envelope(
        final String version,
        final JsonNode requestId,
        final String type,
        final String method,
        final String dataJson,
        final long timestamp
    ) {
        final StringBuilder out = new StringBuilder(160);
        out.append('{');
        if (version != null) {
            out.append(" \"Version\" : \"").append(escape(version)).append("\",");
        }
        out.append(" \"Timestamp\" : ").append(timestamp).append(',');
        if (requestId != null) {
            out.append(" \"RequestId\" : ").append(scalar(requestId)).append(',');
        }
        if (type != null) {
            out.append(" \"Type\" : \"").append(escape(type)).append("\",");
        }
        if (method != null) {
            out.append(" \"Method\" : \"").append(escape(method)).append("\",");
        }
        out.append(" \"Data\" : ").append(dataJson == null ? "{}" : dataJson);
        out.append('}');
        return out.toString();
    }

    private static String scalar(final JsonNode node) {
        if (node.isNumber()) {
            return node.asText();
        }
        return "\"" + escape(node.asText()) + "\"";
    }

    /** Minimal JSON string escaping; output never carries raw control characters. */
    static String escape(final String text) {
        final StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
