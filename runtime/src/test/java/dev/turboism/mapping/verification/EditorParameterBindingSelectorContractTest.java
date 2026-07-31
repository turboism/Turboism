package dev.turboism.mapping.verification;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorParameterBindingSelectorContractTest {

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path LEGACY_EVIDENCE = locateLegacyEvidence();

    @ParameterizedTest
    @CsvSource({
        "Cubism-5.2, cubism-5.2-editor-model.json",
        "Cubism-5.3.02, cubism-5.3.02-editor-model.json"
    })
    void exactRecordVerifiesTheCompleteParameterBindingContract(
        final String artifactDirectory,
        final String recordName
    ) throws Exception {
        final Path artifact = LEGACY_EVIDENCE.resolve(artifactDirectory + "/jars/Live2D_Cubism.jar");
        try (URLClassLoader loader = loader(artifact)) {
            final VerifiedMemberResolver resolver = new VerifiedEditorModelResolverFactory().create(
                PROJECT_ROOT.resolve("docs/migration/verification/static/" + recordName),
                artifact,
                loader
            );
            assertTrue(resolver.authorizesFeature(
                EditorParameterBindingReadSelectorContract.ADAPTER_SLICE_ID,
                EditorParameterBindingReadSelectorContract.CAPABILITY_ID,
                EditorParameterBindingReadSelectorContract.REQUIRED_ALIASES
            ));
            for (String capability : java.util.List.of(
                EditorParameterBindingWriteSelectorContract.ART_MESH_CAPABILITY_ID,
                EditorParameterBindingWriteSelectorContract.WARP_CAPABILITY_ID,
                EditorParameterBindingWriteSelectorContract.ROTATION_CAPABILITY_ID
            )) {
                assertTrue(resolver.authorizesFeature(
                    EditorParameterBindingWriteSelectorContract.ADAPTER_SLICE_ID,
                    capability,
                    EditorParameterBindingWriteSelectorContract.REQUIRED_ALIASES
                ));
            }
            for (String capability : java.util.List.of(
                EditorParameterBindingBatchWriteSelectorContract.INVERT_CAPABILITY_ID,
                EditorParameterBindingBatchWriteSelectorContract.TRANSFER_CAPABILITY_ID
            )) {
                assertTrue(resolver.authorizesFeature(
                    EditorParameterBindingBatchWriteSelectorContract.ADAPTER_SLICE_ID,
                    capability,
                    EditorParameterBindingBatchWriteSelectorContract.REQUIRED_ALIASES
                ));
            }
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
