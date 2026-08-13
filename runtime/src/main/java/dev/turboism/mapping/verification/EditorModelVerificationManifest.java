package dev.turboism.mapping.verification;

import java.util.Set;

/** Runtime trust root for the Cubism 5.3.02 Editor model read/write binding. */
public final class EditorModelVerificationManifest {

    public static final String VERIFICATION_ID = "cubism-5.3.02.editor-model.static";
    public static final String RECORD_SHA256 =
        "5c68daa945977f3a1d43476c3f0ac3858f9cacbe50c10e4dc2bcb76ab2947ea7";
    public static final String CUBISM_VERSION = "5.3.02";
    public static final String PROFILE_ID = "cubism-5.3.02";
    public static final long ARTIFACT_SIZE = 41_922_739L;
    public static final String ARTIFACT_SHA256 =
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";
    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final Set<String> CAPABILITY_IDS = Set.of(
        "cubism.editor-model.read",
        "cubism.editor-model.write",
        EditorParameterDefinitionWriteSelectorContract.CAPABILITY_ID,
        EditorParameterCombinedWriteSelectorContract.CAPABILITY_ID,
        EditorParameterGroupsReadSelectorContract.CAPABILITY_ID,
        EditorNativeControlAppearanceReadSelectorContract.CAPABILITY_ID,
        EditorNativeControlAppearanceWriteSelectorContract.CAPABILITY_ID,
        EditorDefaultKeyformLockReadSelectorContract.CAPABILITY_ID,
        EditorDefaultKeyformLockWriteSelectorContract.CAPABILITY_ID,
        EditorPartOpacitySelectorContract.CAPABILITY_ID,
        EditorPartNameSelectorContract.CAPABILITY_ID,
        EditorPartTreeSelectorContract.CAPABILITY_ID,
        EditorPartNameSelectorContract.WRITE_CAPABILITY_ID,
        EditorPartBasicSettingsSelectorContract.READ_CAPABILITY_ID,
        EditorPartBasicSettingsSelectorContract.WRITE_CAPABILITY_ID,
        EditorObjectReadSelectorContract.CAPABILITY_ID,
        EditorObjectReadSelectorContract.STATISTICS_CAPABILITY_ID,
        EditorObjectWriteSelectorContract.ART_MESH_CAPABILITY_ID,
        EditorObjectWriteSelectorContract.WARP_CAPABILITY_ID,
        EditorObjectWriteSelectorContract.ROTATION_CAPABILITY_ID,
        EditorHistoryReadSelectorContract.CAPABILITY_ID,
            EditorHistoryMoveSelectorContract.CAPABILITY_ID,
        EditorObjectHierarchyEditSelectorContract.CAPABILITY_ID,
        EditorObjectHierarchyEditSelectorContract.RENAME_CAPABILITY_ID,
        EditorObjectHierarchyEditSelectorContract.ART_MESH_CREATE_CAPABILITY_ID,
        ObjectContextMenuVerificationManifest.CAPABILITY_ID,
        EditorParameterBindingReadSelectorContract.CAPABILITY_ID,
        EditorParameterBindingWriteSelectorContract.ART_MESH_CAPABILITY_ID,
        EditorParameterBindingWriteSelectorContract.WARP_CAPABILITY_ID,
        EditorParameterBindingWriteSelectorContract.ROTATION_CAPABILITY_ID,
        EditorParameterBindingBatchWriteSelectorContract.INVERT_CAPABILITY_ID,
        EditorParameterBindingBatchWriteSelectorContract.TRANSFER_CAPABILITY_ID,
        EditorInspectorDrawableWriteSelectorContract.CAPABILITY_ID,
        EditorModelEditLevelReadSelectorContract.CAPABILITY_ID,
        EditorModelEditLevelWriteSelectorContract.CAPABILITY_ID,
        EditorPhysicsReadSelectorContract.CAPABILITY_ID,
        EditorAutoYureReadSelectorContract.CAPABILITY_ID,
        EditorAnimationReadSelectorContract.CAPABILITY_ID,
        EditorModelInstanceReadSelectorContract.CAPABILITY_ID,
        "cubism.texture-atlas.layout.write",
        "cubism.texture-atlas.data-model-hook",
        "cubism.texture-atlas.auto-layout-hook",
        "cubism.texture-atlas.native-layout-invocation",
        "cubism.texture-atlas.dialog-injection",
        "cubism.editor-model.model-name.write",
        "cubism.editor-model.model-profile.read",
        "cubism.editor-model.morph-target.read",
        "cubism.editor-model.morph-target.write",
        "cubism.editor-model.parameter-structure.write",
        "cubism.editor-model.part-structure.write",
        EditorTextureSelectorContract.READ_CAPABILITY_ID,
        EditorTextureSelectorContract.WRITE_CAPABILITY_ID,
        "cubism.editor-model.part-inspector.write",
        "cubism.editor-model.deformer-inspector.write",
        "cubism.editor-model.glue-inspector.write"
    );
    private static final Set<String> STRUCTURE_ALIASES = Set.of(
        "cubism.editor-model.copy-helper.copy",
        "cubism.editor-model.form-guid.value",
        "cubism.editor-model.image-canvas.class",
        "cubism.editor-model.image-canvas.height",
        "cubism.editor-model.image-canvas.width",
        "cubism.editor-model.model-handler.class",
        "cubism.editor-model.model-handler.create-free-id-default",
        "cubism.editor-model.model-handler.move-parameter",
        "cubism.editor-model.model-handler.remove-objects",
        "cubism.editor-model.model-handler.remove-parameter",
        "cubism.editor-model.model-info.class",
        "cubism.editor-model.model-info.origin",
        "cubism.editor-model.model-info.pixels-per-unit",
        "cubism.editor-model.model-source.canvas",
        "cubism.editor-model.model-source.handler",
        "cubism.editor-model.model-source.model-info",
        "cubism.editor-model.model-source.parameter-source-set",
        "cubism.editor-model.model-source.root-part",
        "cubism.editor-model.model-source.set-name",
        "cubism.editor-model.morph-target-set.create-undo",
        "cubism.editor-model.morph-target-set.morph-targets",
        "cubism.editor-model.morph-target-set.remove",
        "cubism.editor-model.morph-target-utils.instance",
        "cubism.editor-model.morph-target.change-parameter",
        "cubism.editor-model.morph-target.class",
        "cubism.editor-model.morph-target.key-value",
        "cubism.editor-model.morph-target.keyform-guid",
        "cubism.editor-model.morph-target.parameter-guid",
        "cubism.editor-model.morph-target.set-parameter",
        "cubism.editor-model.morph-target.set-parameter-and-key-value",
        "cubism.editor-model.parameter-group-handler.add-group-child",
        "cubism.editor-model.parameter-group-handler.add-parameter-child",
        "cubism.editor-model.parameter-group-handler.class",
        "cubism.editor-model.parameter-group-handler.remove-descendant",
        "cubism.editor-model.parameter-group-guid.create",
        "cubism.editor-model.parameter-group-id.create",
        "cubism.editor-model.parameter-group.create",
        "cubism.editor-model.parameter-group.guid",
        "cubism.editor-model.parameter-group.handler",
        "cubism.editor-model.parameter-group.set-folder-opened",
        "cubism.editor-model.parameter-group.set-name",
        "cubism.editor-model.parameter-id.class",
        "cubism.editor-model.parameter-id.create",
        "cubism.editor-model.parameter-source-set.class",
        "cubism.editor-model.parameter-source-set.get",
        "cubism.editor-model.parameter-source-set.get-by-id",
        "cubism.editor-model.parameter-source.create",
        "cubism.editor-model.parameter-source.param-type",
        "cubism.editor-model.parameter-source.set-repeat",
        "cubism.editor-model.parameter-source.type-morph-target",
        "cubism.editor-model.parameter-source.type-normal",
        "cubism.editor-model.part-guid.create",
        "cubism.editor-model.part-handler.add-part-child",
        "cubism.editor-model.part-id.create",
        "cubism.editor-model.part-source.children",
        "cubism.editor-model.part-source.create",
        "cubism.editor-model.part-source.set-guid",
        "cubism.editor-model.part-source.set-id",
        "cubism.editor-model.point.class",
        "cubism.editor-model.point.x",
        "cubism.editor-model.point.y"
    );

