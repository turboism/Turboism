package dev.turboism.mapping.verification.selector;

import java.util.Set;

/** Exact 5.3.02 stored Warp selectors; no calculated/current-form selectors. */
public final class EditorWarpAuthoringSelectorContract {
    public static final String ADAPTER_SLICE_ID = EditorObjectReadSelectorContract.ADAPTER_SLICE_ID;
    public static final Set<String> CAPABILITY_IDS = Set.of("cubism.editor-model.objects.read",
        "cubism.editor-model.parameter-bindings.read", "cubism.editor-model.morph-target.read",
        "cubism.editor-model.read", "cubism.editor-history.semantic-read");
    public static final Set<String> READ_ALIASES = Set.of(
        "cubism.editor-model.deformer-source.guid", "cubism.editor-model.guid.value",
        "cubism.editor-model.id.value", "cubism.editor-model.form-guid.value",
        "cubism.editor-model.warp-source.row", "cubism.editor-model.warp-source.col",
        "cubism.editor-model.warp-source.quad-transform", "cubism.editor-model.warp-source.keyforms",
        "cubism.editor-model.warp-form.positions", "cubism.editor-model.parameter-controllable.keyform-grid",
        "cubism.editor-model.keyform-grid.bindings", "cubism.editor-model.keyform-binding.keys",
        "cubism.editor-model.keyform-binding.parameter-guid", "cubism.editor-model.keyform-binding.parameter-id",
        "cubism.editor-model.parameter-source.minimum", "cubism.editor-model.parameter-source.default",
        "cubism.editor-model.parameter-source.maximum", "cubism.editor-history.semantic.keyform-binding.parameter",
        "cubism.editor-history.semantic.form.guid", "cubism.editor-history.semantic.keyform-grid.forms-for-guid",
        "cubism.editor-history.semantic.keyform-on-grid.access-key", "cubism.editor-history.semantic.keyform-access-key.coordinates",
        "cubism.editor-history.semantic.key-on-parameter.binding", "cubism.editor-history.semantic.key-on-parameter.value",
        "cubism.editor-model.parameter-controllable.morph-target-set", "cubism.editor-model.morph-target-set.morph-targets");
    public static final String WRITE_CAPABILITY_ID = EditorObjectWriteSelectorContract.WARP_CAPABILITY_ID;
    public static final Set<String> WRITE_ALIASES = Set.of(
        "cubism.editor-model.warp-form.set-positions", "cubism.editor-model.parameter-controllable-source.handler",
        "cubism.editor-model.parameter-controllable-handler.create-undo-for-keyform-edit",
        "cubism.editor-model.undo.add", "cubism.editor-model.undo.add-listener", "cubism.editor-model.undo-listener.class",
        "cubism.editor-model.model-source.update-instances", "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.complete-pack", "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.update-deformer-palette", "cubism.editor-model.complete-pack.repaint-canvas");
    private EditorWarpAuthoringSelectorContract() { }
}
