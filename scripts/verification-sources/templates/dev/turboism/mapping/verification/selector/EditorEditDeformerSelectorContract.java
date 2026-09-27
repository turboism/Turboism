package dev.turboism.mapping.verification.selector;

import java.util.HashSet;
import java.util.Set;

/**
 * Exact additive selector contract for the deformer family of the external-application editing
 * surface: {@code GetDeformerStructure}, {@code AddRotationDeformer}, {@code AddWarpDeformer},
 * {@code EditRotationDeformer}, and {@code EditWarpDeformer}.
 *
 * <p>Members are drawn from the verified object-hierarchy, object-write, and deformer-inspector
 * surfaces of the exact Cubism 5.2.03, 5.3.02, and 5.3.03 host artifacts. The capability ids are
 * bound on all three reviewed records; any member a record drops still fails the affected row
 * closed.</p>
 *
 * <p>Open fields stay fail-closed: {@code AddWarpDeformer}'s {@code BezierDivH}/{@code
 * BezierDivV}, {@code ConsiderChildKeyforms}, and {@code SnapCenter}, and {@code
 * EditWarpDeformer}'s {@code BezierDivH}/{@code BezierDivV} have no verified member in any
 * supported record, so no alias is declared for them here — requests carrying them must be
 * rejected by the runtime until verification lands.</p>
 */
