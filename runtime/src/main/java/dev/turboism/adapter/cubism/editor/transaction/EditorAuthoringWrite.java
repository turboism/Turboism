package dev.turboism.adapter.cubism.editor.transaction;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * One changed Editor authoring primitive, built by an access family and executed by an
 * {@link EditorAuthoringWriter}.
 *
 * <p>The builder carries only what the per-site inventory shows is genuinely different between
 * writes: the native Undo admission, the mutation, the postcondition readback, the before-state
 * compensation with its rollback readback, and the refresh work the commit coalesces. Everything
 * else — the ambient-versus-standalone decision, the root {@code edit-mode} bracket, history
 * preparation and verification, refresh, dirty marking, rollback and receipts — belongs to the
 * {@link EditorAuthoringTransactionCoordinator} the writer drives.</p>
 *
 * <p>Invariants a writer enforces for every write: exactly one native edit per write; the Undo
 * object is admitted before the mutation runs; the postcondition is read back immediately after
 * the mutation; a no-change write never moves the history position; refresh work runs once for the
 * whole root; and a failure group-undies the still-open root, closes it with {@code abort=true},
 * and then verifies each compensation in reverse admission order. The native Undo label is frozen
 * bytes: it is {@link #label()} and is never derived from the target.</p>
 */
public final class EditorAuthoringWrite {

    /** How the write proves the mutation reached the native model. */
    public enum Postcondition {
        /** The site reads the changed native value back and compares it with the requested value. */
        READBACK,
        /**
         * Only admission is verified: the site has no cheap value readback, so the contribution
         * reports {@code applied = true}. Declared explicitly so a new write cannot omit the probe
         * by accident — the harm is a runtime that reports a write the user cannot see applied.
         */
        ADMISSION_ONLY
    }

    private final String operationId;
    private final String label;
    private final String targetIdentity;
    private final String refusalLabel;
    private final EditorUndoContribution.UndoAdmission undoAdmission;
    private final Runnable mutation;
    private final Postcondition postcondition;
    private final BooleanSupplier applied;
    private final Runnable compensation;
    private final BooleanSupplier restored;
    private final Set<EditorRefreshRequirement> refreshRequirements;

    private EditorAuthoringWrite(final Builder builder) {
        this.operationId = builder.operationId;
        this.label = builder.label;
        this.targetIdentity = builder.targetIdentity;
        this.refusalLabel = builder.refusalLabel;
        this.undoAdmission = builder.undoAdmission;
        this.mutation = builder.mutation;
        this.postcondition = builder.postcondition;
        this.applied = builder.applied;
        this.compensation = builder.compensation;
        this.restored = builder.restored;
        this.refreshRequirements = builder.refreshRequirements;
    }

    /**
     * Starts one authoring write.
     *
     * @param operationId stable operation identity carried by the history detail origin
     * @param label native Undo presentation name, frozen bytes
     * @return mutable builder for this write
     */
    public static Builder of(final String operationId, final String label) {
        return new Builder(operationId, label);
    }

    /**
     * Projects this write onto the contribution contract the coordinator consumes.
     *
     * @param groupUndoApplied ambient root's native group-undo probe, used as the rollback readback
     *     when the site declares no before-state readback of its own
     * @return contribution ready for the coordinator
     */
    public EditorUndoContribution contribution(final BooleanSupplier groupUndoApplied) {
        final BooleanSupplier checkedGroupUndo = Objects.requireNonNull(groupUndoApplied, "groupUndoApplied");
        return new EditorUndoContribution(
                operationId,
                targetIdentity,
                label,
                undoAdmission,
                mutation,
                applied,
                compensation,
                restored != null ? restored : checkedGroupUndo,
                refreshRequirements);
    }

    /**
     * @return stable operation identity carried by the history detail origin
     */
    public String operationId() {
        return operationId;
    }

    /**
     * @return native Undo presentation name
     */
    public String label() {
        return label;
    }

    /**
     * @return identity of the primitive this write changes, carried by the contribution
     */
    public String targetIdentity() {
        return targetIdentity;
    }

    /**
     * Human-readable native write name used when this write cannot join the open ambient root.
     *
     * @return write name for the typed ambient rejection
     */
    public String refusalLabel() {
        return refusalLabel;
    }

    /**
     * @return whether the write reads its result back or only verifies admission
     */
    public Postcondition postcondition() {
        return postcondition;
    }

    /**
     * @return coalesced refresh work the root commit performs once
     */
    public Set<EditorRefreshRequirement> refreshRequirements() {
        return refreshRequirements;
    }

    /** Builds one {@link EditorAuthoringWrite}. */
    public static final class Builder {

        private final String operationId;
        private final String label;
        private String targetIdentity;
        private String refusalLabel;
        private EditorUndoContribution.UndoAdmission undoAdmission;
        private Runnable mutation;
        private Postcondition postcondition;
        private BooleanSupplier applied;
        private Runnable compensation = () -> {};
        private BooleanSupplier restored;
        private Set<EditorRefreshRequirement> refreshRequirements = Set.of();

        private Builder(final String operationId, final String label) {
            this.operationId = operationId;
            this.label = label;
            this.refusalLabel = label;
        }

        /**
         * Sets the identity of the changed primitive.
         *
         * @param targetIdentity stable per-object identity, never a run-dependent hash
         * @return this builder
         */
        public Builder subject(final String targetIdentity) {
            this.targetIdentity = targetIdentity;
            return this;
        }

        /**
         * Sets the human-readable native write name used by the typed ambient rejection.
         *
         * @param refusalLabel native write name, for example {@code "Model.setName"}
         * @return this builder
         */
        public Builder refusalLabel(final String refusalLabel) {
            this.refusalLabel = refusalLabel;
            return this;
        }

        /**
         * Sets the native Undo admission: it adds this write's Undo object to the supplied root
         * edit. The coordinator admits before the mutation runs, so an admission that already
         * mutated the host is still visible to recovery.
         *
         * @param undoAdmission native admission callback
         * @return this builder
         */
        public Builder admit(final EditorUndoContribution.UndoAdmission undoAdmission) {
            this.undoAdmission = undoAdmission;
            return this;
        }

        /**
         * Sets the native write. May be a no-op for construct-and-redo writes whose Undo factory
         * already performed the write.
         *
         * @param mutation native write callback
         * @return this builder
         */
        public Builder mutate(final Runnable mutation) {
            this.mutation = mutation;
            return this;
        }

        /**
         * Declares a value readback that proves the mutation was applied.
         *
         * @param readback postcondition over the native model
         * @return this builder
         */
        public Builder verifyApplied(final BooleanSupplier readback) {
            this.postcondition = Postcondition.READBACK;
            this.applied = readback;
            return this;
        }

        /**
         * Declares that this write only verifies admission because it has no cheap value readback.
         *
         * @return this builder
         */
        public Builder admissionVerified() {
            this.postcondition = Postcondition.ADMISSION_ONLY;
            this.applied = () -> true;
            return this;
        }

        /**
         * Sets the before-state restore the rollback runs.
         *
         * @param compensation native compensation callback
         * @return this builder
         */
        public Builder compensate(final Runnable compensation) {
            this.compensation = Objects.requireNonNull(compensation, "compensation");
            return this;
        }

        /**
         * Sets the readback that verifies the compensation restored the before state.
         *
         * @param readback rollback postcondition over the native model
         * @return this builder
         */
        public Builder restored(final BooleanSupplier readback) {
            this.restored = Objects.requireNonNull(readback, "readback");
            return this;
        }

        /**
         * Sets the refresh work the root commit coalesces after the last admission.
         *
         * @param refreshRequirements requested refresh requirements
         * @return this builder
         */
        public Builder refresh(final Set<EditorRefreshRequirement> refreshRequirements) {
            final Set<EditorRefreshRequirement> requested =
                    Objects.requireNonNull(refreshRequirements, "refreshRequirements");
            this.refreshRequirements = requested.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(requested));
            return this;
        }

        /**
         * Freezes the write.
         *
         * @return immutable write
         * @throws IllegalStateException when a mandatory part of the write is missing
         */
        public EditorAuthoringWrite build() {
            requireText(operationId, "operationId");
            requireText(label, "label");
            requireText(targetIdentity, "targetIdentity");
            requireText(refusalLabel, "refusalLabel");
            Objects.requireNonNull(undoAdmission, "admit(...)");
            Objects.requireNonNull(mutation, "mutate(...)");
            if (postcondition == null) {
                throw new IllegalStateException(
                        "An authoring write must declare verifyApplied(...) or admissionVerified().");
            }
            Objects.requireNonNull(applied, "applied");
            return new EditorAuthoringWrite(this);
        }

        private static void requireText(final String value, final String name) {
            final String checked = Objects.requireNonNull(value, name).strip();
            if (checked.isEmpty()) throw new IllegalStateException(name + " must not be blank");
        }
    }
}
