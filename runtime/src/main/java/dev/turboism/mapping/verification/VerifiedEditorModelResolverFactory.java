package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/** Pinned resolver factory for the verified Editor model read/write slice. */
public final class VerifiedEditorModelResolverFactory {

    private static final String EDIT_LEVEL_WRITE_VALIDATION_PROPERTY =
        "turboism.validation.editorModelEditLevelWrite";
    private static final String EDIT_LEVEL_WRITE_VALIDATION_TOKEN =
        "EXACT_5303_EDIT_LEVEL_WRITE_CANDIDATE";
    private static final String EDIT_LEVEL_WRITE_VALIDATION_MODE =
        "edit-level-write-5303";
    private static final String PARAMETER_VALUE_WRITE_VALIDATION_PROPERTY =
        "turboism.validation.editorParameterValueWrite";
    private static final String PARAMETER_VALUE_WRITE_VALIDATION_TOKEN =
        "EXACT_5303_PARAMETER_VALUE_WRITE_CANDIDATE";
    private static final String PARAMETER_VALUE_WRITE_VALIDATION_MODE =
        "parameter-value-write-5303";
    private static final String PARAMETER_LIFECYCLE_VALIDATION_PROPERTY =
        "turboism.validation.parameterLifecycle";
    private static final String PARAMETER_LIFECYCLE_VALIDATION_TOKEN =
        "EXACT_5303_PARAMETER_LIFECYCLE_HOOK_CANDIDATE";
    private static final String PARAMETER_LIFECYCLE_VALIDATION_MODE =
        "parameter-lifecycle-hook-5303";
    private static final String AUTO_BACKUP_VALIDATION_PROPERTY =
        "turboism.validation.autoBackup";
    private static final String AUTO_BACKUP_VALIDATION_TOKEN =
        "EXACT_5303_AUTOBACKUP_CANDIDATE";
    private static final String AUTO_BACKUP_VALIDATION_MODE_PROPERTY =
        "turboism.validation.autoBackup.mode";
    private static final String AUTO_BACKUP_VALIDATION_MODE = "matrix";
    private static final String DEFAULT_KEYFORM_LOCK_WRITE_VALIDATION_PROPERTY =
        "turboism.validation.editorDefaultKeyformLockWrite";
    private static final String DEFAULT_KEYFORM_LOCK_WRITE_VALIDATION_TOKEN =
        "EXACT_5303_DEFAULT_KEYFORM_LOCK_WRITE_CANDIDATE";
    private static final String DEFAULT_KEYFORM_LOCK_WRITE_VALIDATION_MODE =
        "default-keyform-lock-write-5303";
    private static final String PARAMETER_DEFINITION_WRITE_VALIDATION_PROPERTY =
        "turboism.validation.editorParameterDefinitionWrite";
    private static final String PARAMETER_DEFINITION_WRITE_VALIDATION_TOKEN =
        "EXACT_5303_PARAMETER_DEFINITION_WRITE_CANDIDATE";
    private static final String PARAMETER_DEFINITION_WRITE_VALIDATION_MODE =
        "parameter-definition-write-5303";
    private static final String PARAMETER_COMBINED_WRITE_VALIDATION_PROPERTY =
        "turboism.validation.editorParameterCombinedWrite";
    private static final String PARAMETER_COMBINED_WRITE_VALIDATION_TOKEN =
        "EXACT_5303_PARAMETER_COMBINED_WRITE_CANDIDATE";
    private static final String PARAMETER_COMBINED_WRITE_VALIDATION_MODE =
        "parameter-combined-write-5303";
    private static final String MODEL_NAME_WRITE_VALIDATION_PROPERTY =
        "turboism.validation.editorModelNameWrite";
    private static final String MODEL_NAME_WRITE_VALIDATION_TOKEN =
        "EXACT_5303_MODEL_NAME_WRITE_CANDIDATE";
    private static final String MODEL_NAME_WRITE_VALIDATION_MODE =
        "model-name-write-5303";
    private static final String PARAMETER_STRUCTURE_WRITE_VALIDATION_PROPERTY =
        "turboism.validation.editorParameterStructureWrite";
    private static final String PARAMETER_STRUCTURE_WRITE_VALIDATION_TOKEN =
        "EXACT_5303_PARAMETER_STRUCTURE_WRITE_CANDIDATE";
    private static final String PARAMETER_STRUCTURE_WRITE_VALIDATION_MODE =
        "parameter-structure-write-5303";
    private static final String PART_STRUCTURE_WRITE_VALIDATION_PROPERTY =
        "turboism.validation.editorPartStructureWrite";
    private static final String PART_STRUCTURE_WRITE_VALIDATION_TOKEN =
        "EXACT_5303_PART_STRUCTURE_WRITE_CANDIDATE";
    private static final String PART_STRUCTURE_WRITE_VALIDATION_MODE =
        "part-structure-write-5303";
    private static final String NATIVE_CONTROL_APPEARANCE_WRITE_VALIDATION_PROPERTY =
        "turboism.validation.editorNativeControlAppearanceWrite";
    private static final String NATIVE_CONTROL_APPEARANCE_WRITE_VALIDATION_TOKEN =
        "EXACT_5303_NATIVE_CONTROL_APPEARANCE_WRITE_CANDIDATE";
    private static final String NATIVE_CONTROL_APPEARANCE_WRITE_VALIDATION_MODE =
        "native-control-appearance-write-5303";
    private static final String CLIP_MASK_WRITE_VALIDATION_PROPERTY =
        "turboism.validation.editorClipMaskWrite";
    private static final String CLIP_MASK_WRITE_VALIDATION_TOKEN =
        "EXACT_5303_CLIP_MASK_WRITE_CANDIDATE";
    private static final String CLIP_MASK_WRITE_VALIDATION_MODE =
        "clip-mask-write-5303";
    private static final String ART_MESH_PARAMETER_BINDING_WRITE_VALIDATION_PROPERTY =
        "turboism.validation.editorArtMeshParameterBindingWrite";
    private static final String ART_MESH_PARAMETER_BINDING_WRITE_VALIDATION_TOKEN =
        "EXACT_5303_ART_MESH_PARAMETER_BINDING_WRITE_CANDIDATE";
    private static final String ART_MESH_PARAMETER_BINDING_WRITE_VALIDATION_MODE =
        "art-mesh-parameter-binding-write-5303";
    private static final String WARP_PARAMETER_BINDING_WRITE_VALIDATION_PROPERTY =
        "turboism.validation.editorWarpParameterBindingWrite";
    private static final String WARP_PARAMETER_BINDING_WRITE_VALIDATION_TOKEN =
        "EXACT_5303_WARP_PARAMETER_BINDING_WRITE_CANDIDATE";
    private static final String WARP_PARAMETER_BINDING_WRITE_VALIDATION_MODE =
        "warp-parameter-binding-write-5303";
    private static final String ROTATION_PARAMETER_BINDING_WRITE_VALIDATION_PROPERTY =
        "turboism.validation.editorRotationParameterBindingWrite";
    private static final String ROTATION_PARAMETER_BINDING_WRITE_VALIDATION_TOKEN =
        "EXACT_5303_ROTATION_PARAMETER_BINDING_WRITE_CANDIDATE";
    private static final String ROTATION_PARAMETER_BINDING_WRITE_VALIDATION_MODE =
        "rotation-parameter-binding-write-5303";
    private static final String PARAMETER_BINDING_INVERT_VALIDATION_PROPERTY =
        "turboism.validation.editorParameterBindingInvert";
    private static final String PARAMETER_BINDING_INVERT_VALIDATION_TOKEN =
        "EXACT_5303_PARAMETER_BINDING_INVERT_CANDIDATE";
    private static final String PARAMETER_BINDING_INVERT_VALIDATION_MODE =
        "parameter-binding-invert-5303";
    private static final String PARAMETER_BINDING_TRANSFER_VALIDATION_PROPERTY =
        "turboism.validation.editorParameterBindingTransfer";
    private static final String PARAMETER_BINDING_TRANSFER_VALIDATION_TOKEN =
        "EXACT_5303_PARAMETER_BINDING_TRANSFER_CANDIDATE";
    private static final String PARAMETER_BINDING_TRANSFER_VALIDATION_MODE =
        "parameter-binding-transfer-5303";
    private static final String PARAMETER_BINDING_CLAMPED_TRANSFER_VALIDATION_PROPERTY =
        "turboism.validation.editorParameterBindingClampedTransfer";
    private static final String PARAMETER_BINDING_CLAMPED_TRANSFER_VALIDATION_TOKEN =
        "EXACT_5303_PARAMETER_BINDING_CLAMPED_TRANSFER_CANDIDATE";
    private static final String PARAMETER_BINDING_CLAMPED_TRANSFER_VALIDATION_MODE =
        "parameter-binding-clamped-transfer-5303";
    private static final String PARAMETER_BINDING_MORPH_TRANSFER_VALIDATION_PROPERTY =
        "turboism.validation.editorParameterBindingMorphTransfer";
    private static final String PARAMETER_BINDING_MORPH_TRANSFER_VALIDATION_TOKEN =
        "EXACT_5303_PARAMETER_BINDING_MORPH_TRANSFER_CANDIDATE";
    private static final String PARAMETER_BINDING_MORPH_TRANSFER_VALIDATION_MODE =
        "parameter-binding-morph-transfer-5303";
    private static final String ART_MESH_GEOMETRY_WRITE_VALIDATION_PROPERTY =
        "turboism.validation.editorArtMeshGeometryWrite";
    private static final String ART_MESH_GEOMETRY_WRITE_VALIDATION_TOKEN =
        "EXACT_5303_ART_MESH_GEOMETRY_WRITE_CANDIDATE";
    private static final String ART_MESH_GEOMETRY_WRITE_VALIDATION_MODE =
        "art-mesh-geometry-write-5303";
    private static final String WARP_GRID_WRITE_VALIDATION_PROPERTY =
        "turboism.validation.editorWarpGridWrite";
    private static final String WARP_GRID_WRITE_VALIDATION_TOKEN =
        "EXACT_5303_WARP_GRID_WRITE_CANDIDATE";
    private static final String WARP_GRID_WRITE_VALIDATION_MODE =
        "warp-grid-write-5303";
    private static final String ROTATION_FORM_WRITE_VALIDATION_PROPERTY =
        "turboism.validation.editorRotationFormWrite";
    private static final String ROTATION_FORM_WRITE_VALIDATION_TOKEN =
        "EXACT_5303_ROTATION_FORM_WRITE_CANDIDATE";
    private static final String ROTATION_FORM_WRITE_VALIDATION_MODE =
        "rotation-form-write-5303";

