package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.selector.EditorModelProfileSelectorContract;
import dev.turboism.mapping.verification.selector.EditorMorphTargetSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterStructureSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartStructureSelectorContract;
import dev.turboism.test.Stubs;
import java.net.URLClassLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EditorStructureSelectorContractTest {

    private static final Path PROJECT_ROOT = EditorSelectorContractTestPaths.projectRoot();
    private static final Path LEGACY_EVIDENCE = EditorSelectorContractTestPaths.resolveLegacyEvidence();

    @Test
    void exact5302RecordVerifiesPartStructureContract() throws Exception {
        assumeTrue(
                LEGACY_EVIDENCE != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");
        final Path artifact = LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar");
        final var resolver = SliceRegistry.EDITOR_MODEL
                .resolverFactory()
                .create(
                        PROJECT_ROOT.resolve("build/generated/verification/cubism-5.3.02-editor-model.json"),
                        artifact,
                        loader(artifact));
        assertTrue(resolver.authorizesFeature(
                EditorPartStructureSelectorContract.ADAPTER_SLICE_ID,
                EditorPartStructureSelectorContract.CAPABILITY_ID,
                EditorPartStructureSelectorContract.REQUIRED_ALIASES));
    }

    @Test
    void exact5302RecordVerifiesParameterStructureContract() throws Exception {
        assumeTrue(
                LEGACY_EVIDENCE != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");
        final Path artifact = LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar");
        final var resolver = SliceRegistry.EDITOR_MODEL
                .resolverFactory()
                .create(
                        PROJECT_ROOT.resolve("build/generated/verification/cubism-5.3.02-editor-model.json"),
                        artifact,
                        loader(artifact));
        assertTrue(resolver.authorizesFeature(
                EditorParameterStructureSelectorContract.ADAPTER_SLICE_ID,
                EditorParameterStructureSelectorContract.CAPABILITY_ID,
                EditorParameterStructureSelectorContract.REQUIRED_ALIASES));
    }

    @Test
    void exact5302RecordVerifiesMorphTargetReadAndWriteContracts() throws Exception {
        assumeTrue(
                LEGACY_EVIDENCE != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");
        final Path artifact = LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar");
        final var resolver = SliceRegistry.EDITOR_MODEL
                .resolverFactory()
                .create(
                        PROJECT_ROOT.resolve("build/generated/verification/cubism-5.3.02-editor-model.json"),
                        artifact,
                        loader(artifact));
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
        assumeTrue(
                LEGACY_EVIDENCE != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");
        final Path artifact = LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar");
        final var resolver = SliceRegistry.EDITOR_MODEL
                .resolverFactory()
                .create(
                        PROJECT_ROOT.resolve("build/generated/verification/cubism-5.3.02-editor-model.json"),
                        artifact,
                        loader(artifact));
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
        assumeTrue(
                LEGACY_EVIDENCE != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");
        final Path artifact = LEGACY_EVIDENCE.resolve("Cubism-5.2/jars/Live2D_Cubism.jar");
        final var resolver = SliceRegistry.EDITOR_MODEL
                .resolverFactory()
                .create(
                        PROJECT_ROOT.resolve("build/generated/verification/cubism-5.2.03-editor-model.json"),
                        artifact,
                        loader(artifact));
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

    private static URLClassLoader loader(final Path artifact) throws Exception {
        return Stubs.jarClassLoader(artifact);
    }
}
