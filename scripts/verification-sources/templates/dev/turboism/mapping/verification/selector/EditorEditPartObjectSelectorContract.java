package dev.turboism.mapping.verification.selector;

import java.util.HashSet;
import java.util.Set;

/**
 * Exact additive selector contract for the parts/object family of the external-application
 * editing surface: {@code GetPartStructure}, {@code GetObject}, {@code DeleteObject},
 * {@code MoveObjectOnPartsPalette}, {@code AddPart}, {@code EditPart}, {@code EditArtMesh}, and
 * {@code EditGlue}.
 *
 * <p>Members are drawn from the verified palette-structure, object read/write, inspector, and
 * object-hierarchy surfaces of the exact Cubism 5.2.03, 5.3.02, and 5.3.03 host artifacts. The
 * capability ids are bound on all three reviewed records; any member a record drops still fails
 * the affected row closed.</p>
 *
 * <p>Field-level restrictions from the feasibility matrix are expressed as separate sets:
 * members missing from the 5.2.03 record keep the extended set rejected on that version, and
 * members missing from every record stay declared-but-unbound so admission fails closed.</p>
 */
public final class EditorEditPartObjectSelectorContract {

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-editor-model.json:adapterSliceId}";

    public static final String GET_PART_STRUCTURE_CAPABILITY_ID =
        "cubism.editor-model.edit.part-object.get-part-structure";
    public static final String GET_OBJECT_CAPABILITY_ID =
        "cubism.editor-model.edit.part-object.get-object";
    public static final String DELETE_OBJECT_CAPABILITY_ID =
        "cubism.editor-model.edit.part-object.delete-object";
    public static final String MOVE_OBJECT_ON_PARTS_PALETTE_CAPABILITY_ID =
        "cubism.editor-model.edit.part-object.move-object-on-parts-palette";
    public static final String ADD_PART_CAPABILITY_ID =
        "cubism.editor-model.edit.part-object.add-part";
    public static final String EDIT_PART_CAPABILITY_ID =
        "cubism.editor-model.edit.part-object.edit-part";
    public static final String EDIT_ART_MESH_CAPABILITY_ID =
        "cubism.editor-model.edit.part-object.edit-art-mesh";
    public static final String EDIT_GLUE_CAPABILITY_ID =
        "cubism.editor-model.edit.part-object.edit-glue";

    /**
     * Members that resolve an object id to its source for palette operations: object enumeration
     * plus the controllable-source identity pair.
     */
    public static final Set<String> OBJECT_LOOKUP_ALIASES = Set.of(
        "cubism.editor-model.model-source.all-objects",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.parameter-controllable-source.guid",
        "cubism.editor-model.parameter-controllable-source.local-name",
        "cubism.editor-model.id.value",
        "cubism.editor-model.guid.value"
    );

    /**
     * Members that locate a keyform state for the {@code Parameters[]} request condition. Shared
     * by every edit/read operation that accepts keyform conditions; the condition is optional in
     * the official API, so callers that pass an empty condition list do not need this set.
     */
    public static final Set<String> KEYFORM_CONDITION_ALIASES = Set.of(
        "cubism.editor-model.keyform-grid.bindings",
        "cubism.editor-model.keyform-grid.find-binding",
        "cubism.editor-model.keyform-grid.keyforms-on-grid",
        "cubism.editor-model.keyform-binding.class",
        "cubism.editor-model.keyform-binding.parameter-id",
        "cubism.editor-model.keyform-binding.parameter-guid",
        "cubism.editor-model.keyform-binding.keys",
        "cubism.editor-model.keyform-on-grid.form-guid",
        "cubism.editor-model.part.current-keyform",
        "cubism.editor-model.art-mesh.current-keyform",
        "cubism.editor-model.deformer.current-keyform",
        "cubism.editor-model.glue.current-keyform"
    );

    /** Members that read a label-color payload (preset type plus optional custom color). */
    private static final Set<String> LABEL_COLOR_READ_ALIASES = Set.of(
        "cubism.editor-model.parameter-controllable-source.label-color",
        "cubism.editor-model.label-color.class",
        "cubism.editor-model.label-color.label-type",
        "cubism.editor-model.label-color.customized-color",
        "cubism.editor-model.label-color.color",
        "cubism.editor-model.color.class",
        "cubism.editor-model.color.red",
        "cubism.editor-model.color.green",
        "cubism.editor-model.color.blue",
        "cubism.editor-model.color.alpha"
    );

    /** Members that write a label-color payload. */
    private static final Set<String> LABEL_COLOR_WRITE_ALIASES = Set.of(
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
        "cubism.editor-model.color.alpha"
    );

    /** Members that reparent an object under a part or a deformer. */
    private static final Set<String> REPARENT_ALIASES = Set.of(
        "cubism.editor-model.part-source.handler",
        "cubism.editor-model.part-handler.class",
        "cubism.editor-model.part-handler.add-part-child",
        "cubism.editor-model.part-source.parent",
        "cubism.editor-model.part-source.children",
        "cubism.editor-model.parameter-controllable-handler.change-target-deformer",
        "cubism.editor-model.parameter-controllable-handler.change-target-deformer-guid",
        "cubism.editor-model.parameter-controllable-source.set-target-deformer-guid",
        "cubism.editor-model.parameter-controllable-source.target-deformer-source",
        "cubism.editor-model.deformer-source.class",
        "cubism.editor-model.deformer-source.guid",
        "cubism.editor-model.model-source.all-deformers",
        "cubism.editor-model.model.all-deformers",
        "cubism.editor-model.deformer.source"
    );

    /** {@code GetPartStructure}: the palette tree across all object kinds. */
    public static final Set<String> GET_PART_STRUCTURE_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.SESSION_NAVIGATION_ALIASES,
        EditorPartTreeSelectorContract.REQUIRED_ALIASES,
        EditorObjectReadSelectorContract.REQUIRED_ALIASES,
        Set.of(
            "cubism.editor-model.model-source.all-objects",
            "cubism.editor-model.model-source.all-glues",
            "cubism.editor-model.glue-source.class"
        )
    );

    /**
     * {@code GetObject}: the object read surface shared by every kind — object lookup, palette
     * kind checks, the {@code %Root} parent normalization chain, the interpolated-form read,
     * form visual members, and label color. Warp and rotation payloads need nothing beyond
     * this set, so they read on every supported host.
     */
    public static final Set<String> GET_OBJECT_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.SESSION_NAVIGATION_ALIASES,
        OBJECT_LOOKUP_ALIASES,
        EditorObjectReadSelectorContract.REQUIRED_ALIASES,
        LABEL_COLOR_READ_ALIASES,
        Set.of(
            "cubism.editor-model.model-source.root-part",
            "cubism.editor-model.model.parts",
            "cubism.editor-model.part.source",
            "cubism.editor-model.glue-source.class",
            "cubism.editor-model.parameter-controllable-source.name-or-id-string",
            "cubism.editor-model.parameter-controllable-source.target-deformer-id",
            "cubism.editor-model.parameter-controllable.interpolated-form",
            "cubism.editor-model.deformer-form.multiply-color",
            "cubism.editor-model.deformer-form.screen-color",
            "cubism.editor-model.drawable-form.multiply-color",
            "cubism.editor-model.drawable-form.screen-color",
            "cubism.editor-model.float-color.hex-rgb"
        )
    );

    /**
     * {@code GetObject} Part fields that only exist on 5.3.x hosts: {@code IsGrouped},
     * {@code IsGuidImage}, {@code IsOffscreen}, {@code ClippingIds}, {@code IsReverseMask},
     * the part-form visual members, and both composition enums. Stays rejected on 5.2.03.
     */
    public static final Set<String> GET_OBJECT_PART_EXTENDED_ALIASES = unionAll(
        GET_OBJECT_REQUIRED_ALIASES,
        Set.of(
            "cubism.editor-model.part-source.enable-draw-order-group",
            "cubism.editor-model.part-source.sketch",
            "cubism.editor-model.part-source.use-offscreen",
            "cubism.editor-model.part-source.clip-guid-list",
            "cubism.editor-model.part-source.invert-clipping-mask",
            "cubism.editor-model.part-source.color-composition",
            "cubism.editor-model.part-source.alpha-composition",
            "cubism.editor-model.part-form.class",
            "cubism.editor-model.part-form.draw-order",
            "cubism.editor-model.part-form.opacity",
            "cubism.editor-model.part-form.multiply-color",
            "cubism.editor-model.part-form.screen-color",
            "cubism.editor-model.color-composition.values",
            "cubism.editor-model.alpha-composition.values"
        )
    );

    /**
     * {@code GetObject} ArtMesh fields that only exist on 5.3.x hosts: {@code ColorBlend} and
     * {@code AlphaBlend} compositions — the {@code AlphaComposition} enum does not exist on
     * 5.2.03, so art-mesh reads stay rejected there.
     */
    public static final Set<String> GET_OBJECT_ART_MESH_EXTENDED_ALIASES = unionAll(
        GET_OBJECT_REQUIRED_ALIASES,
        Set.of(
            "cubism.editor-model.art-mesh-source.color-composition",
            "cubism.editor-model.art-mesh-source.alpha-composition",
            "cubism.editor-model.color-composition.values",
            "cubism.editor-model.alpha-composition.values"
        )
    );

    /**
     * {@code GetObject} WarpDeformer detail members: the bezier subdivision extension the
     * official {@code BezierDivH}/{@code BezierDivV} fields read. Bound on every supported
     * host.
     */
    public static final Set<String> GET_OBJECT_WARP_ALIASES = unionAll(
        GET_OBJECT_REQUIRED_ALIASES,
        Set.of(
            "cubism.editor-model.parameter-controllable-source.extensions",
            "cubism.editor-model.warp-bezier-extension.class",
            "cubism.editor-model.warp-bezier-extension.edit-level",
            "cubism.editor-model.warp-bezier-extension.bezier-col",
            "cubism.editor-model.warp-bezier-extension.bezier-row"
        )
    );

    /**
     * {@code GetObject} Glue members: glue enumeration and identity, the glue-local name,
     * the live-instance form chain ({@code model.get-object} → {@code glue.current-keyform} →
     * {@code CGlueForm}), and the intensity read. Bound on every supported host.
     */
    public static final Set<String> GET_OBJECT_GLUE_ALIASES = unionAll(
        GET_OBJECT_REQUIRED_ALIASES,
        Set.of(
            "cubism.editor-model.model-source.all-glues",
            "cubism.editor-model.glue-source.local-name",
            "cubism.editor-model.model.get-object",
            "cubism.editor-model.glue.current-keyform",
            "cubism.editor-model.glue-form.intensity"
        )
    );

    /**
     * {@code DeleteObject}: selection fixup plus the delete command and read-back refresh.
     * ArtPath deletion is open pending host validation; the member set is the verified
     * drawable/deformer/part path.
     */
    public static final Set<String> DELETE_OBJECT_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        OBJECT_LOOKUP_ALIASES,
        Set.of(
            "cubism.editor-model.app-controller.update-manager",
            "cubism.editor-model.update-manager.class",
            "cubism.editor-model.update-manager.set-selection",
            "cubism.editor-model.app-controller.command-delete",
            "cubism.editor-model.model-source.handler",
            "cubism.editor-model.model-handler.class",
            "cubism.editor-model.model-handler.remove-objects",
            "cubism.editor-model.complete-pack.update-part-palette",
            "cubism.editor-model.complete-pack.update-deformer-palette"
        )
    );

    /** {@code MoveObjectOnPartsPalette}: reparent and reorder within the palette. */
    public static final Set<String> MOVE_OBJECT_ON_PARTS_PALETTE_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        OBJECT_LOOKUP_ALIASES,
        REPARENT_ALIASES,
        Set.of(
            "cubism.editor-model.model-source.parts",
            "cubism.editor-model.model.parts",
            "cubism.editor-model.part.class",
            "cubism.editor-model.part.source",
            "cubism.editor-model.part-source.class",
            "cubism.editor-model.part-source.id",
            "cubism.editor-model.part-id.value",
            "cubism.editor-model.part-source.set-default-order",
            "cubism.editor-model.part-source.default-order",
            "cubism.editor-model.parameter-controllable-source.handler",
            "cubism.editor-model.parameter-controllable-handler.class",
            "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
            "cubism.editor-model.complete-pack.update-part-palette",
            "cubism.editor-model.complete-pack.update-deformer-palette"
        )
    );

    /**
     * Extended set for {@code MoveObjectOnPartsPalette} reparenting through
     * {@code part-source.remove-child}, which the 5.2.03 and 5.3.02 records do not carry.
     */
    public static final Set<String> MOVE_OBJECT_REMOVE_CHILD_ALIASES = unionAll(
        MOVE_OBJECT_ON_PARTS_PALETTE_REQUIRED_ALIASES,
        Set.of("cubism.editor-model.part-source.remove-child")
    );

    /**
     * {@code AddPart}: verified part creation plus child attachment and id resolution for the
     * {@code Ids} field.
     */
    public static final Set<String> ADD_PART_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        EditorPartStructureSelectorContract.ROOT_CREATE_REQUIRED_ALIASES,
        OBJECT_LOOKUP_ALIASES
    );

    /**
     * {@code EditPart}: id rename, name, parent move, grouping/guide-image flags, sketch,
     * palette order, visibility/lock, and label color on every supported host.
     */
    public static final Set<String> EDIT_PART_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        EditorPartInspectorIdWriteSelectorContract.REQUIRED_ALIASES,
        EditorPartNameSelectorContract.WRITE_REQUIRED_ALIASES,
        LABEL_COLOR_WRITE_ALIASES,
        REPARENT_ALIASES,
        Set.of(
            "cubism.editor-model.part-source.set-sketch",
            "cubism.editor-model.part-source.sketch",
            "cubism.editor-model.part-source.set-edit-color",
            "cubism.editor-model.part-source.edit-color",
            "cubism.editor-model.part-source.set-default-order",
            "cubism.editor-model.part-source.default-order",
            "cubism.editor-model.parameter-controllable-source.set-visible",
            "cubism.editor-model.parameter-controllable-source.set-locked",
            "cubism.editor-model.parameter-controllable-source.visible",
            "cubism.editor-model.parameter-controllable-source.locked",
            "cubism.editor-model.drawable-form.set-draw-order",
            "cubism.editor-model.drawable-form.draw-order",
            "cubism.editor-model.drawable-form.set-opacity",
            "cubism.editor-model.drawable-form.opacity",
            "cubism.editor-model.drawable-form.multiply-color",
            "cubism.editor-model.drawable-form.screen-color",
            "cubism.editor-model.float-color.set-red",
            "cubism.editor-model.float-color.set-green",
            "cubism.editor-model.float-color.set-blue",
            "cubism.editor-model.float-color.set-alpha",
            "cubism.editor-model.float-color.red",
            "cubism.editor-model.float-color.green",
            "cubism.editor-model.float-color.blue",
            "cubism.editor-model.float-color.alpha"
        )
    );

    /**
     * Extended set for the {@code EditPart} fields that have no verified member on 5.2.03
     * ({@code Opacity}, {@code IsOffscreen}, {@code ClippingIds}, {@code IsReverseMask}, multiply
     * and screen colors, {@code ColorBlend}, {@code AlphaBlend}). Stays rejected on 5.2.03.
     */
    public static final Set<String> EDIT_PART_EXTENDED_ALIASES = unionAll(
        EDIT_PART_REQUIRED_ALIASES,
        Set.of(
            "cubism.editor-model.part-source.use-offscreen",
            "cubism.editor-model.part-source.clip-guid-list",
            "cubism.editor-model.part-source.alpha-composition",
            "cubism.editor-model.part-source.set-alpha-composition",
            "cubism.editor-model.part-form.class",
            "cubism.editor-model.part-form.opacity",
            "cubism.editor-model.part-form.set-opacity",
            "cubism.editor-model.alpha-composition.class",
            "cubism.editor-model.alpha-composition.values",
            "cubism.editor-model.alpha-composition.over",
            "cubism.editor-model.alpha-composition.atop",
            "cubism.editor-model.alpha-composition.out",
            "cubism.editor-model.alpha-composition.conjoint",
            "cubism.editor-model.alpha-composition.disjoint",
            "cubism.editor-model.color-composition.values",
            "cubism.editor-model.model-source.target-version",
            "cubism.editor-model.target-version.number"
        )
    );

    /**
     * {@code EditArtMesh}: name, id rename, reparenting, clipping, mask inversion, draw order,
     * opacity, multiply/screen colors, color blend, culling, and label color.
     */
    public static final Set<String> EDIT_ART_MESH_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        LABEL_COLOR_WRITE_ALIASES,
        REPARENT_ALIASES,
        Set.of(
            "cubism.editor-model.model-source.all-art-meshes",
            "cubism.editor-model.model.all-art-meshes",
            "cubism.editor-model.model-source.get-object",
            "cubism.editor-model.art-mesh.class",
            "cubism.editor-model.art-mesh.source",
            "cubism.editor-model.art-mesh.current-keyform",
            "cubism.editor-model.art-mesh-source.class",
            "cubism.editor-model.art-mesh-source.guid",
            "cubism.editor-model.art-mesh-source.clip-guid-list",
            "cubism.editor-model.art-mesh-source.set-clip-guid-list",
            "cubism.editor-model.art-mesh-source.inverted-mask",
            "cubism.editor-model.art-mesh-source.set-inverted-mask",
            "cubism.editor-model.art-mesh-source.set-invert-clipping-mask",
            "cubism.editor-model.art-mesh-source.culling",
            "cubism.editor-model.art-mesh-source.set-culling",
            "cubism.editor-model.art-mesh-source.set-color-composition",
            "cubism.editor-model.color-composition.values",
            "cubism.editor-model.drawable-source.set-id",
            "cubism.editor-model.drawable-form.draw-order",
            "cubism.editor-model.drawable-form.set-draw-order",
            "cubism.editor-model.drawable-form.opacity",
            "cubism.editor-model.drawable-form.set-opacity",
            "cubism.editor-model.drawable-form.multiply-color",
            "cubism.editor-model.drawable-form.screen-color",
            "cubism.editor-model.float-color.red",
            "cubism.editor-model.float-color.green",
            "cubism.editor-model.float-color.blue",
            "cubism.editor-model.float-color.alpha",
            "cubism.editor-model.float-color.set-red",
            "cubism.editor-model.float-color.set-green",
            "cubism.editor-model.float-color.set-blue",
            "cubism.editor-model.float-color.set-alpha",
            "cubism.editor-model.parameter-controllable-source.set-local-name",
            "cubism.editor-model.parameter-controllable-source.handler",
            "cubism.editor-model.parameter-controllable-handler.class",
            "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
            "cubism.editor-model.complete-pack.update-part-palette",
            "cubism.editor-model.id-list.add-all",
            "cubism.editor-model.id-list.clear",
            "cubism.editor-model.id-map.contains",
            "cubism.editor-model.model-handler.id-map",
            "cubism.editor-model.model-source.verify"
        )
    );

    /**
     * Extended set for the {@code EditArtMesh} {@code AlphaBlend} field, whose members are absent
     * from the 5.2.03 record. Stays rejected on that version.
     */
    public static final Set<String> EDIT_ART_MESH_ALPHA_BLEND_ALIASES = unionAll(
        EDIT_ART_MESH_REQUIRED_ALIASES,
        Set.of(
            "cubism.editor-model.art-mesh-source.set-alpha-composition",
            "cubism.editor-model.alpha-composition.class",
            "cubism.editor-model.alpha-composition.values",
            "cubism.editor-model.alpha-composition.over",
            "cubism.editor-model.alpha-composition.atop",
            "cubism.editor-model.alpha-composition.out",
            "cubism.editor-model.alpha-composition.conjoint",
            "cubism.editor-model.alpha-composition.disjoint",
            "cubism.editor-model.model-source.target-version",
            "cubism.editor-model.target-version.number"
        )
    );

    /**
     * {@code EditGlue}: rename, id rename, part reparenting, per-key intensity, and label color.
     * There is no official {@code AddGlue}; glue creation is not part of this surface.
     */
    public static final Set<String> EDIT_GLUE_REQUIRED_ALIASES = unionAll(
        EditorEditSessionSelectorContract.EDIT_SESSION_WRITE_ENVELOPE_ALIASES,
        EditorGlueInspectorSelectorContract.REQUIRED_ALIASES,
        LABEL_COLOR_WRITE_ALIASES,
        Set.of(
            "cubism.editor-model.part-source.handler",
            "cubism.editor-model.part-handler.class",
            "cubism.editor-model.part-handler.add-part-child",
            "cubism.editor-model.part-source.parent",
            "cubism.editor-model.parameter-controllable-source.label-color"
        )
    );

    private static Set<String> unionAll(final Set<String>... sets) {
        final HashSet<String> values = new HashSet<>();
        for (final Set<String> set : sets) {
            values.addAll(set);
        }
        return Set.copyOf(values);
    }

    private EditorEditPartObjectSelectorContract() {
    }
}