    private final PinnedVerifiedResolverWorkflow workflow = new PinnedVerifiedResolverWorkflow();

    /**
     * Builds a resolver for the Editor model slice, but only after the
     * whole pinned chain checks out: the reviewed record must hash to the
     * trust-root value, its fields must match the manifest, the host artifact
     * must be byte-identical to the reviewed Cubism build, every selector must
     * verify statically, the loaded host classes must attest to that artifact,
     * and the artifact must be unchanged afterwards.
     *
     * @param reviewedRecord path to the reviewed verification record JSON
     * @param verifiedArtifact path to the host jar being admitted
     * @param hostClassLoader loader the verified members will be resolved
     *     against
     * @return a resolver limited to the aliases the manifest authorizes
     * @throws IOException if the record or artifact cannot be read
     * @throws IllegalArgumentException if any link in that chain fails, so an
     *     unrecognized or tampered host yields no resolver at all
     * @throws NullPointerException if any argument is {@code null}
     */
    public VerifiedMemberResolver create(
        final Path reviewedRecord,
        final Path verifiedArtifact,
        final ClassLoader hostClassLoader
    ) throws IOException {
        final HostArtifactDigest artifact = HostArtifactDigest.from(verifiedArtifact);
        return workflow.create(
            reviewedRecord,
            verifiedArtifact,
            hostClassLoader,
            EditorModelVerificationManifest.forArtifact(artifact),
            ReviewedHostArtifacts.CUBISM_5_3_03.equals(artifact)
                ? cubism5303RuntimeScope()
                : null
        );
    }

