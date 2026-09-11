package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.adapter.cubism.editor.history.decoder.SemanticHistoryOperationCatalog;
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
 * <p>The mapping reads only the typed facts the decoder established. It never reads a native edit
 * name: names are localizable labels, so a name-based mapping would silently misclassify on a
 * localized host and would attribute identities the entry does not prove.</p>
 *
 * <p>Which facts select an operation is decided by what the entry proves, not by how the host
 * happens to describe it:</p>
 * <ul>
 *   <li>a decoded direct-hierarchy relation proves a parent change, and the moved object is its
 *       subject whatever its admitted type is;</li>
 *   <li>an entry whose every change is an admitted <em>appearance</em> channel proves a drawable
 *       colour change;</li>
 *   <li>anything else stays the conservative generic editor command. A geometry channel is
 *       deliberately not an appearance channel, so a move or a mesh edit is never reported as a
 *       colour change.</li>
 * </ul>
 */
public final class NativeHistoryOperations {

    private NativeHistoryOperations() {
    }

    /**
     * Resolves the semantic operation and subject one decoded native entry proves.
     *
     * @param detail the decoded entry detail, or {@code null} when the entry could not be decoded
     * @return the proven operation and subject, or the conservative fallback when the entry does
     *     not establish an exact fact
     */
    public static Resolution resolve(final HistoryEntryDetail detail) {
        if (detail == null) return Resolution.fallback();
        final Optional<HistoryRelationChange> relation = relationOf(detail);
        if (relation.isPresent()) {
            final HistoryRelationChange value = relation.orElseThrow();
            return new Resolution(
                value.after().state() == HistoryRelationChange.State.TARGET
                    ? CubismOperation.SET_HIERARCHY_PARENT
                    : CubismOperation.DETACH_HIERARCHY_PARENT,
                subjectOf(detail)
            );
        }
        if (isAppearanceOnly(detail)) {
            return new Resolution(CubismOperation.SET_DRAWABLE_COLOR, subjectOf(detail));
        }
        return Resolution.fallback();
    }

    private static Optional<HistoryRelationChange> relationOf(final HistoryEntryDetail detail) {
        if (detail.changes().size() != 1) return Optional.empty();
        final HistoryChange change = detail.changes().get(0);
        if (change.operation() != HistoryChange.Operation.SET) return Optional.empty();
        return change.relation();
    }

    /**
     * Calculates whether every change is an admitted appearance channel of the same target.
     *
     * <p>An empty change list proves nothing, and a mixed list describes more than one fact, so
     * neither selects a colour change.</p>
     */
    private static boolean isAppearanceOnly(final HistoryEntryDetail detail) {
        if (detail.targets().size() != 1 || detail.changes().isEmpty()) return false;
        final String targetType = detail.targets().get(0).type();
        return detail.changes().stream().allMatch(change -> isAppearanceChange(targetType, change));
    }

    private static boolean isAppearanceChange(final String targetType, final HistoryChange change) {
        if (change.operation() != HistoryChange.Operation.SET || change.relation().isPresent()) {
            return false;
        }
        final Optional<String> property = change.property();
        return property.isPresent()
            && SemanticHistoryOperationCatalog.isAppearanceChannel(targetType, property.orElseThrow());
    }

    /**
     * Reads the subject of a fact about exactly one object.
     *
     * <p>Any admitted target type may be a subject: a Part drag moves a Part, and restricting the
     * subject to deformers would leave those events pointing at nothing.</p>
     */
    private static Optional<String> subjectOf(final HistoryEntryDetail detail) {
        if (detail.targets().size() != 1) return Optional.empty();
        final HistoryTarget target = detail.targets().get(0);
        return target.id();
    }

    /** The proven operation together with the object it applies to. */
    public record Resolution(CubismOperation operation, Optional<String> subjectId) {

        public Resolution {
            operation = Objects.requireNonNull(operation, "operation");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
        }

        /** The conservative identity used when an entry proves no exact fact. */
        static Resolution fallback() {
            return new Resolution(CubismOperation.EXECUTE_EDITOR_COMMAND, Optional.empty());
        }
    }
}
