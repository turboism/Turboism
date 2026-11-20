package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorDefaultKeyformLockWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorModelEditLevelWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorModelProfileSelectorContract;
import dev.turboism.mapping.verification.selector.EditorNativeControlAppearanceWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterBindingBatchWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterBindingWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterCombinedWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterDefinitionWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterStructureSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterValueWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartStructureSelectorContract;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Preserves the exact Editor-model validation scope independently of resolver construction. */
final class EditorModelRuntimeScope {
    private static final String MODE_PROPERTY = "turboism.editorObjectValidation.mode";
    private static final String RUN_ID_PROPERTY = "turboism.validation.runId";
    private static final String HOST_VERSION_PROPERTY = "turboism.validation.hostVersion";
    private static final String AUTO_BACKUP_VALIDATION_PROPERTY = "turboism.validation.autoBackup";
    private static final String AUTO_BACKUP_VALIDATION_TOKEN = "EXACT_5303_AUTOBACKUP_CANDIDATE";
    private static final String AUTO_BACKUP_VALIDATION_MODE_PROPERTY = "turboism.validation.autoBackup.mode";
    private static final String AUTO_BACKUP_VALIDATION_MODE = "matrix";
    private static final String SEMANTIC_HISTORY_VALIDATION_PROPERTY = "turboism.validation.editorSemanticHistory";
    private static final String SEMANTIC_HISTORY_VALIDATION_TOKEN = "EXACT_5303_SEMANTIC_HISTORY_CANDIDATE";
    private static final String SEMANTIC_HISTORY_VALIDATION_MODE = "semantic-history-5303";

    private record ValidationCandidate(
            String property, String token, String mode, Supplier<PinnedVerifiedResolverWorkflow.RuntimeScope> scope) {}

