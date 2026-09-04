package dev.turboism.adapter.cubism.editor.transaction;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * One changed Editor authoring primitive contributed to an ambient root transaction.
 *
 * <p>The provider owns the native Undo snapshot and exact readback/compensation logic. The
 * coordinator owns ordering, native edit lifetime, coalesced refresh, commit, abort, and history
 * verification.</p>
 *
 * @param operationId stable Runtime operation identity for diagnostics
 * @param targetIdentity generation-bound target identity
 * @param standaloneLabel label used when this contribution opens its own root transaction
 * @param undoAdmission adds the provider's native Undo snapshot to the shared edit object
 * @param mutation applies the changed primitive
 * @param applied verifies the expected postcondition after mutation
 * @param compensation restores provider-local state when native abort did not already restore it
 * @param restored verifies the contribution's before-state during reverse recovery
 * @param refreshRequirements refresh and persistence work coalesced at root commit
 */
public record EditorUndoContribution(
    String operationId,
    String targetIdentity,
    String standaloneLabel,
    UndoAdmission undoAdmission,
    Runnable mutation,
    BooleanSupplier applied,
    Runnable compensation,
    BooleanSupplier restored,
    Set<EditorRefreshRequirement> refreshRequirements
) {

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
        if (requested.isEmpty()) {
            refreshRequirements = Set.of();
        } else {
            refreshRequirements = Set.copyOf(EnumSet.copyOf(requested));
        }
    }

    /** Adds this primitive's native Undo snapshot to the supplied shared edit object. */
    @FunctionalInterface
    public interface UndoAdmission {
        /**
         * @param edit native root edit object owned by the coordinator
         * @param transactionLabel root transaction label
         */
        void admit(Object edit, String transactionLabel);
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
        if (checked.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return checked;
    }
}
