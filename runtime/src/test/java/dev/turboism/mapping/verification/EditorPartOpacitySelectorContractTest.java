package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.selector.EditorPartOpacityReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartOpacitySelectorContract;
import org.junit.jupiter.api.Test;

class EditorPartOpacitySelectorContractTest {

    @Test
    void exact5302RecordVerifiesTheCompletePartOpacityContract() throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver("5.3.02");

        assertTrue(resolver.authorizesFeature(
                EditorPartOpacitySelectorContract.ADAPTER_SLICE_ID,
                EditorPartOpacitySelectorContract.CAPABILITY_ID,
                EditorPartOpacitySelectorContract.REQUIRED_ALIASES));
    }

    @Test
    void exact5203RecordVerifiesEvaluationReadButNotAuthoringWrite() throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver("5.2.03");

        assertTrue(resolver.authorizesFeature(
                EditorPartOpacityReadSelectorContract.ADAPTER_SLICE_ID,
                EditorPartOpacityReadSelectorContract.CAPABILITY_ID,
                EditorPartOpacityReadSelectorContract.REQUIRED_ALIASES));
        assertFalse(resolver.authorizesFeature(
                EditorPartOpacitySelectorContract.ADAPTER_SLICE_ID,
                EditorPartOpacitySelectorContract.CAPABILITY_ID,
                EditorPartOpacitySelectorContract.REQUIRED_ALIASES));
    }
}
