package dev.turboism.sdk.cubism.history;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

/** Structured, immutable detail for a history entry when its semantics are known safely. */
@PreviewApi
public record HistoryAction(
    Kind kind,
    String targetType,
    String targetId,
    String property,
    Optional<String> before,
    Optional<String> after,
    DetailLevel detailLevel
) {

    public HistoryAction {
        kind = Objects.requireNonNull(kind, "kind");
        targetType = requireText(targetType, "targetType");
        targetId = requireText(targetId, "targetId");
        property = requireText(property, "property");
        before = Objects.requireNonNull(before, "before").map(String::strip);
        after = Objects.requireNonNull(after, "after").map(String::strip);
        detailLevel = Objects.requireNonNull(detailLevel, "detailLevel");
        if (detailLevel == DetailLevel.LABEL_ONLY) {
            throw new IllegalArgumentException("Structured actions must be PARTIAL or FULL");
        }
    }

    public enum Kind {
        SET_PARAMETER_VALUE,
        UNKNOWN
    }

    public enum DetailLevel {
        FULL,
        PARTIAL,
        LABEL_ONLY
    }

    private static String requireText(final String value, final String name) {
        final String text = Objects.requireNonNull(value, name).strip();
        if (text.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return text;
    }
}
