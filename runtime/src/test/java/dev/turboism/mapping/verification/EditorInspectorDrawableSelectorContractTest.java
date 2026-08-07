package dev.turboism.mapping.verification;

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

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path LEGACY_EVIDENCE = PROJECT_ROOT.resolve("../turboism-legacy/cubism-ref");

    @Test
    void exact5302RecordVerifiesTheCompleteInspectorDrawableWriteContract() throws Exception {
        final var resolver = new VerifiedEditorModelResolverFactory().create(
            PROJECT_ROOT.resolve("cubism-ref/verification/cubism-5.3.02-editor-model.json"),
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
            PROJECT_ROOT.resolve("cubism-ref/verification/cubism-5.2-editor-model.json"),
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

    private static Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !java.nio.file.Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("project root is unavailable");
        return current;
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
