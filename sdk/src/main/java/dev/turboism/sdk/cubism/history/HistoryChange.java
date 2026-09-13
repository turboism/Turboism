package dev.turboism.sdk.cubism.history;

import java.util.Objects;
import java.util.List;
import java.util.Optional;

/** One trusted semantic change within a history entry. */
public record HistoryChange(
    Operation operation,
    Optional<Integer> targetIndex,
    Optional<String> property,
    Optional<String> before,
    Optional<String> after,
    HistoryEditContext context
) {

    private static final int MAX_PROPERTY_LENGTH = 128;
    private static final int MAX_VALUE_LENGTH = 256;

    public HistoryChange {
        operation = Objects.requireNonNull(operation, "operation");
        targetIndex = Objects.requireNonNull(targetIndex, "targetIndex")
            .map(HistoryChange::validTargetIndex);
        property = normalizedOptional(property, "property", MAX_PROPERTY_LENGTH, false);
        before = normalizedOptional(before, "before", MAX_VALUE_LENGTH, true);
        after = normalizedOptional(after, "after", MAX_VALUE_LENGTH, true);
        context = Objects.requireNonNull(context, "context");
    }

    /**
     * Creates a change without a verified edit location.
     *
     * <p>This compatibility overload derives an {@link HistoryEditContext.Kind#UNKNOWN}
     * context and therefore cannot support a new {@code FULL} detail by itself.</p>
     */
    public HistoryChange(
        final Operation operation,
        final Optional<Integer> targetIndex,
        final Optional<String> property,
        final Optional<String> before,
        final Optional<String> after
    ) {
        this(
            operation,
            targetIndex,
            property,
            before,
            after,
            new HistoryEditContext(
                HistoryEditContext.Kind.UNKNOWN,
                Optional.empty(),
                List.of()
            )
        );
    }

    /** Creates a complete SET change for one indexed target. */
    public static HistoryChange set(
        final int targetIndex,
        final String property,
        final String before,
        final String after
    ) {
        return new HistoryChange(
            Operation.SET,
            Optional.of(targetIndex),
            Optional.of(property),
            Optional.of(before),
            Optional.of(after),
            new HistoryEditContext(
                HistoryEditContext.Kind.OBJECT,
                Optional.empty(),
                List.of()
            )
        );
    }

    private static int validTargetIndex(final int value) {
        if (value < 0) {
            throw new IllegalArgumentException("targetIndex must not be negative");
        }
        return value;
    }

    private static Optional<String> normalizedOptional(
        final Optional<String> value,
        final String fieldName,
        final int maxLength,
        final boolean allowEmpty
    ) {
        return Objects.requireNonNull(value, fieldName)
            .map(item -> normalizedText(item, fieldName, maxLength, allowEmpty));
    }

    private static String normalizedText(
        final String value,
        final String fieldName,
        final int maxLength,
        final boolean allowEmpty
    ) {
        final String normalized = Objects.requireNonNull(value, fieldName).strip();
        if (!allowEmpty && normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                fieldName + " must not exceed " + maxLength + " characters"
            );
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " must not contain control characters");
        }
        return normalized;
    }

    public enum Operation {
        SET,
        ADD,
        REMOVE,
        UNKNOWN
    }
}
