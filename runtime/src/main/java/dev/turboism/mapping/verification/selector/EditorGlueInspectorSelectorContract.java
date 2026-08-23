package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact additive selector contract for the Editor Inspector Glue family
 * (name / id / intensity / drawableA / drawableB writes). Evidence: 5302-src
 * and 52-src {@code Glue_wrapperForInspector} (setName/setId/prepareUndo and the
 * {@code IntensityEditor} inner class), model-level
 * {@code CGlueSource.setTargetArtMeshA_guid/setTargetArtMeshB_guid} and
 * {@code CGlueForm.setIntensity}; bytecode-verified against both host JARs.
 */
public final class EditorGlueInspectorSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.glue-inspector.write";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.app-controller.complete-pack",
        "cubism.editor-model.modeling-document.edit-mode",
        "cubism.editor-model.modeling-document.mark-dirty",
        "cubism.editor-model.edit-mode.begin",
        "cubism.editor-model.edit-mode.end",
        "cubism.editor-model.undo.add",
        "cubism.editor-model.undo.add-listener",
        "cubism.editor-model.undo-listener.class",
        "cubism.editor-model.model-source.parts",
        "cubism.editor-model.model-source.all-deformers",
        "cubism.editor-model.model-source.all-glues",
        "cubism.editor-model.model-source.all-art-meshes",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.model-source.verify",
        "cubism.editor-model.glue-source.class",
        "cubism.editor-model.glue-source.set-local-name",
        "cubism.editor-model.glue-source.local-name",
        "cubism.editor-model.glue-source.set-id",
        "cubism.editor-model.glue-source.set-target-art-mesh-a",
        "cubism.editor-model.glue-source.set-target-art-mesh-b",
        "cubism.editor-model.glue-id.create",
        "cubism.editor-model.glue.current-keyform",
        "cubism.editor-model.glue-form.class",
        "cubism.editor-model.glue-form.intensity",
        "cubism.editor-model.glue-form.set-intensity",
        "cubism.editor-model.model.get-object",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.id.value",
        "cubism.editor-model.parameter-controllable-source.handler",
        "cubism.editor-model.parameter-controllable-handler.class",
        "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
        "cubism.editor-model.art-mesh-source.class",
        "cubism.editor-model.art-mesh-source.guid",
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.update-deformer-palette",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    private EditorGlueInspectorSelectorContract() {
    }
}
