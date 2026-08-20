package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorAnimationReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAutoYureReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorModelInstanceReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPhysicsReadSelectorContract;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-record contract verification for the Editor read-only document families
 * (physics settings, auto-Yure evaluations, animation file contents) against the
 * reviewed real JAR artifacts.
 *
 * <p>This is the gate that reproduces the Wave 1 host BLOCKED state: the records
 * previously advertised none of the three read capabilities, so
 * {@code authorizesFeature} never returned {@code true} on the host.</p>
 */
class EditorDocumentReadSelectorContractTest {

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path LEGACY_EVIDENCE = PROJECT_ROOT.resolve("../turboism-legacy/cubism-ref");

    @Test
    void exact5302RecordVerifiesAllDocumentReadContracts() throws Exception {
        final Path artifact = LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar");
        final var resolver = new VerifiedEditorModelResolverFactory().create(
            PROJECT_ROOT.resolve("cubism-ref/verification/cubism-5.3.02-editor-model.json"),
            artifact,
            loader(artifact)
        );
        assertTrue(resolver.authorizesFeature(
            EditorPhysicsReadSelectorContract.ADAPTER_SLICE_ID,
            EditorPhysicsReadSelectorContract.CAPABILITY_ID,
            EditorPhysicsReadSelectorContract.REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorAutoYureReadSelectorContract.ADAPTER_SLICE_ID,
            EditorAutoYureReadSelectorContract.CAPABILITY_ID,
            EditorAutoYureReadSelectorContract.REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorAnimationReadSelectorContract.ADAPTER_SLICE_ID,
            EditorAnimationReadSelectorContract.CAPABILITY_ID,
            EditorAnimationReadSelectorContract.REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorModelInstanceReadSelectorContract.ADAPTER_SLICE_ID,
            EditorModelInstanceReadSelectorContract.CAPABILITY_ID,
            EditorModelInstanceReadSelectorContract.REQUIRED_ALIASES
        ));
    }

    @Test
    void exact5203RecordVerifiesAllDocumentReadContracts() throws Exception {
        final Path artifact = LEGACY_EVIDENCE.resolve("Cubism-5.2/jars/Live2D_Cubism.jar");
        final var resolver = new VerifiedEditorModelResolverFactory().create(
            PROJECT_ROOT.resolve("cubism-ref/verification/cubism-5.2.03-editor-model.json"),
            artifact,
            loader(artifact)
        );
        assertTrue(resolver.authorizesFeature(
            EditorPhysicsReadSelectorContract.ADAPTER_SLICE_ID,
            EditorPhysicsReadSelectorContract.CAPABILITY_ID,
            EditorPhysicsReadSelectorContract.REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorAutoYureReadSelectorContract.ADAPTER_SLICE_ID,
            EditorAutoYureReadSelectorContract.CAPABILITY_ID,
            EditorAutoYureReadSelectorContract.REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorAnimationReadSelectorContract.ADAPTER_SLICE_ID,
            EditorAnimationReadSelectorContract.CAPABILITY_ID,
            EditorAnimationReadSelectorContract.REQUIRED_ALIASES
        ));
        final java.util.HashSet<String> aliases52 = new java.util.HashSet<>(
            EditorModelInstanceReadSelectorContract.REQUIRED_ALIASES
        );
        aliases52.removeAll(EditorModelInstanceReadSelectorContract.ONION_SKIN_ALIASES);
        assertTrue(resolver.authorizesFeature(
            EditorModelInstanceReadSelectorContract.ADAPTER_SLICE_ID,
            EditorModelInstanceReadSelectorContract.CAPABILITY_ID,
            java.util.Set.copyOf(aliases52)
        ));
    }

    private static Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
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
