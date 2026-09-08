package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.util.IdentityHashMap;
import java.util.Objects;

/** Per-top-level-entry decode budgets and identity-cycle protection. */
final class NativeHistoryDecodeContext {

    static final int MAX_DEPTH = 4;
    static final int MAX_NODES = 64;
    static final int MAX_STRING_LENGTH = 256;

    private final VerifiedMemberResolver resolver;
    private final IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
    private int nodes;

    NativeHistoryDecodeContext(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    VerifiedMemberResolver resolver() {
        return resolver;
    }

    boolean enter(final Object entry, final int depth) {
        if (entry == null || depth > MAX_DEPTH || nodes >= MAX_NODES || visited.containsKey(entry)) {
            return false;
        }
        visited.put(entry, Boolean.TRUE);
        nodes++;
        return true;
    }

    String boundedLabel(final String value) {
        final String text = Objects.requireNonNull(value, "value").strip();
        final String nonBlank = text.isEmpty() ? "History entry" : text;
        return nonBlank.length() <= MAX_STRING_LENGTH
            ? nonBlank
            : nonBlank.substring(0, MAX_STRING_LENGTH);
    }

    ProjectedValue safeValue(final Object value) {
        if (value == null) return new ProjectedValue(java.util.Optional.empty(), false);
        final String text;
        if (value instanceof String string) {
            text = string;
        } else if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            text = String.valueOf(value);
        } else if (value instanceof Enum<?> enumeration) {
            text = enumeration.name();
        } else {
            return new ProjectedValue(java.util.Optional.empty(), false);
        }
        if (text.length() <= MAX_STRING_LENGTH) {
            return new ProjectedValue(java.util.Optional.of(text), false);
        }
        return new ProjectedValue(
            java.util.Optional.of(text.substring(0, MAX_STRING_LENGTH)),
            true
        );
    }

    String className(final Object value) {
        if (value == null) return "unknown";
        final String name = value.getClass().getName();
        return name.length() <= MAX_STRING_LENGTH
            ? name
            : name.substring(0, MAX_STRING_LENGTH);
    }

    record ProjectedValue(java.util.Optional<String> value, boolean truncated) {
    }
}
