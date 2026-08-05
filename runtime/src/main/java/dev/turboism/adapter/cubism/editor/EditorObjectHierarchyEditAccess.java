package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorObjectHierarchyEditSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exact, generation-bound Editor authoring projection for object-hierarchy editing:
 * Part/Deformer/Drawable create, delete, rename, and reparent.
 *
 * <p>Every mutation is admitted through the native Undo skeleton (editMode begin/end,
 * create-undo-for-all-edit, undo.add, undo listener → update-instances + palette refresh +
 * repaint + markDirty). Delete uses the NATIVE delete path: {@code CEUpdateManager.setSelection}
 * with the object's GUID followed by {@code CEAppCtrl.command_delete()} — child semantics
 * (Part cascade, Deformer child re-parent, Drawable leaf) are owned by Cubism itself and are
 * never re-implemented here. Reparent under a Part uses the native {@code addChild} (which
 * natively detaches from the old parent and calls {@code internal_setParent}); reparent under a
 * Deformer uses the native target-deformer relation ({@code setTargetDeformerGuid}).</p>
 */
final class EditorObjectHierarchyEditAccess {

    private static final String CREATE_PART_ACTION = "Turboism: Create Part";
    private static final String CREATE_WARP_ACTION = "Turboism: Create Warp Deformer";
    private static final String CREATE_ROTATION_ACTION = "Turboism: Create Rotation Deformer";
    private static final String DELETE_ACTION = "Turboism: Delete ";
    private static final String SET_NAME_ACTION = "Turboism: Rename ";
    private static final String SET_PARENT_ACTION = "Turboism: Set Parent ";

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard currentGuard;

    EditorObjectHierarchyEditAccess(
        final VerifiedMemberResolver resolver,
        final EditorParameterCombinedAccess.ModelGuard currentGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.currentGuard = Objects.requireNonNull(currentGuard, "currentGuard");
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    Object createPartSource(
        final String identity,
        final Object modelSource,
        final Object model,
        final String requestedName,
        final Object parentSource,
        final int index
    ) {
        final String name = requireName(requestedName);
        requireEditAuthorized();
        currentGuard.requireCurrent(identity, model);
        final Object created = resolver.construct(
            "cubism.editor-model.part-source.create", name, modelSource
        );
        if (!resolver.isInstance("cubism.editor-model.part-source.class", created)) {
            throw unavailable("Editor Part source construction is invalid.");
        }
        write(
            "cubism.editor-model.complete-pack.update-part-palette",
            "Part",
            modelSource,
            created,
            CREATE_PART_ACTION,
            () -> {
                final Object partSet = resolver.invoke(
                    "cubism.editor-model.model-source.part-source-set", modelSource
                );
                resolver.invoke(
                    "cubism.editor-model.part-source-set.add",
                    partSet, created, Integer.valueOf(index)
                );
                attachToParent(created, parentSource, index);
            }
        );
        return created;
    }

    Object createWarpSource(
        final String identity,
        final Object modelSource,
        final Object model,
        final String requestedName,
        final Object parentSource,
        final int index,
        final int rows,
        final int columns
    ) {
        requireGrid(rows, columns);
        final String name = requireName(requestedName);
        requireEditAuthorized();
        currentGuard.requireCurrent(identity, model);
        final Object created = resolver.construct(
            "cubism.editor-model.warp-deformer-source.create", modelSource
        );
        if (!resolver.isInstance("cubism.editor-model.warp-source.class", created)) {
            throw unavailable("Editor Warp Deformer source construction is invalid.");
        }
        write(
            "cubism.editor-model.complete-pack.update-deformer-palette",
            "Warp Deformer",
            modelSource,
            created,
            CREATE_WARP_ACTION,
            () -> {
                resolver.invoke(
                    "cubism.editor-model.warp-source.set-row",
                    created, Integer.valueOf(rows)
                );
                resolver.invoke(
                    "cubism.editor-model.warp-source.set-col",
                    created, Integer.valueOf(columns)
                );
                resolver.invoke(
                    "cubism.editor-model.parameter-controllable-source.set-local-name",
                    created, name
                );
                final Object deformerSet = resolver.invoke(
                    "cubism.editor-model.model-source.deformer-source-set", modelSource
                );
                resolver.invoke(
                    "cubism.editor-model.deformer-source-set.add",
                    deformerSet, created, Integer.valueOf(index)
                );
                attachToParent(created, parentSource, index);
            }
        );
        return created;
    }

    Object createRotationSource(
        final String identity,
        final Object modelSource,
        final Object model,
        final String requestedName,
        final Object parentSource,
        final int index
    ) {
        final String name = requireName(requestedName);
        requireEditAuthorized();
        currentGuard.requireCurrent(identity, model);
        final Object created = resolver.construct(
            "cubism.editor-model.rotation-deformer-source.create", modelSource
        );
        if (!resolver.isInstance("cubism.editor-model.rotation-source.class", created)) {
            throw unavailable("Editor Rotation Deformer source construction is invalid.");
        }
        write(
            "cubism.editor-model.complete-pack.update-deformer-palette",
            "Rotation Deformer",
            modelSource,
            created,
            CREATE_ROTATION_ACTION,
            () -> {
                resolver.invoke(
                    "cubism.editor-model.parameter-controllable-source.set-local-name",
                    created, name
                );
                final Object deformerSet = resolver.invoke(
                    "cubism.editor-model.model-source.deformer-source-set", modelSource
                );
                resolver.invoke(
                    "cubism.editor-model.deformer-source-set.add",
                    deformerSet, created, Integer.valueOf(index)
                );
                attachToParent(created, parentSource, index);
            }
        );
        return created;
    }

    private void attachToParent(final Object nodeSource, final Object parentSource, final int index) {
        if (parentSource == null) return;
        resolver.invoke(
            "cubism.editor-model.part-source.add-child",
            parentSource, nodeSource, Integer.valueOf(index)
        );
    }

    // ------------------------------------------------------------------
    // delete — NATIVE path: selection + native DELETE command
    // ------------------------------------------------------------------

    void remove(
        final String identity,
        final Object modelSource,
        final Object model,
        final Object nodeSource,
        final String kindLabel
    ) {
        requireEditAuthorized();
        currentGuard.requireCurrent(identity, model);
        write(
            "cubism.editor-model.complete-pack.update-deformer-palette",
            kindLabel,
            modelSource,
            nodeSource,
            DELETE_ACTION + kindLabel,
            () -> {
                final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
                final Object document = resolver.invoke(
                    "cubism.editor-model.app-controller.current-document", app
                );
                final Object guid = resolver.invoke(
                    "cubism.editor-model.parameter-controllable-source.guid", nodeSource
                );
                final Object updateManager = resolver.invoke(
                    "cubism.editor-model.app-controller.update-manager", app
                );
                resolver.invoke(
                    "cubism.editor-model.update-manager.set-selection",
                    updateManager, document, List.of(guid), Boolean.FALSE, Boolean.TRUE
                );
                resolver.invoke("cubism.editor-model.app-controller.command-delete", app);
            }
        );
    }

    // ------------------------------------------------------------------
    // rename — set-local-name on the shared base class
    // ------------------------------------------------------------------

    void setName(
        final String identity,
        final Object modelSource,
        final Object model,
        final Object nodeSource,
        final String requestedName,
        final String kindLabel
    ) {
        final String name = requireName(requestedName);
        requireRenameAuthorized();
        currentGuard.requireCurrent(identity, model);
        if (name.equals(localName(nodeSource, kindLabel))) return;
        write(
            "cubism.editor-model.complete-pack.update-part-palette",
            kindLabel,
            modelSource,
            nodeSource,
            SET_NAME_ACTION + kindLabel,
            () -> resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.set-local-name",
                nodeSource, name
            )
        );
    }

