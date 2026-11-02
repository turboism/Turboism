package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.selector.EditorDeformerInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorGlueInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartInspectorIdWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartInspectorSelectorContract;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Wave 3 Inspector family contracts (Deformer / Part / Glue
 * writes) against the exact reviewed host JARs, both 5.3.02 and 5.2.
 */
class EditorInspectorSelectorContractTest {

    @Test
    void exact5302RecordVerifiesDeformerPartAndGlueInspectorContracts() throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver("5.3.02");

        assertTrue(resolver.authorizesFeature(
                EditorDeformerInspectorSelectorContract.ADAPTER_SLICE_ID,
                EditorDeformerInspectorSelectorContract.CAPABILITY_ID,
                EditorDeformerInspectorSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorPartInspectorSelectorContract.ADAPTER_SLICE_ID,
                EditorPartInspectorSelectorContract.CAPABILITY_ID,
                EditorPartInspectorSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorGlueInspectorSelectorContract.ADAPTER_SLICE_ID,
                EditorGlueInspectorSelectorContract.CAPABILITY_ID,
                EditorGlueInspectorSelectorContract.REQUIRED_ALIASES));
    }

    @Test
    void exact5203RecordVerifiesDeformerGlueAndPartIdButRejectsPartMaskAndAlpha() throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver("5.2.03");

        // Deformer and Glue Inspector entries exist in both versions.
        assertTrue(resolver.authorizesFeature(
                EditorDeformerInspectorSelectorContract.ADAPTER_SLICE_ID,
                EditorDeformerInspectorSelectorContract.CAPABILITY_ID,
                EditorDeformerInspectorSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorGlueInspectorSelectorContract.ADAPTER_SLICE_ID,
                EditorGlueInspectorSelectorContract.CAPABILITY_ID,
                EditorGlueInspectorSelectorContract.REQUIRED_ALIASES));
        // Part id write is available on 5.2 ...
        assertTrue(resolver.authorizesFeature(
                EditorPartInspectorIdWriteSelectorContract.ADAPTER_SLICE_ID,
                EditorPartInspectorIdWriteSelectorContract.CAPABILITY_ID,
                EditorPartInspectorIdWriteSelectorContract.REQUIRED_ALIASES));
        // ... but clipping-mask and alpha-composition writes fail closed (no
        // 5.2 evidence: 52-src Parts_wrapperForInspector has no such entries).
        assertFalse(resolver.authorizesFeature(
                EditorPartInspectorSelectorContract.ADAPTER_SLICE_ID,
                EditorPartInspectorSelectorContract.CAPABILITY_ID,
                EditorPartInspectorSelectorContract.REQUIRED_ALIASES));
    }
}
