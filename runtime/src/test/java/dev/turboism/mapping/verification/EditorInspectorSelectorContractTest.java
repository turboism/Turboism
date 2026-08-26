package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorDeformerInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorGlueInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartInspector52SelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartInspectorSelectorContract;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Wave 3 Inspector family contracts (Deformer / Part / Glue
 * writes) against the exact reviewed host JARs, both 5.3.02 and 5.2.
 */
class EditorInspectorSelectorContractTest {

    private static final Path PROJECT_ROOT = EditorSelectorContractTestPaths.projectRoot();
    private static final Path LEGACY_EVIDENCE = EditorSelectorContractTestPaths.legacyEvidence();

    @Test
    void exact5302RecordVerifiesDeformerPartAndGlueInspectorContracts() throws Exception {
        final Path artifact = LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar");
        final var resolver = new VerifiedEditorModelResolverFactory().create(
            PROJECT_ROOT.resolve("compatibility/cubism/verification/cubism-5.3.02-editor-model.json"),
            artifact,
            loader(artifact)
        );

        assertTrue(resolver.authorizesFeature(
            EditorDeformerInspectorSelectorContract.ADAPTER_SLICE_ID,
            EditorDeformerInspectorSelectorContract.CAPABILITY_ID,
            EditorDeformerInspectorSelectorContract.REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorPartInspectorSelectorContract.ADAPTER_SLICE_ID,
            EditorPartInspectorSelectorContract.CAPABILITY_ID,
            EditorPartInspectorSelectorContract.REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorGlueInspectorSelectorContract.ADAPTER_SLICE_ID,
            EditorGlueInspectorSelectorContract.CAPABILITY_ID,
            EditorGlueInspectorSelectorContract.REQUIRED_ALIASES
        ));
    }

    @Test
    void exact5203RecordVerifiesDeformerGlueAndPartIdButRejectsPartMaskAndAlpha() throws Exception {
        final Path artifact = LEGACY_EVIDENCE.resolve("Cubism-5.2/jars/Live2D_Cubism.jar");
        final var resolver = new VerifiedEditorModelResolverFactory().create(
            PROJECT_ROOT.resolve("compatibility/cubism/verification/cubism-5.2.03-editor-model.json"),
            artifact,
            loader(artifact)
        );

        // Deformer and Glue Inspector entries exist in both versions.
        assertTrue(resolver.authorizesFeature(
            EditorDeformerInspectorSelectorContract.ADAPTER_SLICE_ID,
            EditorDeformerInspectorSelectorContract.CAPABILITY_ID,
            EditorDeformerInspectorSelectorContract.REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorGlueInspectorSelectorContract.ADAPTER_SLICE_ID,
            EditorGlueInspectorSelectorContract.CAPABILITY_ID,
            EditorGlueInspectorSelectorContract.REQUIRED_ALIASES
        ));
        // Part id write is available on 5.2 ...
        assertTrue(resolver.authorizesFeature(
            EditorPartInspector52SelectorContract.ADAPTER_SLICE_ID,
            EditorPartInspector52SelectorContract.CAPABILITY_ID,
            EditorPartInspector52SelectorContract.REQUIRED_ALIASES
        ));
        // ... but clipping-mask and alpha-composition writes fail closed (no
        // 5.2 evidence: 52-src Parts_wrapperForInspector has no such entries).
        assertFalse(resolver.authorizesFeature(
            EditorPartInspectorSelectorContract.ADAPTER_SLICE_ID,
            EditorPartInspectorSelectorContract.CAPABILITY_ID,
            EditorPartInspectorSelectorContract.REQUIRED_ALIASES
        ));
    }

    private static URLClassLoader loader(final Path artifact) throws Exception {
        try (Stream<Path> files = Files.list(artifact.getParent())) {
            final URL[] classpath = files
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .sorted()
                .map(path -> {
                    try {
                        return path.toUri().toURL();
                    } catch (java.net.MalformedURLException exception) {
                        throw new IllegalArgumentException(exception);
                    }
                })
                .toArray(URL[]::new);
            return new URLClassLoader(classpath, ClassLoader.getPlatformClassLoader());
        }
    }
}