    private String localName(final Object nodeSource, final String kindLabel) {
        final Object value = resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.local-name", nodeSource
        );
        if (value == null) return "";
        if (!(value instanceof String name)) {
            throw unavailable("Editor " + kindLabel + " display name is invalid.");
        }
        return name;
    }

    // ------------------------------------------------------------------
    // reparent — native addChild (Part parent) or native target-deformer relation
    // ------------------------------------------------------------------

    void setParent(
        final String identity,
        final Object modelSource,
        final Object model,
        final Object nodeSource,
        final Object parentSource,
        final boolean parentIsDeformer,
        final int index,
        final String kindLabel
    ) {
        requireEditAuthorized();
        currentGuard.requireCurrent(identity, model);
        rejectCycle(nodeSource, parentSource, parentIsDeformer);
        write(
            "cubism.editor-model.complete-pack.update-deformer-palette",
            kindLabel,
            modelSource,
            nodeSource,
            SET_PARENT_ACTION + kindLabel,
            () -> {
                if (parentIsDeformer) {
                    final Object parentGuid = resolver.invoke(
                        "cubism.editor-model.parameter-controllable-source.guid",
                        parentSource
                    );
                    resolver.invoke(
                        "cubism.editor-model.parameter-controllable-source.set-target-deformer-guid",
                        nodeSource, parentGuid
                    );
                } else {
                    resolver.invoke(
                        "cubism.editor-model.part-source.add-child",
                        parentSource, nodeSource, Integer.valueOf(index)
                    );
                }
            }
        );
    }

    /**
     * Read-only native ancestor walk. A cycle is rejected by us with a clear exception because
     * the native {@code addChild}/{@code setTargetDeformerGuid} members do not guard against
     * ancestor cycles themselves (javap evidence, 2026-08-05).
     */
    private void rejectCycle(
        final Object nodeSource,
        final Object parentSource,
        final boolean parentIsDeformer
    ) {
        if (parentIsDeformer) {
            final Iterable<?> deformerAncestors = iterable(
                resolver.invoke(
                    "cubism.editor-model.parameter-controllable-source.all-parent-deformers",
                    parentSource
                ),
                "Editor Deformer ancestor chain"
            );
            for (Object ancestor : deformerAncestors) {
                if (ancestor == nodeSource) throw cycle(parentSource);
            }
            rejectPartChainCycle(nodeSource, parentSource);
            return;
        }
        rejectPartChainCycle(nodeSource, parentSource);
    }

    private void rejectPartChainCycle(final Object nodeSource, final Object parentSource) {
        Object current = parentSource;
        final java.util.IdentityHashMap<Object, Boolean> visited = new java.util.IdentityHashMap<>();
        while (current != null && !visited.containsKey(current)) {
            visited.put(current, Boolean.TRUE);
            if (current == nodeSource) throw cycle(parentSource);
            final Object targetDeformer = resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.target-deformer-source",
                current
            );
            if (targetDeformer != null) {
                if (targetDeformer == nodeSource) throw cycle(parentSource);
                for (Object ancestor : iterable(
                    resolver.invoke(
                        "cubism.editor-model.parameter-controllable-source.all-parent-deformers",
                        targetDeformer
                    ),
                    "Editor Deformer ancestor chain"
                )) {
                    if (ancestor == nodeSource) throw cycle(parentSource);
                }
            }
            current = resolver.invoke("cubism.editor-model.part-source.parent", current);
        }
    }

    // ------------------------------------------------------------------
    // native Undo skeleton
    // ------------------------------------------------------------------

    private void write(
        final String paletteAlias,
        final String kindLabel,
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
        final Object edit = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, action
        );
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
                handler, action
            );
            final Object accepted = resolver.invoke(
                "cubism.editor-model.undo.add", edit, objectUndo, Boolean.TRUE
            );
            if (!(accepted instanceof Boolean value) || !value) {
                throw new IllegalStateException(
                    "Cubism rejected the " + kindLabel + " hierarchy Undo entry."
                );
            }
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
                    refresh(app, paletteAlias);
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", objectUndo, listener);
            mutation.run();
            resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
            refresh(app, paletteAlias);
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end",
                editMode, Boolean.valueOf(!completed), null
            );
        }
    }

    private void refresh(final Object app, final String paletteAlias) {
        final Object completePack = resolver.invoke(
            "cubism.editor-model.app-controller.complete-pack", app
        );
        resolver.invoke(paletteAlias, completePack, Boolean.TRUE);
        resolver.invoke(
            "cubism.editor-model.complete-pack.repaint-canvas", completePack, Boolean.TRUE
        );
    }

    // ------------------------------------------------------------------
    // gates and helpers
    // ------------------------------------------------------------------

    private boolean editAuthorized() {
        return resolver.authorizesFeature(
            EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectHierarchyEditSelectorContract.CAPABILITY_ID,
            EditorObjectHierarchyEditSelectorContract.REQUIRED_ALIASES
        );
    }

    private boolean renameAuthorized() {
        return resolver.authorizesFeature(
            EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectHierarchyEditSelectorContract.RENAME_CAPABILITY_ID,
            EditorObjectHierarchyEditSelectorContract.RENAME_REQUIRED_ALIASES
        );
    }

    private void requireEditAuthorized() {
        if (!editAuthorized()) {
            throw new UnsupportedOperationException(
                "Editor object-hierarchy editing is unavailable without exact verified host evidence."
            );
        }
    }

    private void requireRenameAuthorized() {
        if (!renameAuthorized()) {
            throw new UnsupportedOperationException(
                "Editor object renaming is unavailable without exact verified host evidence."
            );
        }
    }

    private static String requireName(final String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        return name;
    }

    private static void requireGrid(final int rows, final int columns) {
        if (rows <= 0 || columns <= 0) {
            throw new IllegalArgumentException("rows and columns must be positive");
        }
    }

    private static Iterable<?> iterable(final Object value, final String label) {
        if (!(value instanceof Iterable<?> iterable)) {
            throw unavailable(label + " is unavailable.");
        }
        final ArrayList<Object> copy = new ArrayList<>();
        iterable.forEach(copy::add);
        return List.copyOf(copy);
    }

    private static IllegalArgumentException cycle(final Object parentSource) {
        return new IllegalArgumentException(
            "Cycle rejected: the requested parent is inside the node's own subtree (native source "
                + parentSource.getClass().getName() + ")."
        );
    }

    private static IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }
}
