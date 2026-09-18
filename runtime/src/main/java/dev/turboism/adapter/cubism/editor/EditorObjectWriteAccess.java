package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.EditorObjectReadCore.*;
import static dev.turboism.adapter.cubism.editor.EditorObjectReadCore.*;

import dev.turboism.adapter.cubism.editor.history.decoder.ArtMeshPropertyCapture;
import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.adapter.cubism.editor.transaction.EditorUndoContribution;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorHistoryReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract;
import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectWriteSelectorContract;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.AlphaComposition;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.ColorComposition;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.WarpGrid;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Exact-version Editor write envelopes and Inspector property writes behind {@link EditorObjectReadAccess}. */
final class EditorObjectWriteAccess {

    private final VerifiedMemberResolver resolver;
    private final EditorObjectReadCore core;
    private final EditorAuthoringTransactionCoordinator authoringCoordinator;
    private final Supplier<EditorAuthoringTransactionCoordinator.Binding> authoringBinding;

    EditorObjectWriteAccess(
        final VerifiedMemberResolver resolver,
        final EditorObjectReadCore core,
        final EditorAuthoringTransactionCoordinator authoringCoordinator,
        final Supplier<EditorAuthoringTransactionCoordinator.Binding> authoringBinding
    ) {
        this.resolver = resolver;
        this.core = core;
        this.authoringCoordinator = authoringCoordinator;
        this.authoringBinding = authoringBinding;
    }

    void requireWriteAuthorized(final Kind kind) {
        final String capability = switch (kind) {
            case ART_MESH -> EditorObjectWriteSelectorContract.ART_MESH_CAPABILITY_ID;
            case WARP -> EditorObjectWriteSelectorContract.WARP_CAPABILITY_ID;
            case ROTATION -> EditorObjectWriteSelectorContract.ROTATION_CAPABILITY_ID;
        };
        final java.util.Set<String> aliases = switch (kind) {
            case ART_MESH -> EditorObjectWriteSelectorContract.ART_MESH_REQUIRED_ALIASES;
            case WARP -> EditorObjectWriteSelectorContract.WARP_REQUIRED_ALIASES;
            case ROTATION -> EditorObjectWriteSelectorContract.ROTATION_REQUIRED_ALIASES;
        };
        if (!resolver.authorizesFeature(EditorObjectWriteSelectorContract.ADAPTER_SLICE_ID, capability, aliases)) {
            throw new UnsupportedOperationException(
                "Editor " + kind.label + " writes require exact verified host evidence."
            );
        }
    }

    /** Undo envelope family mirrored from the Inspector prepareUndo routing. */
    private enum UndoKind {
        ALL_EDIT,
        BASIC_SETTING,
        KEYFORM_EDIT
    }

    private boolean isCubism52() {
        return resolver.isExactCubismVersion(EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract.CUBISM_VERSION);
    }

    private void requireInspectorWriteAuthorized() {
        final boolean authorized = isCubism52()
            ? resolver.authorizesFeature(
                EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract.ADAPTER_SLICE_ID,
                EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract.CAPABILITY_ID,
                EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract.REQUIRED_ALIASES
            )
            : resolver.authorizesFeature(
                EditorInspectorDrawableWriteSelectorContract.ADAPTER_SLICE_ID,
                EditorInspectorDrawableWriteSelectorContract.CAPABILITY_ID,
                EditorInspectorDrawableWriteSelectorContract.REQUIRED_ALIASES
            );
        if (!authorized) {
            throw new UnsupportedOperationException(
                "Editor ArtMesh Inspector writes require exact verified host evidence."
            );
        }
    }

