package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorTextureSelectorContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorTextureSelectorContractTest {

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path LEGACY_EVIDENCE = locateLegacyEvidence();

    @ParameterizedTest
    @CsvSource({
        "Cubism-5.2, cubism-5.2-editor-model.json",
        "Cubism-5.3.02, cubism-5.3.02-editor-model.json"
    })
    void exactRecordVerifiesTheTextureReadContract(
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
            EditorTextureSelectorContract.ADAPTER_SLICE_ID,
            EditorTextureSelectorContract.READ_CAPABILITY_ID,
            EditorTextureSelectorContract.READ_REQUIRED_ALIASES
        ));
        assertTrue(resolver.authorizesFeature(
            EditorTextureSelectorContract.ADAPTER_SLICE_ID,
            EditorTextureSelectorContract.WRITE_CAPABILITY_ID,
            EditorTextureSelectorContract.WRITE_REQUIRED_ALIASES
        ));
        if (evidenceDirectory.equals("Cubism-5.3.02")) {
            assertTrue(resolver.authorizesFeature(
                EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                EditorTextureSelectorContract.WRITE_CAPABILITY_ID,
                EditorTextureSelectorContract.REMOVE_RAW_IMAGE_ALIASES
            ));
        } else {
            // 5.2.03 exposes only a confirmation-dialog raw-image removal path; the
            // selector is absent from the record and must not be required.
            assertTrue(!resolver.authorizesFeature(
                EditorTextureSelectorContract.ADAPTER_SLICE_ID,
                EditorTextureSelectorContract.WRITE_CAPABILITY_ID,
                EditorTextureSelectorContract.REMOVE_RAW_IMAGE_ALIASES
            ), "5.2 record must not authorize the 5.3.02-only raw image removal path");
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
