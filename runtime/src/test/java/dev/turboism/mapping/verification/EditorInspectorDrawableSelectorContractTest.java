package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWrite52SelectorContract;
import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWriteSelectorContract;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reviewed-record trust-root tests for the Inspector Drawable write contracts. */
class EditorInspectorDrawableSelectorContractTest {

    private static final Path PROJECT_ROOT = EditorSelectorContractTestPaths.projectRoot();
    private static final Path LEGACY_EVIDENCE = EditorSelectorContractTestPaths.legacyEvidence();

    @Test
    void exact5302RecordVerifiesTheCompleteInspectorDrawableWriteContract() throws Exception {
        final var resolver = new VerifiedEditorModelResolverFactory().create(
            PROJECT_ROOT.resolve("compatibility/cubism/verification/cubism-5.3.02-editor-model.json"),
            LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar"),
            loader(LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar"))
        );

        assertTrue(resolver.authorizesFeature(
            EditorInspectorDrawableWriteSelectorContract.ADAPTER_SLICE_ID,
            EditorInspectorDrawableWriteSelectorContract.CAPABILITY_ID,
            EditorInspectorDrawableWriteSelectorContract.REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorInspectorDrawableWrite52SelectorContract.ADAPTER_SLICE_ID,
            EditorInspectorDrawableWrite52SelectorContract.CAPABILITY_ID,
            EditorInspectorDrawableWrite52SelectorContract.REQUIRED_ALIASES
        ));
    }

    @Test
    void exact5203RecordVerifiesThe52InspectorDrawableWriteContractWithoutAlpha() throws Exception {
        final Path artifact = LEGACY_EVIDENCE.resolve("Cubism-5.2/jars/Live2D_Cubism.jar");
        final var resolver = new VerifiedEditorModelResolverFactory().create(
            PROJECT_ROOT.resolve("compatibility/cubism/verification/cubism-5.2.03-editor-model.json"),
            artifact,
            loader(artifact)
        );

        assertTrue(resolver.authorizesFeature(
            EditorInspectorDrawableWrite52SelectorContract.ADAPTER_SLICE_ID,
            EditorInspectorDrawableWrite52SelectorContract.CAPABILITY_ID,
            EditorInspectorDrawableWrite52SelectorContract.REQUIRED_ALIASES
        ));
        assertFalse(resolver.authorizesFeature(
            EditorInspectorDrawableWriteSelectorContract.ADAPTER_SLICE_ID,
            EditorInspectorDrawableWriteSelectorContract.CAPABILITY_ID,
            EditorInspectorDrawableWriteSelectorContract.REQUIRED_ALIASES
        ));
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

    private static void assertInstanceBindings(
        final String evidenceDirectory,
        final String recordName
    ) throws Exception {
        final Path artifact = LEGACY_EVIDENCE.resolve(evidenceDirectory + "/jars/Live2D_Cubism.jar");
        final var resolver = new VerifiedEditorModelResolverFactory().create(
            PROJECT_ROOT.resolve("compatibility/cubism/verification/" + recordName),
            artifact,
            loader(artifact)
        );
        final java.util.Set<String> instanceAliases = java.util.Set.of(
            "cubism.editor-model.drawable-source.set-id",
            "cubism.editor-model.deformer-source.set-id",
            "cubism.editor-model.glue-source.set-id",
            "cubism.editor-model.parameter-controllable-handler.create-undo-for-basic-setting"
        );
        for (final String alias : instanceAliases) {
            assertTrue(resolver.bind(alias) != null, alias + " must bind as an instance method");
        }
        // Kotlin default-arg bridges and other genuinely static call sites bind statically.
        // model-source.verify is the verify$default bridge (5-parameter static entry point
        // the host itself uses: CModelSource.verify$default(source, true, null, 2, null)).
        final java.util.Set<String> staticAliases = java.util.Set.of(
            "cubism.editor-model.model-source.verify",
            "cubism.editor-model.app-controller.instance"
        );
        for (final String alias : staticAliases) {
            assertTrue(resolver.bindStatic(alias) != null, alias + " must bind as a static method");
        }
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
