package dev.turboism.mapping.verification;

import java.util.Set;

/** Exact additive selector contract for Editor ArtMesh, Warp, and Rotation reads. */
public final class EditorObjectReadSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.objects.read";

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.modeling-document.model-source",
        "cubism.editor-model.model-source.guid",
        "cubism.editor-model.model-source.current-instance",
        "cubism.editor-model.guid.value",
        "cubism.editor-model.id.value",
        "cubism.editor-model.part-id.value",
        "cubism.editor-model.model-source.parts",
        "cubism.editor-model.part-source.class",
        "cubism.editor-model.part-source.id",
        "cubism.editor-model.part-source.parent",
        "cubism.editor-model.model.parameter-set",
        "cubism.editor-model.parameter-set.parameters",
        "cubism.editor-model.parameter.class",
        "cubism.editor-model.parameter.id",
        "cubism.editor-model.model-source.all-art-meshes",
        "cubism.editor-model.model.all-art-meshes",
        "cubism.editor-model.art-mesh-source.class",
        "cubism.editor-model.art-mesh.class",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.parameter-controllable-source.local-name",
        "cubism.editor-model.parameter-controllable-source.visible",
        "cubism.editor-model.parameter-controllable-source.locked",
        "cubism.editor-model.parameter-controllable-source.visible-in-hierarchy",
        "cubism.editor-model.parameter-controllable-source.locked-in-hierarchy",
        "cubism.editor-model.parameter-controllable-source.target-deformer-source",
        "cubism.editor-model.art-mesh.source",
        "cubism.editor-model.art-mesh.current-keyform",
        "cubism.editor-model.drawable-form.opacity",
        "cubism.editor-model.drawable-form.draw-order",
        "cubism.editor-model.art-mesh-form.positions",
        "cubism.editor-model.art-mesh-source.guid",
        "cubism.editor-model.art-mesh-source.clip-guid-list",
        "cubism.editor-model.art-mesh-source.positions",
        "cubism.editor-model.art-mesh-source.uvs",
        "cubism.editor-model.art-mesh-source.indices",
        "cubism.editor-model.art-mesh-source.culling",
        "cubism.editor-model.art-mesh-source.user-data",
        "cubism.editor-model.art-mesh-source.inverted-mask",
        "cubism.editor-model.model-source.all-deformers",
        "cubism.editor-model.model.all-deformers",
        "cubism.editor-model.warp-source.class",
        "cubism.editor-model.warp.class",
        "cubism.editor-model.rotation-source.class",
        "cubism.editor-model.rotation.class",
        "cubism.editor-model.deformer.source",
        "cubism.editor-model.deformer.current-keyform",
        "cubism.editor-model.deformer-form.opacity",
        "cubism.editor-model.warp-source.row",
        "cubism.editor-model.warp-source.col",
        "cubism.editor-model.warp-source.quad-transform",
        "cubism.editor-model.warp-form.positions",
        "cubism.editor-model.rotation-source.base-angle",
        "cubism.editor-model.rotation-form.angle",
        "cubism.editor-model.rotation-form.origin-x",
        "cubism.editor-model.rotation-form.origin-y",
        "cubism.editor-model.rotation-form.scale",
        "cubism.editor-model.rotation-form.reflect-x",
        "cubism.editor-model.rotation-form.reflect-y",
        "cubism.editor-model.model-source.all-glues",
        "cubism.editor-model.glue-source.class",
        "cubism.editor-model.glue-source.target-art-mesh-a",
        "cubism.editor-model.glue-source.target-art-mesh-b"
    );

    private EditorObjectReadSelectorContract() {
    }
}
