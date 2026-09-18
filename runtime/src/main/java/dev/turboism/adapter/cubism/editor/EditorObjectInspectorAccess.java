package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.EditorObjectReadCore.*;
import static dev.turboism.adapter.cubism.editor.EditorObjectReadCore.*;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.adapter.cubism.editor.transaction.EditorUndoContribution;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorDeformerInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorGlueInspectorSelectorContract;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.GlueId;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Exact-version Inspector writes for Deformer and Glue editor objects. */
final class EditorObjectInspectorAccess {

    private final VerifiedMemberResolver resolver;
    private final EditorObjectReadAccess.CurrentGuard currentGuard;
    private final EditorObjectReadCore core;
    private final EditorAuthoringTransactionCoordinator authoringCoordinator;
    private final Supplier<EditorAuthoringTransactionCoordinator.Binding> authoringBinding;
    private final EditorObjectHierarchyEditAccess hierarchyEditAccess;

    EditorObjectInspectorAccess(
        final VerifiedMemberResolver resolver,
        final EditorObjectReadAccess.CurrentGuard currentGuard,
        final EditorObjectReadCore core,
        final EditorObjectHierarchyEditAccess hierarchyEditAccess,
        final EditorAuthoringTransactionCoordinator authoringCoordinator,
        final Supplier<EditorAuthoringTransactionCoordinator.Binding> authoringBinding
    ) {
        this.resolver = resolver;
        this.currentGuard = currentGuard;
        this.core = core;
        this.hierarchyEditAccess = hierarchyEditAccess;
        this.authoringCoordinator = authoringCoordinator;
        this.authoringBinding = authoringBinding;
    }

    // ===== Editor Inspector family writes: Deformer and Glue =====

