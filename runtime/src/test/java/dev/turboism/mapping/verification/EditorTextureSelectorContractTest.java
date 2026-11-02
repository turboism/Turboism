package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.selector.EditorTextureSelectorContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EditorTextureSelectorContractTest {

    @ParameterizedTest
    @CsvSource({"Cubism-5.2, cubism-5.2.03-editor-model.json", "Cubism-5.3.02, cubism-5.3.02-editor-model.json"})
    void exactRecordVerifiesTheTextureReadContract(final String evidenceDirectory, final String recordName)
            throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver(recordName, evidenceDirectory);
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
}
