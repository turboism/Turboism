package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.selector.EditorObjectHierarchyEditSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectWriteSelectorContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EditorObjectReadSelectorContractTest {

    @ParameterizedTest
    @CsvSource({"Cubism-5.2, cubism-5.2.03-editor-model.json", "Cubism-5.3.02, cubism-5.3.02-editor-model.json"})
    void exactRecordVerifiesTheCompleteObjectReadContract(final String evidenceDirectory, final String recordName)
            throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver(recordName, evidenceDirectory);

        assertTrue(resolver.authorizesFeature(
                EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
                EditorObjectReadSelectorContract.CAPABILITY_ID,
                EditorObjectReadSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
                EditorObjectReadSelectorContract.STATISTICS_CAPABILITY_ID,
                EditorObjectReadSelectorContract.STATISTICS_ALIASES));
        if (evidenceDirectory.equals("Cubism-5.3.02")) {
            assertTrue(resolver.authorizesFeature(
                    EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
                    EditorObjectReadSelectorContract.STATISTICS_CAPABILITY_ID,
                    EditorObjectReadSelectorContract.OFFSCREEN_STATISTICS_ALIASES));
        }
        assertTrue(resolver.authorizesFeature(
                EditorObjectWriteSelectorContract.ADAPTER_SLICE_ID,
                EditorObjectWriteSelectorContract.ART_MESH_CAPABILITY_ID,
                EditorObjectWriteSelectorContract.ART_MESH_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorObjectWriteSelectorContract.ADAPTER_SLICE_ID,
                EditorObjectWriteSelectorContract.WARP_CAPABILITY_ID,
                EditorObjectWriteSelectorContract.WARP_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorObjectWriteSelectorContract.ADAPTER_SLICE_ID,
                EditorObjectWriteSelectorContract.ROTATION_CAPABILITY_ID,
                EditorObjectWriteSelectorContract.ROTATION_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
                EditorObjectHierarchyEditSelectorContract.CAPABILITY_ID,
                EditorObjectHierarchyEditSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
                EditorObjectHierarchyEditSelectorContract.RENAME_CAPABILITY_ID,
                EditorObjectHierarchyEditSelectorContract.RENAME_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
                EditorObjectHierarchyEditSelectorContract.ART_MESH_CREATE_CAPABILITY_ID,
                EditorObjectHierarchyEditSelectorContract.ART_MESH_CREATE_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
                EditorObjectHierarchyEditSelectorContract.CAPABILITY_ID,
                EditorObjectHierarchyEditSelectorContract.ART_MESH_SELECT_5302_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
                EditorObjectHierarchyEditSelectorContract.ART_MESH_CREATE_CAPABILITY_ID,
                EditorObjectHierarchyEditSelectorContract.ART_MESH_PART_CREATE_5302_REQUIRED_ALIASES));
        for (final String capability :
                dev.turboism.mapping.verification.selector.EditorArtMeshAuthoringReadSelectorContract.CAPABILITY_IDS) {
            assertTrue(resolver.authorizesFeature(
                    dev.turboism.mapping.verification.selector.EditorArtMeshAuthoringReadSelectorContract
                            .ADAPTER_SLICE_ID,
                    capability,
                    dev.turboism.mapping.verification.selector.EditorArtMeshAuthoringReadSelectorContract
                            .REQUIRED_ALIASES));
        }
        assertTrue(resolver.authorizesFeature(
                dev.turboism.mapping.verification.selector.EditorArtMeshKeyformSelectorContract.ADAPTER_SLICE_ID,
                dev.turboism.mapping.verification.selector.EditorArtMeshKeyformSelectorContract.CAPABILITY_ID,
                dev.turboism.mapping.verification.selector.EditorArtMeshKeyformSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                dev.turboism.mapping.verification.selector.EditorArtMeshEvaluationReadSelectorContract.ADAPTER_SLICE_ID,
                dev.turboism.mapping.verification.selector.EditorArtMeshEvaluationReadSelectorContract.CAPABILITY_ID,
                dev.turboism.mapping.verification.selector.EditorArtMeshEvaluationReadSelectorContract
                        .REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                dev.turboism.mapping.verification.selector.EditorArtMeshMorphSelectorContract.ADAPTER_SLICE_ID,
                dev.turboism.mapping.verification.selector.EditorArtMeshMorphSelectorContract.CAPABILITY_ID,
                dev.turboism.mapping.verification.selector.EditorArtMeshMorphSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                dev.turboism.mapping.verification.selector.EditorArtMeshMorphSelectorContract.ADAPTER_SLICE_ID,
                dev.turboism.mapping.verification.selector.EditorArtMeshMorphSelectorContract.CAPABILITY_ID,
                dev.turboism.mapping.verification.selector.EditorArtMeshMorphSelectorContract
                        .DRAW_ORDER_REQUIRED_ALIASES));
        if (evidenceDirectory.equals("Cubism-5.3.02")) {
            assertTrue(resolver.authorizesFeature(
                    EditorObjectWriteSelectorContract.ADAPTER_SLICE_ID,
                    EditorObjectWriteSelectorContract.CLIP_MASK_CAPABILITY_ID,
                    EditorObjectWriteSelectorContract.CLIP_MASK_REQUIRED_ALIASES));
        }
    }

    /** Dedicated exact-version entry: the ordinary test task excludes licensed evidence tests. */
    @org.junit.jupiter.api.Test
    void exact5302AuthoringSnapshotContract() throws Exception {
        exactRecordVerifiesTheCompleteObjectReadContract("Cubism-5.3.02", "cubism-5.3.02-editor-model.json");
    }
}
