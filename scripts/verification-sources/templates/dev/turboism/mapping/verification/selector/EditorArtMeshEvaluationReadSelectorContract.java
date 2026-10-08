package dev.turboism.mapping.verification.selector;

import java.util.Set;

/** Pure last-calculated-form observation; no evaluator, repaint or stored/current-form selector. */
public final class EditorArtMeshEvaluationReadSelectorContract {
    public static final String ADAPTER_SLICE_ID = EditorObjectReadSelectorContract.ADAPTER_SLICE_ID;
    public static final String CAPABILITY_ID = "cubism.editor-model.art-mesh.evaluated-read";
    public static final Set<String> NEW_ALIASES = Set.of(
        "cubism.editor-model.art-mesh.calculated-form",
        "cubism.editor-model.art-mesh.interpolated-form",
        "cubism.editor-model.drawable-form.coord-type",
        "cubism.editor-model.coord-type.label",
        "cubism.editor-model.model.last-updated-parameter-set",
        "cubism.editor-model.model.last-updated-time",
        "cubism.editor-model.parameter-set.update-version"
    );
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.art-mesh.calculated-form",
        "cubism.editor-model.art-mesh.interpolated-form",
        "cubism.editor-model.drawable-form.coord-type",
        "cubism.editor-model.coord-type.label",
        "cubism.editor-model.model.last-updated-parameter-set",
        "cubism.editor-model.model.last-updated-time",
        "cubism.editor-model.parameter-set.update-version",
        "cubism.editor-model.art-mesh-source.guid",
        "cubism.editor-model.art-mesh-source.positions",
        "cubism.editor-model.art-mesh-form.positions",
        "cubism.editor-model.drawable-form.draw-order",
        "cubism.editor-model.model.parameter-set",
        "cubism.editor-model.parameter-set.parameters",
        "cubism.editor-model.parameter.source",
        "cubism.editor-model.parameter-source.guid",
        "cubism.editor-model.parameter.id",
        "cubism.editor-model.parameter.value",
        "cubism.editor-model.guid.value",
        "cubism.editor-model.id.value"
    );

    private EditorArtMeshEvaluationReadSelectorContract() { }
}
