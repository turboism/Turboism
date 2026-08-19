package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorDefaultKeyformLockReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorDefaultKeyformLockWriteSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.util.Objects;

/** Verified Editor-native default-keyform lock access. */
final class EditorDefaultKeyformLockAccess {

    private static final String ACTION_NAME = "Turboism: Set Default Keyform Lock";

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;

    EditorDefaultKeyformLockAccess(
        final VerifiedMemberResolver resolver,
        final EditorParameterCombinedAccess.ModelGuard modelGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
    }

    boolean locked(
        final String expectedIdentity,
        final Object source,
        final Object expectedModel
    ) {
        requireReadAuthorization();
        modelGuard.requireCurrent(expectedIdentity, expectedModel);
        return locked(source);
    }

    void setLocked(
        final String expectedIdentity,
        final Object source,
        final Object expectedModel,
        final boolean locked
    ) {
        requireWriteAuthorization();
        modelGuard.requireCurrent(expectedIdentity, expectedModel);
        if (locked(source) == locked) {
            return;
        }

        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object undo = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, ACTION_NAME
        );
        boolean completed = false;
        try {
            addUndo(undo, source);
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    refreshUi(app);
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", undo, listener);
            resolver.invoke(
                "cubism.editor-model.model-source.set-default-keyform-locked",
                source,
                Boolean.valueOf(locked)
            );
            refreshUi(app);
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end",
                editMode,
                Boolean.valueOf(!completed),
                null
            );
        }
        modelGuard.requireCurrent(expectedIdentity, expectedModel);
    }

    private boolean locked(final Object source) {
        final Object value = resolver.invoke(
            "cubism.editor-model.model-source.default-keyform-locked", source
        );
        if (!(value instanceof Boolean locked)) {
            throw unavailable("Editor default-keyform lock state is unavailable.");
        }
        return locked;
    }

    private void addUndo(final Object edit, final Object source) {
        final Object sourceUndo = resolver.construct(
            "cubism.editor-model.simple-undo.create",
            ACTION_NAME,
            source,
            null
        );
        final Object accepted = resolver.invoke(
            "cubism.editor-model.undo.add",
            edit,
            sourceUndo,
            Boolean.TRUE
        );
        if (!(accepted instanceof Boolean value) || !value) {
            throw new IllegalStateException("Cubism rejected the default-keyform lock Undo entry.");
        }
    }

    private void refreshUi(final Object app) {
        final Object completePack = resolver.invoke(
            "cubism.editor-model.app-controller.complete-pack", app
        );
        resolver.invoke(
            "cubism.editor-model.complete-pack.update-parameter",
            completePack,
            Boolean.TRUE
        );
        resolver.invoke(
            "cubism.editor-model.complete-pack.repaint-canvas",
            completePack,
            Boolean.TRUE
        );
        final Object mainFrame = resolver.invoke(
            "cubism.editor-model.app-controller.main-frame", app
        );
        final Object palette = resolver.invoke(
            "cubism.editor-model.main-frame.parameter-palette", mainFrame
        );
        final Object paletteView = resolver.invoke(
            "cubism.editor-model.parameter-palette.view", palette
        );
        final Object operation = resolver.invoke(
            "cubism.editor-model.parameter-palette-view.operation", paletteView
        );
        resolver.invoke(
            "cubism.editor-model.parameter-operation.refresh",
            operation,
            Boolean.TRUE
        );
    }

    private void requireReadAuthorization() {
        if (!resolver.authorizesFeature(
            EditorDefaultKeyformLockReadSelectorContract.ADAPTER_SLICE_ID,
            EditorDefaultKeyformLockReadSelectorContract.CAPABILITY_ID,
            EditorDefaultKeyformLockReadSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Default-keyform lock access is unavailable without exact verified host evidence."
            );
        }
    }

    private void requireWriteAuthorization() {
        if (!resolver.authorizesFeature(
            EditorDefaultKeyformLockWriteSelectorContract.ADAPTER_SLICE_ID,
            EditorDefaultKeyformLockWriteSelectorContract.CAPABILITY_ID,
            EditorDefaultKeyformLockWriteSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Default-keyform lock editing is unavailable without exact verified host evidence."
            );
        }
    }

    private static IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }
}
