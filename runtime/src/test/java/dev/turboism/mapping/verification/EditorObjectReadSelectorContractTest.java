package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorObjectHierarchyEditSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectWriteSelectorContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorObjectReadSelectorContractTest {

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path LEGACY_EVIDENCE = locateLegacyEvidence();

    @ParameterizedTest
    @CsvSource({
        "Cubism-5.2, cubism-5.2-editor-model.json",
        "Cubism-5.3.02, cubism-5.3.02-editor-model.json"
    })
    void exactRecordVerifiesTheCompleteObjectReadContract(
        final String evidenceDirectory,
        final String recordName
    ) throws Exception {
        final Path artifact = LEGACY_EVIDENCE.resolve(evidenceDirectory + "/jars/Live2D_Cubism.jar");
        final var resolver = new VerifiedEditorModelResolverFactory().create(
            PROJECT_ROOT.resolve("cubism-ref/verification/" + recordName),
            artifact,
            loader(artifact)
        );

        assertTrue(resolver.authorizesFeature(
            EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectReadSelectorContract.CAPABILITY_ID,
            EditorObjectReadSelectorContract.REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectReadSelectorContract.STATISTICS_CAPABILITY_ID,
            EditorObjectReadSelectorContract.STATISTICS_ALIASES
        ));
        if (evidenceDirectory.equals("Cubism-5.3.02")) {
            assertTrue(resolver.authorizesFeature(
                EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
                EditorObjectReadSelectorContract.STATISTICS_CAPABILITY_ID,
                EditorObjectReadSelectorContract.OFFSCREEN_STATISTICS_ALIASES
            ));
        }
        assertTrue(resolver.authorizesFeature(
            EditorObjectWriteSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectWriteSelectorContract.ART_MESH_CAPABILITY_ID,
            EditorObjectWriteSelectorContract.ART_MESH_REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorObjectWriteSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectWriteSelectorContract.WARP_CAPABILITY_ID,
            EditorObjectWriteSelectorContract.WARP_REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorObjectWriteSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectWriteSelectorContract.ROTATION_CAPABILITY_ID,
            EditorObjectWriteSelectorContract.ROTATION_REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectHierarchyEditSelectorContract.CAPABILITY_ID,
            EditorObjectHierarchyEditSelectorContract.REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectHierarchyEditSelectorContract.RENAME_CAPABILITY_ID,
            EditorObjectHierarchyEditSelectorContract.RENAME_REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectHierarchyEditSelectorContract.ART_MESH_CREATE_CAPABILITY_ID,
            EditorObjectHierarchyEditSelectorContract.ART_MESH_CREATE_REQUIRED_ALIASES
        ));
        if (evidenceDirectory.equals("Cubism-5.3.02")) {
            assertTrue(resolver.authorizesFeature(
                EditorObjectWriteSelectorContract.ADAPTER_SLICE_ID,
                EditorObjectWriteSelectorContract.CLIP_MASK_CAPABILITY_ID,
                EditorObjectWriteSelectorContract.CLIP_MASK_REQUIRED_ALIASES
            ));
        }
    }

    private static Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("project root is unavailable");
        return current;
    }

    private static Path locateLegacyEvidence() {
        final String configured = System.getenv("TURBOISM_LEGACY_CUBISM_REF");
        if (configured != null && !configured.isBlank()) {
            final Path candidate = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(candidate)) return candidate;
            throw new IllegalStateException(
                "configured legacy Cubism evidence directory is unavailable: " + candidate
            );
        }
        Path current = PROJECT_ROOT;
        while (current != null) {
            final Path candidate = current.resolveSibling("turboism-legacy/cubism-ref");
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("legacy Cubism evidence directory is unavailable");
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
