package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.selector.EditorTextureRelationsSelectorContract;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class EditorTextureRelationsSelectorContractTest {

    @Test
    void exact5302RecordVerifiesTheReadOnlyRelationContract() throws Exception {
        final Path projectRoot = locateProjectRoot();
        final Path legacyEvidence = locateLegacyEvidence();
        assumeTrue(
                legacyEvidence != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");
        final Path artifact = legacyEvidence.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar");
        final Path recordPath =
                projectRoot.resolve("compatibility/cubism/verification/cubism-5.3.02-editor-model.json");
        final var loaded = new StaticVerificationRecordLoader().load(recordPath);
        final var report = new StaticSelectorVerifier()
                .verify(artifact, loaded.record().artifact(), loaded.record().selectors());
        assertTrue(
                report.allSelectorsVerified(),
                report.results().stream()
                        .filter(result -> result.status() != StaticVerificationStatus.VERIFIED_STATIC)
                        .map(result -> result.alias() + ": " + result.message())
                        .toList()
                        .toString());
        final var resolver = new VerifiedEditorModelResolverFactory().create(recordPath, artifact, loader(artifact));

        assertTrue(resolver.isExactCubismVersion(EditorTextureRelationsSelectorContract.SUPPORTED_CUBISM_VERSION));
        assertTrue(resolver.authorizesFeature(
                EditorTextureRelationsSelectorContract.ADAPTER_SLICE_ID,
                EditorTextureRelationsSelectorContract.CAPABILITY_ID,
                EditorTextureRelationsSelectorContract.REQUIRED_ALIASES));
    }

    @Test
    void relationContractIsNotAuthorizedForAReviewedVersionWithoutItsExactRecord() {
        final var resolver = TestVerifiedResolvers.create(
                "5.3.03",
                EditorTextureRelationsSelectorContract.ADAPTER_SLICE_ID,
                java.util.Set.of(EditorTextureRelationsSelectorContract.CAPABILITY_ID),
                java.util.List.of(StaticSelector.classSelector("fixture.class", "java/lang/Object")),
                getClass().getClassLoader());

        assertFalse(resolver.isExactCubismVersion(EditorTextureRelationsSelectorContract.SUPPORTED_CUBISM_VERSION));
    }

    private static URLClassLoader loader(final Path artifact) throws Exception {
        try (Stream<Path> files = Files.list(artifact.getParent())) {
            final URL[] classpath = files.filter(
                            path -> path.getFileName().toString().endsWith(".jar"))
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

    private static Path locateLegacyEvidence() {
        return EditorSelectorContractTestPaths.resolveLegacyEvidence();
    }

    private static Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("project root is unavailable");
        return current;
    }
}
