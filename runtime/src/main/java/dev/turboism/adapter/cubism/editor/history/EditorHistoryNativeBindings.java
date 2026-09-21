package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.util.Objects;

/**
 * Resolves the active Modeling document's native Undo manager through admitted selectors only.
 *
 * <p>This is the same binding the history snapshot uses; it exists separately so the ingress and
 * the snapshot cannot drift into two different notions of "the active document's history".</p>
 */
public final class EditorHistoryNativeBindings {

    private EditorHistoryNativeBindings() {
    }

    /**
     * Resolves the native Undo manager of the active Modeling document.
     *
     * @param resolver the verified editor-model resolver of the active connection
     * @return the admitted {@code CUndoManager} instance
     * @throws IllegalStateException when no Modeling document or no history manager is available;
     *     callers must treat this as "no history capture right now" rather than as a failure
     */
    public static Object undoManager(final VerifiedMemberResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document",
            app
        );
        if (document == null
            || !resolver.isInstance("cubism.editor-model.modeling-document.class", document)) {
            throw new IllegalStateException("Active Modeling document is unavailable");
        }
        final Object manager = resolver.invoke(
            "cubism.editor-history.document.undo-manager",
            document
        );
        if (!resolver.isInstance("cubism.editor-history.manager.class", manager)) {
            throw new IllegalStateException("Active history manager is unavailable");
        }
        return manager;
    }
}
