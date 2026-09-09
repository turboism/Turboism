package dev.turboism.sdk.cubism.history;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable semantic detail attached to one history entry. */
public record HistoryEntryDetail(
    String summary,
    HistoryAction.DetailLevel detailLevel,
    HistoryOrigin origin,
    List<HistoryTarget> targets,
    List<HistoryChange> changes,
    Optional<HistoryGroup> group,
    Optional<String> degradationCode
) {

    private static final int MAX_SUMMARY_LENGTH = 256;
    private static final int MAX_ITEMS = 64;
    private static final int MAX_DEGRADATION_CODE_LENGTH = 128;
    private static final String DEFAULT_LABEL_ONLY_CODE = "history.detail.label-only";

    public HistoryEntryDetail {
        summary = normalizedText(summary, "summary", MAX_SUMMARY_LENGTH, false);
        detailLevel = Objects.requireNonNull(detailLevel, "detailLevel");
        origin = Objects.requireNonNull(origin, "origin");
        targets = copiedList(targets, "targets");
        changes = copiedList(changes, "changes");
        group = Objects.requireNonNull(group, "group");
        degradationCode = Objects.requireNonNull(degradationCode, "degradationCode")
            .map(value -> normalizedText(
                value,
                "degradationCode",
                MAX_DEGRADATION_CODE_LENGTH,
                false
            ));
        if (targets.size() > MAX_ITEMS || changes.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(
                "targets and changes must not exceed " + MAX_ITEMS + " items each"
            );
        }
        validateReferences(targets, changes);
        validateDetailLevel(detailLevel, targets, changes, group, degradationCode);
    }

    /** Creates host-unattributed label-only detail with the default degradation code. */
    public static HistoryEntryDetail labelOnly(final String label) {
        return labelOnly(label, HistoryOrigin.hostUnattributed(), DEFAULT_LABEL_ONLY_CODE);
    }

    /** Creates label-only detail with an explicit origin and degradation code. */
    public static HistoryEntryDetail labelOnly(
        final String label,
        final HistoryOrigin origin,
        final String degradationCode
    ) {
        return new HistoryEntryDetail(
            summaryFromLabel(label),
            HistoryAction.DetailLevel.LABEL_ONLY,
            origin,
            List.of(),
            List.of(),
            Optional.empty(),
            Optional.of(degradationCode)
        );
    }

    /** Projects a legacy {@link HistoryAction} into the semantic detail model. */
    public static HistoryEntryDetail fromAction(
        final String label,
        final HistoryAction action,
        final HistoryOrigin origin
    ) {
        final HistoryAction trustedAction = Objects.requireNonNull(action, "action");
        final HistoryTarget target = new HistoryTarget(
            trustedAction.targetType(),
            Optional.of(trustedAction.targetId()),
            Optional.empty()
        );
        final HistoryChange.Operation operation = switch (trustedAction.kind()) {
            case SET_PARAMETER_VALUE -> HistoryChange.Operation.SET;
            case UNKNOWN -> HistoryChange.Operation.UNKNOWN;
        };
        final HistoryChange change = new HistoryChange(
            operation,
            Optional.of(0),
            Optional.of(trustedAction.property()),
            trustedAction.before(),
            trustedAction.after(),
            new HistoryEditContext(
                HistoryEditContext.Kind.OBJECT,
                Optional.empty(),
                List.of()
            )
        );
        return new HistoryEntryDetail(
            summaryFromLabel(label),
            trustedAction.detailLevel(),
            origin,
            List.of(target),
            List.of(change),
            Optional.empty(),
            trustedAction.detailLevel() == HistoryAction.DetailLevel.PARTIAL
                ? Optional.of("history.detail.action-partial")
                : Optional.empty()
        );
    }

    /** Returns whether this detail carries a change equivalent to the legacy action. */
    public boolean isCompatibleWith(final HistoryAction action) {
        final HistoryAction trustedAction = Objects.requireNonNull(action, "action");
        return changes.stream().anyMatch(change -> {
            if (change.targetIndex().isEmpty()) return false;
            final int targetIndex = change.targetIndex().orElseThrow();
            if (targetIndex >= targets.size()) return false;
            final HistoryTarget target = targets.get(targetIndex);
            return target.type().equals(trustedAction.targetType())
                && target.id().filter(trustedAction.targetId()::equals).isPresent()
                && change.property().filter(trustedAction.property()::equals).isPresent()
                && change.before().equals(trustedAction.before())
                && change.after().equals(trustedAction.after());
        });
    }

    private static <T> List<T> copiedList(final List<T> values, final String fieldName) {
        final List<T> copied = List.copyOf(Objects.requireNonNull(values, fieldName));
        if (copied.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(fieldName + " must not contain null values");
        }
        return copied;
    }

    private static void validateReferences(
        final List<HistoryTarget> targets,
        final List<HistoryChange> changes
    ) {
        for (final HistoryChange change : changes) {
            if (change.targetIndex().isPresent()
                && change.targetIndex().orElseThrow() >= targets.size()) {
                throw new IllegalArgumentException("change targetIndex is outside targets");
            }
        }
    }

    private static void validateDetailLevel(
        final HistoryAction.DetailLevel detailLevel,
        final List<HistoryTarget> targets,
        final List<HistoryChange> changes,
        final Optional<HistoryGroup> group,
        final Optional<String> degradationCode
    ) {
        final boolean hasStructuredFact = !targets.isEmpty() || !changes.isEmpty() || group.isPresent();
        if (detailLevel == HistoryAction.DetailLevel.LABEL_ONLY) {
            if (hasStructuredFact) {
                throw new IllegalArgumentException("LABEL_ONLY detail must not contain structured facts");
            }
            if (degradationCode.isEmpty()) {
                throw new IllegalArgumentException("LABEL_ONLY detail requires a degradationCode");
            }
            return;
        }
        if (!hasStructuredFact) {
            throw new IllegalArgumentException("Structured detail requires at least one trusted fact");
        }
        if (detailLevel == HistoryAction.DetailLevel.PARTIAL) {
            if (degradationCode.isEmpty()) {
                throw new IllegalArgumentException("PARTIAL detail requires a degradationCode");
            }
            return;
        }
        if (degradationCode.isPresent()) {
            throw new IllegalArgumentException("FULL detail must not contain a degradationCode");
        }
        if (changes.isEmpty() && group.map(HistoryGroup::children).orElse(List.of()).isEmpty()) {
            throw new IllegalArgumentException("FULL detail requires a change or grouped child");
        }
        for (final HistoryChange change : changes) {
            validateFullChange(change);
        }
        group.ifPresent(value -> {
            if (value.truncated()
                || value.children().stream()
                    .anyMatch(child -> child.detailLevel() != HistoryAction.DetailLevel.FULL)) {
                throw new IllegalArgumentException("FULL grouped detail requires complete FULL children");
            }
        });
    }

    private static void validateFullChange(final HistoryChange change) {
        if (change.targetIndex().isEmpty()) {
            throw new IllegalArgumentException("FULL changes require targetIndex");
        }
        if (change.context().kind() == HistoryEditContext.Kind.UNKNOWN) {
            throw new IllegalArgumentException("FULL changes require a known edit context");
        }
        if (change.context().kind() == HistoryEditContext.Kind.KEYFORM
            && change.context().coordinates().isEmpty()) {
            throw new IllegalArgumentException(
                "FULL KEYFORM changes require parameter coordinates"
            );
        }
        switch (change.operation()) {
            case SET -> {
                if (change.property().isEmpty() || change.before().isEmpty() || change.after().isEmpty()) {
                    throw new IllegalArgumentException(
                        "FULL SET changes require property, before, and after"
                    );
                }
            }
            case ADD, REMOVE -> {
                // Direction plus target identity is the complete trusted fact for add/remove.
            }
            case UNKNOWN -> {
                // Compatibility actions can carry complete values without a more specific legacy kind.
            }
        }
    }

    private static String summaryFromLabel(final String label) {
        final String normalized = Objects.requireNonNull(label, "label").strip();
        return normalized.isEmpty() ? "History entry" : normalized;
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
}