    private static PinnedVerifiedResolverWorkflow.RuntimeScope cubism5303RuntimeScope() {
        final String mode = System.getProperty("turboism.editorObjectValidation.mode");
        final String runId = System.getProperty("turboism.validation.runId");
        if (TextureAtlasVerificationManifest.admits5303ValidationCandidate()) {
            return EditorModelVerificationManifest.cubism5303TextureAtlasValidationScope();
        }
        if (admitsNativeControlAppearancePersistenceValidation(
            System.getProperty(NativeControlAppearancePersistenceVerificationManifest.PROPERTY),
            System.getProperty("turboism.validation.hostVersion"),
            mode,
            runId
        )) {
            return EditorModelVerificationManifest
                .cubism5303NativeControlAppearanceWriteValidationScope();
        }
        if (admitsEditLevelWriteValidation(
            System.getProperty(EDIT_LEVEL_WRITE_VALIDATION_PROPERTY), mode, runId
        )) {
            return EditorModelVerificationManifest.cubism5303EditLevelWriteValidationScope();
        }
        if (admitsParameterValueWriteValidation(
            System.getProperty(PARAMETER_VALUE_WRITE_VALIDATION_PROPERTY), mode, runId
        ) || admitsParameterLifecycleValidation(
            System.getProperty(PARAMETER_LIFECYCLE_VALIDATION_PROPERTY), mode, runId
        )) {
            return EditorModelVerificationManifest.cubism5303ParameterValueWriteValidationScope();
        }
        if (admitsAutoBackupValidation(
            System.getProperty(AUTO_BACKUP_VALIDATION_PROPERTY),
            System.getProperty(AUTO_BACKUP_VALIDATION_MODE_PROPERTY),
            runId
        )) {
            // The backup probe's only Editor-model write is the previously proven parameter-value
            // mutation used to make the fixture eligible for native auto-backup. Auto-backup itself
            // remains a separately pinned adapter slice.
            return EditorModelVerificationManifest.cubism5303AutoBackupValidationScope();
        }
        if (admitsDefaultKeyformLockWriteValidation(
            System.getProperty(DEFAULT_KEYFORM_LOCK_WRITE_VALIDATION_PROPERTY), mode, runId
        )) {
            return EditorModelVerificationManifest
                .cubism5303DefaultKeyformLockWriteValidationScope();
        }
        if (admitsParameterDefinitionWriteValidation(
            System.getProperty(PARAMETER_DEFINITION_WRITE_VALIDATION_PROPERTY), mode, runId
        )) {
            return EditorModelVerificationManifest
                .cubism5303ParameterDefinitionWriteValidationScope();
        }
        if (admitsParameterCombinedWriteValidation(
            System.getProperty(PARAMETER_COMBINED_WRITE_VALIDATION_PROPERTY), mode, runId
        )) {
            return EditorModelVerificationManifest
                .cubism5303ParameterCombinedWriteValidationScope();
        }
        if (admitsModelNameWriteValidation(
            System.getProperty(MODEL_NAME_WRITE_VALIDATION_PROPERTY), mode, runId
        )) {
            return EditorModelVerificationManifest.cubism5303ModelNameWriteValidationScope();
        }
        if (admitsParameterStructureWriteValidation(
            System.getProperty(PARAMETER_STRUCTURE_WRITE_VALIDATION_PROPERTY), mode, runId
        )) {
            return EditorModelVerificationManifest
                .cubism5303ParameterStructureWriteValidationScope();
        }
        if (admitsPartStructureWriteValidation(
            System.getProperty(PART_STRUCTURE_WRITE_VALIDATION_PROPERTY), mode, runId
        )) {
            return EditorModelVerificationManifest
                .cubism5303PartStructureWriteValidationScope();
        }
        if (admitsNativeControlAppearanceWriteValidation(
            System.getProperty(NATIVE_CONTROL_APPEARANCE_WRITE_VALIDATION_PROPERTY), mode, runId
        )) {
            return EditorModelVerificationManifest
                .cubism5303NativeControlAppearanceWriteValidationScope();
        }
        if (admitsClipMaskWriteValidation(
            System.getProperty(CLIP_MASK_WRITE_VALIDATION_PROPERTY), mode, runId
        )) {
            return EditorModelVerificationManifest.cubism5303ClipMaskWriteValidationScope();
        }
        if (admitsArtMeshParameterBindingWriteValidation(
            System.getProperty(ART_MESH_PARAMETER_BINDING_WRITE_VALIDATION_PROPERTY),
            mode,
            runId
        )) {
            return EditorModelVerificationManifest
                .cubism5303ArtMeshParameterBindingWriteValidationScope();
        }
        if (admitsWarpParameterBindingWriteValidation(
            System.getProperty(WARP_PARAMETER_BINDING_WRITE_VALIDATION_PROPERTY), mode, runId
        )) {
            return EditorModelVerificationManifest
                .cubism5303WarpParameterBindingWriteValidationScope();
        }
        if (admitsRotationParameterBindingWriteValidation(
            System.getProperty(ROTATION_PARAMETER_BINDING_WRITE_VALIDATION_PROPERTY),
            mode,
            runId
        )) {
            return EditorModelVerificationManifest
                .cubism5303RotationParameterBindingWriteValidationScope();
        }
        if (admitsParameterBindingInvertValidation(
            System.getProperty(PARAMETER_BINDING_INVERT_VALIDATION_PROPERTY), mode, runId
        )) {
            return EditorModelVerificationManifest
                .cubism5303ParameterBindingInvertValidationScope();
        }
        if (admitsParameterBindingTransferValidation(
            System.getProperty(PARAMETER_BINDING_TRANSFER_VALIDATION_PROPERTY), mode, runId
        )) {
            return EditorModelVerificationManifest
                .cubism5303ParameterBindingTransferValidationScope();
        }
        if (admitsParameterBindingClampedTransferValidation(
            System.getProperty(PARAMETER_BINDING_CLAMPED_TRANSFER_VALIDATION_PROPERTY),
            mode,
            runId
        )) {
            return EditorModelVerificationManifest
                .cubism5303ParameterBindingClampedTransferValidationScope();
        }
        if (admitsParameterBindingMorphTransferValidation(
            System.getProperty(PARAMETER_BINDING_MORPH_TRANSFER_VALIDATION_PROPERTY),
            mode,
            runId
        )) {
            return EditorModelVerificationManifest
                .cubism5303ParameterBindingMorphTransferValidationScope();
        }
        if (admitsArtMeshGeometryWriteValidation(
            System.getProperty(ART_MESH_GEOMETRY_WRITE_VALIDATION_PROPERTY), mode, runId
        )) {
            return EditorModelVerificationManifest.cubism5303ArtMeshGeometryWriteValidationScope();
        }
        if (admitsWarpGridWriteValidation(
            System.getProperty(WARP_GRID_WRITE_VALIDATION_PROPERTY), mode, runId
        )) {
            return EditorModelVerificationManifest.cubism5303WarpGridWriteValidationScope();
        }
        if (admitsRotationFormWriteValidation(
            System.getProperty(ROTATION_FORM_WRITE_VALIDATION_PROPERTY), mode, runId
        )) {
            return EditorModelVerificationManifest.cubism5303RotationFormWriteValidationScope();
        }
        return EditorModelVerificationManifest.cubism5303RuntimeScope();
    }

