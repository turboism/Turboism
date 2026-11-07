package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.host.EditorHostThread;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorAnimationSceneOperationSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAnimationTimelineEditSelectorContract;
import dev.turboism.sdk.cubism.model.AnimationCurveType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Verified host plumbing shared by the animation timeline projection in
 * {@link EditorAnimationTimelineAccess}.
 *
 * <p>Read admission, host value decoding, live-scene resolution, and the native
 * {@code ACEditMode} undo envelope live here so the projection classes and the access that builds
 * them cannot drift apart: writes require the timeline edit capability and run as
 * {@code beginEdit} → {@code SimpleUndo} snapshot → {@code GroupUndo.addEdit} → mutation →
 * {@code endEdit}.</p>
 */
final class EditorTimelineAccessCore {

    final VerifiedMemberResolver resolver;

    private final EditorParameterCombinedAccess.ModelGuard modelGuard;
    private final String identity;
    private final Object model;
    private final dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator
            authoringCoordinator;

    EditorTimelineAccessCore(
            final VerifiedMemberResolver resolver,
            final EditorParameterCombinedAccess.ModelGuard modelGuard,
            final String identity,
            final Object model,
            final dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator
                    authoringCoordinator) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.model = Objects.requireNonNull(model, "model");
        this.authoringCoordinator = authoringCoordinator;
    }

    void requireCurrent() {
        modelGuard.requireCurrent(identity, model);
    }

    boolean writeAuthorized() {
        return resolver.authorizesFeature(
                EditorAnimationTimelineEditSelectorContract.ADAPTER_SLICE_ID,
                EditorAnimationTimelineEditSelectorContract.WRITE_CAPABILITY_ID,
                EditorAnimationTimelineEditSelectorContract.WRITE_REQUIRED_ALIASES);
    }

    void requireWriteAuthorization() {
        if (!writeAuthorized()) {
            throw new UnsupportedOperationException(
                    "Animation timeline writing is unavailable without exact verified host evidence.");
        }
    }

    void requirePlaybackAuthorization() {
        if (!resolver.authorizesFeature(
                EditorAnimationSceneOperationSelectorContract.ADAPTER_SLICE_ID,
                EditorAnimationSceneOperationSelectorContract.PLAYBACK_CAPABILITY_ID,
                EditorAnimationSceneOperationSelectorContract.PLAYBACK_REQUIRED_ALIASES)) {
            throw new UnsupportedOperationException(
                    "Animation playback control is unavailable without exact verified host evidence.");
        }
    }

    void requireSceneEditAuthorization() {
        if (!resolver.authorizesFeature(
                EditorAnimationSceneOperationSelectorContract.ADAPTER_SLICE_ID,
                EditorAnimationSceneOperationSelectorContract.SCENE_EDIT_CAPABILITY_ID,
                EditorAnimationSceneOperationSelectorContract.SCENE_EDIT_REQUIRED_ALIASES)) {
            throw new UnsupportedOperationException(
                    "Animation scene management is unavailable without exact verified host evidence.");
        }
    }

    void requireEvalAuthorization() {
        if (!resolver.authorizesFeature(
                EditorAnimationSceneOperationSelectorContract.ADAPTER_SLICE_ID,
                EditorAnimationSceneOperationSelectorContract.EVAL_CAPABILITY_ID,
                EditorAnimationSceneOperationSelectorContract.EVAL_REQUIRED_ALIASES)) {
            throw new UnsupportedOperationException(
                    "Animation evaluated writing is unavailable without exact verified host evidence.");
        }
    }

    // ------------------------------------------------------------------
    // scene document and write envelope
    // ------------------------------------------------------------------

    /** Resolves the live scene document owning {@code sceneSource}. */
    Object sceneDocument(final Object fileContent, final Object sceneSource) {
        requireInstance(
                "cubism.editor-model.animation-file-content.class",
                fileContent,
                "Editor animation file content is invalid.");
        for (Object candidate : list(
                resolver.invoke("cubism.editor-model.animation-file-content.scene-docs", fileContent),
                "Editor animation scene documents")) {
            if (resolver.isInstance("cubism.editor-model.scene-document.class", candidate)
                    && resolver.invoke("cubism.editor-model.scene-document.scene-source", candidate) == sceneSource) {
                return candidate;
            }
        }
        throw unavailable("Editor animation scene document is stale.");
    }

    /**
     * Runs {@code mutation} inside the scene handler's basic-data Undo envelope: the entry is the
     * handler's {@code SceneSourceBasicData_forUndo} DTO — the shape Cubism itself uses for scene
     * name/tag/curve-type edits, and why a whole-scene {@code SimpleUndo} deep copy (heavier and
     * unsafe here) is avoided. The snapshot is taken before the mutation runs, the entry joins the
     * edit group, and the scene edit mode commits it as one undo step.
     */
    void writeWithSceneBasicDataUndo(
            final Object sceneDocument, final Object sceneSource, final String label, final Runnable mutation) {
        write(
                sceneDocument,
                label,
                () -> resolver.invoke(
                        "cubism.editor-model.scene-handler.basic-undo",
                        resolver.construct("cubism.editor-model.scene-handler.create", sceneSource),
                        label),
                mutation);
    }

    void write(final Object sceneDocument, final Object undoTarget, final String label, final Runnable mutation) {
        write(
                sceneDocument,
                label,
                () -> resolver.construct("cubism.editor-model.simple-undo.create", label, undoTarget, null),
                mutation);
    }

    void write(
            final Object sceneDocument,
            final String label,
            final java.util.function.Supplier<Object> undoFactory,
            final Runnable mutation) {
        EditorHostThread.requireHostThread("Cubism animation timeline write");
        if (authoringCoordinator != null) {
            authoringCoordinator.requireNoAmbientTransaction("Animation timeline write: " + label);
        }
        final Object editMode = resolver.invoke("cubism.editor-model.scene-document.current-edit-mode", sceneDocument);
        if (editMode == null) {
            throw unavailable("Editor animation scene edit mode is unavailable.");
        }
        final Object completePack = resolver.invoke("cubism.editor-model.scene-document.complete-pack", sceneDocument);
        final Object edit = resolver.invoke("cubism.editor-model.edit-mode-base.begin", editMode, label);
        boolean completed = false;
        try {
            final Object undo = undoFactory.get();
            final Object accepted = resolver.invoke("cubism.editor-model.undo.add", edit, undo, Boolean.TRUE);
            if (!(accepted instanceof Boolean acceptedValue) || !acceptedValue) {
                throw new IllegalStateException("Cubism rejected the animation timeline Undo entry.");
            }
            final Object listener =
                    resolver.createFunctionalProxy("cubism.editor-model.undo-listener.class", ignored -> {
                        resolver.invoke("cubism.editor-model.complete-pack.update-project", completePack);
                        resolver.invoke("cubism.editor-model.complete-pack.repaint-canvas", completePack, Boolean.TRUE);
                        return null;
                    });
            resolver.invoke("cubism.editor-model.undo.add-listener", undo, listener);
            mutation.run();
            resolver.invoke("cubism.editor-model.complete-pack.update-project", completePack);
            resolver.invoke("cubism.editor-model.complete-pack.repaint-canvas", completePack, Boolean.TRUE);
            resolver.invoke("cubism.editor-model.scene-document.update-modified", sceneDocument);
            completed = true;
        } finally {
            resolver.invoke("cubism.editor-model.edit-mode-base.end", editMode, Boolean.valueOf(!completed), null);
        }
    }

    // ------------------------------------------------------------------
    // host value decoding
    // ------------------------------------------------------------------

    Object mutableSequence(final Object attribute) {
        final Object sequence = resolver.invoke("cubism.editor-model.attr-f.value-data", attribute);
        return sequence != null && resolver.isInstance("cubism.editor-model.mutable-sequence.class", sequence)
                ? sequence
                : null;
    }

    List<Integer> keyframeFrames(final Object attribute) {
        return intArray(
                resolver.invoke("cubism.editor-model.attr.key-frames", attribute),
                "Editor animation attribute keyframes");
    }

    Object curveTypeConstant(final AnimationCurveType curveType) {
        return resolver.readStaticField(
                switch (curveType) {
                    case LINEAR -> "cubism.editor-model.curve-type.linear";
                    case BEZIER -> "cubism.editor-model.curve-type.bezier";
                    case SMOOTH -> "cubism.editor-model.curve-type.smooth";
                    case STEP -> "cubism.editor-model.curve-type.step";
                    case INVERSE_STEP -> "cubism.editor-model.curve-type.inverse-step";
                });
    }

    double scalarValue(final Object attribute, final int frame) {
        final Object rawValue = resolver.invoke("cubism.editor-model.attr.value", attribute, frame);
        if (rawValue instanceof Number number) {
            return number.doubleValue();
        }
        throw unavailable("Editor animation keyframe value is invalid.");
    }

    float[] pointValueOf(final Object attribute, final int frame) {
        final Object rawValue = resolver.invoke("cubism.editor-model.attr.value", attribute, frame);
        if (resolver.isInstance("cubism.editor-model.vector2.class", rawValue)) {
            return new float[] {
                floatValue(resolver.invoke("cubism.editor-model.vector2.x", rawValue), "Editor point X"),
                floatValue(resolver.invoke("cubism.editor-model.vector2.y", rawValue), "Editor point Y")
            };
        }
        throw unavailable("Editor animation keyframe point value is invalid.");
    }

    AnimationCurveType curveType(final Object rawType) {
        if (sameInstance(rawType, "cubism.editor-model.curve-type.linear")) {
            return AnimationCurveType.LINEAR;
        }
        if (sameInstance(rawType, "cubism.editor-model.curve-type.bezier")) {
            return AnimationCurveType.BEZIER;
        }
        if (sameInstance(rawType, "cubism.editor-model.curve-type.smooth")) {
            return AnimationCurveType.SMOOTH;
        }
        if (sameInstance(rawType, "cubism.editor-model.curve-type.step")) {
            return AnimationCurveType.STEP;
        }
        if (sameInstance(rawType, "cubism.editor-model.curve-type.inverse-step")) {
            return AnimationCurveType.INVERSE_STEP;
        }
        throw unavailable("Editor animation curve type is invalid.");
    }

    boolean sameInstance(final Object value, final String fieldAlias) {
        return value != null && value == resolver.readStaticField(fieldAlias);
    }

    String guidValue(final Object rawGuid, final String label) {
        if (rawGuid == null) {
            throw unavailable(label + " is unavailable.");
        }
        return text(resolver.invoke("cubism.editor-model.guid.value", rawGuid), label);
    }

    void requireInstance(final String classAlias, final Object value, final String message) {
        if (!resolver.isInstance(classAlias, value)) {
            throw unavailable(message);
        }
    }

    List<?> list(final Object value, final String label) {
        if (!(value instanceof List<?> list)) {
            throw unavailable(label + " is unavailable.");
        }
        return List.copyOf(list);
    }

    List<Object> objectArray(final Object value, final String label) {
        if (!(value instanceof Object[] array)) {
            throw unavailable("Editor animation value is unavailable: " + label);
        }
        return List.of(array);
    }

    List<Integer> intArray(final Object value, final String label) {
        final int[] frames = rawIntArray(value, label);
        final List<Integer> result = new ArrayList<>(frames.length);
        for (int frame : frames) {
            result.add(frame);
        }
        return List.copyOf(result);
    }

    int[] rawIntArray(final Object value, final String label) {
        if (!(value instanceof int[] array)) {
            throw unavailable(label + " is unavailable.");
        }
        return array;
    }

    String text(final Object value, final String label) {
        if (!(value instanceof String result) || result.isBlank()) {
            throw unavailable(label + " is invalid.");
        }
        return result;
    }

    boolean flag(final Object value, final String label) {
        if (!(value instanceof Boolean result)) {
            throw unavailable(label + " is invalid.");
        }
        return result;
    }

    int intValue(final Object value, final String label) {
        if (!(value instanceof Integer result)) {
            throw unavailable(label + " is invalid.");
        }
        return result;
    }

    float floatValue(final Object value, final String label) {
        if (!(value instanceof Float result) || !Float.isFinite(result)) {
            throw unavailable(label + " is invalid.");
        }
        return result;
    }

    double doubleValue(final Object value, final String label) {
        if (!(value instanceof Number result) || !Double.isFinite(result.doubleValue())) {
            throw unavailable(label + " is invalid.");
        }
        return result.doubleValue();
    }

    IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }
}