    void replaceArtMeshGeometry(
        final Object modelSource,
        final ObjectRef value,
        final ArtMeshGeometry geometry
    ) {
        Objects.requireNonNull(geometry, "geometry");
        requireWriteAuthorized(Kind.ART_MESH);
        if (geometry.equals(core.geometry(value.source(), value.instance()))) return;
        final Object form = core.artMeshForm(value.instance());
        final float[] originalSourcePositions = floats(
            resolver.invoke("cubism.editor-model.art-mesh-source.positions", value.source()),
            "Editor ArtMesh source positions"
        ).clone();
        final float[] originalFormPositions = floats(
            resolver.invoke("cubism.editor-model.art-mesh-form.positions", form),
            "Editor ArtMesh form positions"
        ).clone();
        final float[] originalUvs = floats(
            resolver.invoke("cubism.editor-model.art-mesh-source.uvs", value.source()),
            "Editor ArtMesh UVs"
        ).clone();
        final int[] originalIndices = ints(
            resolver.invoke("cubism.editor-model.art-mesh-source.indices", value.source()),
            "Editor ArtMesh indices"
        ).clone();
        final float[] positions = flattenArray(geometry.positions());
        final float[] uvs = flattenArray(geometry.uvs());
        final int[] indices = indexArray(geometry.triangleIndices());
        write(Kind.ART_MESH, modelSource, value.source(), "Replace ArtMesh geometry", () -> {
            try {
                resolver.invoke("cubism.editor-model.art-mesh-source.set-positions", value.source(), positions.clone());
                resolver.invoke("cubism.editor-model.art-mesh-source.set-uvs", value.source(), uvs.clone());
                resolver.invoke("cubism.editor-model.art-mesh-source.set-indices", value.source(), indices.clone());
                resolver.invoke(
                    "cubism.editor-model.art-mesh-form.set-positions",
                    form,
                    positions.clone()
                );
            } catch (RuntimeException failure) {
                compensate(
                    "ArtMesh geometry",
                    failure,
                    () -> {
                        resolver.invoke("cubism.editor-model.art-mesh-source.set-positions", value.source(), originalSourcePositions.clone());
                        resolver.invoke("cubism.editor-model.art-mesh-source.set-uvs", value.source(), originalUvs.clone());
                        resolver.invoke("cubism.editor-model.art-mesh-source.set-indices", value.source(), originalIndices.clone());
                        resolver.invoke("cubism.editor-model.art-mesh-form.set-positions", form, originalFormPositions.clone());
                    },
                    () -> java.util.Arrays.equals(originalSourcePositions, floats(
                        resolver.invoke("cubism.editor-model.art-mesh-source.positions", value.source()),
                        "Editor ArtMesh source positions"
                    )) && java.util.Arrays.equals(originalUvs, floats(
                        resolver.invoke("cubism.editor-model.art-mesh-source.uvs", value.source()),
                        "Editor ArtMesh UVs"
                    )) && java.util.Arrays.equals(originalIndices, ints(
                        resolver.invoke("cubism.editor-model.art-mesh-source.indices", value.source()),
                        "Editor ArtMesh indices"
                    )) && java.util.Arrays.equals(originalFormPositions, floats(
                        resolver.invoke("cubism.editor-model.art-mesh-form.positions", form),
                        "Editor ArtMesh form positions"
                    ))
                );
            }
        });
    }

    void replaceWarpGrid(
        final Object modelSource,
        final DeformerRef value,
        final WarpGrid grid
    ) {
        Objects.requireNonNull(grid, "grid");
        requireWriteAuthorized(Kind.WARP);
        if (grid.equals(core.warpGrid(value.source(), value.instance()))) return;
        final Object form = core.deformerForm(value.instance());
        final int originalRows = integer(
            resolver.invoke("cubism.editor-model.warp-source.row", value.source()),
            "Editor Warp row count"
        );
        final int originalColumns = integer(
            resolver.invoke("cubism.editor-model.warp-source.col", value.source()),
            "Editor Warp column count"
        );
        final boolean originalQuad = flag(
            resolver.invoke("cubism.editor-model.warp-source.quad-transform", value.source()),
            "Editor Warp quad-transform flag"
        );
        final float[] originalPositions = floats(
            resolver.invoke("cubism.editor-model.warp-form.positions", form),
            "Editor Warp control points"
        ).clone();
        final float[] positions = flattenArray(grid.controlPoints());
        write(Kind.WARP, modelSource, value.source(), "Replace Warp grid", () -> {
            try {
                resolver.invoke("cubism.editor-model.warp-source.set-row", value.source(), Integer.valueOf(grid.rows()));
                resolver.invoke("cubism.editor-model.warp-source.set-col", value.source(), Integer.valueOf(grid.columns()));
                resolver.invoke("cubism.editor-model.warp-source.set-quad-transform", value.source(), Boolean.valueOf(grid.quadTransform()));
                resolver.invoke(
                    "cubism.editor-model.warp-form.set-positions",
                    form,
                    positions.clone()
                );
            } catch (RuntimeException failure) {
                compensate(
                    "Warp grid",
                    failure,
                    () -> {
                        resolver.invoke("cubism.editor-model.warp-source.set-row", value.source(), Integer.valueOf(originalRows));
                        resolver.invoke("cubism.editor-model.warp-source.set-col", value.source(), Integer.valueOf(originalColumns));
                        resolver.invoke("cubism.editor-model.warp-source.set-quad-transform", value.source(), Boolean.valueOf(originalQuad));
                        resolver.invoke("cubism.editor-model.warp-form.set-positions", form, originalPositions.clone());
                    },
                    () -> originalRows == integer(
                        resolver.invoke("cubism.editor-model.warp-source.row", value.source()),
                        "Editor Warp row count"
                    ) && originalColumns == integer(
                        resolver.invoke("cubism.editor-model.warp-source.col", value.source()),
                        "Editor Warp column count"
                    ) && originalQuad == flag(
                        resolver.invoke("cubism.editor-model.warp-source.quad-transform", value.source()),
                        "Editor Warp quad-transform flag"
                    ) && java.util.Arrays.equals(originalPositions, floats(
                        resolver.invoke("cubism.editor-model.warp-form.positions", form),
                        "Editor Warp control points"
                    ))
                );
            }
        });
    }

