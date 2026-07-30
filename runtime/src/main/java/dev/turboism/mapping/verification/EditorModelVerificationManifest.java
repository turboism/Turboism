package dev.turboism.mapping.verification;

import java.util.Set;

/** Runtime trust root for the Cubism 5.3.02 Editor model read/write binding. */
public final class EditorModelVerificationManifest {

    public static final String VERIFICATION_ID = "cubism-5.3.02.editor-model.static";
    public static final String RECORD_SHA256 =
        "6db0216d0587478866317d88a279d4d999fae35cb925e2101d3dcc01c4a7d78b";
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
        EditorParameterGroupLabelColorReadSelectorContract.CAPABILITY_ID,
        EditorParameterGroupLabelColorWriteSelectorContract.CAPABILITY_ID,
        EditorDefaultKeyformLockReadSelectorContract.CAPABILITY_ID,
        EditorDefaultKeyformLockWriteSelectorContract.CAPABILITY_ID,
        EditorPartOpacitySelectorContract.CAPABILITY_ID,
        EditorPartNameSelectorContract.CAPABILITY_ID,
        EditorPartNameSelectorContract.WRITE_CAPABILITY_ID,
        EditorObjectReadSelectorContract.CAPABILITY_ID,
        EditorObjectWriteSelectorContract.ART_MESH_CAPABILITY_ID,
        EditorObjectWriteSelectorContract.WARP_CAPABILITY_ID,
        EditorObjectWriteSelectorContract.ROTATION_CAPABILITY_ID,
        "cubism.texture-atlas.layout.write",
        "cubism.texture-atlas.data-model-hook",
        "cubism.texture-atlas.auto-layout-hook"
    );
    private static final Set<String> TEXTURE_ATLAS_ALIASES =
        union(
            dev.turboism.adapter.cubism.textureatlas.VerifiedCubism5302TextureAtlasSelectorContract.REQUIRED_ALIASES,
            union(
                dev.turboism.adapter.cubism.textureatlas.VerifiedCubism5302TextureAtlasSelectorContract.HOOK_ALIASES,
                dev.turboism.adapter.cubism.textureatlas.VerifiedCubism5302TextureAtlasSelectorContract.AUTO_LAYOUT_HOOK_ALIASES
            )
        );
    public static final Set<String> REQUIRED_ALIASES = union(Set.of(
        "cubism.editor-model.app-controller.class",
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.modeling-document.class",
        "cubism.editor-model.modeling-document.model-source",
        "cubism.editor-model.modeling-document.last-active-view",
        "cubism.editor-model.modeling-view.class",
        "cubism.editor-model.modeling-view.model",
        "cubism.editor-model.model-source.class",
        "cubism.editor-model.model-source.guid",
        "cubism.editor-model.model-source.current-instance",
        "cubism.editor-model.model-source.default-keyform-locked",
        "cubism.editor-model.model-source.set-default-keyform-locked",
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
        "cubism.editor-model.label-color-type.class",
        "cubism.editor-model.label-color-type.custom",
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
        "cubism.editor-model.model.parts",
        "cubism.editor-model.part.class",
        "cubism.editor-model.part.id",
        "cubism.editor-model.part.source",
        "cubism.editor-model.part.current-keyform",
        "cubism.editor-model.part-source.class",
        "cubism.editor-model.part-source.id",
        "cubism.editor-model.part-source.local-name",
        "cubism.editor-model.part-source.set-local-name",
        "cubism.editor-model.part-source.parent",
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
        "cubism.editor-model.complete-pack.update-deformer-palette"
    ), TEXTURE_ATLAS_ALIASES);
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
                cubism52Capabilities(),
                cubism52Aliases()
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
                CAPABILITY_IDS,
                REQUIRED_ALIASES
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
        values.removeAll(PART_OPACITY_ADDITIVE_ALIASES);
        values.addAll(EditorPartOpacity52SelectorContract.REQUIRED_ALIASES);
        values.addAll(PART_NAME_ADDITIVE_ALIASES);
        values.addAll(Set.of(
            "cubism.editor-model.model-source.update-instances",
            "cubism.editor-model.part-source.handler",
            "cubism.editor-model.part-handler.class",
            "cubism.editor-model.part-handler.create-undo-for-all-edit",
            "cubism.editor-model.complete-pack.update-part-palette"
        ));
        values.addAll(TEXTURE_ATLAS_ALIASES);
        return Set.copyOf(values);
    }


    private EditorModelVerificationManifest() {
    }
}
