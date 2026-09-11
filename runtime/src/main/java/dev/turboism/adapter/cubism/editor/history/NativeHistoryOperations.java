package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistoryTarget;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Structural mapping from an exactly decoded native history entry to a semantic operation.
 *
 * <p>The mapping reads only the typed relation the decoder established. It never reads a native
 * edit name: names are localizable labels, so a name-based mapping would silently misclassify on a
 * localized host and would attribute identities the entry does not prove.</p>
 */
public final class NativeHistoryOperations {

    private static final List<String> DEFORMER_CHILD_TYPES = List.of(
        "ART_MESH",
        "WARP_DEFORMER",
        "ROTATION_DEFORMER"
    );

    private NativeHistoryOperations() {
    }

    /**
     * Resolves the semantic operation and subject one decoded native entry proves.
     *
     * @param detail the decoded entry detail, or {@code null} when the entry could not be decoded
     * @return the proven operation and subject, or the conservative fallback when the entry does
     *     not establish an exact hierarchy relation
     */
    public static Resolution resolve(final HistoryEntryDetail detail) {
        if (detail == null) return Resolution.fallback();
        final Optional<HistoryRelationChange> relation = relationOf(detail);
        if (relation.isEmpty()) return Resolution.fallback();
        final HistoryRelationChange value = relation.orElseThrow();
        final Optional<String> subject = subjectOf(detail);
        return switch (value.kind()) {
            case PART_MEMBERSHIP -> new Resolution(
                value.after().state() == HistoryRelationChange.State.TARGET
                    ? CubismOperation.SET_HIERARCHY_PARENT
                    : CubismOperation.DETACH_HIERARCHY_PARENT,
                subject
            );
            case DEFORMER_PARENT -> new Resolution(
                value.after().state() == HistoryRelationChange.State.TARGET
                    ? CubismOperation.SET_HIERARCHY_PARENT
                    : CubismOperation.DETACH_HIERARCHY_PARENT,
                subject
            );
        };
    }

    private static Optional<HistoryRelationChange> relationOf(final HistoryEntryDetail detail) {
        if (detail.changes().size() != 1) return Optional.empty();
        final HistoryChange change = detail.changes().get(0);
        if (change.operation() != HistoryChange.Operation.SET) return Optional.empty();
        return change.relation();
    }

    private static Optional<String> subjectOf(final HistoryEntryDetail detail) {
        if (detail.targets().size() != 1) return Optional.empty();
        final HistoryTarget child = detail.targets().get(0);
        if (!DEFORMER_CHILD_TYPES.contains(child.type())) return Optional.empty();
        return child.id();
    }

    /** The proven operation together with the object it applies to. */
    public record Resolution(CubismOperation operation, Optional<String> subjectId) {

        public Resolution {
            operation = Objects.requireNonNull(operation, "operation");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
        }

        /** The conservative identity used when an entry proves no exact hierarchy relation. */
        static Resolution fallback() {
            return new Resolution(CubismOperation.EXECUTE_EDITOR_COMMAND, Optional.empty());
        }
    }
}
