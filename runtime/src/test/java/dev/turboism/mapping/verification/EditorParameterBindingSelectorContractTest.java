package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.selector.EditorParameterBindingBatchWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterBindingReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorParameterBindingWriteSelectorContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EditorParameterBindingSelectorContractTest {

    @ParameterizedTest
    @CsvSource({"Cubism-5.2, cubism-5.2.03-editor-model.json", "Cubism-5.3.02, cubism-5.3.02-editor-model.json"})
    void exactRecordVerifiesTheCompleteParameterBindingContract(final String artifactDirectory, final String recordName)
            throws Exception {
        final VerifiedMemberResolver resolver =
                EditorSelectorContractTestPaths.editorModelResolver(recordName, artifactDirectory);
        assertTrue(resolver.authorizesFeature(
                EditorParameterBindingReadSelectorContract.ADAPTER_SLICE_ID,
                EditorParameterBindingReadSelectorContract.CAPABILITY_ID,
                EditorParameterBindingReadSelectorContract.REQUIRED_ALIASES));
        for (String capability : java.util.List.of(
                EditorParameterBindingWriteSelectorContract.ART_MESH_CAPABILITY_ID,
                EditorParameterBindingWriteSelectorContract.WARP_CAPABILITY_ID,
                EditorParameterBindingWriteSelectorContract.ROTATION_CAPABILITY_ID)) {
            assertTrue(resolver.authorizesFeature(
                    EditorParameterBindingWriteSelectorContract.ADAPTER_SLICE_ID,
                    capability,
                    EditorParameterBindingWriteSelectorContract.REQUIRED_ALIASES));
        }
        for (String capability : java.util.List.of(
                EditorParameterBindingBatchWriteSelectorContract.INVERT_CAPABILITY_ID,
                EditorParameterBindingBatchWriteSelectorContract.TRANSFER_CAPABILITY_ID)) {
            assertTrue(resolver.authorizesFeature(
                    EditorParameterBindingBatchWriteSelectorContract.ADAPTER_SLICE_ID,
                    capability,
                    EditorParameterBindingBatchWriteSelectorContract.REQUIRED_ALIASES));
        }
    }
}
