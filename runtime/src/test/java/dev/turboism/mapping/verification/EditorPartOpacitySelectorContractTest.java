package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorPartOpacity52SelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartOpacitySelectorContract;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorPartOpacitySelectorContractTest {

    private static final Path PROJECT_ROOT = EditorSelectorContractTestPaths.projectRoot();
    private static final Path LEGACY_EVIDENCE = EditorSelectorContractTestPaths.legacyEvidence();

    @Test
    void exact5302RecordVerifiesTheCompletePartOpacityContract() throws Exception {
        final var resolver = new VerifiedEditorModelResolverFactory().create(
            PROJECT_ROOT.resolve("compatibility/cubism/verification/cubism-5.3.02-editor-model.json"),
            LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar"),
            loader(LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar"))
        );

        assertTrue(resolver.authorizesFeature(
            EditorPartOpacitySelectorContract.ADAPTER_SLICE_ID,
            EditorPartOpacitySelectorContract.CAPABILITY_ID,
            EditorPartOpacitySelectorContract.REQUIRED_ALIASES
        ));
    }

    @Test
    void exact5203RecordVerifiesEvaluationReadButNotAuthoringWrite() throws Exception {
        final Path artifact = LEGACY_EVIDENCE.resolve("Cubism-5.2/jars/Live2D_Cubism.jar");
        final var resolver = new VerifiedEditorModelResolverFactory().create(
            PROJECT_ROOT.resolve("compatibility/cubism/verification/cubism-5.2.03-editor-model.json"),
            artifact,
            loader(artifact)
        );

        assertTrue(resolver.authorizesFeature(
            EditorPartOpacity52SelectorContract.ADAPTER_SLICE_ID,
            EditorPartOpacity52SelectorContract.CAPABILITY_ID,
            EditorPartOpacity52SelectorContract.REQUIRED_ALIASES
        ));
        assertFalse(resolver.authorizesFeature(
            EditorPartOpacitySelectorContract.ADAPTER_SLICE_ID,
            EditorPartOpacitySelectorContract.CAPABILITY_ID,
            EditorPartOpacitySelectorContract.REQUIRED_ALIASES
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
