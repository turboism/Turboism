package dev.turboism.mapping.verification.selector;

import java.util.Set;

/** Existing 5.3.02 read selectors needed for one detached stored-authoring observation. */
public final class EditorArtMeshAuthoringReadSelectorContract {
    public static final String ADAPTER_SLICE_ID = EditorObjectReadSelectorContract.ADAPTER_SLICE_ID;
    public static final Set<String> CAPABILITY_IDS = Set.of(
        "cubism.editor-model.objects.read",
        "cubism.editor-model.parameter-bindings.read",
        "cubism.editor-model.morph-target.read",
        "cubism.editor-model.read",
        "cubism.editor-history.semantic-read"
    );
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.art-mesh-source.guid",
        "cubism.editor-model.guid.value",
        "cubism.editor-model.id.value",
        "cubism.editor-model.form-guid.value",
        "cubism.editor-model.art-mesh-source.positions",
        "cubism.editor-model.art-mesh-source.uvs",
        "cubism.editor-model.art-mesh-source.indices",
        "cubism.editor-model.art-mesh-source.keyforms",
        "cubism.editor-model.art-mesh-form.positions",
        "cubism.editor-model.drawable-form.draw-order",
        "cubism.editor-model.parameter-controllable.keyform-grid",
        "cubism.editor-model.keyform-grid.bindings",
        "cubism.editor-model.keyform-binding.keys",
        "cubism.editor-model.keyform-binding.parameter-guid",
        "cubism.editor-model.keyform-binding.parameter-id",
        "cubism.editor-model.parameter-source.minimum",
        "cubism.editor-model.parameter-source.default",
        "cubism.editor-model.parameter-source.maximum",
        "cubism.editor-history.semantic.keyform-binding.parameter",
        "cubism.editor-history.semantic.form.guid",
        "cubism.editor-history.semantic.keyform-grid.forms-for-guid",
        "cubism.editor-history.semantic.keyform-on-grid.access-key",
        "cubism.editor-history.semantic.keyform-access-key.coordinates",
        "cubism.editor-history.semantic.key-on-parameter.binding",
        "cubism.editor-history.semantic.key-on-parameter.value",
        "cubism.editor-model.parameter-controllable.morph-target-set",
        "cubism.editor-model.morph-target-set.morph-targets",
        "cubism.editor-model.morph-target.key-value",
        "cubism.editor-model.morph-target.keyform-guid",
        "cubism.editor-model.morph-target.parameter-guid"
    );

    private EditorArtMeshAuthoringReadSelectorContract() { }
}
