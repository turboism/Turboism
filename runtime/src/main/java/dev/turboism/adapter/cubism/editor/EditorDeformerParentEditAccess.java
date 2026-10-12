package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.adapter.cubism.editor.transaction.EditorUndoContribution;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.EnumSet;
import java.util.Objects;
import java.util.function.Supplier;

/** Native parent conversion participates in the root and verifies its full stored before image. */
final class EditorDeformerParentEditAccess {
    private static final String M = "cubism.editor-model.";

    static void change(
            final VerifiedMemberResolver resolver,
            final EditorAuthoringTransactionCoordinator coordinator,
            final EditorAuthoringTransactionCoordinator.Binding binding,
            final Object model,
            final Object source,
            final String id,
            final Object targetGuid,
            final Object expectedParent,
            final Supplier<?> storedAuthoring,
            final Runnable update) {
        if (!EditorReviewedGeneration.admits(resolver))
            throw new UnsupportedOperationException(
                    "Deformer parent root conversion requires a verified reviewed host");
        final Object before = storedAuthoring.get();
        final Supplier<Object> parent =
                () -> resolver.invoke(M + "parameter-controllable-source.target-deformer-source", source);
        final Object oldParent = parent.get();
        final Object[] undo = new Object[1];
        coordinator.mutate(
                binding,
                new EditorUndoContribution(
                        "cubism.deformer.set-target-deformer",
                        binding.modelIdentity() + ":deformer:" + id,
                        "Change Deformer Target",
                        (edit, label) -> {
                            final Object handler = resolver.invoke(M + "parameter-controllable-source.handler", source);
                            // The factory eagerly transforms all stored coordinates before Undo admission.
                            undo[0] = resolver.invoke(
                                    M + "parameter-controllable-handler.change-target-deformer-guid",
                                    handler,
                                    model,
                                    targetGuid,
                                    Boolean.FALSE);
                            if (!Boolean.TRUE.equals(resolver.invoke(M + "undo.add", edit, undo[0], Boolean.TRUE)))
                                throw new IllegalStateException("Deformer parent Undo admission failed");
                            final Object listener =
                                    resolver.createFunctionalProxy(M + "undo-listener.class", ignored -> {
                                        update.run();
                                        return null;
                                    });
                            resolver.invoke(M + "undo.add-listener", undo[0], listener);
                        },
                        update,
                        () -> {
                            storedAuthoring.get();
                            return parent.get() == expectedParent;
                        },
                        () -> {
                            if (!coordinator.isCreatedTargetRestored(binding, source) && undo[0] != null) {
                                resolver.invoke(M + "undo.execute-undo", undo[0]);
                                update.run();
                            }
                        },
                        () -> coordinator.isCreatedTargetRestored(binding, source)
                                || (parent.get() == oldParent && Objects.equals(before, storedAuthoring.get())),
                        EnumSet.of(EditorRefreshRequirement.MARK_DIRTY)));
    }
}
