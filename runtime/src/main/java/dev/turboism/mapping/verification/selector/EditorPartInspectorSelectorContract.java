package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact additive selector contract for the Cubism 5.3.02 Editor Inspector Part
 * family: {@code id} write, {@code clippingMaskId} write and
 * {@code alphaComposition} write. Evidence: 5302-src
 * {@code Parts_wrapperForInspector}, {@code Parts_wrapperForInspector$clippingMaskId$1},
 * {@code Parts_wrapperForInspector$alphaComposition$1} and
 * {@code properties/AlphaCompostionSelectable}; bytecode-verified against the
 * 5.3.02 host JAR. Cubism 5.2 uses {@link EditorPartInspector52SelectorContract}
 * (id write only).
 */
public final class EditorPartInspectorSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.part-inspector.write";
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
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.model-source.verify",
        "cubism.editor-model.model-source.all-deformers",
        "cubism.editor-model.model-source.all-art-meshes",
        "cubism.editor-model.model-source.all-glues",
        "cubism.editor-model.model.parts",
        "cubism.editor-model.part.class",
        "cubism.editor-model.part.source",
        "cubism.editor-model.part.current-keyform",
        "cubism.editor-model.part-source.class",
        "cubism.editor-model.part-source.id",
        "cubism.editor-model.part-source.set-id",
        "cubism.editor-model.id.value",
        "cubism.editor-model.part-source.clip-guid-list",
        "cubism.editor-model.part-source.alpha-composition",
        "cubism.editor-model.part-source.set-alpha-composition",
        "cubism.editor-model.part-id.create",
        "cubism.editor-model.part-id.value",
        "cubism.editor-model.art-mesh-source.class",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.art-mesh-source.guid",
        "cubism.editor-model.alpha-composition.class",
        "cubism.editor-model.alpha-composition.over",
        "cubism.editor-model.alpha-composition.atop",
        "cubism.editor-model.alpha-composition.out",
        "cubism.editor-model.alpha-composition.conjoint",
        "cubism.editor-model.alpha-composition.disjoint",
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    private EditorPartInspectorSelectorContract() {
    }
}
