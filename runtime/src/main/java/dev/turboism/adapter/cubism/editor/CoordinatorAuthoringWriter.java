package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.host.EditorHostThread;
import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringWrite;
import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringWriter;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorEditSessionSelectorContract;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The single authoring write envelope: host-thread dispatch, the ambient-versus-standalone
 * decision, and the coordinator call that owns the native bracket, Undo admission, history
 * verification, refresh, dirty marking and rollback.
 *
 * <p>A migrated access family drops its hand-written {@code edit-mode.begin/…/end} branch and its
 * ambient-join branch and instead submits one {@link EditorAuthoringWrite}. With no ambient root
 * the coordinator opens a standalone root with the same admission, rollback and verification
 * semantics; inside one the write joins the shared root edit through
 * {@link EditorAuthoringTransactionCoordinator#mutateEnvelopeOrStandalone}.</p>
 *
 * <p>The runtime kill switch {@value HostUndoMutationScope#ENABLED_PROPERTY} keeps exactly one
 * meaning here: {@code false} means no envelope joins at all. Inside an ambient root such a write
 * fails closed with the typed {@link EditorAmbientTransactionRejection} before any native edit
 * begins; outside one it runs its own standalone transaction, exactly like the envelope families
 * that have not migrated yet. Admission also requires the verified native
 * {@code cubism.editor-model.undo.group-undo} row, because that member is the only mechanism that
 * rewinds admitted Undo objects when the root recovers.</p>
 */
final class CoordinatorAuthoringWriter implements EditorAuthoringWriter {

    private static final Set<String> GROUP_UNDO_REQUIRED = Set.of("cubism.editor-model.undo.group-undo");

    private final EditorAuthoringTransactionCoordinator coordinator;
    private final VerifiedMemberResolver resolver;
    private final Supplier<EditorAuthoringTransactionCoordinator.Binding> binding;

    CoordinatorAuthoringWriter(
            final EditorAuthoringTransactionCoordinator coordinator,
            final VerifiedMemberResolver resolver,
            final Supplier<EditorAuthoringTransactionCoordinator.Binding> binding) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    @Override
    public void apply(final EditorAuthoringWrite write) {
        final EditorAuthoringWrite checked = Objects.requireNonNull(write, "write");
        EditorHostThread.dispatch("Cubism authoring write " + checked.operationId(), () -> {
            applyOnHostThread(checked);
            return null;
        });
    }

    private void applyOnHostThread(final EditorAuthoringWrite write) {
        EditorHostThread.requireHostThread("Cubism authoring write " + write.operationId());
        if (!joinsAmbientRoot()) {
            throw new EditorAmbientTransactionRejection(write.refusalLabel());
        }
        coordinator.mutateEnvelopeOrStandalone(binding.get(), write.contribution(coordinator::ambientGroupUndoApplied));
    }

    /**
     * Whether this write may join the ambient root open on this thread. A write with no ambient
     * root never joins one: it runs its own standalone transaction, so only the ambient case
     * consults the kill switch and the verified group-undo row.
     */
    private boolean joinsAmbientRoot() {
        if (!coordinator.ambientScopeActive()) return true;
        if ("false".equalsIgnoreCase(System.getProperty(HostUndoMutationScope.ENABLED_PROPERTY))) return false;
        return resolver.authorizesFeature(
                EditorEditSessionSelectorContract.ADAPTER_SLICE_ID,
                EditorEditSessionSelectorContract.EDIT_BEGIN_CAPABILITY_ID,
                GROUP_UNDO_REQUIRED);
    }
}
