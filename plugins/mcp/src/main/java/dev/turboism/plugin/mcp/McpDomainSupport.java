package dev.turboism.plugin.mcp;

import dev.turboism.sdk.json.Json;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shared wire primitives for the transactional MCP domains: state-token comparison, encoded-size
 * accounting, argument validation, and the tool-result envelope.
 *
 * <p>Every write domain compares the state token it issued against the token it reads back. The
 * comparison keys are the wire contract: a domain that compares a different set either reports a
 * false stale rejection or admits a write against a changed document.</p>
 */
final class McpDomainSupport {

    /**
     * State-token keys that identify the document, the model, and the history position a write was
     * planned against.
     */
    private static final List<String> SCOPE_KEYS =
            List.of("documentId", "modelId", "historyGeneration", "historyDocumentBinding", "historyManagerBinding");

    private McpDomainSupport() {}

    /**
     * Returns {@code true} when {@code a} and {@code b} agree on every {@link #SCOPE_KEYS} member.
     * A key absent from both tokens compares equal.
     */
    static boolean sameScope(final Map<String, Object> a, final Map<String, Object> b) {
        return SCOPE_KEYS.stream().allMatch(key -> Objects.equals(a.get(key), b.get(key)));
    }

    /** Returns the UTF-8 length of the strict JSON encoding of {@code value}. */
    static int bytes(final Map<String, ?> value) {
        return Json.stringify(value).getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Returns {@code raw} as a string of at most {@code max} characters.
     *
     * <p>Rejects non-strings, blank text, over-long text, and any ISO control character. Callers rely
     * on this rejection to keep identifiers printable and bounded on the wire.</p>
     *
     * @throws IllegalArgumentException when {@code raw} violates any of those bounds
     */
    static String text(final Object raw, final int max) {
        if (!(raw instanceof String s)
                || s.isBlank()
                || s.length() > max
                || s.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return s;
    }

    /**
     * Returns {@code raw} as a finite double.
     *
     * @throws IllegalArgumentException when {@code raw} is not a finite number
     */
    static double number(final Object raw) {
        if (!(raw instanceof Number value) || !Double.isFinite(value.doubleValue()))
            throw new IllegalArgumentException();
        return value.doubleValue();
    }

    /**
     * Returns {@code raw} as an exact 32-bit integer.
     *
     * @throws IllegalArgumentException when {@code raw} is not a number, or when its value is not an
     *     exact {@code int}
     */
    static int integer(final Object raw) {
        if (!(raw instanceof Number number)) throw new IllegalArgumentException();
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    /**
     * Wraps {@code output} as an MCP tool result whose error flag follows the {@code ok} member.
     *
     * <p>Member order stays fixed so the encoded envelope is stable across runs.</p>
     */
    static Map<String, Object> envelope(final Map<String, Object> output) {
        final Map<String, Object> safe = McpJsonSupport.encodable(output);
        return McpSchemas.immutableMap(
                McpSchemas.entry(
                        "content",
                        List.of(McpSchemas.immutableMap(
                                McpSchemas.entry("type", "text"), McpSchemas.entry("text", Json.stringify(safe))))),
                McpSchemas.entry("structuredContent", safe),
                McpSchemas.entry("isError", !Boolean.TRUE.equals(safe.get("ok"))));
    }
}
