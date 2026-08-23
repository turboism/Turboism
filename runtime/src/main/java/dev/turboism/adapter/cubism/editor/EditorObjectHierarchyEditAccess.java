package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorObjectHierarchyEditSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.WarpGrid;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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

    private static final int MAX_OBJECT_ID_LENGTH = 128;

    private static final String CREATE_PART_ACTION = "Turboism: Create Part";
    private static final String CREATE_ART_MESH_ACTION = "Turboism: Create ArtMesh";
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
            "cubism.editor-model.part-source.create", modelSource
        );
        if (!resolver.isInstance("cubism.editor-model.part-source.class", created)) {
            throw unavailable("Editor Part source construction is invalid.");
        }
        setObjectId(created, "Part", nextObjectId(modelSource, "Part", name));
        resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.set-local-name",
            created,
            name
        );
        initializePart(created, modelSource);
        writeCreatedSource(
            modelSource,
            created,
            parentSource,
            index,
            CREATE_PART_ACTION,
            "cubism.editor-model.complete-pack.update-part-palette"
        );
        return created;
    }

    Object createArtMeshSource(
        final String identity,
        final Object modelSource,
        final Object model,
        final String requestedName,
        final Object parentSource,
        final int index,
        final ArtMeshGeometry geometry
    ) {
        final String name = requireName(requestedName);
        final ArtMeshGeometry checkedGeometry = Objects.requireNonNull(geometry, "geometry");
        requireArtMeshCreateAuthorized();
        currentGuard.requireCurrent(identity, model);
        final Object created = resolver.construct(
            "cubism.editor-model.art-mesh-source.create", modelSource
        );
        if (!resolver.isInstance("cubism.editor-model.art-mesh-source.class", created)) {
            throw unavailable("Editor ArtMesh source construction is invalid.");
        }
        final String id = nextObjectId(modelSource, "ArtMesh", name);
        setObjectId(created, "ArtMesh", id);
        resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.set-local-name",
            created, name
        );
        initializeArtMesh(created, checkedGeometry, modelSource);
        writeCreatedSource(
            modelSource,
            created,
            parentSource,
            index,
            CREATE_ART_MESH_ACTION,
            "cubism.editor-model.complete-pack.update-part-palette"
        );
        return created;
    }

    private void initializePart(final Object source, final Object modelSource) {
        final Object form = resolver.construct(
            "cubism.editor-model.part-form.create",
            source,
            null
        );
        initializeKeyformSource(
            source,
            form,
            modelSource,
            "cubism.editor-model.part-source.keyforms"
        );
    }

    private void initializeArtMesh(
        final Object source,
        final ArtMeshGeometry geometry,
        final Object modelSource
    ) {
        final float[] positions = flatten(geometry.positions());
        final float[] uvs = flatten(geometry.uvs());
        final int[] indices = geometry.triangleIndices().stream()
            .mapToInt(Integer::intValue)
            .toArray();
        resolver.invoke("cubism.editor-model.art-mesh-source.set-positions", source, positions);
        resolver.invoke("cubism.editor-model.art-mesh-source.set-uvs", source, uvs);
        resolver.invoke("cubism.editor-model.art-mesh-source.set-indices", source, indices);
        final Object form = resolver.construct(
            "cubism.editor-model.art-mesh-form.create",
            source,
            null,
            resolver.invokeStatic("cubism.editor-model.coord-type.canvas")
        );
        resolver.invoke(
            "cubism.editor-model.art-mesh-form.set-positions", form, positions.clone()
        );
        initializeKeyformSource(
            source,
            form,
            modelSource,
            "cubism.editor-model.art-mesh-source.keyforms"
        );
    }

    private void initializeWarp(
        final Object source,
        final Object modelSource,
        final WarpGrid grid
    ) {
        resolver.invoke(
            "cubism.editor-model.warp-source.set-row",
            source,
            Integer.valueOf(grid.rows())
        );
        resolver.invoke(
            "cubism.editor-model.warp-source.set-col",
            source,
            Integer.valueOf(grid.columns())
        );
        resolver.invoke(
            "cubism.editor-model.warp-source.set-quad-transform",
            source,
            Boolean.valueOf(grid.quadTransform())
        );
        final Object form = resolver.construct(
            "cubism.editor-model.warp-form.create",
            source,
            null,
            resolver.invokeStatic("cubism.editor-model.coord-type.canvas")
        );
        resolver.invoke(
            "cubism.editor-model.warp-form.set-positions",
            form,
            flatten(grid.controlPoints())
        );
        initializeKeyformSource(
            source,
            form,
            modelSource,
            "cubism.editor-model.warp-source.keyforms"
        );
    }

    private void initializeRotation(final Object source, final Object modelSource) {
        final Object form = resolver.construct(
            "cubism.editor-model.rotation-form.create",
            source,
            null,
            resolver.invokeStatic("cubism.editor-model.coord-type.canvas")
        );
        resolver.invoke("cubism.editor-model.rotation-form.set-angle", form, Float.valueOf(0.0F));
        resolver.invoke("cubism.editor-model.rotation-form.set-origin-x", form, Float.valueOf(0.0F));
        resolver.invoke("cubism.editor-model.rotation-form.set-origin-y", form, Float.valueOf(0.0F));
        resolver.invoke("cubism.editor-model.rotation-form.set-scale", form, Float.valueOf(1.0F));
        resolver.invoke("cubism.editor-model.rotation-form.set-reflect-x", form, Boolean.FALSE);
        resolver.invoke("cubism.editor-model.rotation-form.set-reflect-y", form, Boolean.FALSE);
        initializeKeyformSource(
            source,
            form,
            modelSource,
            "cubism.editor-model.rotation-source.keyforms"
        );
    }

    private void initializeKeyformSource(
        final Object source,
        final Object form,
        final Object modelSource,
        final String keyformsAlias
    ) {
        final Object formGuid = resolver.construct("cubism.editor-model.form-guid.create");
        resolver.invoke("cubism.editor-model.form.set-guid", form, formGuid);
        final Object keyforms = resolver.invoke(keyformsAlias, source);
        resolver.invoke("cubism.editor-model.c-array-list.add", keyforms, form);
        final Object keyformGrid = resolver.construct(
            "cubism.editor-model.keyform-grid-source.create", source
        );
        resolver.invoke(
            "cubism.editor-model.keyform-grid-source.import-cubism21",
            keyformGrid,
            modelSource,
            List.of(),
            List.of(formGuid),
            null
        );
        resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.set-keyform-grid-source",
            source,
            keyformGrid
        );
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
            "cubism.editor-model.warp-source.create", modelSource
        );
        if (!resolver.isInstance("cubism.editor-model.warp-source.class", created)) {
            throw unavailable("Editor Warp Deformer source construction is invalid.");
        }
        setObjectId(created, "Deformer", nextObjectId(modelSource, "WarpDeformer", name));
        resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.set-local-name",
            created,
            name
        );
        initializeWarp(created, modelSource, defaultWarpGrid(rows, columns));
        writeCreatedSource(
            modelSource,
            created,
            parentSource,
            index,
            CREATE_WARP_ACTION,
            "cubism.editor-model.complete-pack.update-deformer-palette"
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
            "cubism.editor-model.rotation-source.create", modelSource
        );
        if (!resolver.isInstance("cubism.editor-model.rotation-source.class", created)) {
            throw unavailable("Editor Rotation Deformer source construction is invalid.");
        }
        setObjectId(
            created,
            "Deformer",
            nextObjectId(modelSource, "RotationDeformer", name)
        );
        resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.set-local-name",
            created,
            name
        );
        initializeRotation(created, modelSource);
        writeCreatedSource(
            modelSource,
            created,
            parentSource,
            index,
            CREATE_ROTATION_ACTION,
            "cubism.editor-model.complete-pack.update-deformer-palette"
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

    private void writeCreatedSource(
        final Object modelSource,
        final Object objectSource,
        final Object parentSource,
        final int index,
        final String action,
        final String paletteAlias
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
            final Object modelHandler = resolver.invoke(
                "cubism.editor-model.model-source.handler", modelSource
            );
            final Object addUndo = resolver.invoke(
                "cubism.editor-model.model-handler.add-source-undo",
                modelHandler,
                objectSource,
                Integer.valueOf(index)
            );
            requireUndoAccepted(edit, addUndo, "ArtMesh creation");
            if (parentSource != null) {
                final Object parentHandler = resolver.invoke(
                    "cubism.editor-model.parameter-controllable-source.handler", parentSource
                );
                if (!resolver.isInstance(
                    "cubism.editor-model.parameter-controllable-handler.class", parentHandler
                )) {
                    throw unavailable("Editor parent Undo handler is unavailable.");
                }
                final Object parentUndo = resolver.invoke(
                    "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
                    parentHandler,
                    action
                );
                requireUndoAccepted(edit, parentUndo, "ArtMesh parent attachment");
                attachToParent(objectSource, parentSource, index);
            }
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    resolver.invoke(
                        "cubism.editor-model.model-source.update-instances", modelSource
                    );
                    refresh(app, paletteAlias);
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", addUndo, listener);
            resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
            refresh(app, paletteAlias);
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

    private void requireUndoAccepted(
        final Object edit,
        final Object undo,
        final String operation
    ) {
        final Object accepted = resolver.invoke(
            "cubism.editor-model.undo.add", edit, undo, Boolean.TRUE
        );
        if (!(accepted instanceof Boolean value) || !value) {
            throw new IllegalStateException("Cubism rejected the " + operation + " Undo entry.");
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

    private boolean artMeshCreateAuthorized() {
        return resolver.authorizesFeature(
            EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectHierarchyEditSelectorContract.ART_MESH_CREATE_CAPABILITY_ID,
            EditorObjectHierarchyEditSelectorContract.ART_MESH_CREATE_REQUIRED_ALIASES
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

    private void requireArtMeshCreateAuthorized() {
        if (!artMeshCreateAuthorized()) {
            throw new UnsupportedOperationException(
                "Editor ArtMesh creation is unavailable without exact verified host evidence."
            );
        }
    }

    private String nextObjectId(
        final Object modelSource,
        final String prefix,
        final String name
    ) {
        final Set<String> existing = new HashSet<>();
        for (Object source : iterable(
            resolver.invoke("cubism.editor-model.model-source.all-objects", modelSource),
            "Editor object collection"
        )) {
            existing.add(objectId(source));
        }
        final String base = truncate(prefix + slug(name), MAX_OBJECT_ID_LENGTH - 8);
        if (!existing.contains(base)) return base;
        for (int suffix = 2; suffix < 1_000_000; suffix++) {
            final String candidate = truncate(base, MAX_OBJECT_ID_LENGTH - 8)
                + "_" + suffix;
            if (!existing.contains(candidate)) return candidate;
        }
        throw new IllegalStateException("Could not allocate a unique Cubism ArtMesh ID.");
    }

    private void setObjectId(
        final Object source,
        final String family,
        final String id
    ) {
        final String idAlias;
        final String setterAlias;
        switch (family) {
            case "Part" -> {
                idAlias = "cubism.editor-model.part-id.create";
                setterAlias = "cubism.editor-model.part-source.set-id";
            }
            case "ArtMesh" -> {
                idAlias = "cubism.editor-model.drawable-id.create";
                setterAlias = "cubism.editor-model.drawable-source.set-id";
            }
            case "Deformer" -> {
                idAlias = "cubism.editor-model.deformer-id.create";
                setterAlias = "cubism.editor-model.deformer-source.set-id";
            }
            default -> throw new IllegalArgumentException(
                "Unsupported Editor object ID family: " + family
            );
        }
        resolver.invoke(setterAlias, source, resolver.construct(idAlias, id));
    }

    private String objectId(final Object source) {
        final Object id = resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.id", source
        );
        final Object value = resolver.invoke("cubism.editor-model.id.value", id);
        if (!(value instanceof String text) || text.isBlank()) {
            throw unavailable("Editor object ID is invalid.");
        }
        return text;
    }

    private static WarpGrid defaultWarpGrid(final int rows, final int columns) {
        final ArrayList<Point2> points = new ArrayList<>((rows + 1) * (columns + 1));
        for (int row = 0; row <= rows; row++) {
            for (int column = 0; column <= columns; column++) {
                points.add(new Point2(
                    column / (float) columns - 0.5F,
                    row / (float) rows - 0.5F
                ));
            }
        }
        return new WarpGrid(rows, columns, false, points);
    }

    private static float[] flatten(final List<Point2> points) {
        final float[] values = new float[points.size() * 2];
        for (int index = 0; index < points.size(); index++) {
            values[index * 2] = points.get(index).x();
            values[index * 2 + 1] = points.get(index).y();
        }
        return values;
    }

    private static String slug(final String name) {
        final StringBuilder result = new StringBuilder();
        boolean upper = true;
        for (int offset = 0; offset < name.length();) {
            final int codePoint = name.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint < 128 && Character.isLetterOrDigit(codePoint)) {
                final char value = (char) codePoint;
                result.append(upper ? Character.toUpperCase(value) : value);
                upper = false;
            } else {
                upper = true;
            }
        }
        return result.isEmpty() ? "Object" : result.toString();
    }

    private static String truncate(final String value, final int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
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
