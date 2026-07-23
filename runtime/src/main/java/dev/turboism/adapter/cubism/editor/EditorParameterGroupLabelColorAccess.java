package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorParameterGroupLabelColorReadSelectorContract;
import dev.turboism.mapping.verification.EditorParameterGroupLabelColorWriteSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.Color;

import java.util.Objects;

/** Verified Editor-native parameter-group label-color access. */
final class EditorParameterGroupLabelColorAccess {

    private static final String ACTION_NAME = "Turboism: Set Parameter Group Label Color";

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;

    EditorParameterGroupLabelColorAccess(
        final VerifiedMemberResolver resolver,
        final EditorParameterCombinedAccess.ModelGuard modelGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
    }

    Color color(
        final String expectedIdentity,
        final Object expectedModel,
        final Runnable groupGuard,
        final Object group
    ) {
        requireReadAuthorization();
        modelGuard.requireCurrent(expectedIdentity, expectedModel);
        groupGuard.run();
        return readColor(labelColor(group));
    }

    void setColor(
        final String expectedIdentity,
        final Object expectedModel,
        final Runnable groupGuard,
        final Object group,
        final Color color
    ) {
        Objects.requireNonNull(color, "color");
        requireWriteAuthorization();
        modelGuard.requireCurrent(expectedIdentity, expectedModel);
        groupGuard.run();
        final Object labelColor = labelColor(group);
        if (readColor(labelColor).equals(color)) {
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
            addUndo(undo, labelColor);
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    refreshUi(app);
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", undo, listener);
            final Object hostColor = resolver.construct(
                "cubism.editor-model.color.create",
                Float.valueOf(color.red()),
                Float.valueOf(color.green()),
                Float.valueOf(color.blue()),
                Float.valueOf(color.alpha())
            );
            resolver.invoke(
                "cubism.editor-model.label-color.set-color",
                labelColor,
                resolver.readStaticField("cubism.editor-model.label-color-type.custom"),
                hostColor
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
        groupGuard.run();
    }

    private Object labelColor(final Object group) {
        final Object labelColor = resolver.invoke(
            "cubism.editor-model.parameter-group.label-color", group
        );
        if (!resolver.isInstance("cubism.editor-model.label-color.class", labelColor)) {
            throw unavailable("Editor parameter group label color is unavailable.");
        }
        return labelColor;
    }

    private Color readColor(final Object labelColor) {
        final Object color = resolver.invoke("cubism.editor-model.label-color.color", labelColor);
        if (!resolver.isInstance("cubism.editor-model.color.class", color)) {
            throw unavailable("Editor parameter group effective color is unavailable.");
        }
        return new Color(
            number(resolver.invoke("cubism.editor-model.color.red", color)),
            number(resolver.invoke("cubism.editor-model.color.green", color)),
            number(resolver.invoke("cubism.editor-model.color.blue", color)),
            number(resolver.invoke("cubism.editor-model.color.alpha", color))
        );
    }

    private void addUndo(final Object edit, final Object labelColor) {
        final Object colorUndo = resolver.construct(
            "cubism.editor-model.simple-undo.create",
            ACTION_NAME,
            labelColor,
            null
        );
        final Object accepted = resolver.invoke(
            "cubism.editor-model.undo.add",
            edit,
            colorUndo,
            Boolean.TRUE
        );
        if (!(accepted instanceof Boolean value) || !value) {
            throw new IllegalStateException("Cubism rejected the parameter-group color Undo entry.");
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
            EditorParameterGroupLabelColorReadSelectorContract.ADAPTER_SLICE_ID,
            EditorParameterGroupLabelColorReadSelectorContract.CAPABILITY_ID,
            EditorParameterGroupLabelColorReadSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Parameter-group label color access is unavailable without exact verified host evidence."
            );
        }
    }

    private void requireWriteAuthorization() {
        if (!resolver.authorizesFeature(
            EditorParameterGroupLabelColorWriteSelectorContract.ADAPTER_SLICE_ID,
            EditorParameterGroupLabelColorWriteSelectorContract.CAPABILITY_ID,
            EditorParameterGroupLabelColorWriteSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Parameter-group label color editing is unavailable without exact verified host evidence."
            );
        }
    }

    private static float number(final Object value) {
        if (!(value instanceof Number number) || !Float.isFinite(number.floatValue())) {
            throw unavailable("Editor parameter group color component is invalid.");
        }
        return number.floatValue();
    }

    private static IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }
}
