package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.adapter.cubism.editor.transaction.EditorUndoContribution;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorEditSessionSelectorContract;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Join adapter that lets a hand-written native Undo envelope participate in the ambient
 * authoring transaction instead of opening a detached edit-mode bracket.
 *
 * <p>An admitted contribution adds its native Undo object to the transaction's shared root
 * edit via {@link EditorUndoContribution.UndoAdmission}; the envelope's own
 * {@code edit-mode.begin/end} is skipped entirely, and its refresh/dirty work is deferred to
 * the root commit through {@link EditorRefreshRequirement}s. Rollback of the root then runs the
 * verified {@code cubism.editor-model.undo.group-undo} member on the still-open root edit, which
 * is the only mechanism that rewinds arbitrary admitted Undo objects ({@code endEdit(abort)}
 * discards the group without restoring model state). Admission is therefore gated on the
 * verified {@code group-undo} capability row.</p>
 *
 * <p>The runtime kill switch {@value #ENABLED_PROPERTY} restores the batch-056 wave-1
 * fail-closed behaviour when set to {@code false}: {@link #ambient} reports empty, the envelope
 * reaches {@link EditorAmbientTransactionGuard}, and the write is rejected inside ambient
 * transactions exactly as before.</p>
 */
final class HostUndoMutationScope {

    /**
     * Runtime kill switch for ambient envelope admission. Unset or any value other than
     * {@code "false"} keeps admission enabled; {@code "false"} restores fail-closed behaviour.
     */
    static final String ENABLED_PROPERTY = "turboism.editorAmbientEnvelopeJoin";

    private static final Set<String> GROUP_UNDO_REQUIRED = Set.of(
        "cubism.editor-model.undo.group-undo"
    );

    private final EditorAuthoringTransactionCoordinator coordinator;

    private HostUndoMutationScope(final EditorAuthoringTransactionCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /**
     * Returns the join adapter for the ambient authoring transaction open on this thread, or
     * empty when no ambient scope is active, the kill switch is off, or the host connection did
     * not verify the {@code undo.group-undo} member rollback depends on.
     */
    static Optional<HostUndoMutationScope> ambient(
        final EditorAuthoringTransactionCoordinator coordinator,
        final VerifiedMemberResolver resolver
    ) {
        if (coordinator == null || !coordinator.ambientScopeActive()) {
            return Optional.empty();
        }
        if ("false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY))) {
            return Optional.empty();
        }
        if (resolver == null || !resolver.authorizesFeature(
            EditorEditSessionSelectorContract.ADAPTER_SLICE_ID,
            EditorEditSessionSelectorContract.EDIT_BEGIN_CAPABILITY_ID,
            GROUP_UNDO_REQUIRED
        )) {
            return Optional.empty();
        }
        return Optional.of(new HostUndoMutationScope(coordinator));
    }

    /**
     * Admits one envelope's Undo/mutation/postcondition into the ambient root transaction.
     * The contribution's restored probe defaults to {@link #groupUndoApplied()}: envelopes
     * without a cheap before-state readback rely on the native group undo as their restore
     * mechanism and report unrestored when it did not run.
     */
    void admit(
        final String operationId,
        final String targetIdentity,
        final String label,
        final EditorUndoContribution.UndoAdmission admission,
        final Runnable mutation,
        final BooleanSupplier applied,
        final Set<EditorRefreshRequirement> refreshRequirements
    ) {
        admit(
            operationId,
            targetIdentity,
            label,
            admission,
            mutation,
            applied,
            () -> { },
            this::groupUndoApplied,
            refreshRequirements
        );
    }

    /**
     * Admits one envelope contribution with an explicit compensation and restored readback.
     * Sites that can read the pre-write state back cheaply supply both so rollback verifies the
     * actual model state instead of only the group-undo step's completion.
     */
    void admit(
        final String operationId,
        final String targetIdentity,
        final String label,
        final EditorUndoContribution.UndoAdmission admission,
        final Runnable mutation,
        final BooleanSupplier applied,
        final Runnable compensation,
        final BooleanSupplier restored,
        final Set<EditorRefreshRequirement> refreshRequirements
    ) {
        coordinator.mutateEnvelope(new EditorUndoContribution(
            Objects.requireNonNull(operationId, "operationId"),
            Objects.requireNonNull(targetIdentity, "targetIdentity"),
            Objects.requireNonNull(label, "label"),
            Objects.requireNonNull(admission, "admission"),
            Objects.requireNonNull(mutation, "mutation"),
            Objects.requireNonNull(applied, "applied"),
            Objects.requireNonNull(compensation, "compensation"),
            Objects.requireNonNull(restored, "restored"),
            Objects.requireNonNull(refreshRequirements, "refreshRequirements")
        ));
    }

    /** Restored probe for contributions without a cheap before-state readback. */
    boolean groupUndoApplied() {
        return coordinator.ambientGroupUndoApplied();
    }

    /**
     * Enforces the {@code cubism.editor-model.undo.add} admission contract shared by every
     * migrated envelope: the host must accept the Undo object into the root edit.
     */
    static void requireUndoAccepted(final Object accepted, final String label) {
        if (!(accepted instanceof Boolean value) || !value) {
            throw new IllegalStateException(
                "Cubism rejected the " + label + " Undo entry."
            );
        }
    }
}