    void replaceRotationForm(
        final Object modelSource,
        final DeformerRef value,
        final RotationDeformerForm form
    ) {
        Objects.requireNonNull(form, "form");
        requireWriteAuthorized(Kind.ROTATION);
        final RotationDeformerForm original = core.rotationForm(value.instance());
        if (form.equals(original)) return;
        final Object current = core.deformerForm(value.instance());
        write(Kind.ROTATION, modelSource, value.source(), "Replace Rotation form", () -> {
            try {
                setRotationForm(current, form);
            } catch (RuntimeException failure) {
                compensate(
                    "Rotation form",
                    failure,
                    () -> setRotationForm(current, original),
                    () -> original.equals(core.rotationForm(value.instance()))
                );
            }
        });
    }

    private void setRotationForm(final Object current, final RotationDeformerForm form) {
        resolver.invoke("cubism.editor-model.rotation-form.set-angle", current, Float.valueOf(form.angle()));
        resolver.invoke("cubism.editor-model.rotation-form.set-origin-x", current, Float.valueOf(form.originX()));
        resolver.invoke("cubism.editor-model.rotation-form.set-origin-y", current, Float.valueOf(form.originY()));
        resolver.invoke("cubism.editor-model.rotation-form.set-scale", current, Float.valueOf(form.scale()));
        resolver.invoke("cubism.editor-model.rotation-form.set-reflect-x", current, Boolean.valueOf(form.reflectedX()));
        resolver.invoke("cubism.editor-model.rotation-form.set-reflect-y", current, Boolean.valueOf(form.reflectedY()));
    }

    private static void compensate(
        final String label,
        final RuntimeException failure,
        final Runnable restore,
        final java.util.function.BooleanSupplier restored
    ) {
        try {
            restore.run();
            if (!restored.getAsBoolean()) {
                throw new IllegalStateException(label + " rollback verification failed.");
            }
        } catch (RuntimeException rollbackFailure) {
            final IllegalStateException combined = new IllegalStateException(
                label + " mutation failed and rollback did not complete.",
                failure
            );
            combined.addSuppressed(rollbackFailure);
            throw combined;
        }
        throw failure;
    }

    void write(
        final Kind kind,
        final Object modelSource,
        final Object objectSource,
        final String action,
        final Runnable mutation
    ) {
        requireWriteAuthorized(kind);
        writeEnvelope(UndoKind.ALL_EDIT, kind, modelSource, List.of(objectSource), action, mutation, false);
    }

    /** Clip-mask batch envelope after the caller's dedicated capability admission. */
    void writeClipMaskBatch(
        final Object modelSource,
        final List<Object> undoSources,
        final String action,
        final Runnable mutation
    ) {
        writeEnvelope(
            UndoKind.ALL_EDIT,
            Kind.ART_MESH,
            modelSource,
            undoSources,
            action,
            mutation,
            false
        );
    }

    /** Inspector-write envelope: requires the dedicated inspector capability and mirrors the Inspector undo kinds. */
    private void writeInspector(
        final UndoKind undoKind,
        final Object modelSource,
        final Object objectSource,
        final String action,
        final Runnable mutation
    ) {
        requireInspectorWriteAuthorized();
        writeEnvelope(undoKind, Kind.ART_MESH, modelSource, List.of(objectSource), action, mutation, true);
    }

