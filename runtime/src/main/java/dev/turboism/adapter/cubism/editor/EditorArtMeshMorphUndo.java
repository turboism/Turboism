package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.transaction.EditorUndoContribution;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.Objects;

/** Pure admission snapshots. The eager native creation undo must never be registered too. */
final class EditorArtMeshMorphUndo implements EditorUndoContribution.UndoAdmission {
    private final VerifiedMemberResolver resolver;
    private final EditorArtMeshMorphState before;
    private final Runnable requireCurrent;
    private final Runnable refresh;
    private Object captured;

    EditorArtMeshMorphUndo(
            final VerifiedMemberResolver resolver,
            final EditorArtMeshMorphState before,
            final Runnable requireCurrent,
            final Runnable refresh) {
        this.resolver = Objects.requireNonNull(resolver);
        this.before = Objects.requireNonNull(before);
        this.requireCurrent = Objects.requireNonNull(requireCurrent);
        this.refresh = Objects.requireNonNull(refresh);
    }

    @Override
    public void admit(final Object edit, final String label) {
        requireCurrent.run();
        if (captured != null) throw new IllegalStateException("morph Undo already admitted");
        final Object forms = resolver.construct("cubism.editor-model.list-undo.create", label, before.formList);
        final Object targets =
                resolver.invoke("cubism.editor-model.morph-target-set.create-undo", before.targetSet, label);
        final Object group = resolver.construct("cubism.editor-model.group-undo.create", label);
        add(group, forms);
        add(group, targets);
        final Object listener = resolver.createFunctionalProxy("cubism.editor-model.undo-listener.class", ignored -> {
            // Fail closed rather than refresh another document; do not hide listener errors.
            // Native listener exception isolation remains an exact-host observation.
            requireCurrent.run();
            refresh.run();
            return null;
        });
        if (!Boolean.TRUE.equals(resolver.invoke("cubism.editor-model.undo.add-listener", group, listener))) {
            throw new IllegalStateException("morph Undo listener admission failed");
        }
        // All snapshots and their listener exist before the one root registration. No eager call here.
        add(edit, group);
        captured = group;
    }

    void restore() {
        requireCurrent.run();
        // Call the captured group directly, even when native abort has already replayed it.
        // Do not move the history stack: reverse contributions rebuild before_k then the root.
        resolver.invoke(
                "cubism.editor-model.undo.execute-undo", Objects.requireNonNull(captured, "unadmitted morph Undo"));
    }

    private void add(final Object group, final Object child) {
        if (!Boolean.TRUE.equals(resolver.invoke("cubism.editor-model.undo.add", group, child, Boolean.TRUE))) {
            throw new IllegalStateException("morph Undo admission failed");
        }
    }
}