public final class EditorEditDeformerSelectorContract {

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-editor-model.json:adapterSliceId}";

    public static final String GET_DEFORMER_STRUCTURE_CAPABILITY_ID =
        "cubism.editor-model.edit.deformer.get-deformer-structure";
    public static final String ADD_ROTATION_DEFORMER_CAPABILITY_ID =
        "cubism.editor-model.edit.deformer.add-rotation-deformer";
    public static final String ADD_WARP_DEFORMER_CAPABILITY_ID =
        "cubism.editor-model.edit.deformer.add-warp-deformer";
    public static final String EDIT_ROTATION_DEFORMER_CAPABILITY_ID =
        "cubism.editor-model.edit.deformer.edit-rotation-deformer";
    public static final String EDIT_WARP_DEFORMER_CAPABILITY_ID =
        "cubism.editor-model.edit.deformer.edit-warp-deformer";

    /**
     * Members that create a deformer source with its id, name, keyform grid, and default form,
     * and register the creation on the model undo handler.
     */
    private static final Set<String> DEFORMER_CREATE_ALIASES = Set.of(
        "cubism.editor-model.model-source.handler",
        "cubism.editor-model.model-handler.class",
        "cubism.editor-model.model-handler.add-source-undo",
        "cubism.editor-model.model-source.all-objects",
        "cubism.editor-model.deformer-id.create",
        "cubism.editor-model.deformer-source.class",
        "cubism.editor-model.deformer-source.set-id",
        "cubism.editor-model.deformer-source.guid",
        "cubism.editor-model.parameter-controllable-source.set-local-name",
        "cubism.editor-model.parameter-controllable-source.set-keyform-grid-source",
        "cubism.editor-model.form-guid.create",
        "cubism.editor-model.form.set-guid",
        "cubism.editor-model.c-array-list.add",
        "cubism.editor-model.keyform-grid-source.create",
        "cubism.editor-model.keyform-grid-source.import-cubism21",
        "cubism.editor-model.coord-type.canvas",
        "cubism.editor-model.id.value",
        "cubism.editor-model.guid.value"
    );

    /**
     * Members that attach the new deformer under a part or another deformer, covering both
     * {@code DeformerAttachMode} values.
     */
    private static final Set<String> DEFORMER_ATTACH_ALIASES = Set.of(
        "cubism.editor-model.part-source.handler",
        "cubism.editor-model.part-handler.class",
        "cubism.editor-model.part-handler.add-part-child",
        "cubism.editor-model.part-source.add-child",
        "cubism.editor-model.part-source.class",
        "cubism.editor-model.part-source.id",
        "cubism.editor-model.part-id.value",
        "cubism.editor-model.model-source.parts",
        "cubism.editor-model.model.parts",
        "cubism.editor-model.part.class",
        "cubism.editor-model.part.source",
        "cubism.editor-model.parameter-controllable-handler.change-target-deformer",
        "cubism.editor-model.parameter-controllable-handler.change-target-deformer-guid",
        "cubism.editor-model.parameter-controllable-source.set-target-deformer-guid",
        "cubism.editor-model.parameter-controllable-source.target-deformer-source",
        "cubism.editor-model.parameter-controllable-source.handler",
        "cubism.editor-model.parameter-controllable-handler.class",
        "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
        "cubism.editor-model.model-source.all-deformers",
        "cubism.editor-model.model.all-deformers",
        "cubism.editor-model.deformer.source"
    );

    /** Members that edit a deformer's identity, name, parent, opacity, colors, and label. */
    private static final Set<String> DEFORMER_EDIT_BASE_ALIASES = Set.of(
        "cubism.editor-model.deformer-source.class",
        "cubism.editor-model.deformer-source.guid",
        "cubism.editor-model.deformer-source.set-id",
        "cubism.editor-model.deformer-source.set-local-name",
        "cubism.editor-model.deformer-guid.companion",
        "cubism.editor-model.deformer-guid.root",
        "cubism.editor-model.deformer.current-keyform",
        "cubism.editor-model.deformer-form.opacity",
        "cubism.editor-model.deformer-form.set-opacity",
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
        "cubism.editor-model.parameter-controllable-source.label-color",
        "cubism.editor-model.label-color.class",
        "cubism.editor-model.label-color.label-type",
        "cubism.editor-model.label-color.customized-color",
        "cubism.editor-model.label-color.set-label-type",
        "cubism.editor-model.label-color.set-color",
        "cubism.editor-model.color.class",
        "cubism.editor-model.color.create",
        "cubism.editor-model.color.red",
        "cubism.editor-model.color.green",
        "cubism.editor-model.color.blue",
        "cubism.editor-model.color.alpha",
        "cubism.editor-model.complete-pack.update-deformer-palette"
    );

    /** {@code GetDeformerStructure}: the deformer tree read surface. */
    public static final Set<String> GET_DEFORMER_STRUCTURE_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.SESSION_NAVIGATION_ALIASES,
        EditorObjectReadSelectorContract.REQUIRED_ALIASES
    );

    /** {@code AddRotationDeformer}: rotation-source creation plus attachment. */
    public static final Set<String> ADD_ROTATION_DEFORMER_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        DEFORMER_CREATE_ALIASES,
        DEFORMER_ATTACH_ALIASES,
        Set.of(
            "cubism.editor-model.rotation-source.create",
            "cubism.editor-model.rotation-source.class",
            "cubism.editor-model.rotation-source.keyforms",
            "cubism.editor-model.rotation.class",
            "cubism.editor-model.rotation-form.create",
            "cubism.editor-model.rotation-form.set-angle",
            "cubism.editor-model.rotation-form.set-origin-x",
            "cubism.editor-model.rotation-form.set-origin-y",
            "cubism.editor-model.rotation-form.set-scale",
            "cubism.editor-model.rotation-form.set-reflect-x",
            "cubism.editor-model.rotation-form.set-reflect-y",
            "cubism.editor-model.parameter-controllable-source.id"
        )
    );

    /**
     * {@code AddWarpDeformer}: warp-source creation plus attachment and lattice setup. The
     * {@code BezierDiv}, {@code ConsiderChildKeyforms}, and {@code SnapCenter} fields have no
     * verified member and stay rejected until host validation lands.
     */
    public static final Set<String> ADD_WARP_DEFORMER_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        DEFORMER_CREATE_ALIASES,
        DEFORMER_ATTACH_ALIASES,
        Set.of(
            "cubism.editor-model.warp-source.create",
            "cubism.editor-model.warp-source.class",
            "cubism.editor-model.warp-source.keyforms",
            "cubism.editor-model.warp.class",
            "cubism.editor-model.warp-form.create",
            "cubism.editor-model.warp-form.set-positions",
            "cubism.editor-model.warp-source.set-col",
            "cubism.editor-model.warp-source.set-row",
            "cubism.editor-model.warp-source.set-quad-transform",
            "cubism.editor-model.parameter-controllable-source.id"
        )
    );

    /** {@code EditRotationDeformer}: full rotation-deformer edit surface. */
    public static final Set<String> EDIT_ROTATION_DEFORMER_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        EditorObjectWriteSelectorContract.ROTATION_REQUIRED_ALIASES,
        DEFORMER_EDIT_BASE_ALIASES,
        DEFORMER_ATTACH_ALIASES,
        Set.of(
            "cubism.editor-model.rotation-source.class",
            "cubism.editor-model.rotation.class",
            "cubism.editor-model.rotation-source.base-angle",
            "cubism.editor-model.rotation-source.set-base-angle",
            "cubism.editor-model.rotation-form.angle",
            "cubism.editor-model.rotation-form.origin-x",
            "cubism.editor-model.rotation-form.origin-y",
            "cubism.editor-model.rotation-form.scale",
            "cubism.editor-model.parameter-controllable-source.id",
            "cubism.editor-model.parameter-controllable-source.local-name"
        )
    );

    /** {@code EditWarpDeformer}: warp lattice and form edits; BezierDiv fields stay open. */
    public static final Set<String> EDIT_WARP_DEFORMER_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        EditorObjectWriteSelectorContract.WARP_REQUIRED_ALIASES,
        DEFORMER_EDIT_BASE_ALIASES,
        DEFORMER_ATTACH_ALIASES,
        Set.of(
            "cubism.editor-model.warp-source.class",
            "cubism.editor-model.warp.class",
            "cubism.editor-model.warp-source.col",
            "cubism.editor-model.warp-source.row",
            "cubism.editor-model.warp-source.quad-transform",
            "cubism.editor-model.warp-form.positions",
            "cubism.editor-model.parameter-controllable-source.id",
            "cubism.editor-model.parameter-controllable-source.local-name"
        )
    );

    private static Set<String> unionAll(final Set<String>... sets) {
        final HashSet<String> values = new HashSet<>();
        for (final Set<String> set : sets) {
            values.addAll(set);
        }
        return Set.copyOf(values);
    }

    private EditorEditDeformerSelectorContract() {
    }
}