    private void writeArtMeshProperty(
        final UndoKind undoKind, final Object modelSource, final Object objectSource,
        final Object form, final String property, final String label,
        final Runnable mutation, final BooleanSupplier applied,
        final Runnable compensation, final BooleanSupplier restored
    ) {
        if (authoringCoordinator == null || !resolver.authorizesFeature(
            "adapter.editor-model.readwrite", "cubism.editor-history.read",
            EditorHistoryReadSelectorContract.REQUIRED_ALIASES)) {
            writeEnvelope(undoKind, Kind.ART_MESH, modelSource, List.of(objectSource), label,
                mutation, undoKind != UndoKind.ALL_EDIT);
            return;
        }
        final var binding = Objects.requireNonNull(authoringBinding.get(), "authoring binding");
        final String operationId = "cubism.art-mesh.set-" + property;
        final HistoryOrigin origin = HistoryOrigin.turboism(binding.pluginId(), operationId);
        final Optional<ArtMeshPropertyCapture> before = ArtMeshPropertyCapture.capture(resolver, form, property);
        final HistoryEntryDetail pending = before.map(value -> value.detail(label, origin, Optional.empty()))
            .orElseGet(() -> new HistoryEntryDetail(label, HistoryAction.DetailLevel.PARTIAL, origin,
                List.of(new HistoryTarget("ART_MESH", Optional.of(core.objectId(objectSource)), Optional.empty())),
                List.of(new HistoryChange(HistoryChange.Operation.SET, Optional.of(0), Optional.of(property),
                    Optional.empty(), Optional.empty())), Optional.empty(), Optional.of("history.capture.before-unavailable")));
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Runnable update = () -> {
            resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
            refresh(app, Kind.ART_MESH, undoKind != UndoKind.ALL_EDIT);
        };
        authoringCoordinator.mutate(binding, new EditorUndoContribution(operationId,
            binding.modelIdentity() + ":artmesh:" + core.objectId(objectSource), label,
            (edit, transactionLabel) -> {
                final Object handler = resolver.invoke("cubism.editor-model.parameter-controllable-source.handler", objectSource);
                final Object undo = resolver.invoke(undoAlias(undoKind), handler, transactionLabel);
                if (!Boolean.TRUE.equals(resolver.invoke("cubism.editor-model.undo.add", edit, undo, Boolean.TRUE))) {
                    throw new IllegalStateException("Artmesh Undo admission failed");
                }
                final Object listener = resolver.createFunctionalProxy("cubism.editor-model.undo-listener.class",
                    ignored -> { update.run(); return null; });
                resolver.invoke("cubism.editor-model.undo.add-listener", undo, listener);
            }, () -> { mutation.run(); update.run(); }, applied,
            () -> { compensation.run(); update.run(); }, restored,
            EnumSet.of(EditorRefreshRequirement.MARK_DIRTY), pending
        ).withCaptureAfter(() -> {
            if (!binding.equals(authoringBinding.get())) throw new IllegalStateException("Artmesh capture binding changed");
            return before.map(value -> value.detail(label, origin, ArtMeshPropertyCapture.capture(resolver, form, property)))
                .orElse(pending);
        }));
    }

