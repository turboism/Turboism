package dev.turboism.adapter.cubism.mesh;

import dev.turboism.automesh.AutoMeshEditableWrite;
import dev.turboism.automesh.AutoMeshWrite;
import dev.turboism.automesh.AutoMeshWriteLayout;
import dev.turboism.automesh.AutoMeshWriteRefusal;
import dev.turboism.automesh.host.AutoMeshFunnel;
import dev.turboism.automesh.host.AutoMeshResolvedTarget;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Writes a plugin layout into the editable meshes a guarded funnel delivered, as the Editor writes
 * its own Auto Mesh result.
 *
 * <p>One write is one Editor operation: the funnel's own Undo envelope opens, every target receives
 * its canvas geometry and its Undo snapshot, the envelope closes, and the canvas repaints. A
 * failure at any step cancels the envelope, which reverts the snapshots, so a refused write leaves
 * every editable mesh exactly as it was.</p>
 *
 * <p>The mesh-editor funnel and the modeling funnel differ only in the Undo snapshot they take, so
 * one implementation covers both. The mode, the editable mesh and the pack all come from the funnel
 * arguments; nothing is re-resolved from the current selection.</p>
 *
 * <p>The canvas transform exists only for an ArtMesh that samples a model image. A target bound to
 * a texture atlas has no reviewed image-local transform, so the write refuses it with a reason
 * that names the ArtMesh instead of drawing the layout in the wrong space.</p>
 */
public final class EditorAutoMeshEditableWrite implements AutoMeshEditableWrite<AutoMeshResolvedTarget> {

    /** Reason code of a funnel call the write cannot follow back to an edit mode. */
    public static final String REASON_NO_EDIT_MODE = "no-edit-mode";

    /** Reason code of a target the host exposed without an editable mesh. */
    public static final String REASON_NO_EDITABLE_MESH = "no-editable-mesh";

    /** Reason code of a confirmation that mixed the two funnels. */
    public static final String REASON_MIXED_FUNNELS = "mixed-funnels";

    /** Reason code of a layout the conversion cannot express. */
    public static final String REASON_INVALID_LAYOUT = "invalid-layout";

    /** Reason code of a host that refused a step of the write. */
    public static final String REASON_WRITE_FAILED = "write-failed";

    private static final String MESSAGE_NO_EDIT_MODE =
            "The Editor is not in an edit mode that can receive an auto-mesh result.";
    private static final String MESSAGE_NO_EDITABLE_MESH = "The Editor exposes no editable mesh for ";
    private static final String MESSAGE_MIXED_FUNNELS =
            "The selected ArtMeshes did not come from the same Auto Mesh entry.";
    private static final String MESSAGE_INVALID_LAYOUT = "The generated layout cannot be drawn on the canvas: ";
    private static final String MESSAGE_WRITE_FAILED = "The Editor refused the auto-mesh result: ";
    private static final String MESSAGE_SUFFIX = ".";

    private static final String EDIT_MODE_CLASS = "cubism.editor-model.mesh-editor.class";
    private static final String MODE_FOR_MESH_EDITOR = "getEditMode_meshEdit";

    private static final String CURRENT_EDIT_MODE = "cubism.editor-model.modeling-document.current-edit-mode";

    /** Components of the row-major 2x3 affine the canvas conversion consumes. */

    /** Index of the pack among the bridge's token-and-receiver-prefixed arguments. */
    private static final int PACK_ARGUMENT = 2;

    private final VerifiedMemberResolver resolver;
    private final Consumer<String> diagnostics;

    /**
     * Creates the writer.
     *
     * @param resolver the verified Editor-model resolver of the active session
     * @param diagnostics receives every swallowed detail; it must not throw
     */
    public EditorAutoMeshEditableWrite(final VerifiedMemberResolver resolver, final Consumer<String> diagnostics) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override
    public List<ArtMeshId> write(final AutoMeshWrite<AutoMeshResolvedTarget> write) throws AutoMeshWriteRefusal {
        Objects.requireNonNull(write, "write");
        final AutoMeshFunnel funnel = funnelOf(write);
        final List<AutoMeshCanvasPoints> geometries = convert(write);
        final Object mode = editModeOf(write.targets().get(0), funnel);
        final Object group = NativeMeshMirrorBridge.beginUndoGroup(mode, write.undoLabel());
        if (group == null) {
            throw new AutoMeshWriteRefusal(REASON_NO_EDIT_MODE, MESSAGE_NO_EDIT_MODE);
        }
        boolean committed = false;
        try {
            clearSubSelection(funnel, write.targets().get(0));
            for (int index = 0; index < write.targets().size(); index++) {
                snapshot(funnel, write, index, group);
                NativeAutoMeshWrite.fireTopologyWillChange(
                        write.targets().get(index).source());
            }
            for (int index = 0; index < write.targets().size(); index++) {
                replace(write.targets().get(index), geometries.get(index));
            }
            NativeMeshMirrorBridge.commitUndoGroup(mode);
            committed = true;
            return ids(write);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new AutoMeshWriteRefusal(REASON_WRITE_FAILED, MESSAGE_WRITE_FAILED + describe(failure));
        } finally {
            if (!committed) {
                cancel(mode);
            }
        }
    }