    private static final List<ValidationCandidate> CANDIDATES = List.of(
            candidate(
                    "turboism.validation.editorModelEditLevelWrite",
                    "EXACT_5303_EDIT_LEVEL_WRITE_CANDIDATE",
                    "edit-level-write-5303",
                    Set.of(EditorModelEditLevelWriteSelectorContract.CAPABILITY_ID),
                    EditorModelEditLevelWriteSelectorContract.REQUIRED_ALIASES),
            new ValidationCandidate(
                    SEMANTIC_HISTORY_VALIDATION_PROPERTY,
                    SEMANTIC_HISTORY_VALIDATION_TOKEN,
                    SEMANTIC_HISTORY_VALIDATION_MODE,
                    EditorModelVerificationManifest::cubism5303SemanticHistoryValidationScope),
            candidate(
                    "turboism.validation.editorParameterValueWrite",
                    "EXACT_5303_PARAMETER_VALUE_WRITE_CANDIDATE",
                    "parameter-value-write-5303",
                    Set.of(EditorParameterValueWriteSelectorContract.CAPABILITY_ID),
                    EditorParameterValueWriteSelectorContract.REQUIRED_ALIASES),
            candidate(
                    "turboism.validation.parameterLifecycle",
                    "EXACT_5303_PARAMETER_LIFECYCLE_HOOK_CANDIDATE",
                    "parameter-lifecycle-hook-5303",
                    Set.of(EditorParameterValueWriteSelectorContract.CAPABILITY_ID),
                    EditorParameterValueWriteSelectorContract.REQUIRED_ALIASES),
            candidate(
                    "turboism.validation.editorDefaultKeyformLockWrite",
                    "EXACT_5303_DEFAULT_KEYFORM_LOCK_WRITE_CANDIDATE",
                    "default-keyform-lock-write-5303",
                    Set.of(EditorDefaultKeyformLockWriteSelectorContract.CAPABILITY_ID),
                    EditorDefaultKeyformLockWriteSelectorContract.REQUIRED_ALIASES),
            candidate(
                    "turboism.validation.editorParameterDefinitionWrite",
                    "EXACT_5303_PARAMETER_DEFINITION_WRITE_CANDIDATE",
                    "parameter-definition-write-5303",
                    Set.of(EditorParameterDefinitionWriteSelectorContract.CAPABILITY_ID),
                    EditorParameterDefinitionWriteSelectorContract.REQUIRED_ALIASES),
            candidate(
                    "turboism.validation.editorParameterCombinedWrite",
                    "EXACT_5303_PARAMETER_COMBINED_WRITE_CANDIDATE",
                    "parameter-combined-write-5303",
                    Set.of(EditorParameterCombinedWriteSelectorContract.CAPABILITY_ID),
                    EditorParameterCombinedWriteSelectorContract.REQUIRED_ALIASES),
            candidate(
                    "turboism.validation.editorModelNameWrite",
                    "EXACT_5303_MODEL_NAME_WRITE_CANDIDATE",
                    "model-name-write-5303",
                    Set.of(EditorModelProfileSelectorContract.NAME_WRITE_CAPABILITY_ID),
                    EditorModelProfileSelectorContract.NAME_WRITE_REQUIRED_ALIASES),
            candidate(
                    "turboism.validation.editorParameterStructureWrite",
                    "EXACT_5303_PARAMETER_STRUCTURE_WRITE_CANDIDATE",
                    "parameter-structure-write-5303",
                    Set.of(EditorParameterStructureSelectorContract.CAPABILITY_ID),
                    EditorParameterStructureSelectorContract.FOLDER_CREATE_REQUIRED_ALIASES),
            candidate(
                    "turboism.validation.editorPartStructureWrite",
                    "EXACT_5303_PART_STRUCTURE_WRITE_CANDIDATE",
                    "part-structure-write-5303",
                    Set.of(EditorPartStructureSelectorContract.CAPABILITY_ID),
                    EditorPartStructureSelectorContract.ROOT_CREATE_REQUIRED_ALIASES),
            candidate(
                    "turboism.validation.editorNativeControlAppearanceWrite",
                    "EXACT_5303_NATIVE_CONTROL_APPEARANCE_WRITE_CANDIDATE",
                    "native-control-appearance-write-5303",
                    Set.of(EditorNativeControlAppearanceWriteSelectorContract.CAPABILITY_ID),
                    EditorNativeControlAppearanceWriteSelectorContract.REQUIRED_ALIASES),
            candidate(
                    "turboism.validation.editorClipMaskWrite",
                    "EXACT_5303_CLIP_MASK_WRITE_CANDIDATE",
                    "clip-mask-write-5303",
                    Set.of(EditorObjectWriteSelectorContract.CLIP_MASK_CAPABILITY_ID),
                    EditorObjectWriteSelectorContract.CLIP_MASK_REQUIRED_ALIASES),
            candidate(
                    "turboism.validation.editorArtMeshParameterBindingWrite",
                    "EXACT_5303_ART_MESH_PARAMETER_BINDING_WRITE_CANDIDATE",
                    "art-mesh-parameter-binding-write-5303",
                    Set.of(EditorParameterBindingWriteSelectorContract.ART_MESH_CAPABILITY_ID),
                    EditorParameterBindingWriteSelectorContract.REQUIRED_ALIASES),
            candidate(
                    "turboism.validation.editorWarpParameterBindingWrite",
                    "EXACT_5303_WARP_PARAMETER_BINDING_WRITE_CANDIDATE",
                    "warp-parameter-binding-write-5303",
                    Set.of(EditorParameterBindingWriteSelectorContract.WARP_CAPABILITY_ID),
                    EditorParameterBindingWriteSelectorContract.REQUIRED_ALIASES),
            candidate(
                    "turboism.validation.editorRotationParameterBindingWrite",
                    "EXACT_5303_ROTATION_PARAMETER_BINDING_WRITE_CANDIDATE",
                    "rotation-parameter-binding-write-5303",
                    Set.of(EditorParameterBindingWriteSelectorContract.ROTATION_CAPABILITY_ID),
                    EditorParameterBindingWriteSelectorContract.REQUIRED_ALIASES),
            candidate(
                    "turboism.validation.editorParameterBindingInvert",
                    "EXACT_5303_PARAMETER_BINDING_INVERT_CANDIDATE",
                    "parameter-binding-invert-5303",
                    Set.of(
                            EditorParameterBindingBatchWriteSelectorContract.INVERT_CAPABILITY_ID,
                            EditorParameterBindingWriteSelectorContract.ART_MESH_CAPABILITY_ID,
                            EditorParameterBindingWriteSelectorContract.WARP_CAPABILITY_ID,
                            EditorParameterBindingWriteSelectorContract.ROTATION_CAPABILITY_ID),
                    unionAll(
                            EditorParameterBindingBatchWriteSelectorContract.INVERT_REQUIRED_ALIASES,
                            EditorParameterBindingWriteSelectorContract.REQUIRED_ALIASES,
                            EditorParameterBindingBatchWriteSelectorContract.INVERT_VALIDATION_OBSERVATION_ALIASES)),
            candidate(
                    "turboism.validation.editorParameterBindingTransfer",
                    "EXACT_5303_PARAMETER_BINDING_TRANSFER_CANDIDATE",
                    "parameter-binding-transfer-5303",
                    Set.of(
                            EditorParameterBindingBatchWriteSelectorContract.TRANSFER_CAPABILITY_ID,
                            EditorParameterBindingWriteSelectorContract.ART_MESH_CAPABILITY_ID,
                            EditorParameterBindingWriteSelectorContract.WARP_CAPABILITY_ID,
                            EditorParameterBindingWriteSelectorContract.ROTATION_CAPABILITY_ID),
                    unionAll(
                            EditorParameterBindingBatchWriteSelectorContract.TRANSFER_REQUIRED_ALIASES,
                            EditorParameterBindingWriteSelectorContract.REQUIRED_ALIASES,
                            EditorParameterBindingBatchWriteSelectorContract
                                    .TRANSFER_VALIDATION_SETUP_REQUIRED_ALIASES)),
            candidate(
                    "turboism.validation.editorParameterBindingClampedTransfer",
                    "EXACT_5303_PARAMETER_BINDING_CLAMPED_TRANSFER_CANDIDATE",
                    "parameter-binding-clamped-transfer-5303",
                    Set.of(
                            EditorParameterBindingBatchWriteSelectorContract.TRANSFER_CAPABILITY_ID,
                            EditorParameterBindingWriteSelectorContract.ART_MESH_CAPABILITY_ID,
                            EditorParameterBindingWriteSelectorContract.WARP_CAPABILITY_ID,
                            EditorParameterBindingWriteSelectorContract.ROTATION_CAPABILITY_ID),
                    unionAll(
                            EditorParameterBindingBatchWriteSelectorContract.REQUIRED_ALIASES,
                            EditorParameterBindingWriteSelectorContract.REQUIRED_ALIASES,
                            EditorParameterBindingBatchWriteSelectorContract
                                    .CLAMPED_TRANSFER_VALIDATION_OBSERVATION_ALIASES)),
            candidate(
                    "turboism.validation.editorParameterBindingMorphTransfer",
                    "EXACT_5303_PARAMETER_BINDING_MORPH_TRANSFER_CANDIDATE",
                    "parameter-binding-morph-transfer-5303",
                    Set.of(
                            EditorParameterBindingBatchWriteSelectorContract.TRANSFER_CAPABILITY_ID,
                            EditorParameterStructureSelectorContract.CAPABILITY_ID),
                    unionAll(
                            EditorParameterBindingBatchWriteSelectorContract.MORPH_TRANSFER_REQUIRED_ALIASES,
                            EditorParameterBindingBatchWriteSelectorContract
                                    .MORPH_TRANSFER_VALIDATION_OBSERVATION_ALIASES,
                            EditorParameterStructureSelectorContract.PARAMETER_CREATE_REQUIRED_ALIASES)),
            candidate(
                    "turboism.validation.editorArtMeshGeometryWrite",
                    "EXACT_5303_ART_MESH_GEOMETRY_WRITE_CANDIDATE",
                    "art-mesh-geometry-write-5303",
                    Set.of(EditorObjectWriteSelectorContract.ART_MESH_CAPABILITY_ID),
                    EditorObjectWriteSelectorContract.ART_MESH_REQUIRED_ALIASES),
            candidate(
                    "turboism.validation.editorWarpGridWrite",
                    "EXACT_5303_WARP_GRID_WRITE_CANDIDATE",
                    "warp-grid-write-5303",
                    Set.of(EditorObjectWriteSelectorContract.WARP_CAPABILITY_ID),
                    EditorObjectWriteSelectorContract.WARP_REQUIRED_ALIASES),
            candidate(
                    "turboism.validation.editorRotationFormWrite",
                    "EXACT_5303_ROTATION_FORM_WRITE_CANDIDATE",
                    "rotation-form-write-5303",
                    Set.of(EditorObjectWriteSelectorContract.ROTATION_CAPABILITY_ID),
                    EditorObjectWriteSelectorContract.ROTATION_REQUIRED_ALIASES));