    static boolean admitsTextureAtlasValidation(
        final String token,
        final String hostVersion,
        final String mode,
        final String runId
    ) {
        return TextureAtlasVerificationManifest.admits5303ValidationCandidate(
            token, hostVersion, mode, runId
        );
    }

    static boolean admitsNativeControlAppearancePersistenceValidation(
        final String token,
        final String hostVersion,
        final String mode,
        final String runId
    ) {
        return NativeControlAppearancePersistenceVerificationManifest
            .admits5303ValidationCandidate(token, hostVersion, mode, runId);
    }

    static boolean admitsEditLevelWriteValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            EDIT_LEVEL_WRITE_VALIDATION_TOKEN,
            EDIT_LEVEL_WRITE_VALIDATION_MODE
        );
    }

    static boolean admitsParameterValueWriteValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            PARAMETER_VALUE_WRITE_VALIDATION_TOKEN,
            PARAMETER_VALUE_WRITE_VALIDATION_MODE
        );
    }

    static boolean admitsParameterLifecycleValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            PARAMETER_LIFECYCLE_VALIDATION_TOKEN,
            PARAMETER_LIFECYCLE_VALIDATION_MODE
        );
    }

    static boolean admitsAutoBackupValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            AUTO_BACKUP_VALIDATION_TOKEN,
            AUTO_BACKUP_VALIDATION_MODE
        );
    }

    static boolean admitsDefaultKeyformLockWriteValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            DEFAULT_KEYFORM_LOCK_WRITE_VALIDATION_TOKEN,
            DEFAULT_KEYFORM_LOCK_WRITE_VALIDATION_MODE
        );
    }

    static boolean admitsParameterDefinitionWriteValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            PARAMETER_DEFINITION_WRITE_VALIDATION_TOKEN,
            PARAMETER_DEFINITION_WRITE_VALIDATION_MODE
        );
    }

    static boolean admitsParameterCombinedWriteValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            PARAMETER_COMBINED_WRITE_VALIDATION_TOKEN,
            PARAMETER_COMBINED_WRITE_VALIDATION_MODE
        );
    }

    static boolean admitsModelNameWriteValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            MODEL_NAME_WRITE_VALIDATION_TOKEN,
            MODEL_NAME_WRITE_VALIDATION_MODE
        );
    }

    static boolean admitsParameterStructureWriteValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            PARAMETER_STRUCTURE_WRITE_VALIDATION_TOKEN,
            PARAMETER_STRUCTURE_WRITE_VALIDATION_MODE
        );
    }

    static boolean admitsPartStructureWriteValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            PART_STRUCTURE_WRITE_VALIDATION_TOKEN,
            PART_STRUCTURE_WRITE_VALIDATION_MODE
        );
    }

    static boolean admitsNativeControlAppearanceWriteValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            NATIVE_CONTROL_APPEARANCE_WRITE_VALIDATION_TOKEN,
            NATIVE_CONTROL_APPEARANCE_WRITE_VALIDATION_MODE
        );
    }

    static boolean admitsClipMaskWriteValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            CLIP_MASK_WRITE_VALIDATION_TOKEN,
            CLIP_MASK_WRITE_VALIDATION_MODE
        );
    }

    static boolean admitsArtMeshParameterBindingWriteValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            ART_MESH_PARAMETER_BINDING_WRITE_VALIDATION_TOKEN,
            ART_MESH_PARAMETER_BINDING_WRITE_VALIDATION_MODE
        );
    }

    static boolean admitsWarpParameterBindingWriteValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            WARP_PARAMETER_BINDING_WRITE_VALIDATION_TOKEN,
            WARP_PARAMETER_BINDING_WRITE_VALIDATION_MODE
        );
    }

    static boolean admitsRotationParameterBindingWriteValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            ROTATION_PARAMETER_BINDING_WRITE_VALIDATION_TOKEN,
            ROTATION_PARAMETER_BINDING_WRITE_VALIDATION_MODE
        );
    }

    static boolean admitsParameterBindingInvertValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            PARAMETER_BINDING_INVERT_VALIDATION_TOKEN,
            PARAMETER_BINDING_INVERT_VALIDATION_MODE
        );
    }

    static boolean admitsParameterBindingTransferValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            PARAMETER_BINDING_TRANSFER_VALIDATION_TOKEN,
            PARAMETER_BINDING_TRANSFER_VALIDATION_MODE
        );
    }

    static boolean admitsParameterBindingClampedTransferValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            PARAMETER_BINDING_CLAMPED_TRANSFER_VALIDATION_TOKEN,
            PARAMETER_BINDING_CLAMPED_TRANSFER_VALIDATION_MODE
        );
    }

    static boolean admitsParameterBindingMorphTransferValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            PARAMETER_BINDING_MORPH_TRANSFER_VALIDATION_TOKEN,
            PARAMETER_BINDING_MORPH_TRANSFER_VALIDATION_MODE
        );
    }

    static boolean admitsArtMeshGeometryWriteValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            ART_MESH_GEOMETRY_WRITE_VALIDATION_TOKEN,
            ART_MESH_GEOMETRY_WRITE_VALIDATION_MODE
        );
    }

    static boolean admitsWarpGridWriteValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            WARP_GRID_WRITE_VALIDATION_TOKEN,
            WARP_GRID_WRITE_VALIDATION_MODE
        );
    }

    static boolean admitsRotationFormWriteValidation(
        final String token,
        final String mode,
        final String runId
    ) {
        return admitsValidationCandidate(
            token,
            mode,
            runId,
            ROTATION_FORM_WRITE_VALIDATION_TOKEN,
            ROTATION_FORM_WRITE_VALIDATION_MODE
        );
    }

    private static boolean admitsValidationCandidate(
        final String token,
        final String mode,
        final String runId,
        final String expectedToken,
        final String expectedMode
    ) {
        return expectedToken.equals(token)
            && expectedMode.equals(mode)
            && runId != null
            && !runId.isBlank();
    }
}