    /** Returns the single funnel behind the selection, or refuses a mixed confirmation. */
    private static AutoMeshFunnel funnelOf(final AutoMeshWrite<AutoMeshResolvedTarget> write)
            throws AutoMeshWriteRefusal {
        final AutoMeshFunnel funnel = write.targets().get(0).funnel();
        for (final AutoMeshResolvedTarget target : write.targets()) {
            if (target.funnel() != funnel) {
                throw new AutoMeshWriteRefusal(REASON_MIXED_FUNNELS, MESSAGE_MIXED_FUNNELS);
            }
        }
        return funnel;
    }

    /**
     * Converts every layout into canvas geometry before the write opens any Undo envelope.
     *
     * <p>A layout the host cannot map must leave the model untouched, so every transform resolves
     * first; only a complete set reaches the write.</p>
     */
    private List<AutoMeshCanvasPoints> convert(final AutoMeshWrite<AutoMeshResolvedTarget> write)
            throws AutoMeshWriteRefusal {
        final List<AutoMeshCanvasPoints> geometries =
                new ArrayList<>(write.targets().size());
        for (int index = 0; index < write.targets().size(); index++) {
            final AutoMeshResolvedTarget target = write.targets().get(index);
            final AutoMeshWriteLayout layout = write.layouts().get(index);
            try {
                geometries.add(AutoMeshCanvasPoints.of(
                        layout,
                        NativeAutoMeshWrite.sourceUvs(target.source()),
                        NativeAutoMeshWrite.sourcePositions(target.source()),
                        NativeAutoMeshWrite.sourceIndices(target.source())));
            } catch (ReflectiveOperationException | RuntimeException invalid) {
                FatalErrors.rethrowIfFatal(invalid);
                throw new AutoMeshWriteRefusal(
                        REASON_INVALID_LAYOUT,
                        MESSAGE_INVALID_LAYOUT + target.drawable().id().value() + MESSAGE_SUFFIX);
            }
        }
        return geometries;
    }

    /** Replaces one editable mesh and publishes the change, in the Editor's own order. */
    private void replace(final AutoMeshResolvedTarget target, final AutoMeshCanvasPoints geometry)
            throws ReflectiveOperationException, AutoMeshWriteRefusal {
        final Object editableMesh = target.editableMesh();
        if (editableMesh == null) {
            throw new AutoMeshWriteRefusal(
                    REASON_NO_EDITABLE_MESH,
                    MESSAGE_NO_EDITABLE_MESH + target.drawable().id().value() + MESSAGE_SUFFIX);
        }
        NativeAutoMeshWrite.verifySource(target.source(), false);
        switch (target.funnel()) {
            case MESH_EDITOR_MODE ->
                NativeAutoMeshWrite.replaceGeometry(editableMesh, geometry.pointsXY(), geometry.edgePairs());
            case MODELING_MODE ->
                NativeAutoMeshWrite.applyToSource(
                        target.source(), editableMesh, geometry.pointsXY(), geometry.edgePairs());
        }
        verifyAfterReplace(target.source());
        NativeAutoMeshWrite.publishSourceChange(target.source(), target.completePack());
    }

    /**
     * Verifies the replaced source and recovers it once, exactly as the Editor does.
     *
     * <p>The Editor treats a failed verification as a recoverable inconsistency: it asks the source
     * to recover, then verifies again so a second failure refuses the write instead of keeping a
     * mesh the source cannot describe.</p>
     */
    private static void verifyAfterReplace(final Object source) throws ReflectiveOperationException {
        try {
            NativeAutoMeshWrite.verifySource(source, true);
        } catch (ReflectiveOperationException | RuntimeException failed) {
            FatalErrors.rethrowIfFatal(failed);
            NativeAutoMeshWrite.recoverSource(source);
            NativeAutoMeshWrite.verifySource(source, true);
        }
    }