    private void writeEnvelope(
        final UndoKind undoKind,
        final Kind kind,
        final Object modelSource,
        final List<Object> undoObjectSources,
        final String action,
        final Runnable mutation,
        final boolean inspectorRefresh
    ) {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final String sourceId = core.objectId(undoObjectSources.get(0));
        final long trace = EditorObjectValidationTrace.begin(
            kind.label,
            action,
            sourceId,
            document,
            modelSource
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object edit = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, action
        );
        EditorObjectValidationTrace.event(trace, "edit-begin", kind.label, action, sourceId, document, modelSource, "");
        boolean completed = false;
        try {
            Object objectUndo = null;
            for (Object undoObjectSource : undoObjectSources) {
                final Object handler = resolver.invoke(
                    "cubism.editor-model.parameter-controllable-source.handler", undoObjectSource
                );
                if (!resolver.isInstance("cubism.editor-model.parameter-controllable-handler.class", handler)) {
                    throw unavailable("Editor object Undo handler is unavailable.");
                }
                objectUndo = resolver.invoke(
                    undoAlias(undoKind),
                    handler,
                    action
                );
                final Object accepted = resolver.invoke(
                    "cubism.editor-model.undo.add", edit, objectUndo, Boolean.TRUE
                );
                if (!(accepted instanceof Boolean value) || !value) {
                    throw new IllegalStateException("Cubism rejected the Editor object Undo entry.");
                }
                EditorObjectValidationTrace.event(trace, "undo-admitted", kind.label, action,
                    core.objectId(undoObjectSource), document, modelSource,
                    "accepted=true kind=" + undoKind.name());
            }
            final java.util.concurrent.atomic.AtomicInteger listenerCount = new java.util.concurrent.atomic.AtomicInteger();
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
                    refresh(app, kind, inspectorRefresh);
                    EditorObjectValidationTrace.event(
                        trace,
                        "undo-listener",
                        kind.label,
                        action,
                        sourceId,
                        document,
                        modelSource,
                        "callback=" + listenerCount.incrementAndGet() + " updated=true refreshed=true"
                    );
                    return null;
                }
            );
            // The refresh listener rides the last admitted snapshot; the whole edit session undoes as one step.
            resolver.invoke("cubism.editor-model.undo.add-listener", objectUndo, listener);
            mutation.run();
            EditorObjectValidationTrace.event(trace, "mutation", kind.label, action, sourceId, document, modelSource, "completed=true");
            resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
            EditorObjectValidationTrace.event(trace, "instances-updated", kind.label, action, sourceId, document, modelSource, "completed=true");
            refresh(app, kind, inspectorRefresh);
            EditorObjectValidationTrace.event(trace, "refresh", kind.label, action, sourceId, document, modelSource, "palette=true canvas=true");
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            EditorObjectValidationTrace.event(trace, "dirty", kind.label, action, sourceId, document, modelSource, "marked=true");
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end",
                editMode,
                Boolean.valueOf(!completed),
                null
            );
            EditorObjectValidationTrace.event(
                trace,
                "edit-end",
                kind.label,
                action,
                sourceId,
                document,
                modelSource,
                "completed=" + completed + " aborted=" + !completed
            );
        }
    }

    private static String undoAlias(final UndoKind kind) {
        return switch (kind) {
            case ALL_EDIT -> "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit";
            case BASIC_SETTING -> "cubism.editor-model.parameter-controllable-handler.create-undo-for-basic-setting";
            case KEYFORM_EDIT -> "cubism.editor-model.parameter-controllable-handler.create-undo-for-keyform-edit";
        };
    }

    private void refresh(final Object app, final Kind kind) {
        refresh(app, kind, false);
    }

    private void refresh(final Object app, final Kind kind, final boolean inspector) {
        final Object completePack = resolver.invoke(
            "cubism.editor-model.app-controller.complete-pack", app
        );
        resolver.invoke(
            kind == Kind.ART_MESH
                ? "cubism.editor-model.complete-pack.update-part-palette"
                : "cubism.editor-model.complete-pack.update-deformer-palette",
            completePack,
            Boolean.TRUE
        );
        if (inspector) {
            resolver.invoke(
                "cubism.editor-model.complete-pack.update-deformer-palette",
                completePack,
                Boolean.TRUE
            );
        }
        resolver.invoke(
            "cubism.editor-model.complete-pack.repaint-canvas", completePack, Boolean.TRUE
        );
    }

    void setSourceFlag(
        final Kind kind,
        final Object modelSource,
        final Object objectSource,
        final String readAlias,
        final String writeAlias,
        final boolean value,
        final String action
    ) {
        requireWriteAuthorized(kind);
        if (core.sourceFlag(readAlias, objectSource, action) == value) return;
        write(kind, modelSource, objectSource, action, () ->
            resolver.invoke(writeAlias, objectSource, Boolean.valueOf(value))
        );
    }

    void setOpacity(
        final Kind kind,
        final Object modelSource,
        final Object objectSource,
        final Object form,
        final String readAlias,
        final String writeAlias,
        final float value,
        final String action
    ) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("opacity must be finite");
        requireWriteAuthorized(kind);
        final float original = number(resolver.invoke(readAlias, form), action);
        if (Float.compare(original, value) == 0) return;
        if (kind == Kind.ART_MESH) {
            writeArtMeshProperty(UndoKind.ALL_EDIT, modelSource, objectSource, form, "opacity", action,
                () -> resolver.invoke(writeAlias, form, Float.valueOf(value)),
                () -> Float.compare(number(resolver.invoke(readAlias, form), action), value) == 0,
                () -> resolver.invoke(writeAlias, form, Float.valueOf(original)),
                () -> Float.compare(number(resolver.invoke(readAlias, form), action), original) == 0);
        } else {
            write(kind, modelSource, objectSource, action, () -> resolver.invoke(writeAlias, form, Float.valueOf(value)));
        }
    }

    private static final java.util.regex.Pattern ID_FORBIDDEN_START =
        java.util.regex.Pattern.compile("^[0-9]");

    private static final java.util.regex.Pattern ID_ALLOWED =
        java.util.regex.Pattern.compile("^[0-9a-zA-Z_@]+$");

    private static final int ID_MAX_LENGTH = 64;

    private static final int TARGET_VERSION_SDK40 = 400_000;

    private static final int TARGET_VERSION_SDK42 = 4_020_000;

    private int targetVersionNumber(final Object modelSource) {
        final Object version = resolver.invoke(
            "cubism.editor-model.model-source.target-version", modelSource
        );
        return integer(
            resolver.invoke("cubism.editor-model.target-version.number", version),
            "Editor model target version"
        );
    }

    void setId(
        final Object modelSource,
        final Object objectSource,
        final String id
    ) {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        final String current = core.objectId(objectSource);
        if (id.equals(current)) return;
        if (id.trim().isEmpty()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (ID_FORBIDDEN_START.matcher(id).find()
            || !ID_ALLOWED.matcher(id).matches()
            || id.length() >= ID_MAX_LENGTH) {
            throw new IllegalArgumentException(
                "id must not start with a digit, must match [0-9a-zA-Z_@]+, and must be shorter than 64 characters"
            );
        }
        final Object modelHandler = resolver.invoke(
            "cubism.editor-model.model-source.handler", modelSource
        );
        final Object idMap = resolver.invoke(
            "cubism.editor-model.model-handler.id-map", modelHandler
        );
        final Object duplicate = resolver.invoke(
            "cubism.editor-model.id-map.contains", idMap, id
        );
        if (duplicate instanceof Boolean used && used) {
            throw new IllegalArgumentException("id is already used by another model object: " + id);
        }
        writeInspector(UndoKind.BASIC_SETTING, modelSource, objectSource, "Set ArtMesh ID", () -> {
            final Object drawableId = resolver.construct(
                "cubism.editor-model.drawable-id.create", id
            );
            resolver.invoke(
                "cubism.editor-model.drawable-source.set-id", objectSource, drawableId
            );
            resolver.invokeStatic(
                "cubism.editor-model.model-source.verify",
                modelSource,
                Boolean.TRUE,
                null,
                Integer.valueOf(2),
                null
            );
            final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
            final Object completePack = resolver.invoke(
                "cubism.editor-model.app-controller.complete-pack", app
            );
            final Object updateManager = resolver.invoke(
                "cubism.editor-model.complete-pack.update-manager", completePack
            );
            resolver.invoke(
                "cubism.editor-model.update-manager.update-part", updateManager, Boolean.TRUE
            );
            resolver.invoke(
                "cubism.editor-model.update-manager.update-deformer", updateManager, Boolean.TRUE
            );
        });
    }

    void setTargetDeformer(
        final String identity,
        final Object modelSource,
        final Object model,
        final ObjectRef current,
        final Optional<DeformerId> targetDeformer
    ) {
        requireInspectorWriteAuthorized();
        final Object target;
        if (targetDeformer.isPresent()) {
            final DeformerId targetId = targetDeformer.get();
            final Object source = core.deformerSourceById(identity, modelSource, model, targetId);
            if (source == null) {
                throw new NoSuchElementException(
                    "No Editor Deformer has id " + targetId.value()
                );
            }
            target = resolver.construct("cubism.editor-model.deformer-id.create", targetId.value());
        } else {
            target = null;
        }
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object edit = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, "Set ArtMesh target Deformer"
        );
        boolean completed = false;
        try {
            final Object handler = resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.handler", current.source()
            );
            if (!resolver.isInstance("cubism.editor-model.parameter-controllable-handler.class", handler)) {
                throw unavailable("Editor object Undo handler is unavailable.");
            }
            final Object changeUndo;
            if (target == null) {
                final Object companion = resolver.readStaticField(
                    "cubism.editor-model.deformer-guid.companion"
                );
                final Object rootGuid = resolver.invoke(
                    "cubism.editor-model.deformer-guid.root", companion
                );
                changeUndo = resolver.invoke(
                    "cubism.editor-model.parameter-controllable-handler.change-target-deformer-guid",
                    handler,
                    model,
                    rootGuid,
                    Boolean.FALSE
                );
            } else {
                changeUndo = resolver.invoke(
                    "cubism.editor-model.parameter-controllable-handler.change-target-deformer",
                    handler,
                    model,
                    target
                );
            }
            final Object accepted = resolver.invoke(
                "cubism.editor-model.undo.add", edit, changeUndo, Boolean.TRUE
            );
            if (!(accepted instanceof Boolean value) || !value) {
                throw new IllegalStateException("Cubism rejected the target Deformer Undo entry.");
            }
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
                    refresh(app, Kind.ART_MESH, true);
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", changeUndo, listener);
            resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
            refresh(app, Kind.ART_MESH, true);
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end", editMode, Boolean.valueOf(!completed), null
            );
        }
    }

    void setClippingMaskIds(
        final String identity,
        final Object modelSource,
        final Object model,
        final ObjectRef current,
        final List<ArtMeshId> maskIds
    ) {
        requireInspectorWriteAuthorized();
        final ArrayList<Object> resolved = new ArrayList<>(maskIds.size());
        for (ArtMeshId maskId : maskIds) {
            if (maskId == null) throw new IllegalArgumentException("mask IDs must not contain null");
            final Object object = resolver.invoke(
                "cubism.editor-model.model-source.get-object", modelSource, maskId.value()
            );
            if (object == null || !resolver.isInstance("cubism.editor-model.drawable-guid.class",
                resolver.invoke("cubism.editor-model.parameter-controllable-source.guid", object))) {
                throw new IllegalArgumentException(
                    "clipping mask ID does not resolve to a Drawable: " + maskId.value()
                );
            }
            resolved.add(resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.guid", object
            ));
        }
        writeInspector(UndoKind.BASIC_SETTING, modelSource, current.source(), "Set ArtMesh clipping masks", () -> {
            final Object clipList = resolver.invoke(
                "cubism.editor-model.art-mesh-source.clip-guid-list", current.source()
            );
            resolver.invoke("cubism.editor-model.id-list.clear", clipList);
            if (!resolved.isEmpty()) {
                resolver.invoke("cubism.editor-model.id-list.add-all", clipList, resolved);
            }
        });
    }

    void setInvertedMask(
        final Object modelSource,
        final Object objectSource,
        final boolean value
    ) {
        requireInspectorWriteAuthorized();
        if (core.sourceFlag("cubism.editor-model.art-mesh-source.inverted-mask", objectSource, "ArtMesh inverted-mask state") == value) {
            return;
        }
        if (value && targetVersionNumber(modelSource) < TARGET_VERSION_SDK40) {
            throw new UnsupportedOperationException(
                "Inverted clipping masks require a Cubism 4.0+ model target (CUB3-2528)."
            );
        }
        writeInspector(UndoKind.BASIC_SETTING, modelSource, objectSource, "Set ArtMesh inverted mask", () ->
            resolver.invoke(
                "cubism.editor-model.art-mesh-source.set-invert-clipping-mask",
                objectSource,
                Boolean.valueOf(value)
            )
        );
    }

    void setDrawOrder(
        final Object modelSource,
        final Object objectSource,
        final Object form,
        final int value
    ) {
        requireInspectorWriteAuthorized();
        final int clamped = Math.max(0, Math.min(1000, value));
        final int original = integer(resolver.invoke("cubism.editor-model.drawable-form.draw-order", form), "draw order");
        if (original == clamped) return;
        writeArtMeshProperty(UndoKind.KEYFORM_EDIT, modelSource, objectSource, form, "drawOrder", "Set ArtMesh draw order",
            () -> resolver.invoke("cubism.editor-model.drawable-form.set-draw-order", form, Integer.valueOf(clamped)),
            () -> integer(resolver.invoke("cubism.editor-model.drawable-form.draw-order", form), "draw order") == clamped,
            () -> resolver.invoke("cubism.editor-model.drawable-form.set-draw-order", form, Integer.valueOf(original)),
            () -> integer(resolver.invoke("cubism.editor-model.drawable-form.draw-order", form), "draw order") == original);
    }

    void setColor(
        final Object modelSource,
        final Object objectSource,
        final Object form,
        final String colorAlias,
        final Color color,
        final String action
    ) {
        requireInspectorWriteAuthorized();
        if (targetVersionNumber(modelSource) < TARGET_VERSION_SDK42) {
            throw new UnsupportedOperationException(
                action + " requires a Cubism 4.2+ model target (CUB3-3264/CUB3-3265)."
            );
        }
        final Object hostColor = resolver.invoke(colorAlias, form);
        if (hostColor == null) {
            throw unavailable("Editor drawable color is unavailable.");
        }
        if (equalsColor(hostColor, color)) return;
        final Color original = new Color(
            number(resolver.invoke("cubism.editor-model.float-color.red", hostColor), "red"),
            number(resolver.invoke("cubism.editor-model.float-color.green", hostColor), "green"),
            number(resolver.invoke("cubism.editor-model.float-color.blue", hostColor), "blue"),
            number(resolver.invoke("cubism.editor-model.float-color.alpha", hostColor), "alpha"));
        final String property = colorAlias.equals("cubism.editor-model.drawable-form.multiply-color")
            ? "multiplyColor" : "screenColor";
        writeArtMeshProperty(UndoKind.KEYFORM_EDIT, modelSource, objectSource, form, property, action,
            () -> setCapturedHostColor(hostColor, color), () -> equalsColor(hostColor, color),
            () -> setCapturedHostColor(hostColor, original), () -> equalsColor(hostColor, original));
    }

    private void setCapturedHostColor(final Object hostColor, final Color color) {
        resolver.invoke("cubism.editor-model.float-color.set-red", hostColor, Float.valueOf(color.red()));
        resolver.invoke("cubism.editor-model.float-color.set-green", hostColor, Float.valueOf(color.green()));
        resolver.invoke("cubism.editor-model.float-color.set-blue", hostColor, Float.valueOf(color.blue()));
        resolver.invoke("cubism.editor-model.float-color.set-alpha", hostColor, Float.valueOf(color.alpha()));
    }

    private boolean equalsColor(final Object hostColor, final Color color) {
        final float red = number(resolver.invoke("cubism.editor-model.float-color.red", hostColor), "ArtMesh color red");
        final float green = number(resolver.invoke("cubism.editor-model.float-color.green", hostColor), "ArtMesh color green");
        final float blue = number(resolver.invoke("cubism.editor-model.float-color.blue", hostColor), "ArtMesh color blue");
        final float alpha = number(resolver.invoke("cubism.editor-model.float-color.alpha", hostColor), "ArtMesh color alpha");
        return Float.compare(red, color.red()) == 0
            && Float.compare(green, color.green()) == 0
            && Float.compare(blue, color.blue()) == 0
            && Float.compare(alpha, color.alpha()) == 0;
    }

    void setColorComposition(
        final Object modelSource,
        final Object objectSource,
        final ColorComposition composition
    ) {
        requireInspectorWriteAuthorized();
        final Object hostValue = hostEnumValue(
            "cubism.editor-model.color-composition.values",
            "Color composition",
            composition.name()
        );
        writeInspector(UndoKind.BASIC_SETTING, modelSource, objectSource, "Set ArtMesh color composition", () ->
            resolver.invoke(
                "cubism.editor-model.art-mesh-source.set-color-composition",
                objectSource,
                hostValue
            )
        );
    }

    void setAlphaComposition(
        final Object modelSource,
        final Object objectSource,
        final AlphaComposition composition
    ) {
        if (isCubism52()) {
            throw new UnsupportedOperationException(
                "ArtMesh alpha composition is unavailable on Cubism 5.2 hosts (AlphaComposition introduced in 5.3)."
            );
        }
        requireInspectorWriteAuthorized();
        final Object hostValue = hostEnumValue(
            "cubism.editor-model.alpha-composition.values",
            "Alpha composition",
            composition.name()
        );
        writeInspector(UndoKind.BASIC_SETTING, modelSource, objectSource, "Set ArtMesh alpha composition", () ->
            resolver.invoke(
                "cubism.editor-model.art-mesh-source.set-alpha-composition",
                objectSource,
                hostValue
            )
        );
    }

    private Object hostEnumValue(final String valuesAlias, final String label, final String name) {
        final Object values = resolver.invokeStatic(valuesAlias);
        if (!(values instanceof Object[] hostValues)) {
            throw unavailable("Editor " + label + " host values are unavailable.");
        }
        for (Object hostValue : hostValues) {
            if (hostValue != null && name.equals(hostValue.toString())) {
                return hostValue;
            }
        }
        throw new UnsupportedOperationException(
            label + " " + name + " is not supported by this Cubism host."
        );
    }

    void setCulling(
        final Object modelSource,
        final Object objectSource,
        final Object artMesh,
        final boolean value
    ) {
        requireInspectorWriteAuthorized();
        if (core.sourceFlag("cubism.editor-model.art-mesh-source.culling", objectSource, "ArtMesh culling state") == value) {
            return;
        }
        writeInspector(UndoKind.BASIC_SETTING, modelSource, objectSource, "Set ArtMesh culling", () -> {
            resolver.invoke(
                "cubism.editor-model.art-mesh-source.set-culling", objectSource, Boolean.valueOf(value)
            );
            resolver.invoke("cubism.editor-model.art-mesh.setup-shader", artMesh, new Object[]{null});
        });
    }

    void setUserData(
        final Object modelSource,
        final Object objectSource,
        final String userData
    ) {
        requireInspectorWriteAuthorized();
        if (userData == null) throw new IllegalArgumentException("userData must not be null");
        writeInspector(UndoKind.BASIC_SETTING, modelSource, objectSource, "Set ArtMesh user data", () ->
            resolver.invoke(
                "cubism.editor-model.art-mesh-source.set-user-data", objectSource, userData
            )
        );
    }
}
