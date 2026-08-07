package dev.turboism.mapping.verification;

import java.util.Set;

/**
 * Exact additive selector contract for the Editor Inspector Deformer family
 * (name / id / targetDeformer / multiplyColor / screenColor writes). Evidence:
 * 5302-src and 52-src {@code Deformer_wrapperForInspector},
 * {@code ParameterControllable_wrapperForInspector$DeformerSelectorUiFactory}
 * and {@code properties/CMultiplyColorSelectable|CScreenColorSelectable};
 * bytecode-verified against both host JARs.
 */
public final class EditorDeformerInspectorSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.deformer-inspector.write";
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
        "cubism.editor-model.model-source.all-art-meshes",
        "cubism.editor-model.model-source.all-glues",
        "cubism.editor-model.model.all-deformers",
        "cubism.editor-model.deformer-source.class",
        "cubism.editor-model.deformer-source.set-local-name",
        "cubism.editor-model.deformer-source.set-id",
        "cubism.editor-model.deformer-source.guid",
        "cubism.editor-model.deformer-guid.companion",
        "cubism.editor-model.deformer-guid.root",
        "cubism.editor-model.deformer-id.create",
        "cubism.editor-model.deformer.current-keyform",
        "cubism.editor-model.deformer-form.multiply-color",
        "cubism.editor-model.deformer-form.screen-color",
        "cubism.editor-model.float-color.red",
        "cubism.editor-model.float-color.green",
        "cubism.editor-model.float-color.blue",
        "cubism.editor-model.float-color.alpha",
        "cubism.editor-model.float-color.set-red",
        "cubism.editor-model.float-color.set-green",
        "cubism.editor-model.float-color.set-blue",
        "cubism.editor-model.float-color.set-alpha",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.id.value",
        "cubism.editor-model.parameter-controllable-source.handler",
        "cubism.editor-model.parameter-controllable-source.target-deformer-source",
        "cubism.editor-model.parameter-controllable-handler.class",
        "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
        "cubism.editor-model.parameter-controllable-handler.change-target-deformer",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.model-source.verify",
        "cubism.editor-model.model-source.target-version",
        "cubism.editor-model.target-version.number",
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.update-deformer-palette",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    private EditorDeformerInspectorSelectorContract() {
    }
}