    private EditorModelRuntimeScope() {}

    static PinnedVerifiedResolverWorkflow.RuntimeScope runtimeScopeFor(final HostArtifactDigest artifact) {
        return ReviewedHostArtifacts.CUBISM_5_3_03.equals(artifact) ? cubism5303RuntimeScope() : null;
    }

    static PinnedVerifiedResolverWorkflow.RuntimeScope cubism5303RuntimeScope() {
        final String mode = System.getProperty(MODE_PROPERTY);
        final String runId = System.getProperty(RUN_ID_PROPERTY);
        if (TextureAtlasVerificationManifest.admits5303ValidationCandidate()) {
            return EditorModelVerificationManifest.cubism5303TextureAtlasValidationScope();
        }
        if (NativeControlAppearancePersistenceVerificationManifest.admits5303ValidationCandidate(
                System.getProperty(NativeControlAppearancePersistenceVerificationManifest.PROPERTY),
                System.getProperty(HOST_VERSION_PROPERTY),
                mode,
                runId)) {
            return EditorModelVerificationManifest.cubism5303ValidationScope(
                    Set.of(EditorNativeControlAppearanceWriteSelectorContract.CAPABILITY_ID),
                    EditorNativeControlAppearanceWriteSelectorContract.REQUIRED_ALIASES);
        }
        // Check auto-backup before the parameter-value candidate because auto-backup uses a
        // separate mode property while sharing the parameter-value write scope.
        if (admitsValidationCandidate(
                System.getProperty(AUTO_BACKUP_VALIDATION_PROPERTY),
                System.getProperty(AUTO_BACKUP_VALIDATION_MODE_PROPERTY),
                runId,
                AUTO_BACKUP_VALIDATION_TOKEN,
                AUTO_BACKUP_VALIDATION_MODE)) {
            return EditorModelVerificationManifest.cubism5303ValidationScope(
                    Set.of(EditorParameterValueWriteSelectorContract.CAPABILITY_ID),
                    EditorParameterValueWriteSelectorContract.REQUIRED_ALIASES);
        }
        for (final ValidationCandidate entry : CANDIDATES) {
            if (admitsValidationCandidate(
                    System.getProperty(entry.property()), mode, runId, entry.token(), entry.mode())) {
                return entry.scope().get();
            }
        }
        return EditorModelVerificationManifest.cubism5303RuntimeScope();
    }

