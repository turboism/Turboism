package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.host.EditorHostThread;
import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringWrite;
import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringWriter;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorModelProfileSelectorContract;
import dev.turboism.sdk.cubism.model.Canvas;
import dev.turboism.sdk.cubism.model.ModelProfile;
import java.util.EnumSet;
import java.util.Objects;

/** Exact, generation-bound Editor projection for model name writes, model profile reads, and canvas metrics. */
final class EditorModelProfileAccess {

    private static final String OPERATION_ID = "cubism.model.set-name";
    private static final String ACTION_NAME = "Turboism: Set Model Name";

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;
    private final EditorAuthoringWriter authoringWriter;

    EditorModelProfileAccess(
            final VerifiedMemberResolver resolver,
            final EditorParameterCombinedAccess.ModelGuard modelGuard,
            final EditorAuthoringWriter authoringWriter) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
        this.authoringWriter = Objects.requireNonNull(authoringWriter, "authoringWriter");
    }

    void setName(final String identity, final Object source, final Object model, final String name) {
        EditorHostThread.dispatch("Cubism model name write", () -> {
            setNameOnEdt(identity, source, model, name);
            return null;
        });
    }

    private void setNameOnEdt(final String identity, final Object source, final Object model, final String name) {
        EditorHostThread.requireHostThread("Cubism model name write");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (!resolver.authorizesFeature(
                EditorModelProfileSelectorContract.ADAPTER_SLICE_ID,
                EditorModelProfileSelectorContract.NAME_WRITE_CAPABILITY_ID,
                EditorModelProfileSelectorContract.NAME_WRITE_REQUIRED_ALIASES)) {
            throw new UnsupportedOperationException(
                    "Model-name writing is unavailable without exact verified host evidence.");
        }
        modelGuard.requireCurrent(identity, model);
        final Object current = resolver.invoke("cubism.editor-model.model-source.name", source);
        if (current instanceof String existing && existing.equals(name)) return;
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final String beforeName = current instanceof String text ? text : null;
        final EditorAuthoringWrite.Builder write = EditorAuthoringWrite.of(OPERATION_ID, ACTION_NAME)
                .subject(identity + ":model:name")
                .refusalLabel("Model.setName")
                .admit((edit, transactionLabel) -> {
                    final Object undo =
                            resolver.construct("cubism.editor-model.simple-undo.create", ACTION_NAME, source, null);
                    final Object accepted = resolver.invoke("cubism.editor-model.undo.add", edit, undo, Boolean.TRUE);
                    if (!(accepted instanceof Boolean admitted) || !admitted) {
                        throw new IllegalStateException("Cubism rejected the model-name Undo entry.");
                    }
                    final Object listener =
                            resolver.createFunctionalProxy("cubism.editor-model.undo-listener.class", ignored -> {
                                resolver.invoke("cubism.editor-model.model-source.update-instances", source);
                                refresh(app);
                                return null;
                            });
                    resolver.invoke("cubism.editor-model.undo.add-listener", undo, listener);
                })
                .mutate(() -> resolver.invoke("cubism.editor-model.model-source.set-name", source, name))
                .verifyApplied(() -> name.equals(resolver.invoke("cubism.editor-model.model-source.name", source)))
                .refresh(EnumSet.of(
                        EditorRefreshRequirement.MODEL_INSTANCES,
                        EditorRefreshRequirement.PART_PALETTE,
                        EditorRefreshRequirement.CANVAS,
                        EditorRefreshRequirement.MARK_DIRTY));
        if (beforeName != null) {
            write.compensate(() -> resolver.invoke("cubism.editor-model.model-source.set-name", source, beforeName))
                    .restored(
                            () -> beforeName.equals(resolver.invoke("cubism.editor-model.model-source.name", source)));
        }
        authoringWriter.apply(write.build());
        modelGuard.requireCurrent(identity, model);
    }

    ModelProfile profile(final String identity, final Object source, final Object model) {
        requireProfileAuthorization();
        modelGuard.requireCurrent(identity, model);
        final Object info = resolver.invoke("cubism.editor-model.model-source.model-info", source);
        if (!resolver.isInstance("cubism.editor-model.model-info.class", info)) {
            throw new IllegalStateException("Editor model info is unavailable.");
        }
        final Object origin = resolver.invoke("cubism.editor-model.model-info.origin", info);
        if (!resolver.isInstance("cubism.editor-model.point.class", origin)) {
            throw new IllegalStateException("Editor model origin is unavailable.");
        }
        final Object rawX = resolver.invoke("cubism.editor-model.point.x", origin);
        final Object rawY = resolver.invoke("cubism.editor-model.point.y", origin);
        final Object rawPpu = resolver.invoke("cubism.editor-model.model-info.pixels-per-unit", info);
        if (!(rawX instanceof Integer x)
                || !(rawY instanceof Integer y)
                || !(rawPpu instanceof Float ppu)
                || !Float.isFinite(ppu)) {
            throw new IllegalStateException("Editor model profile metrics are invalid.");
        }
        return new EditorProfile(x.floatValue(), y.floatValue(), ppu);
    }

    Canvas canvas(final String identity, final Object source, final Object model) {
        requireProfileAuthorization();
        modelGuard.requireCurrent(identity, model);
        final Object info = resolver.invoke("cubism.editor-model.model-source.model-info", source);
        if (!resolver.isInstance("cubism.editor-model.model-info.class", info)) {
            throw new IllegalStateException("Editor model info is unavailable.");
        }
        final Object origin = resolver.invoke("cubism.editor-model.model-info.origin", info);
        if (!resolver.isInstance("cubism.editor-model.point.class", origin)) {
            throw new IllegalStateException("Editor model origin is unavailable.");
        }
        final Object canvas = resolver.invoke("cubism.editor-model.model-source.canvas", source);
        if (!resolver.isInstance("cubism.editor-model.image-canvas.class", canvas)) {
            throw new IllegalStateException("Editor model canvas is unavailable.");
        }
        final Object rawX = resolver.invoke("cubism.editor-model.point.x", origin);
        final Object rawY = resolver.invoke("cubism.editor-model.point.y", origin);
        final Object rawPpu = resolver.invoke("cubism.editor-model.model-info.pixels-per-unit", info);
        final Object rawWidth = resolver.invoke("cubism.editor-model.image-canvas.width", canvas);
        final Object rawHeight = resolver.invoke("cubism.editor-model.image-canvas.height", canvas);
        if (!(rawX instanceof Integer x)
                || !(rawY instanceof Integer y)
                || !(rawPpu instanceof Float ppu)
                || !Float.isFinite(ppu)
                || !(rawWidth instanceof Integer width)
                || !(rawHeight instanceof Integer height)) {
            throw new IllegalStateException("Editor model canvas metrics are invalid.");
        }
        return new EditorCanvas(width, height, x.floatValue(), y.floatValue(), ppu);
    }

    private void requireProfileAuthorization() {
        if (!resolver.authorizesFeature(
                EditorModelProfileSelectorContract.ADAPTER_SLICE_ID,
                EditorModelProfileSelectorContract.PROFILE_READ_CAPABILITY_ID,
                EditorModelProfileSelectorContract.PROFILE_READ_REQUIRED_ALIASES)) {
            throw new UnsupportedOperationException(
                    "Model profile reading is unavailable without exact verified host evidence.");
        }
    }

    private void refresh(final Object app) {
        final Object completePack = resolver.invoke("cubism.editor-model.app-controller.complete-pack", app);
        resolver.invoke("cubism.editor-model.complete-pack.update-part-palette", completePack, Boolean.TRUE);
        resolver.invoke("cubism.editor-model.complete-pack.repaint-canvas", completePack, Boolean.TRUE);
    }

    private record EditorProfile(float originXPixels, float originYPixels, float pixelsPerUnit)
            implements ModelProfile {
        @Override
        public float pixelsPerUnit() {
            return pixelsPerUnit;
        }

        @Override
        public float originXPixels() {
            return originXPixels;
        }

        @Override
        public float originYPixels() {
            return originYPixels;
        }
    }

    private record EditorCanvas(
            float widthPixels, float heightPixels, float originXPixels, float originYPixels, float pixelsPerUnit)
            implements Canvas {
        @Override
        public float widthPixels() {
            return widthPixels;
        }

        @Override
        public float heightPixels() {
            return heightPixels;
        }

        @Override
        public float originXPixels() {
            return originXPixels;
        }

        @Override
        public float originYPixels() {
            return originYPixels;
        }

        @Override
        public float pixelsPerUnit() {
            return pixelsPerUnit;
        }
    }
}
