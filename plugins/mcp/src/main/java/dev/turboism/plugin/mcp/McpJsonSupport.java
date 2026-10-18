package dev.turboism.plugin.mcp;

import dev.turboism.sdk.json.Json;
import java.util.Map;

/**
 * JSON encoding boundary for MCP results and resources.
 *
 * <p>{@link Json} encodes strictly: unpaired surrogates and non-finite numbers
 * fail with {@link IllegalArgumentException}. Host-provided strings (model
 * object names, authored text) can legitimately carry lone surrogates, so an
 * otherwise valid result would surface as an encoding failure — and inside a
 * {@code catch (IllegalArgumentException)} block be misreported as a caller
 * error. {@link #encodable} rewrites exactly those values (unpaired
 * surrogates to U+FFFD, non-finite numbers to {@code null}), so the same
 * result always encodes; anything else that still fails is a genuine internal
 * bug rather than a wire-format edge case.
 */
final class McpJsonSupport {

    private McpJsonSupport() {}

    /**
     * Strict-encodes {@code value} after rewriting the values strict JSON
     * cannot represent (see {@link #encodable}).
     */
    static String stringify(final Map<String, ?> value) {
        return Json.stringify(Json.sanitize(value));
    }

    /**
     * Returns {@code value} — or a copy — with every unpaired surrogate in
     * strings replaced by U+FFFD and every non-finite {@link Float} or
     * {@link Double} replaced by {@code null}. Map keys are rewritten the same
     * way as values. The input is never mutated; when nothing needs rewriting
     * the same instance is returned.
     */
    static <T> T encodable(final T value) {
        return Json.sanitize(value);
    }
}