    static boolean admitsTextureAtlasValidation(
            final String token, final String hostVersion, final String mode, final String runId) {
        return TextureAtlasVerificationManifest.admits5303ValidationCandidate(token, hostVersion, mode, runId);
    }

    static boolean admitsSemanticHistoryValidation(final String token, final String mode, final String runId) {
        return admitsValidationCandidate(
                token, mode, runId, SEMANTIC_HISTORY_VALIDATION_TOKEN, SEMANTIC_HISTORY_VALIDATION_MODE);
    }

    private static ValidationCandidate candidate(
            final String property,
            final String token,
            final String mode,
            final Set<String> extraCapabilities,
            final Set<String> extraAliases) {
        return new ValidationCandidate(
                property,
                token,
                mode,
                () -> EditorModelVerificationManifest.cubism5303ValidationScope(extraCapabilities, extraAliases));
    }

    @SafeVarargs
    private static Set<String> unionAll(final Set<String>... sets) {
        final HashSet<String> merged = new HashSet<>();
        for (final Set<String> set : sets) {
            merged.addAll(set);
        }
        return Set.copyOf(merged);
    }

    private static boolean admitsValidationCandidate(
            final String token,
            final String mode,
            final String runId,
            final String expectedToken,
            final String expectedMode) {
        return expectedToken.equals(token) && expectedMode.equals(mode) && runId != null && !runId.isBlank();
    }
}
