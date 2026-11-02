package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.selector.EditorPartOpacityReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartOpacitySelectorContract;
import dev.turboism.test.Stubs;
import java.net.URLClassLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EditorPartOpacitySelectorContractTest {

    private static final Path PROJECT_ROOT = EditorSelectorContractTestPaths.projectRoot();
    private static final Path LEGACY_EVIDENCE = EditorSelectorContractTestPaths.resolveLegacyEvidence();

    @Test
    void exact5302RecordVerifiesTheCompletePartOpacityContract() throws Exception {
        assumeTrue(
                LEGACY_EVIDENCE != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");
        final var resolver = SliceRegistry.EDITOR_MODEL
                .resolverFactory()
                .create(
                        PROJECT_ROOT.resolve("build/generated/verification/cubism-5.3.02-editor-model.json"),
                        LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar"),
                        loader(LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar")));

        assertTrue(resolver.authorizesFeature(
                EditorPartOpacitySelectorContract.ADAPTER_SLICE_ID,
                EditorPartOpacitySelectorContract.CAPABILITY_ID,
                EditorPartOpacitySelectorContract.REQUIRED_ALIASES));
    }

    @Test
    void exact5203RecordVerifiesEvaluationReadButNotAuthoringWrite() throws Exception {
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
                EditorPartOpacityReadSelectorContract.ADAPTER_SLICE_ID,
                EditorPartOpacityReadSelectorContract.CAPABILITY_ID,
                EditorPartOpacityReadSelectorContract.REQUIRED_ALIASES));
        assertFalse(resolver.authorizesFeature(
                EditorPartOpacitySelectorContract.ADAPTER_SLICE_ID,
                EditorPartOpacitySelectorContract.CAPABILITY_ID,
                EditorPartOpacitySelectorContract.REQUIRED_ALIASES));
    }

    private static URLClassLoader loader(final Path artifact) throws Exception {
        return Stubs.jarClassLoader(artifact);
    }
}
