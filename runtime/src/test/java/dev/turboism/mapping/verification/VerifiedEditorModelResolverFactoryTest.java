package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedEditorModelResolverFactoryTest {

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path LEGACY_EVIDENCE = locateLegacyEvidence();
    private final VerifiedEditorModelResolverFactory factory =
        new VerifiedEditorModelResolverFactory();

    @Test
    void admitsPinnedEditorArtifactsForBothSupportedProfiles() throws Exception {
        assertAdmitted("5.2", "5.2.0");
        assertAdmitted("5.3.02", "5.3.02");
    }

    @Test
    void rejectsRecordAndArtifactFromDifferentProfiles() throws Exception {
        Path artifact = editorArtifact("5.3.02");
        try (URLClassLoader loader = loader(artifact)) {
            assertThrows(IllegalArgumentException.class, () -> factory.create(
                record("5.2"), artifact, loader
            ));
        }
    }

    private void assertAdmitted(
        final String profile,
        final String exactVersion
    ) throws Exception {
        Path artifact = editorArtifact(profile);
        assertTrue(Files.isRegularFile(artifact), "missing local Editor evidence artifact " + artifact);
        try (URLClassLoader loader = loader(artifact)) {
            VerifiedMemberResolver resolver = factory.create(
                record(profile), artifact, loader
            );
            assertEquals(exactVersion, resolver.cubismVersion());
            assertTrue(resolver.isExactCubismVersion(exactVersion));
            assertTrue(resolver.authorizes(
                EditorModelVerificationManifest.ADAPTER_SLICE_ID,
                EditorModelVerificationManifest.CAPABILITY_IDS,
                EditorModelVerificationManifest.REQUIRED_ALIASES
            ));
        }
    }

    private static Path record(final String profile) {
        return PROJECT_ROOT.resolve(Path.of(
            "docs", "migration", "verification", "static",
            "cubism-" + profile + "-editor-model.json"
        ));
    }

    private static Path editorArtifact(final String profile) {
        return LEGACY_EVIDENCE.resolve(
            "Cubism-" + profile + "/jars/Live2D_Cubism.jar"
        );
    }

    private static URLClassLoader loader(final Path artifact) throws Exception {
        final Path directory = artifact.getParent();
        try (Stream<Path> files = Files.list(directory)) {
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

    private static Path locateProjectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("could not locate Turboism project root");
        }
        return current;
    }

    private static Path locateLegacyEvidence() {
        for (Path current = PROJECT_ROOT; current != null; current = current.getParent()) {
            final Path candidate = current.resolve("turboism-legacy/cubism-ref");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not locate local Cubism Editor evidence");
    }
}