    private void snapshot(
            final AutoMeshFunnel funnel,
            final AutoMeshWrite<AutoMeshResolvedTarget> write,
            final int index,
            final Object group)
            throws ReflectiveOperationException, AutoMeshWriteRefusal {
        final AutoMeshResolvedTarget target = write.targets().get(index);
        switch (funnel) {
            case MESH_EDITOR_MODE -> {
                if (target.editableMesh() == null) {
                    throw new AutoMeshWriteRefusal(
                            REASON_NO_EDITABLE_MESH,
                            MESSAGE_NO_EDITABLE_MESH + target.drawable().id().value() + MESSAGE_SUFFIX);
                }
                NativeAutoMeshWrite.snapshotMesh(write.undoLabel(), group, target.editableMesh());
            }
            case MODELING_MODE -> NativeAutoMeshWrite.snapshotSource(write.undoLabel(), group, target.source());
        }
    }

    /** Clears the mesh point selection so the replaced mesh carries no stale reference. */
    private static void clearSubSelection(final AutoMeshFunnel funnel, final AutoMeshResolvedTarget target) {
        if (funnel != AutoMeshFunnel.MESH_EDITOR_MODE) return;
        try {
            final Object document = NativeMeshMirrorReflectionOps.call(
                    NativeMeshMirrorReflectionOps.call(
                            target.completePack(), "getCurrentDoc", NativeMeshMirrorReflectionOps.NO_PARAMS),
                    "getSelector",
                    NativeMeshMirrorReflectionOps.NO_PARAMS);
            if (document != null) {
                NativeMeshMirrorReflectionOps.call(
                        document, "clearSubSelection", NativeMeshMirrorReflectionOps.NO_PARAMS);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // A selection reset is a hygiene step; the write itself must still run.
        }
    }

    /** Returns the edit mode the funnel delivered, or refuses when the host exposes none. */
    private Object editModeOf(final AutoMeshResolvedTarget target, final AutoMeshFunnel funnel)
            throws AutoMeshWriteRefusal {
        try {
            final Object document = documentOf(target);
            if (document == null) {
                throw new AutoMeshWriteRefusal(REASON_NO_EDIT_MODE, MESSAGE_NO_EDIT_MODE);
            }
            final Object current = resolver.invoke(CURRENT_EDIT_MODE, document);
            if (funnel != AutoMeshFunnel.MESH_EDITOR_MODE) {
                return requireEditMode(current);
            }
            if (!resolver.isInstance(EDIT_MODE_CLASS, current)) {
                final Object meshEditor = NativeMeshMirrorReflectionOps.call(
                        document, MODE_FOR_MESH_EDITOR, NativeMeshMirrorReflectionOps.NO_PARAMS);
                return requireEditMode(meshEditor);
            }
            return requireEditMode(current);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new AutoMeshWriteRefusal(REASON_NO_EDIT_MODE, MESSAGE_NO_EDIT_MODE);
        }
    }

    private static Object requireEditMode(final Object mode) throws AutoMeshWriteRefusal {
        if (mode == null) {
            throw new AutoMeshWriteRefusal(REASON_NO_EDIT_MODE, MESSAGE_NO_EDIT_MODE);
        }
        return mode;
    }

    private static Object documentOf(final AutoMeshResolvedTarget target) throws ReflectiveOperationException {
        final Object pack = target.completePack();
        return pack == null
                ? null
                : NativeMeshMirrorReflectionOps.call(pack, "getCurrentDoc", NativeMeshMirrorReflectionOps.NO_PARAMS);
    }

    private void cancel(final Object mode) {
        try {
            NativeMeshMirrorBridge.cancelUndoGroup(mode);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnose("auto mesh write: the Editor did not cancel the refused edit: " + describe(failure));
        }
    }

    private static List<ArtMeshId> ids(final AutoMeshWrite<AutoMeshResolvedTarget> write) {
        final List<ArtMeshId> ids = new ArrayList<>(write.targets().size());
        for (final AutoMeshResolvedTarget target : write.targets()) {
            ids.add(target.drawable().id());
        }
        return List.copyOf(ids);
    }

    private void diagnose(final String message) {
        try {
            diagnostics.accept(message);
        } catch (RuntimeException | LinkageError failure) {
            FatalErrors.rethrowIfFatal(failure);
            // A diagnostic sink must never change the write's answer.
        }
    }

    private static String describe(final Throwable failure) {
        final String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message.replace('\n', ' '));
    }
}
