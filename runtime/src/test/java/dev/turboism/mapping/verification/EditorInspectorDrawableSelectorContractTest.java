package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract;
import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWriteSelectorContract;
import org.junit.jupiter.api.Test;

/** Reviewed-record trust-root tests for the Inspector Drawable write contracts. */
class EditorInspectorDrawableSelectorContractTest {

    @Test
    void exact5302RecordVerifiesTheCompleteInspectorDrawableWriteContract() throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver("5.3.02");

        assertTrue(resolver.authorizesFeature(
                EditorInspectorDrawableWriteSelectorContract.ADAPTER_SLICE_ID,
                EditorInspectorDrawableWriteSelectorContract.CAPABILITY_ID,
                EditorInspectorDrawableWriteSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract.ADAPTER_SLICE_ID,
                EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract.CAPABILITY_ID,
                EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract.REQUIRED_ALIASES));
    }

    @Test
    void exact5203RecordVerifiesThe52InspectorDrawableWriteContractWithoutAlpha() throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver("5.2.03");

        assertTrue(resolver.authorizesFeature(
                EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract.ADAPTER_SLICE_ID,
                EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract.CAPABILITY_ID,
                EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract.REQUIRED_ALIASES));
        assertFalse(resolver.authorizesFeature(
                EditorInspectorDrawableWriteSelectorContract.ADAPTER_SLICE_ID,
                EditorInspectorDrawableWriteSelectorContract.CAPABILITY_ID,
                EditorInspectorDrawableWriteSelectorContract.REQUIRED_ALIASES));
    }

    @Test
    void setIdAliasesBindAsInstanceMethodsOnBothReviewedRecords() throws Exception {
        // Regression guard for the r2 real-host failure
        // ("Verified alias is not an instance method."): every setId alias in the
        // inspector write family must declare static access as forbidden so the
        // instance-method call site resolves instead of failing closed.
        assertInstanceBindings("Cubism-5.3.02", "cubism-5.3.02-editor-model.json");
        assertInstanceBindings("Cubism-5.2", "cubism-5.2.03-editor-model.json");
    }

    private static void assertInstanceBindings(final String evidenceDirectory, final String recordName)
            throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver(recordName, evidenceDirectory);
        final java.util.Set<String> instanceAliases = java.util.Set.of(
                "cubism.editor-model.drawable-source.set-id",
                "cubism.editor-model.deformer-source.set-id",
                "cubism.editor-model.glue-source.set-id",
                "cubism.editor-model.parameter-controllable-handler.create-undo-for-basic-setting");
        for (final String alias : instanceAliases) {
            assertTrue(resolver.bind(alias) != null, alias + " must bind as an instance method");
        }
        // Kotlin default-arg bridges and other genuinely static call sites bind statically.
        // model-source.verify is the verify$default bridge (5-parameter static entry point
        // the host itself uses: CModelSource.verify$default(source, true, null, 2, null)).
        final java.util.Set<String> staticAliases = java.util.Set.of(
                "cubism.editor-model.model-source.verify", "cubism.editor-model.app-controller.instance");
        for (final String alias : staticAliases) {
            assertTrue(resolver.bindStatic(alias) != null, alias + " must bind as a static method");
        }
    }
}