    private static final Set<String> TEXTURE_ATLAS_ALIASES =
        union(
            union(
                dev.turboism.adapter.cubism.textureatlas.VerifiedCubism5302TextureAtlasSelectorContract.REQUIRED_ALIASES,
                union(
                    union(
                        union(
                            dev.turboism.adapter.cubism.textureatlas.VerifiedCubism5302TextureAtlasSelectorContract.HOOK_ALIASES,
                            union(
                                dev.turboism.adapter.cubism.textureatlas.VerifiedCubism5302TextureAtlasSelectorContract.AUTO_LAYOUT_HOOK_ALIASES,
                                dev.turboism.adapter.cubism.textureatlas.VerifiedCubism5302TextureAtlasSelectorContract.NATIVE_INVOCATION_ALIASES
                            )
                        ),
                        dev.turboism.adapter.cubism.textureatlas.VerifiedCubism5302TextureAtlasSelectorContract.DIALOG_INJECTION_ALIASES
                    ),
                    dev.turboism.adapter.cubism.textureatlas.VerifiedCubism5302TextureAtlasSelectorContract.STATISTICS_ALIASES
                )
            ),
            STRUCTURE_ALIASES
        );

    private static final Set<String> TEXTURE_ATLAS_ALIASES_52 =
        union(
            dev.turboism.adapter.cubism.textureatlas.VerifiedCubism520TextureAtlasSelectorContract.REQUIRED_ALIASES,
            union(
                union(
                    dev.turboism.adapter.cubism.textureatlas.VerifiedCubism520TextureAtlasSelectorContract.HOOK_ALIASES,
                    union(
                        dev.turboism.adapter.cubism.textureatlas.VerifiedCubism520TextureAtlasSelectorContract.AUTO_LAYOUT_HOOK_ALIASES,
                        dev.turboism.adapter.cubism.textureatlas.VerifiedCubism520TextureAtlasSelectorContract.NATIVE_INVOCATION_ALIASES
                    )
                ),
                dev.turboism.adapter.cubism.textureatlas.VerifiedCubism520TextureAtlasSelectorContract.DIALOG_INJECTION_ALIASES
            )
        )
    ;

