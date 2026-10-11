package dev.turboism.mapping.verification.selector;

import java.util.HashSet;
import java.util.Set;

/** Exact 5.3.02 member snapshots and absolute, non-basic ArtMesh morph authoring. */
public final class EditorArtMeshMorphSelectorContract {
    public static final String ADAPTER_SLICE_ID = EditorObjectReadSelectorContract.ADAPTER_SLICE_ID;
    public static final String CAPABILITY_ID = "cubism.editor-model.art-mesh.morph-authoring";
    public static final Set<String> NEW_ALIASES = Set.of(
        "cubism.editor-model.morph-authoring-utils.instance",
        "cubism.editor-model.morph-authoring-utils.create-key",
        "cubism.editor-model.morph-target-set.create-undo",
        "cubism.editor-model.morph-target-set.can-add-key",
        "cubism.editor-model.morph-target-set.owner",
        "cubism.editor-model.morph-target-set.constraints",
        "cubism.editor-model.morph-constraints.entries",
        "cubism.editor-model.morph-target.owner",
        "cubism.editor-model.parameter-source.snap-epsilon",
        "cubism.editor-model.native-list.immutable",
        "cubism.editor-model.list-undo.create",
        "cubism.editor-model.group-undo.create",
        "cubism.editor-model.undo.execute-undo",
        "cubism.editor-model.parameter-controllable-source.extended-morph-set",
        "cubism.editor-model.extended-interpolation.class",
        "cubism.editor-model.form.name",
        "cubism.editor-model.form.notes"
    );
    public static final Set<String> REQUIRED_ALIASES = required();
    /** Only the draw-order setter needs the stored-order write member; reads/positions keep REQUIRED_ALIASES. */
    public static final Set<String> DRAW_ORDER_REQUIRED_ALIASES = drawOrderRequired();

    private static Set<String> required() {
        final var aliases = new HashSet<>(EditorArtMeshAuthoringReadSelectorContract.REQUIRED_ALIASES);
        aliases.addAll(NEW_ALIASES);
        aliases.addAll(Set.of(
            "cubism.editor-model.model.parameter-set",
            "cubism.editor-model.parameter-set.parameters",
            "cubism.editor-model.parameter.source",
            "cubism.editor-model.parameter.value",
            "cubism.editor-model.parameter-source.guid",
            "cubism.editor-model.parameter-source.id",
            "cubism.editor-model.parameter-source.name",
            "cubism.editor-model.parameter-source.model-source",
            "cubism.editor-model.parameter-source.morph-target",
            "cubism.editor-model.parameter-source.repeat",
            "cubism.editor-model.parameter-controllable-source.extensions",
            "cubism.editor-model.parameter-controllable-source.locked-in-hierarchy",
            "cubism.editor-model.parameter-controllable-source.target-deformer-source",
            "cubism.editor-model.model-source.root-part",
            "cubism.editor-model.part-source.children",
            "cubism.editor-model.drawable-form.opacity",
            "cubism.editor-model.drawable-form.multiply-color",
            "cubism.editor-model.drawable-form.screen-color",
            "cubism.editor-model.drawable-form.coord-type",
            "cubism.editor-model.coord-type.label",
            "cubism.editor-model.float-color.red",
            "cubism.editor-model.float-color.green",
            "cubism.editor-model.float-color.blue",
            "cubism.editor-model.float-color.alpha",
            "cubism.editor-history.semantic.art-mesh-form.source",
            "cubism.editor-model.art-mesh-form.set-positions",
            "cubism.editor-model.undo.add",
            "cubism.editor-model.undo.add-listener",
            "cubism.editor-model.undo-listener.class",
            "cubism.editor-model.model-source.update-instances",
            "cubism.editor-model.app-controller.instance",
            "cubism.editor-model.app-controller.complete-pack",
            "cubism.editor-model.complete-pack.update-part-palette",
            "cubism.editor-model.complete-pack.update-deformer-palette",
            "cubism.editor-model.complete-pack.repaint-canvas"
        ));
        return Set.copyOf(aliases);
    }

    private static Set<String> drawOrderRequired() {
        final var aliases = new HashSet<>(REQUIRED_ALIASES);
        aliases.add("cubism.editor-model.drawable-form.set-draw-order");
        return Set.copyOf(aliases);
    }

    private EditorArtMeshMorphSelectorContract() { }
}
