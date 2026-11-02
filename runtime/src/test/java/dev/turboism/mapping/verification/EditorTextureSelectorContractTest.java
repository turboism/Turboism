package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.selector.EditorTextureSelectorContract;
import dev.turboism.test.Stubs;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EditorTextureSelectorContractTest {

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path LEGACY_EVIDENCE = locateLegacyEvidence();

    @ParameterizedTest
    @CsvSource({"Cubism-5.2, cubism-5.2.03-editor-model.json", "Cubism-5.3.02, cubism-5.3.02-editor-model.json"})
    void exactRecordVerifiesTheTextureReadContract(final String evidenceDirectory, final String recordName)
            throws Exception {
        assumeTrue(
                LEGACY_EVIDENCE != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");
        final Path artifact = LEGACY_EVIDENCE.resolve(evidenceDirectory + "/jars/Live2D_Cubism.jar");
        final var resolver = SliceRegistry.EDITOR_MODEL
                .resolverFactory()
                .create(PROJECT_ROOT.resolve("build/generated/verification/" + recordName), artifact, loader(artifact));
        assertTrue(resolver.authorizesFeature(
                EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                EditorTextureSelectorContract.READ_CAPABILITY_ID,
                EditorTextureSelectorContract.READ_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                EditorTextureSelectorContract.WRITE_CAPABILITY_ID,
                EditorTextureSelectorContract.WRITE_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                EditorTextureSelectorContract.REGISTER_BIND_CAPABILITY_ID,
                EditorTextureSelectorContract.REGISTER_BIND_REQUIRED_ALIASES));
        if (evidenceDirectory.equals("Cubism-5.3.02")) {
            assertTrue(resolver.authorizesFeature(
                    EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                    EditorTextureSelectorContract.WRITE_CAPABILITY_ID,
                    EditorTextureSelectorContract.REMOVE_RAW_IMAGE_ALIASES));
        } else {
            // 5.2.03 exposes only a confirmation-dialog raw-image removal path; the
            // selector is absent from the record and must not be required.
            assertTrue(
                    !resolver.authorizesFeature(
                            EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                            EditorTextureSelectorContract.WRITE_CAPABILITY_ID,
                            EditorTextureSelectorContract.REMOVE_RAW_IMAGE_ALIASES),
                    "5.2 record must not authorize the 5.3.02-only raw image removal path");
        }
    }

    private static Path locateProjectRoot() {
        return Stubs.locateProjectRoot();
    }

    private static Path locateLegacyEvidence() {
        Path current = PROJECT_ROOT;
        while (current != null) {
            final Path candidate = current.resolveSibling("turboism-legacy/cubism-ref");
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        return null;
    }

    private static URLClassLoader loader(final Path artifact) throws Exception {
        return Stubs.jarClassLoader(artifact);
    }
}