    private static final Set<String> INSPECTOR_WRITE_ALIASES = union(
        union(
            EditorDeformerInspectorSelectorContract.REQUIRED_ALIASES,
            EditorPartInspectorSelectorContract.REQUIRED_ALIASES
        ),
        EditorGlueInspectorSelectorContract.REQUIRED_ALIASES
    );

    public static final Set<String> REQUIRED_ALIASES = union(union(Set.of(
        "cubism.editor-model.app-controller.class",
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.modeling-document.class",
        "cubism.editor-model.modeling-document.model-source",
        "cubism.editor-model.modeling-document.last-active-view",
        "cubism.editor-model.modeling-view.class",
        "cubism.editor-model.modeling-view.model",
        "cubism.editor-model.model-source.class",
        "cubism.editor-model.model-source.name",
        "cubism.editor-model.model-source.guid",
        "cubism.editor-model.model-source.current-instance",
        "cubism.editor-model.model-source.default-keyform-locked",
        "cubism.editor-model.model-source.set-default-keyform-locked",
        "cubism.editor-model.app-controller.edit-level",
        "cubism.editor-model.app-controller.set-edit-level",
        "cubism.editor-model.model-source.all-parameters",
        "cubism.editor-model.model-source.root-parameter-group",
        "cubism.editor-model.model.class",
        "cubism.editor-model.model.parameter-set",
        "cubism.editor-model.parameter-set.class",
        "cubism.editor-model.parameter-set.parameters",
        "cubism.editor-model.parameter.class",
        "cubism.editor-model.parameter.id",
        "cubism.editor-model.parameter.value",
        "cubism.editor-model.parameter.source",
        "cubism.editor-model.parameter-source.class",
        "cubism.editor-model.parameter-source.minimum",
        "cubism.editor-model.parameter-source.maximum",
        "cubism.editor-model.parameter-source.default",
        "cubism.editor-model.parameter-source.name",
        "cubism.editor-model.parameter-source.repeat",
        "cubism.editor-model.parameter-source.morph-target",
        "cubism.editor-model.parameter-source.combined",
        "cubism.editor-model.parameter-source.id",
        "cubism.editor-model.id.class",
        "cubism.editor-model.id.value",
        "cubism.editor-model.guid.class",
        "cubism.editor-model.guid.value",
        "cubism.editor-model.complete-pack.class",
        "cubism.editor-model.app-controller.complete-pack",
        "cubism.editor-model.main-frame.class",
        "cubism.editor-model.app-controller.main-frame",
        "cubism.editor-model.main-frame.parameter-palette",
        "cubism.editor-model.parameter-palette.class",
        "cubism.editor-model.parameter-palette.view",
        "cubism.editor-model.parameter-palette-view.class",
        "cubism.editor-model.parameter-palette-view.operation",
        "cubism.editor-model.parameter-operation.class",
        "cubism.editor-model.parameter-operation.rows",
        "cubism.editor-model.parameter-operation.set-value",
        "cubism.editor-model.complete-pack.update-parameter",
        "cubism.editor-model.complete-pack.repaint-canvas",
        "cubism.editor-model.modeling-document.edit-mode",
        "cubism.editor-model.modeling-document.mark-dirty",
        "cubism.editor-model.edit-mode.class",
        "cubism.editor-model.edit-mode.begin",
        "cubism.editor-model.edit-mode.end",
        "cubism.editor-model.undo.class",
        "cubism.editor-model.undo.add",
        "cubism.editor-model.simple-undo.create",
        "cubism.editor-model.parameter-operation.property-editor",
        "cubism.editor-model.parameter-operation.validator",
        "cubism.editor-model.parameter-operation.refresh",
        "cubism.editor-model.parameter-property-editor.class",
        "cubism.editor-model.parameter-property-editor.update-definition",
        "cubism.editor-model.parameter-property-editor.rebuild-keep-value",
        "cubism.editor-model.parameter-validator.class",
        "cubism.editor-model.parameter-validator.valid-id",
        "cubism.editor-model.parameter-validator.supports-type",
        "cubism.editor-model.parameter-validator.reject-type-change",
        "cubism.editor-model.parameter-validator.allow-repeat",
        "cubism.editor-model.parameter-validator.keys-outside-range",
        "cubism.editor-model.parameter-validator.default-change-affects-morph-target",
        "cubism.editor-model.parameter-helper-owner.class",
        "cubism.editor-model.parameter-helper.class",
        "cubism.editor-model.parameter-helper.instance",
        "cubism.editor-model.parameter-helper.morph-target-eligible",
        "cubism.editor-model.parameter-source.model-source",
        "cubism.editor-model.model-source.all-objects",
        "cubism.editor-model.parameter-controllable.class",
        "cubism.editor-model.parameter-controllable.keyform-grid",
        "cubism.editor-model.keyform-grid.class",
        "cubism.editor-model.keyform-grid.contains-parameter",
        "cubism.editor-model.parameter-controllable.morph-target-set",
        "cubism.editor-model.morph-target-set.class",
        "cubism.editor-model.morph-target-set.contains-parameter",
        "cubism.editor-model.parameter-source.guid",
        "cubism.editor-model.parameter-refresh-callback.create",
        "cubism.editor-model.parameter-group.class",
        "cubism.editor-model.parameter-group.id",
        "cubism.editor-model.parameter-group.name",
        "cubism.editor-model.parameter-group.parent",
        "cubism.editor-model.parameter-group.label-color",
        "cubism.editor-model.label-color.class",
        "cubism.editor-model.label-color.color",
        "cubism.editor-model.label-color.set-color",
        "cubism.editor-model.label-color.set-label-type",
        "cubism.editor-model.label-color-type.class",
        "cubism.editor-model.label-color-type.custom",
        "cubism.editor-model.label-color-type.undefined",
        "cubism.editor-model.label-color-type.red",
        "cubism.editor-model.label-color-type.orange",
        "cubism.editor-model.label-color-type.yellow",
        "cubism.editor-model.label-color-type.green",
        "cubism.editor-model.label-color-type.blue",
        "cubism.editor-model.label-color-type.purple",
        "cubism.editor-model.label-color-type.gray",
        "cubism.editor-model.label-color.label-type",
        "cubism.editor-model.label-color.customized-color",
        "cubism.editor-model.parameter-controllable-source.label-color",
        "cubism.editor-model.deformer-source.class",
        "cubism.editor-model.color.class",
        "cubism.editor-model.color.create",
        "cubism.editor-model.color.red",
        "cubism.editor-model.color.green",
        "cubism.editor-model.color.blue",
        "cubism.editor-model.color.alpha",
        "cubism.editor-model.undo-listener.class",
        "cubism.editor-model.parameter-source.set-combined",
        "cubism.editor-model.parameter-source.parent-group",
        "cubism.editor-model.parameter-group.children",
        "cubism.editor-model.parameter-group.remove",
        "cubism.editor-model.parameter-group.add",
        "cubism.editor-model.undo.add-listener",
        "cubism.editor-model.model-source.parts",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.model-source.update-visible-lock-hierarchy",
        "cubism.editor-model.model.parts",
        "cubism.editor-model.part.class",
        "cubism.editor-model.part.id",
        "cubism.editor-model.part.source",
        "cubism.editor-model.part.current-keyform",
        "cubism.editor-model.part-source.class",
        "cubism.editor-model.part-source.id",
        "cubism.editor-model.part-source.local-name",
        "cubism.editor-model.part-source.set-local-name",
        "cubism.editor-model.part-source.default-order",
        "cubism.editor-model.part-source.set-default-order",
        "cubism.editor-model.part-source.sketch",
        "cubism.editor-model.part-source.set-sketch",
        "cubism.editor-model.part-source.edit-color",
        "cubism.editor-model.part-source.set-edit-color",
        "cubism.editor-model.part-source.create-undo-for-basic-settings",
        "cubism.editor-model.part-source.parent",
        "cubism.editor-model.part-source.use-offscreen",
        "cubism.editor-model.part-source.handler",
        "cubism.editor-model.part-handler.class",
        "cubism.editor-model.part-handler.create-undo-for-all-edit",
        "cubism.editor-model.part-form.class",
        "cubism.editor-model.part-form.opacity",
        "cubism.editor-model.part-form.set-opacity",
        "cubism.editor-model.part-id.class",
        "cubism.editor-model.part-id.value",
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.model-source.all-art-meshes",
        "cubism.editor-model.model.all-art-meshes",
        "cubism.editor-model.art-mesh-source.class",
        "cubism.editor-model.art-mesh.class",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.parameter-controllable-source.local-name",
        "cubism.editor-model.parameter-controllable-source.visible",
        "cubism.editor-model.parameter-controllable-source.locked",
        "cubism.editor-model.parameter-controllable-source.visible-in-hierarchy",
        "cubism.editor-model.parameter-controllable-source.locked-in-hierarchy",
        "cubism.editor-model.parameter-controllable-source.target-deformer-source",
        "cubism.editor-model.art-mesh.source",
        "cubism.editor-model.art-mesh.current-keyform",
        "cubism.editor-model.drawable-form.opacity",
        "cubism.editor-model.drawable-form.draw-order",
        "cubism.editor-model.art-mesh-form.positions",
        "cubism.editor-model.art-mesh-source.positions",
        "cubism.editor-model.art-mesh-source.uvs",
        "cubism.editor-model.art-mesh-source.indices",
        "cubism.editor-model.art-mesh-source.culling",
        "cubism.editor-model.art-mesh-source.user-data",
        "cubism.editor-model.art-mesh-source.inverted-mask",
        "cubism.editor-model.art-mesh-source.guid",
        "cubism.editor-model.art-mesh-source.clip-guid-list",
        "cubism.editor-model.art-mesh-source.texture",
        "cubism.editor-model.texture.guid",
        "cubism.editor-model.model-source.all-glues",
        "cubism.editor-model.glue-source.class",
        "cubism.editor-model.glue-source.target-art-mesh-a",
        "cubism.editor-model.glue-source.target-art-mesh-b",
        "cubism.editor-model.model-source.all-deformers",
        "cubism.editor-model.model.all-deformers",
        "cubism.editor-model.warp-source.class",
        "cubism.editor-model.warp.class",
        "cubism.editor-model.rotation-source.class",
        "cubism.editor-model.rotation.class",
        "cubism.editor-model.deformer.source",
        "cubism.editor-model.deformer.current-keyform",
        "cubism.editor-model.deformer-form.opacity",
        "cubism.editor-model.warp-source.row",
        "cubism.editor-model.warp-source.col",
        "cubism.editor-model.warp-source.quad-transform",
        "cubism.editor-model.warp-form.positions",
        "cubism.editor-model.rotation-source.base-angle",
        "cubism.editor-model.rotation-form.angle",
        "cubism.editor-model.rotation-form.origin-x",
        "cubism.editor-model.rotation-form.origin-y",
        "cubism.editor-model.rotation-form.scale",
        "cubism.editor-model.rotation-form.reflect-x",
        "cubism.editor-model.rotation-form.reflect-y",
        "cubism.editor-model.parameter-controllable-source.handler",
        "cubism.editor-model.parameter-controllable-handler.class",
        "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
        "cubism.editor-model.parameter-controllable-source.set-visible",
        "cubism.editor-model.parameter-controllable-source.set-locked",
        "cubism.editor-model.drawable-form.set-opacity",
        "cubism.editor-model.art-mesh-form.set-positions",
        "cubism.editor-model.art-mesh-source.set-positions",
        "cubism.editor-model.art-mesh-source.set-uvs",
        "cubism.editor-model.art-mesh-source.set-indices",
        "cubism.editor-model.deformer-form.set-opacity",
        "cubism.editor-model.warp-source.set-row",
        "cubism.editor-model.warp-source.set-col",
        "cubism.editor-model.warp-source.set-quad-transform",
        "cubism.editor-model.warp-form.set-positions",
        "cubism.editor-model.rotation-source.set-base-angle",
        "cubism.editor-model.rotation-form.set-angle",
        "cubism.editor-model.rotation-form.set-origin-x",
        "cubism.editor-model.rotation-form.set-origin-y",
        "cubism.editor-model.rotation-form.set-scale",
        "cubism.editor-model.rotation-form.set-reflect-x",
        "cubism.editor-model.rotation-form.set-reflect-y",
        "cubism.editor-model.complete-pack.update-deformer-palette",
        "cubism.editor-history.document.undo-manager",
        "cubism.editor-history.manager.class",
        "cubism.editor-history.manager.entries",
        "cubism.editor-history.manager.position",
        "cubism.editor-history.manager.can-undo",
        "cubism.editor-history.manager.can-redo",
        "cubism.editor-history.entry.class",
        "cubism.editor-history.entry.presentation-name",
        "cubism.editor-history.entry.significant",
        "cubism.editor-history.manager.move-to",
        "object-context-menu.parameter.group-row.class",
        "object-context-menu.parameter.group-row.source",
        "object-context-menu.parameter.row-parameters",
        "object-context-menu.workspace.selector",
        "object-context-menu.workspace.selected",
        "object-context-menu.workspace.selection.class",
        "object-context-menu.workspace.selection-source",
        "object-context-menu.warp.class",
        "object-context-menu.rotation.class",
        "object-context-menu.art-mesh.class",
        "object-context-menu.part.class",
        "object-context-menu.glue.class",
        "object-context-menu.parameter.class",
        "object-context-menu.parameter-group.class",
        "object-context-menu.object-id",
        "object-context-menu.parameter-id",
        "object-context-menu.parameter-group-id",
        "object-context-menu.id-value",
        "object-context-menu.menu-item.create",
        "object-context-menu.menu.append",
        "object-context-menu.submenu.append",
        "object-context-menu.menu-separator.create",
        "object-context-menu.submenu.create",
        "object-context-menu.menu.items",
        "object-context-menu.menu-item.label",
        "object-context-menu.parameter-point.guid-value",
        "object-context-menu.menu.component",
        "cubism.editor-model.keyform-grid.bindings",
        "cubism.editor-model.keyform-binding.class",
        "cubism.editor-model.keyform-binding.parameter-id",
        "cubism.editor-model.keyform-binding.parameter-guid",
        "cubism.editor-model.keyform-binding.keys",
        "cubism.editor-model.keyform-grid.find-binding",
        "cubism.editor-model.keyform-grid.reverse-parameter",
        "cubism.editor-model.keyform-grid.change-parameter",
        "cubism.editor-model.keyform-grid.add-key",
        "cubism.editor-model.keyform-grid.remove-key",
        "cubism.editor-model.keyform-grid.remove-all-key",
        "cubism.editor-model.keyform-grid.rearrange-keys"
    ), union(

            union(
                union(
                    EditorPhysicsReadSelectorContract.REQUIRED_ALIASES,
                    EditorAutoYureReadSelectorContract.REQUIRED_ALIASES
                ),
                EditorAnimationReadSelectorContract.REQUIRED_ALIASES
            ),
            EditorModelInstanceReadSelectorContract.REQUIRED_ALIASES
        )), union(
            union(
                INSPECTOR_WRITE_ALIASES,
                union(
                    TEXTURE_ATLAS_ALIASES,
                    union(
                        union(
                            EditorTextureSelectorContract.WRITE_REQUIRED_ALIASES,
                            EditorTextureSelectorContract.REMOVE_RAW_IMAGE_ALIASES
                        ),
                        EditorInspectorDrawableWriteSelectorContract.REQUIRED_ALIASES
                    )
                )
            ),
            union(
                EditorObjectHierarchyEditSelectorContract.REQUIRED_ALIASES,
                union(
                    EditorObjectHierarchyEditSelectorContract.RENAME_REQUIRED_ALIASES,
                    union(
                        EditorObjectHierarchyEditSelectorContract.ART_MESH_CREATE_REQUIRED_ALIASES,
                        TEXTURE_ATLAS_ALIASES
                    )
                )
            )
        )
    );


