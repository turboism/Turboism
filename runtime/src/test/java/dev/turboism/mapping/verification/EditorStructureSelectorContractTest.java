package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.selector.EditorModelProfileSelectorContract;
import dev.turboism.mapping.verification.selector.EditorMorphTargetSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterStructureSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartStructureSelectorContract;
import org.junit.jupiter.api.Test;

class EditorStructureSelectorContractTest {

    @Test
    void exact5302RecordVerifiesPartStructureContract() throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver("5.3.02");
        assertTrue(resolver.authorizesFeature(
                EditorPartStructureSelectorContract.ADAPTER_SLICE_ID,
                EditorPartStructureSelectorContract.CAPABILITY_ID,
                EditorPartStructureSelectorContract.REQUIRED_ALIASES));
    }

    @Test
    void exact5302RecordVerifiesParameterStructureContract() throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver("5.3.02");
        assertTrue(resolver.authorizesFeature(
                EditorParameterStructureSelectorContract.ADAPTER_SLICE_ID,
                EditorParameterStructureSelectorContract.CAPABILITY_ID,
                EditorParameterStructureSelectorContract.REQUIRED_ALIASES));
    }

    @Test
    void exact5302RecordVerifiesMorphTargetReadAndWriteContracts() throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver("5.3.02");
        assertTrue(resolver.authorizesFeature(
                EditorMorphTargetSelectorContract.ADAPTER_SLICE_ID,
                EditorMorphTargetSelectorContract.READ_CAPABILITY_ID,
                EditorMorphTargetSelectorContract.READ_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorMorphTargetSelectorContract.ADAPTER_SLICE_ID,
                EditorMorphTargetSelectorContract.WRITE_CAPABILITY_ID,
                EditorMorphTargetSelectorContract.WRITE_REQUIRED_ALIASES));
    }

    @Test
    void exact5302RecordVerifiesModelProfileContracts() throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver("5.3.02");
        assertTrue(resolver.authorizesFeature(
                EditorModelProfileSelectorContract.ADAPTER_SLICE_ID,
                EditorModelProfileSelectorContract.NAME_WRITE_CAPABILITY_ID,
                EditorModelProfileSelectorContract.NAME_WRITE_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorModelProfileSelectorContract.ADAPTER_SLICE_ID,
                EditorModelProfileSelectorContract.PROFILE_READ_CAPABILITY_ID,
                EditorModelProfileSelectorContract.PROFILE_READ_REQUIRED_ALIASES));
    }

    @Test
    void exact5203RecordVerifiesAllNewStructureContracts() throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver("5.2.03");
        assertTrue(resolver.authorizesFeature(
                EditorPartStructureSelectorContract.ADAPTER_SLICE_ID,
                EditorPartStructureSelectorContract.CAPABILITY_ID,
                EditorPartStructureSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorParameterStructureSelectorContract.ADAPTER_SLICE_ID,
                EditorParameterStructureSelectorContract.CAPABILITY_ID,
                EditorParameterStructureSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorMorphTargetSelectorContract.ADAPTER_SLICE_ID,
                EditorMorphTargetSelectorContract.READ_CAPABILITY_ID,
                EditorMorphTargetSelectorContract.READ_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorMorphTargetSelectorContract.ADAPTER_SLICE_ID,
                EditorMorphTargetSelectorContract.WRITE_CAPABILITY_ID,
                EditorMorphTargetSelectorContract.WRITE_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorModelProfileSelectorContract.ADAPTER_SLICE_ID,
                EditorModelProfileSelectorContract.NAME_WRITE_CAPABILITY_ID,
                EditorModelProfileSelectorContract.NAME_WRITE_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorModelProfileSelectorContract.ADAPTER_SLICE_ID,
                EditorModelProfileSelectorContract.PROFILE_READ_CAPABILITY_ID,
                EditorModelProfileSelectorContract.PROFILE_READ_REQUIRED_ALIASES));
    }
}
