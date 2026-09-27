package dev.turboism.adapter.cubism.integration;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Read-only view over the official request envelope
 * ({@code Version}/{@code Timestamp}/{@code RequestId}/{@code Type}/{@code Method}/{@code Data}).
 *
 * <p>Field semantics mirror the host's {@code a.e()} validation: {@code Version},
 * {@code Type} and {@code Method} are expected to be strings, {@code Timestamp} and
 * {@code RequestId} may be numbers or strings. Accessors return {@code null} for absent or
 * wrongly-typed fields rather than throwing, so the bridge can always decide between a typed
 * error response and a native fall-through.</p>
 */
public final class EditApiEnvelope {

    private final JsonNode root;

    private EditApiEnvelope(final JsonNode root) {
        this.root = root;
    }

    /**
     * Wraps a parsed JSON root.
     *
     * @param root the parsed message; must be a JSON object for field accessors to see anything
     * @return the envelope view
     */
    public static EditApiEnvelope of(final JsonNode root) {
        return new EditApiEnvelope(root);
    }

    /** {@return the request {@code Version} string, or null when absent or not textual} */
    public String version() {
        return text("Version");
    }

    /** {@return the raw {@code Timestamp} node (number or string), or null} */
    public JsonNode timestamp() {
        return scalar("Timestamp");
    }

    /** {@return the raw {@code RequestId} node (number or string), or null} */
    public JsonNode requestId() {
        return scalar("RequestId");
    }

    /** {@return the request {@code Type} string, or null when absent or not textual} */
    public String type() {
        return text("Type");
    }

    /** {@return the request {@code Method} string, or null when absent or not textual} */
    public String method() {
        return text("Method");
    }

    /** {@return the {@code Data} node, or null when absent} */
    public JsonNode data() {
        return root.isObject() ? root.get("Data") : null;
    }

    private String text(final String field) {
        final JsonNode node = root.isObject() ? root.get(field) : null;
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private JsonNode scalar(final String field) {
        final JsonNode node = root.isObject() ? root.get(field) : null;
        return node != null && (node.isNumber() || node.isTextual()) ? node : null;
    }
}
