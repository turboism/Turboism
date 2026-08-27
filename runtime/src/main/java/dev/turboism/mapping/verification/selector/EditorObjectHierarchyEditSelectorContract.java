package dev.turboism.mapping.verification.selector;

import java.util.HashSet;
import java.util.Set;

/**
 * Exact additive selector contract for Editor object-hierarchy editing (Part/Deformer/Drawable
 * create, delete, rename, and reparent).
 *
 * <p>Every member below is declared with the precise owner internal name, member name, JVM
 * descriptor, and access flags observed on the exact Cubism 5.2.03, 5.3.02, and 5.3.03 host
 * artifacts. Public draft contracts from {@code compatibility/cubism/mapping-packs/draft} are
 * admitted only through this contract; no bare-string reflective calls are allowed outside it.</p>
 */
public final class EditorObjectHierarchyEditSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.object-hierarchy.edit";
    public static final String RENAME_CAPABILITY_ID = "cubism.editor-model.object-hierarchy.rename";
    public static final String ART_MESH_CREATE_CAPABILITY_ID =
        "cubism.editor-model.object-hierarchy.art-mesh.create";

    /** Aliases required by every mutation (create/delete/reparent/rename). */
    private static final Set<String> COMMON_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.app-controller.complete-pack",
        "cubism.editor-model.modeling-document.model-source",
        "cubism.editor-model.modeling-document.edit-mode",
        "cubism.editor-model.modeling-document.mark-dirty",
        "cubism.editor-model.edit-mode.begin",
        "cubism.editor-model.edit-mode.end",
        "cubism.editor-model.undo.add",
        "cubism.editor-model.undo.add-listener",
        "cubism.editor-model.undo-listener.class",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.model-source.update-visible-lock-hierarchy",
        "cubism.editor-model.parameter-controllable-source.handler",
        "cubism.editor-model.parameter-controllable-handler.class",
        "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.id.value",
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.update-deformer-palette",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    private static final Set<String> CREATE_ALIASES = Set.of(
        "cubism.editor-model.model-source.all-objects",
        "cubism.editor-model.model-source.handler",
        "cubism.editor-model.model-handler.add-source-undo",
        "cubism.editor-model.part-source.create",
        "cubism.editor-model.warp-source.create",
        "cubism.editor-model.rotation-source.create",
        "cubism.editor-model.part-id.create",
        "cubism.editor-model.deformer-id.create",
        "cubism.editor-model.part-source.set-id",
        "cubism.editor-model.deformer-source.set-id",
        "cubism.editor-model.parameter-controllable-source.set-local-name",
        "cubism.editor-model.parameter-controllable-source.set-keyform-grid-source",
        "cubism.editor-model.part-form.create",
        "cubism.editor-model.warp-form.create",
        "cubism.editor-model.rotation-form.create",
        "cubism.editor-model.form-guid.create",
        "cubism.editor-model.form.set-guid",
        "cubism.editor-model.part-source.keyforms",
        "cubism.editor-model.warp-source.keyforms",
        "cubism.editor-model.rotation-source.keyforms",
        "cubism.editor-model.c-array-list.add",
        "cubism.editor-model.keyform-grid-source.create",
        "cubism.editor-model.keyform-grid-source.import-cubism21",
        "cubism.editor-model.coord-type.canvas",
        "cubism.editor-model.warp-source.set-col",
        "cubism.editor-model.warp-source.set-row",
        "cubism.editor-model.warp-source.set-quad-transform",
        "cubism.editor-model.warp-form.set-positions",
        "cubism.editor-model.rotation-form.set-angle",
        "cubism.editor-model.rotation-form.set-origin-x",
        "cubism.editor-model.rotation-form.set-origin-y",
        "cubism.editor-model.rotation-form.set-scale",
        "cubism.editor-model.rotation-form.set-reflect-x",
        "cubism.editor-model.rotation-form.set-reflect-y",
        "cubism.editor-model.part-source.add-child",
        "cubism.editor-model.part-source.class",
        "cubism.editor-model.warp-source.class",
        "cubism.editor-model.rotation-source.class",
        "cubism.editor-model.part-source.id",
        "cubism.editor-model.part-id.value",
        "cubism.editor-model.model-source.parts",
        "cubism.editor-model.model.parts",
        "cubism.editor-model.part.class",
        "cubism.editor-model.part.source",
        "cubism.editor-model.model-source.all-deformers",
        "cubism.editor-model.model.all-deformers",
        "cubism.editor-model.deformer.source",
        "cubism.editor-model.warp.class",
        "cubism.editor-model.rotation.class"
    );

    private static final Set<String> DELETE_ALIASES = Set.of(
        "cubism.editor-model.app-controller.update-manager",
        "cubism.editor-model.update-manager.class",
        "cubism.editor-model.update-manager.set-selection",
        "cubism.editor-model.app-controller.command-delete",
        "cubism.editor-model.parameter-controllable-source.guid"
    );

    private static final Set<String> REPARENT_ALIASES = Set.of(
        "cubism.editor-model.part-source.add-child",
        "cubism.editor-model.parameter-controllable-source.set-target-deformer-guid",
        "cubism.editor-model.parameter-controllable-source.all-parent-deformers",
        "cubism.editor-model.part-source.parent"
    );

    private static final Set<String> READ_BACK_ALIASES = Set.of(
        "cubism.editor-model.model-source.parts",
        "cubism.editor-model.model.parts",
        "cubism.editor-model.part.class",
        "cubism.editor-model.part.source",
        "cubism.editor-model.part-source.class",
        "cubism.editor-model.part-source.id",
        "cubism.editor-model.part-id.value",
        "cubism.editor-model.model-source.all-deformers",
        "cubism.editor-model.model.all-deformers",
        "cubism.editor-model.deformer.source",
        "cubism.editor-model.warp-source.class",
        "cubism.editor-model.warp.class",
        "cubism.editor-model.rotation-source.class",
        "cubism.editor-model.rotation.class",
        "cubism.editor-model.model-source.all-art-meshes",
        "cubism.editor-model.model.all-art-meshes",
        "cubism.editor-model.art-mesh.source",
        "cubism.editor-model.art-mesh-source.class",
        "cubism.editor-model.art-mesh.class",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.parameter-controllable-source.local-name",
        "cubism.editor-model.id.value",
        "cubism.editor-model.part-source.parent",
        "cubism.editor-model.parameter-controllable-source.target-deformer-source"
    );

    public static final Set<String> REQUIRED_ALIASES = unionAll(
        COMMON_ALIASES,
        CREATE_ALIASES,
        DELETE_ALIASES,
        REPARENT_ALIASES,
        READ_BACK_ALIASES
    );

    /** Rename-only capability (set-local-name on the shared base class). */
    public static final Set<String> RENAME_REQUIRED_ALIASES = unionAll(
        COMMON_ALIASES,
        READ_BACK_ALIASES,
        Set.of("cubism.editor-model.parameter-controllable-source.set-local-name")
    );

    /** Additional exact members needed to create a complete ArtMesh source and default form. */
    public static final Set<String> ART_MESH_CREATE_REQUIRED_ALIASES = unionAll(
        COMMON_ALIASES,
        READ_BACK_ALIASES,
        Set.of(
            "cubism.editor-model.model-source.all-objects",
            "cubism.editor-model.model-source.handler",
            "cubism.editor-model.model-handler.add-source-undo",
            "cubism.editor-model.art-mesh-source.create",
            "cubism.editor-model.drawable-id.create",
            "cubism.editor-model.drawable-source.set-id",
            "cubism.editor-model.art-mesh-source.set-positions",
            "cubism.editor-model.art-mesh-source.set-uvs",
            "cubism.editor-model.art-mesh-source.set-indices",
            "cubism.editor-model.art-mesh-source.keyforms",
            "cubism.editor-model.art-mesh-form.create",
            "cubism.editor-model.art-mesh-form.set-positions",
            "cubism.editor-model.form-guid.create",
            "cubism.editor-model.form.set-guid",
            "cubism.editor-model.c-array-list.add",
            "cubism.editor-model.keyform-grid-source.create",
            "cubism.editor-model.keyform-grid-source.import-cubism21",
            "cubism.editor-model.parameter-controllable-source.set-keyform-grid-source",
            "cubism.editor-model.coord-type.canvas",
            "cubism.editor-model.part-source.add-child",
            "cubism.editor-model.parameter-controllable-source.set-local-name"
        )
    );

    private static Set<String> unionAll(final Set<String>... sets) {
        final HashSet<String> values = new HashSet<>();
        for (Set<String> set : sets) {
            values.addAll(set);
        }
        return Set.copyOf(values);
    }

    private EditorObjectHierarchyEditSelectorContract() {
    }
}
