package dev.turboism.mapping.verification.selector;

import java.util.Set;

/** Additive exact 5.3.02 ArtMesh interpolation authoring contract. */
public final class EditorParameterInterpolationSelectorContract {
    public static final String CAPABILITY_ID = "cubism.editor-model.art-mesh.parameter-interpolation.write";
    public static final String ADAPTER_SLICE_ID = EditorObjectReadSelectorContract.ADAPTER_SLICE_ID;
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.keyform-binding.extended-type",
        "cubism.editor-model.keyform-binding.set-extended-type",
        "cubism.editor-model.keyform-binding.insert-count",
        "cubism.editor-model.keyform-binding.set-insert-count",
        "cubism.editor-model.keyform-binding.extended-scale",
        "cubism.editor-model.keyform-binding.legacy-type",
        "cubism.editor-model.keyform-binding.illegal-extended",
        "cubism.editor-model.keyform-binding.set-extended-scale",
        "cubism.editor-model.extended-type.linear",
        "cubism.editor-model.extended-type.ellipse",
        "cubism.editor-model.extended-type.sns-curve",
        "cubism.editor-model.extended-interpolation.class",
        "cubism.editor-model.extended-interpolation.create",
        "cubism.editor-model.extended-interpolation.owner",
        "cubism.editor-model.extended-interpolation.enabled",
        "cubism.editor-model.extended-interpolation.grid",
        "cubism.editor-model.extended-interpolation.forms",
        "cubism.editor-model.extended-interpolation.setup-forms",
        "cubism.editor-model.extended-interpolation.update-forms",
        "cubism.editor-model.parameter-controllable-source.extensions",
        "cubism.editor-model.keyform-grid.find-binding",
        "cubism.editor-model.keyform-grid.bindings",
        "cubism.editor-model.keyform-grid.rows",
        "cubism.editor-model.keyform-grid-row.guid",
        "cubism.editor-history.semantic.keyform-on-grid.access-key",
        "cubism.editor-history.semantic.keyform-access-key.coordinates",
        "cubism.editor-history.semantic.key-on-parameter.binding",
        "cubism.editor-history.semantic.key-on-parameter.value",
        "cubism.editor-model.drawable-form.opacity",
        "cubism.editor-model.undo.execute-undo",
        "cubism.editor-model.undo-add-remove-extension.create",
        "cubism.editor-model.group-undo.create",
        "cubism.texture-atlas.group-undo.add",
        "cubism.texture-atlas.undo.force-redo"
    );
    private EditorParameterInterpolationSelectorContract() { }
}