    void setDeformerName(
        final String identity,
        final Object modelSource,
        final Object model,
        final DeformerRef ref,
        final String name
    ) {
        requireDeformerInspectorAuthorization();
        final DeformerRef value = core.currentDeformer(identity, modelSource, model, ref);
        final String requested = Objects.requireNonNull(name, "name");
        if (requested.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (requested.equals(core.objectName(value.source(), value.id()))) return;
        writeInspector(modelSource, value.source(), "Turboism: Set Deformer Name", () ->
            resolver.invoke(
                "cubism.editor-model.deformer-source.set-local-name",
                value.source(),
                requested
            )
        );
    }

    void setDeformerId(
        final String identity,
        final Object modelSource,
        final Object model,
        final DeformerRef ref,
        final DeformerId id
    ) {
        requireDeformerInspectorAuthorization();
        final DeformerRef value = core.currentDeformer(identity, modelSource, model, ref);
        final String newId = Objects.requireNonNull(id, "id").value();
        if (newId.equals(value.id())) return;
        if (newId.isEmpty()) throw new IllegalArgumentException("id must not be blank");
        if (!InspectorIdRules.isValidCubismId(newId)) {
            throw new IllegalArgumentException("id violates Cubism ID rules: " + newId);
        }
        if (duplicateObjectId(identity, modelSource, model, newId)) {
            throw new IllegalArgumentException("Cubism object ID is already present: " + newId);
        }
        writeInspector(modelSource, value.source(), "Turboism: Set Deformer ID", () -> {
            final Object hostId = resolver.construct("cubism.editor-model.deformer-id.create", newId);
            resolver.invoke("cubism.editor-model.deformer-source.set-id", value.source(), hostId);
            verifyModel(modelSource);
        });
    }

    void setDeformerTarget(
        final String identity,
        final Object modelSource,
        final Object model,
        final DeformerRef ref,
        final Optional<DeformerId> target
    ) {
        requireDeformerInspectorAuthorization();
        final DeformerRef value = core.currentDeformer(identity, modelSource, model, ref);
        final Optional<DeformerId> requested = Objects.requireNonNull(target, "target");
        final boolean detach = requested.isEmpty();
        if (!detach && requested.orElseThrow().value().equals(value.id())) {
            throw new IllegalArgumentException("a Deformer cannot target itself");
        }
        final Object targetSource;
        final Object targetGuid;
        if (detach) {
            targetSource = null;
            targetGuid = rootDeformerGuid();
        } else {
            final String targetId = requested.orElseThrow().value();
            targetSource = core.deformerRefs(identity, modelSource, model).stream()
                .filter(candidate -> candidate.id().equals(targetId))
                .map(DeformerRef::source)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "Cubism Deformer is absent from the active model: " + targetId
                ));
            if (targetIsDescendantOf(value.source(), targetSource)) {
                throw new IllegalArgumentException(
                    "a Deformer cannot target its own descendant: " + targetId
                );
            }
            targetGuid = resolver.invoke("cubism.editor-model.deformer-source.guid", targetSource);
        }
        final Object currentGuid = resolver.invoke(
            "cubism.editor-model.deformer-source.guid", value.source()
        );
        if (currentGuid == targetGuid) return;
        if (targetSource != null
            && hierarchyEditAccess != null
            && hierarchyEditAccess.relationCaptureAvailable()) {
            hierarchyEditAccess.setParent(
                identity,
                modelSource,
                model,
                value.source(),
                targetSource,
                true,
                -1,
                "Deformer"
            );
            return;
        }
        changeDeformerTarget(modelSource, model, value.source(), targetGuid);
    }

    void setDeformerMultiplyColor(
        final String identity,
        final Object modelSource,
        final Object model,
        final DeformerRef ref,
        final Color color
    ) {
        setDeformerColor(identity, modelSource, model, ref, color, true);
    }

    void setDeformerScreenColor(
        final String identity,
        final Object modelSource,
        final Object model,
        final DeformerRef ref,
        final Color color
    ) {
        setDeformerColor(identity, modelSource, model, ref, color, false);
    }

    void setGlueName(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef ref,
        final String name
    ) {
        requireGlueInspectorAuthorization();
        final GlueRef value = core.currentGlue(identity, modelSource, model, ref);
        final String requested = Objects.requireNonNull(name, "name");
        if (requested.isEmpty()) throw new IllegalArgumentException("name must not be empty");
        if (requested.equals(glueName(value))) return;
        final Object original = rawGlueName(value.source());
        writeGlueContribution(
            identity,
            modelSource,
            model,
            value.source(),
            "Turboism: Set Glue Name",
            "cubism.glue.name.set",
            "name",
            original == null ? "<unset>" : (String) original,
            requested,
            () -> {
                resolver.invoke(
                    "cubism.editor-model.glue-source.set-local-name",
                    value.source(),
                    requested
                );
                verifyModel(modelSource);
            },
            () -> requested.equals(rawGlueName(value.source())),
            () -> {
                resolver.invoke(
                    "cubism.editor-model.glue-source.set-local-name",
                    value.source(),
                    original
                );
                verifyModel(modelSource);
            },
            () -> Objects.equals(original, rawGlueName(value.source()))
        );
    }

    void setGlueId(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef ref,
        final GlueId id
    ) {
        requireGlueInspectorAuthorization();
        final GlueRef value = core.currentGlue(identity, modelSource, model, ref);
        final String newId = Objects.requireNonNull(id, "id").value();
        if (newId.equals(value.id())) return;
        if (newId.isEmpty()) throw new IllegalArgumentException("id must not be blank");
        if (!InspectorIdRules.isValidCubismId(newId)) {
            throw new IllegalArgumentException("id violates Cubism ID rules: " + newId);
        }
        if (duplicateObjectId(identity, modelSource, model, newId)) {
            throw new IllegalArgumentException("Cubism object ID is already present: " + newId);
        }
        final String original = value.id();
        writeGlueContribution(
            identity,
            modelSource,
            model,
            value.source(),
            "Turboism: Set Glue ID",
            "cubism.glue.id.set",
            "id",
            original,
            newId,
            () -> {
                setGlueIdValue(value.source(), newId);
                verifyModel(modelSource);
            },
            () -> newId.equals(core.objectId(value.source())),
            () -> {
                setGlueIdValue(value.source(), original);
                verifyModel(modelSource);
            },
            () -> original.equals(core.objectId(value.source()))
        );
    }

    void setGlueIntensity(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef ref,
        final float intensity
    ) {
        requireGlueInspectorAuthorization();
        final GlueRef value = core.currentGlue(identity, modelSource, model, ref);
        if (!Float.isFinite(intensity)) throw new IllegalArgumentException("intensity must be finite");
        if (intensity < 0.0F || intensity > 1.0F) {
            throw new IllegalArgumentException("intensity must be within [0,1]");
        }
        final Object form = glueForm(identity, modelSource, model, value);
        final float original = number(
            resolver.invoke("cubism.editor-model.glue-form.intensity", form),
            "Glue intensity"
        );
        if (Float.compare(original, intensity) == 0) return;
        writeGlueContribution(
            identity,
            modelSource,
            model,
            value.source(),
            "Turboism: Set Glue Intensity",
            "cubism.glue.intensity.set",
            "intensity",
            Float.toString(original),
            Float.toString(intensity),
            () -> resolver.invoke(
                "cubism.editor-model.glue-form.set-intensity",
                form,
                Float.valueOf(intensity)
            ),
            () -> Float.compare(number(
                resolver.invoke("cubism.editor-model.glue-form.intensity", form),
                "Glue intensity"
            ), intensity) == 0,
            () -> resolver.invoke(
                "cubism.editor-model.glue-form.set-intensity",
                form,
                Float.valueOf(original)
            ),
            () -> Float.compare(number(
                resolver.invoke("cubism.editor-model.glue-form.intensity", form),
                "Glue intensity"
            ), original) == 0
        );
    }

    void setGlueDrawableA(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef ref,
        final ArtMeshId id
    ) {
        setGlueDrawable(identity, modelSource, model, ref, id, true);
    }

    void setGlueDrawableB(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef ref,
        final ArtMeshId id
    ) {
        setGlueDrawable(identity, modelSource, model, ref, id, false);
    }

    float glueIntensity(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef ref
    ) {
        final GlueRef value = core.currentGlue(identity, modelSource, model, ref);
        return number(
            resolver.invoke(
                "cubism.editor-model.glue-form.intensity",
                glueForm(identity, modelSource, model, value)
            ),
            "Glue intensity"
        );
    }

    String glueName(final GlueRef ref) {
        final Object value = rawGlueName(ref.source());
        if (value == null) return ref.id();
        final String name = (String) value;
        return name.isBlank() ? ref.id() : name;
    }

    private Object rawGlueName(final Object source) {
        final Object value = resolver.invoke(
            "cubism.editor-model.glue-source.local-name", source
        );
        if (value != null && !(value instanceof String)) {
            throw unavailable("Editor Glue name is invalid.");
        }
        return value;
    }

    private void setGlueIdValue(final Object source, final String id) {
        final Object hostId = resolver.construct(
            "cubism.editor-model.glue-id.create",
            id
        );
        resolver.invoke(
            "cubism.editor-model.glue-source.set-id",
            source,
            hostId
        );
    }

    private void setDeformerColor(
        final String identity,
        final Object modelSource,
        final Object model,
        final DeformerRef ref,
        final Color color,
        final boolean multiply
    ) {
        requireDeformerInspectorAuthorization();
        final DeformerRef value = core.currentDeformer(identity, modelSource, model, ref);
        final Color requested = Objects.requireNonNull(color, "color");
        requireColorChannels(requested);
        requireColorSupportVersion(modelSource);
        final Object form = core.deformerForm(value.instance());
        final Object hostColor = resolver.invoke(
            multiply
                ? "cubism.editor-model.deformer-form.multiply-color"
                : "cubism.editor-model.deformer-form.screen-color",
            form
        );
        if (hostColor == null) throw unavailable("Editor Deformer color is unavailable.");
        if (sameColor(hostColor, requested)) return;
        writeInspector(
            modelSource,
            value.source(),
            multiply ? "Turboism: Set Deformer Multiply Color" : "Turboism: Set Deformer Screen Color",
            () -> {
                resolver.invoke("cubism.editor-model.float-color.set-red", hostColor, Float.valueOf(requested.red()));
                resolver.invoke("cubism.editor-model.float-color.set-green", hostColor, Float.valueOf(requested.green()));
                resolver.invoke("cubism.editor-model.float-color.set-blue", hostColor, Float.valueOf(requested.blue()));
                resolver.invoke("cubism.editor-model.float-color.set-alpha", hostColor, Float.valueOf(requested.alpha()));
            }
        );
    }

    private void setGlueDrawable(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef ref,
        final ArtMeshId id,
        final boolean targetA
    ) {
        requireGlueInspectorAuthorization();
        final GlueRef value = core.currentGlue(identity, modelSource, model, ref);
        final ArtMeshId requested = Objects.requireNonNull(id, "id");
        final Object targetSource = core.artMeshes(identity, modelSource, model).stream()
            .filter(candidate -> candidate.id().equals(requested.value()))
            .map(ObjectRef::source)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Cubism ArtMesh is absent from the active model: " + requested.value()
            ));
        final Object current = resolver.invoke(
            targetA
                ? "cubism.editor-model.glue-source.target-art-mesh-a"
                : "cubism.editor-model.glue-source.target-art-mesh-b",
            value.source()
        );
        if (current == targetSource) return;
        final Object targetGuid = resolver.invoke(
            "cubism.editor-model.art-mesh-source.guid", targetSource
        );
        final Object originalGuid = resolver.invoke(
            "cubism.editor-model.art-mesh-source.guid", current
        );
        final String readAlias = targetA
            ? "cubism.editor-model.glue-source.target-art-mesh-a"
            : "cubism.editor-model.glue-source.target-art-mesh-b";
        final String writeAlias = targetA
            ? "cubism.editor-model.glue-source.set-target-art-mesh-a"
            : "cubism.editor-model.glue-source.set-target-art-mesh-b";
        writeGlueContribution(
            identity,
            modelSource,
            model,
            value.source(),
            targetA ? "Turboism: Set Glue Drawable A" : "Turboism: Set Glue Drawable B",
            targetA ? "cubism.glue.drawable-a.set" : "cubism.glue.drawable-b.set",
            targetA ? "drawableA" : "drawableB",
            core.objectId(current),
            requested.value(),
            () -> resolver.invoke(writeAlias, value.source(), targetGuid),
            () -> resolver.invoke(readAlias, value.source()) == targetSource,
            () -> resolver.invoke(writeAlias, value.source(), originalGuid),
            () -> resolver.invoke(readAlias, value.source()) == current
        );
    }

    private void writeGlueContribution(
        final String identity,
        final Object modelSource,
        final Object model,
        final Object objectSource,
        final String action,
        final String operationId,
        final String property,
        final String before,
        final String after,
        final Runnable mutation,
        final BooleanSupplier applied,
        final Runnable compensation,
        final BooleanSupplier restored
    ) {
        currentGuard.requireCurrent(identity, model);
        if (authoringCoordinator == null) {
            writeInspector(modelSource, objectSource, action, mutation);
            if (!applied.getAsBoolean()) {
                throw new IllegalStateException(action + " postcondition failed.");
            }
            return;
        }
        final EditorAuthoringTransactionCoordinator.Binding binding =
            Objects.requireNonNull(authoringBinding.get(), "authoringBinding");
        if (!identity.equals(binding.modelIdentity())) {
            throw new IllegalStateException(
                "Glue reference is stale for the active authoring binding."
            );
        }
        authoringCoordinator.mutate(
            binding,
            new EditorUndoContribution(
                operationId,
                identity + ":glue@" + Integer.toHexString(System.identityHashCode(objectSource)),
                action,
                (edit, transactionLabel) -> admitGlueUndo(
                    edit,
                    modelSource,
                    objectSource,
                    transactionLabel
                ),
                mutation,
                applied,
                compensation,
                restored,
                EnumSet.of(
                    EditorRefreshRequirement.MODEL_INSTANCES,
                    EditorRefreshRequirement.PART_PALETTE,
                    EditorRefreshRequirement.DEFORMER_PALETTE,
                    EditorRefreshRequirement.CANVAS,
                    EditorRefreshRequirement.MARK_DIRTY
                ),
                new HistoryEntryDetail(
                    action,
                    HistoryAction.DetailLevel.FULL,
                    HistoryOrigin.turboism(binding.pluginId(), operationId),
                    java.util.List.of(new HistoryTarget("GLUE", java.util.Optional.of(core.objectId(objectSource)), java.util.Optional.empty())),
                    java.util.List.of(HistoryChange.set(0, property, before, after)),
                    Optional.empty(),
                    Optional.empty()
                )
            )
        );
    }

    private void admitGlueUndo(
        final Object edit,
        final Object modelSource,
        final Object objectSource,
        final String transactionLabel
    ) {
        final Object handler = resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.handler",
            objectSource
        );
        if (!resolver.isInstance(
            "cubism.editor-model.parameter-controllable-handler.class",
            handler
        )) {
            throw unavailable("Editor object Undo handler is unavailable.");
        }
        final Object objectUndo = resolver.invoke(
            "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
            handler,
            transactionLabel
        );
        final Object accepted = resolver.invoke(
            "cubism.editor-model.undo.add",
            edit,
            objectUndo,
            Boolean.TRUE
        );
        if (!(accepted instanceof Boolean value) || !value) {
            throw new IllegalStateException(
                "Cubism rejected the Editor Glue Undo entry."
            );
        }
        final Object app = resolver.invokeStatic(
            "cubism.editor-model.app-controller.instance"
        );
        final Object listener = resolver.createFunctionalProxy(
            "cubism.editor-model.undo-listener.class",
            ignored -> {
                resolver.invoke(
                    "cubism.editor-model.model-source.update-instances",
                    modelSource
                );
                refreshBoth(app);
                return null;
            }
        );
        resolver.invoke(
            "cubism.editor-model.undo.add-listener",
            objectUndo,
            listener
        );
    }

    private void writeInspector(
        final Object modelSource,
        final Object objectSource,
        final String action,
        final Runnable mutation
    ) {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object edit = resolver.invoke("cubism.editor-model.edit-mode.begin", editMode, action);
        boolean completed = false;
        try {
            final Object handler = resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.handler", objectSource
            );
            if (!resolver.isInstance("cubism.editor-model.parameter-controllable-handler.class", handler)) {
                throw unavailable("Editor object Undo handler is unavailable.");
            }
            final Object objectUndo = resolver.invoke(
                "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
                handler,
                action
            );
            final Object accepted = resolver.invoke(
                "cubism.editor-model.undo.add", edit, objectUndo, Boolean.TRUE
            );
            if (!(accepted instanceof Boolean value) || !value) {
                throw new IllegalStateException("Cubism rejected the Editor Inspector Undo entry.");
            }
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
                    refreshBoth(app);
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", objectUndo, listener);
            mutation.run();
            resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
            refreshBoth(app);
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
    }

    private void changeDeformerTarget(
        final Object modelSource,
        final Object model,
        final Object deformerSource,
        final Object targetGuid
    ) {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object edit = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, "Turboism: Change Deformer Target"
        );
        boolean completed = false;
        try {
            final Object handler = resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.handler", deformerSource
            );
            if (!resolver.isInstance("cubism.editor-model.parameter-controllable-handler.class", handler)) {
                throw unavailable("Editor Deformer Undo handler is unavailable.");
            }
            final Object changeUndo = resolver.invoke(
                "cubism.editor-model.parameter-controllable-handler.change-target-deformer-guid",
                handler,
                model,
                targetGuid,
                Boolean.FALSE
            );
            final Object accepted = resolver.invoke(
                "cubism.editor-model.undo.add", edit, changeUndo, Boolean.TRUE
            );
            if (!(accepted instanceof Boolean value) || !value) {
                throw new IllegalStateException("Cubism rejected the Deformer target Undo entry.");
            }
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
                    refreshBoth(app);
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", changeUndo, listener);
            resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
            refreshBoth(app);
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
    }

    private void refreshBoth(final Object app) {
        final Object completePack = resolver.invoke(
            "cubism.editor-model.app-controller.complete-pack", app
        );
        resolver.invoke(
            "cubism.editor-model.complete-pack.update-part-palette",
            completePack,
            Boolean.TRUE
        );
        resolver.invoke(
            "cubism.editor-model.complete-pack.update-deformer-palette",
            completePack,
            Boolean.TRUE
        );
        resolver.invoke(
            "cubism.editor-model.complete-pack.repaint-canvas",
            completePack,
            Boolean.TRUE
        );
    }

    private void verifyModel(final Object modelSource) {
        resolver.invokeStatic(
            "cubism.editor-model.model-source.verify",
            modelSource,
            Boolean.TRUE,
            null,
            Integer.valueOf(2),
            null
        );
    }

    private Object glueForm(
        final String identity,
        final Object modelSource,
        final Object model,
        final GlueRef ref
    ) {
        core.currentGlue(identity, modelSource, model, ref);
        final Object idObject = resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.id", ref.source()
        );
        final Object instance = resolver.invoke(
            "cubism.editor-model.model.get-object", model, idObject
        );
        if (instance == null) throw unavailable("Editor Glue instance is unavailable.");
        final Object form = resolver.invoke(
            "cubism.editor-model.glue.current-keyform", instance
        );
        if (form == null) throw unavailable("Editor Glue current keyform is unavailable.");
        return form;
    }

    private boolean targetIsDescendantOf(final Object ownSource, final Object targetSource) {
        Object cursor = targetSource;
        int guard = 0;
        while (cursor != null) {
            if (cursor == ownSource) return true;
            cursor = resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.target-deformer-source",
                cursor
            );
            if (++guard > 4096) {
                throw new IllegalStateException("Deformer ancestor chain is cyclic.");
            }
        }
        return false;
    }

    private Object rootDeformerGuid() {
        final Object companion = resolver.readStaticField("cubism.editor-model.deformer-guid.companion");
        return resolver.invoke("cubism.editor-model.deformer-guid.root", companion);
    }

    private boolean sameColor(final Object hostColor, final Color requested) {
        return number(resolver.invoke("cubism.editor-model.float-color.red", hostColor), "color red") == requested.red()
            && number(resolver.invoke("cubism.editor-model.float-color.green", hostColor), "color green") == requested.green()
            && number(resolver.invoke("cubism.editor-model.float-color.blue", hostColor), "color blue") == requested.blue()
            && number(resolver.invoke("cubism.editor-model.float-color.alpha", hostColor), "color alpha") == requested.alpha();
    }

    private static void requireColorChannels(final Color color) {
        for (float channel : new float[]{color.red(), color.green(), color.blue(), color.alpha()}) {
            if (channel < 0.0F || channel > 1.0F) {
                throw new IllegalArgumentException("color channels must be within [0,1]");
            }
        }
    }

    private void requireColorSupportVersion(final Object modelSource) {
        final Object version = resolver.invoke(
            "cubism.editor-model.model-source.target-version", modelSource
        );
        final Object number = resolver.invoke("cubism.editor-model.target-version.number", version);
        if (!(number instanceof Integer value) || value < InspectorIdRules.CUBISM_42_TARGET_VERSION) {
            throw new IllegalStateException(
                "Deformer colors require a Cubism 4.2+ model target version (CUB3-3264/3265)."
            );
        }
    }

    private boolean duplicateObjectId(
        final String identity,
        final Object modelSource,
        final Object model,
        final String candidate
    ) {
        currentGuard.requireCurrent(identity, model);
        for (String collectionAlias : new String[]{
            "cubism.editor-model.model-source.parts",
            "cubism.editor-model.model-source.all-deformers",
            "cubism.editor-model.model-source.all-glues",
            "cubism.editor-model.model-source.all-art-meshes"
        }) {
            final List<?> values = list(
                resolver.invoke(collectionAlias, modelSource),
                "Editor object source collection"
            );
            for (Object value : values) {
                final Object idObject = resolver.invoke(
                    "cubism.editor-model.parameter-controllable-source.id", value
                );
                final String idText = text(
                    resolver.invoke("cubism.editor-model.id.value", idObject),
                    "Editor object ID"
                );
                if (idText.equals(candidate)) return true;
            }
        }
        return false;
    }

    private void requireDeformerInspectorAuthorization() {
        if (!resolver.authorizesFeature(
            EditorDeformerInspectorSelectorContract.ADAPTER_SLICE_ID,
            EditorDeformerInspectorSelectorContract.CAPABILITY_ID,
            EditorDeformerInspectorSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Editor Deformer Inspector writes require exact verified host evidence."
            );
        }
    }

    private void requireGlueInspectorAuthorization() {
        if (!resolver.authorizesFeature(
            EditorGlueInspectorSelectorContract.ADAPTER_SLICE_ID,
            EditorGlueInspectorSelectorContract.CAPABILITY_ID,
            EditorGlueInspectorSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Editor Glue Inspector writes require exact verified host evidence."
            );
        }
    }
}
