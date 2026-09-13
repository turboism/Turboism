package dev.turboism.adapter.cubism.editor.transaction;

import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryTarget;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** One changed Editor authoring primitive contributed to an ambient root transaction. */
public record EditorUndoContribution(
    String operationId,
    String targetIdentity,
    String standaloneLabel,
    UndoAdmission undoAdmission,
    Runnable mutation,
    BooleanSupplier applied,
    Runnable compensation,
    BooleanSupplier restored,
    Set<EditorRefreshRequirement> refreshRequirements,
    HistoryEntryDetail semanticDetail,
    Supplier<HistoryEntryDetail> captureAfter
) {

    /** Retains contributions whose metadata was already captured by the adapter. */
    public EditorUndoContribution(
        final String operationId,
        final String targetIdentity,
        final String standaloneLabel,
        final UndoAdmission undoAdmission,
        final Runnable mutation,
        final BooleanSupplier applied,
        final Runnable compensation,
        final BooleanSupplier restored,
        final Set<EditorRefreshRequirement> refreshRequirements,
        final HistoryEntryDetail semanticDetail
    ) {
        this(operationId, targetIdentity, standaloneLabel, undoAdmission, mutation, applied,
            compensation, restored, refreshRequirements, semanticDetail, () -> semanticDetail);
    }
    /** Retains the prior internal construction surface with conservative partial semantics. */
    public EditorUndoContribution(
        final String operationId,
        final String targetIdentity,
        final String standaloneLabel,
        final UndoAdmission undoAdmission,
        final Runnable mutation,
        final BooleanSupplier applied,
        final Runnable compensation,
        final BooleanSupplier restored,
        final Set<EditorRefreshRequirement> refreshRequirements
    ) {
        this(
            operationId,
            targetIdentity,
            standaloneLabel,
            undoAdmission,
            mutation,
            applied,
            compensation,
            restored,
            refreshRequirements,
            legacySemanticDetail(operationId, targetIdentity, standaloneLabel)
        );
    }

    /** Validates and defensively copies one contribution. */
    public EditorUndoContribution {
        operationId = requireText(operationId, "operationId", 160);
        targetIdentity = requireText(targetIdentity, "targetIdentity", 256);
        standaloneLabel = requireText(standaloneLabel, "standaloneLabel", 128);
        undoAdmission = Objects.requireNonNull(undoAdmission, "undoAdmission");
        mutation = Objects.requireNonNull(mutation, "mutation");
        applied = Objects.requireNonNull(applied, "applied");
        compensation = Objects.requireNonNull(compensation, "compensation");
        restored = Objects.requireNonNull(restored, "restored");
        final Set<EditorRefreshRequirement> requested = Objects.requireNonNull(
            refreshRequirements,
            "refreshRequirements"
        );
        refreshRequirements = requested.isEmpty()
            ? Set.of()
            : Set.copyOf(EnumSet.copyOf(requested));
        semanticDetail = Objects.requireNonNull(semanticDetail, "semanticDetail");
        captureAfter = Objects.requireNonNull(captureAfter, "captureAfter");
        if (semanticDetail.origin().kind() != HistoryOrigin.Kind.TURBOISM
            || semanticDetail.origin().operationId().filter(operationId::equals).isEmpty()) {
            throw new IllegalArgumentException(
                "semanticDetail must carry matching Turboism operation identity"
            );
        }
        if (semanticDetail.detailLevel() == HistoryAction.DetailLevel.LABEL_ONLY
            || semanticDetail.targets().isEmpty()
            || semanticDetail.changes().isEmpty()
            || semanticDetail.group().isPresent()) {
            throw new IllegalArgumentException(
                "semanticDetail must describe one structured, ungrouped contribution"
            );
        }
    }

    /** Installs an adapter readback executed immediately after this primitive succeeds. */
    public EditorUndoContribution withCaptureAfter(final Supplier<HistoryEntryDetail> readback) {
        return new EditorUndoContribution(operationId, targetIdentity, standaloneLabel,
            undoAdmission, mutation, applied, compensation, restored, refreshRequirements,
            semanticDetail, readback);
    }

    /** Freezes readback without retaining its native-reading callback in the completed scope. */
    EditorUndoContribution captureActual() {
        HistoryEntryDetail captured;
        try {
            captured = Objects.requireNonNull(captureAfter.get(), "captured detail");
            if (!sameCaptureIdentity(captured)) {
                throw new IllegalArgumentException("capture identity changed during readback");
            }
            return frozen(captured);
        } catch (RuntimeException unavailable) {
            // Observation failure must not roll back an otherwise valid authoring write.
            captured = new HistoryEntryDetail(
                semanticDetail.summary(), HistoryAction.DetailLevel.PARTIAL, semanticDetail.origin(),
                semanticDetail.targets(), semanticDetail.changes().stream().map(change -> new HistoryChange(
                    change.operation(), change.targetIndex(), change.property(), change.before(),
                    Optional.empty(), change.context()
                )).toList(), Optional.empty(), Optional.of("history.capture.after-unavailable")
            );
            return frozen(captured);
        }
    }

    private EditorUndoContribution frozen(final HistoryEntryDetail detail) {
        return new EditorUndoContribution(operationId, targetIdentity, standaloneLabel,
            undoAdmission, mutation, applied, compensation, restored, refreshRequirements, detail);
    }

    private boolean sameCaptureIdentity(final HistoryEntryDetail captured) {
        if (!semanticDetail.origin().equals(captured.origin())
            || !semanticDetail.targets().equals(captured.targets())
            || semanticDetail.changes().size() != captured.changes().size()) return false;
        for (int index = 0; index < semanticDetail.changes().size(); index++) {
            final HistoryChange before = semanticDetail.changes().get(index);
            final HistoryChange after = captured.changes().get(index);
            if (before.operation() != after.operation()
                || !before.targetIndex().equals(after.targetIndex())
                || !before.property().equals(after.property())
                || !before.before().equals(after.before())
                || !before.context().equals(after.context())) return false;
        }
        return true;
    }

    /** Adds this primitive's native Undo snapshot to the supplied shared edit object. */
    @FunctionalInterface
    public interface UndoAdmission {
        /** Adds the contribution to the native root edit. */
        void admit(Object edit, String transactionLabel);
    }

    private static HistoryEntryDetail legacySemanticDetail(
        final String operationId,
        final String targetIdentity,
        final String standaloneLabel
    ) {
        return new HistoryEntryDetail(
            standaloneLabel,
            HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.turboism("dev.turboism.runtime", operationId),
            List.of(new HistoryTarget("EDITOR_OBJECT", Optional.of(targetIdentity), Optional.empty())),
            List.of(new HistoryChange(
                HistoryChange.Operation.UNKNOWN,
                Optional.of(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            )),
            Optional.empty(),
            Optional.of("history.detail.legacy-contribution")
        );
    }

    private static String requireText(
        final String value,
        final String name,
        final int maximumLength
    ) {
        final String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        if (checked.length() > maximumLength) {
            throw new IllegalArgumentException(
                name + " must not exceed " + maximumLength + " characters"
            );
        }
        if (checked.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return checked;
    }
}
