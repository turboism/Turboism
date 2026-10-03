package dev.turboism.plugin.mcp;

import dev.turboism.sdk.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    static String stringify(final Object value) {
        return Json.stringify(encodable(value));
    }

    /**
     * Returns {@code value} — or a copy — with every unpaired surrogate in
     * strings replaced by U+FFFD and every non-finite {@link Float} or
     * {@link Double} replaced by {@code null}. Map keys are rewritten the same
     * way as values. The input is never mutated; when nothing needs rewriting
     * the same instance is returned.
     */
    @SuppressWarnings("unchecked")
    static <T> T encodable(final T value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return (T) replaceUnpairedSurrogates(text);
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<Object, Object> rewritten = null;
            for (Map.Entry<?, ?> member : map.entrySet()) {
                final Object key = encodable(member.getKey());
                final Object item = encodable(member.getValue());
                if (key != member.getKey() || item != member.getValue()) {
                    if (rewritten == null) {
                        rewritten = new LinkedHashMap<>(map);
                    }
                    if (key != member.getKey()) {
                        rewritten.remove(member.getKey());
                    }
                    rewritten.put(key, item);
                }
            }
            return rewritten == null ? value : (T) rewritten;
        }
        if (value instanceof Iterable<?> items) {
            List<Object> rewritten = null;
            int index = 0;
            for (Object item : items) {
                final Object encoded = encodable(item);
                if (encoded != item && rewritten == null) {
                    rewritten = new ArrayList<>();
                    for (Object existing : items) rewritten.add(existing);
                }
                if (rewritten != null) {
                    rewritten.set(index, encoded);
                }
                index++;
            }
            return rewritten == null ? value : (T) rewritten;
        }
        if (value instanceof Object[] array) {
            Object[] rewritten = null;
            for (int index = 0; index < array.length; index++) {
                final Object encoded = encodable(array[index]);
                if (encoded != array[index]) {
                    if (rewritten == null) {
                        rewritten = array.clone();
                    }
                    rewritten[index] = encoded;
                }
            }
            return rewritten == null ? value : (T) rewritten;
        }
        if (value instanceof Float number) {
            return Float.isFinite(number) ? value : null;
        }
        if (value instanceof Double number) {
            return Double.isFinite(number) ? value : null;
        }
        return value;
    }

    /** Replaces each unpaired surrogate with U+FFFD; returns the input when already clean. */
    private static String replaceUnpairedSurrogates(final String text) {
        final int length = text.length();
        StringBuilder rewritten = null;
        for (int offset = 0; offset < length; offset++) {
            final char unit = text.charAt(offset);
            if (Character.isHighSurrogate(unit)) {
                if (offset + 1 < length && Character.isLowSurrogate(text.charAt(offset + 1))) {
                    offset++;
                    if (rewritten != null) {
                        rewritten.append(unit).append(text.charAt(offset));
                    }
                    continue;
                }
            } else if (!Character.isLowSurrogate(unit)) {
                if (rewritten != null) {
                    rewritten.append(unit);
                }
                continue;
            }
            if (rewritten == null) {
                rewritten = new StringBuilder(length);
                rewritten.append(text, 0, offset);
            }
            rewritten.append('�');
        }
        return rewritten == null ? text : rewritten.toString();
    }
}