    /**
     * Aliases exclusive to the Cubism 5.3.02 Part Inspector entries (clip
     * mask / alpha composition / instance-level part access); absent from the
     * 5.2 record, so they are removed from the 5.2 manifest alias set while
     * shared aliases (including the Glue drawable ArtMesh resolution aliases)
     * are preserved.
     */
    private static final Set<String> PART_INSPECTOR_5302_ONLY_ALIASES = Set.of(
        "cubism.editor-model.part.id",
        "cubism.editor-model.part.current-keyform",
        "cubism.editor-model.part-source.clip-guid-list",
        "cubism.editor-model.part-source.alpha-composition",
        "cubism.editor-model.part-source.set-alpha-composition",
        "cubism.editor-model.alpha-composition.class",
        "cubism.editor-model.alpha-composition.over",
        "cubism.editor-model.alpha-composition.atop",
        "cubism.editor-model.alpha-composition.out",
        "cubism.editor-model.alpha-composition.conjoint",
        "cubism.editor-model.alpha-composition.disjoint"
    );
    private static final Set<String> PART_OPACITY_ADDITIVE_ALIASES = Set.of(
        "cubism.editor-model.model-source.parts",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.model.parts",
        "cubism.editor-model.part.class",
        "cubism.editor-model.part.id",
        "cubism.editor-model.part.source",
        "cubism.editor-model.part.current-keyform",
        "cubism.editor-model.part-source.class",
        "cubism.editor-model.part-source.id",
        "cubism.editor-model.part-source.parent",
        "cubism.editor-model.part-source.handler",
        "cubism.editor-model.part-handler.class",
        "cubism.editor-model.part-handler.create-undo-for-all-edit",
        "cubism.editor-model.part-form.class",
        "cubism.editor-model.part-form.opacity",
        "cubism.editor-model.part-form.set-opacity",
        "cubism.editor-model.part-id.class",
        "cubism.editor-model.part-id.value",
        "cubism.editor-model.complete-pack.update-part-palette"
    );
    private static final Set<String> PART_NAME_ADDITIVE_ALIASES = Set.of(
        "cubism.editor-model.model-source.parts",
        "cubism.editor-model.model.parts",
        "cubism.editor-model.part.class",
        "cubism.editor-model.part.source",
        "cubism.editor-model.part-source.class",
        "cubism.editor-model.part-source.id",
        "cubism.editor-model.part-source.local-name",
        "cubism.editor-model.part-source.set-local-name",
        "cubism.editor-model.part-id.value"
    );

    public static String resourceProfileForArtifact(final HostArtifactDigest artifact) {
        return forArtifact(artifact).profileId().substring("cubism-".length());
    }

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(
        final HostArtifactDigest artifact
    ) {
        if (artifact.size() == EditorModelVerificationManifest52.ARTIFACT_SIZE
            && artifact.sha256().equals(EditorModelVerificationManifest52.ARTIFACT_SHA256)) {
            return manifest(
                EditorModelVerificationManifest52.VERIFICATION_ID,
                EditorModelVerificationManifest52.RECORD_SHA256,
                EditorModelVerificationManifest52.CUBISM_VERSION,
                EditorModelVerificationManifest52.PROFILE_ID,
                EditorModelVerificationManifest52.ARTIFACT_SIZE,
                EditorModelVerificationManifest52.ARTIFACT_SHA256,
                ObjectContextMenuVerificationManifest.capabilities(cubism52Capabilities()),
                ObjectContextMenuVerificationManifest.aliases(cubism52Aliases())
            );
        }
        if (artifact.size() == ARTIFACT_SIZE && artifact.sha256().equals(ARTIFACT_SHA256)) {
            return manifest(
                VERIFICATION_ID,
                RECORD_SHA256,
                CUBISM_VERSION,
                PROFILE_ID,
                ARTIFACT_SIZE,
                ARTIFACT_SHA256,
                ObjectContextMenuVerificationManifest.capabilities(CAPABILITY_IDS),
                ObjectContextMenuVerificationManifest.aliases(REQUIRED_ALIASES)
            );
        }
        throw new IllegalArgumentException(
            "host artifact is not a reviewed Cubism Editor model artifact"
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest(
        final String verificationId,
        final String recordSha256,
        final String cubismVersion,
        final String profileId,
        final long artifactSize,
        final String artifactSha256,
        final Set<String> capabilityIds,
        final Set<String> requiredAliases
    ) {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            verificationId,
            recordSha256,
            cubismVersion,
            profileId,
            artifactSize,
            artifactSha256,
            ADAPTER_SLICE_ID,
            capabilityIds,
            requiredAliases
        );
    }

    private static Set<String> union(final Set<String> left, final Set<String> right) {
        final java.util.HashSet<String> values = new java.util.HashSet<>(left);
        values.addAll(right);
        return Set.copyOf(values);
    }

    private static Set<String> partNameOnlyCapabilities() {
        final java.util.HashSet<String> values = new java.util.HashSet<>(CAPABILITY_IDS);
        values.remove(EditorPartOpacitySelectorContract.CAPABILITY_ID);
        return Set.copyOf(values);
    }

    private static Set<String> cubism52Capabilities() {
        final java.util.HashSet<String> values = new java.util.HashSet<>(CAPABILITY_IDS);
        values.remove(EditorPartOpacitySelectorContract.CAPABILITY_ID);
        values.add(EditorPartOpacity52SelectorContract.CAPABILITY_ID);
        values.remove(EditorInspectorDrawableWriteSelectorContract.CAPABILITY_ID);
        values.add(EditorInspectorDrawableWrite52SelectorContract.CAPABILITY_ID);
        values.remove(EditorPartInspectorSelectorContract.CAPABILITY_ID);
        values.add(EditorPartInspector52SelectorContract.CAPABILITY_ID);
        return Set.copyOf(values);
    }

    private static Set<String> withoutPartOpacityAliases() {
        final java.util.HashSet<String> values = new java.util.HashSet<>(REQUIRED_ALIASES);
        values.removeAll(PART_OPACITY_ADDITIVE_ALIASES);
        values.addAll(PART_NAME_ADDITIVE_ALIASES);
        values.addAll(Set.of(
            "cubism.editor-model.model-source.update-instances",
            "cubism.editor-model.part-source.handler",
            "cubism.editor-model.part-handler.class",
            "cubism.editor-model.part-handler.create-undo-for-all-edit",
            "cubism.editor-model.complete-pack.update-part-palette"
        ));
        return Set.copyOf(values);
    }

    private static Set<String> cubism52Aliases() {
        final java.util.HashSet<String> values = new java.util.HashSet<>(REQUIRED_ALIASES);
        values.removeAll(EditorModelInstanceReadSelectorContract.ONION_SKIN_ALIASES);
        values.removeAll(dev.turboism.adapter.cubism.textureatlas.VerifiedCubism5302TextureAtlasSelectorContract.STATISTICS_ALIASES);
        values.removeAll(PART_OPACITY_ADDITIVE_ALIASES);
        values.removeAll(EditorObjectReadSelectorContract.OFFSCREEN_STATISTICS_ALIASES);
        values.addAll(EditorPartOpacity52SelectorContract.REQUIRED_ALIASES);
        values.removeAll(PART_INSPECTOR_5302_ONLY_ALIASES);
        values.addAll(EditorPartInspector52SelectorContract.REQUIRED_ALIASES);
        values.addAll(PART_NAME_ADDITIVE_ALIASES);
        values.addAll(Set.of(
            "cubism.editor-model.model-source.update-instances",
            "cubism.editor-model.part-source.handler",
            "cubism.editor-model.part-handler.class",
            "cubism.editor-model.part-handler.create-undo-for-all-edit",
            "cubism.editor-model.complete-pack.update-part-palette"
        ));
        values.addAll(TEXTURE_ATLAS_ALIASES_52);
        values.addAll(EditorTextureSelectorContract.WRITE_REQUIRED_ALIASES);
        values.removeAll(EditorTextureSelectorContract.REMOVE_RAW_IMAGE_ALIASES);
        values.removeAll(EditorInspectorDrawableWriteSelectorContract.ALPHA_COMPOSITION_ALIASES);
        values.addAll(EditorInspectorDrawableWrite52SelectorContract.REQUIRED_ALIASES);
        return Set.copyOf(values);
    }


    private EditorModelVerificationManifest() {
    }
}
