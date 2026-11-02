package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.selector.EditorTextureRelationsSelectorContract;
import dev.turboism.test.Stubs;
import java.net.URLClassLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditorTextureRelationsSelectorContractTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
            })
    void exactRecordsVerifyTheReadOnlyRelationContract(final String version) throws Exception {
        final Path projectRoot = locateProjectRoot();
        final Path artifact = EditorSelectorContractTestPaths.artifact(version);
        final Path recordPath =
                projectRoot.resolve("build/generated/verification/cubism-" + version + "-editor-model.json");
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
        try (final URLClassLoader loader = loader(artifact)) {
            final var resolver = SliceRegistry.EDITOR_MODEL.resolverFactory().create(recordPath, artifact, loader);
            assertTrue(EditorTextureRelationsSelectorContract.SUPPORTED_CUBISM_VERSIONS.contains(version));
            assertTrue(resolver.isExactCubismVersion(version));
            assertTrue(resolver.authorizesFeature(
                    EditorTextureRelationsSelectorContract.ADAPTER_SLICE_ID,
                    EditorTextureRelationsSelectorContract.CAPABILITY_ID,
                    EditorTextureRelationsSelectorContract.REQUIRED_ALIASES));
        }
    }

    @Test
    void relationContractIsNotAuthorizedForAReviewedVersionWithoutItsExactRecord() {
        final var resolver = TestVerifiedResolvers.create(
                "5.3.03",
                EditorTextureRelationsSelectorContract.ADAPTER_SLICE_ID,
                java.util.Set.of(EditorTextureRelationsSelectorContract.CAPABILITY_ID),
                java.util.List.of(StaticSelector.classSelector("fixture.class", "java/lang/Object")),
                getClass().getClassLoader());

        assertTrue(EditorTextureRelationsSelectorContract.SUPPORTED_CUBISM_VERSIONS.stream()
                .anyMatch(resolver::isExactCubismVersion));
        assertFalse(resolver.authorizesFeature(
                EditorTextureRelationsSelectorContract.ADAPTER_SLICE_ID,
                EditorTextureRelationsSelectorContract.CAPABILITY_ID,
                EditorTextureRelationsSelectorContract.REQUIRED_ALIASES));
    }

    private static URLClassLoader loader(final Path artifact) throws Exception {
        return Stubs.jarClassLoader(artifact);
    }

    private static Path locateProjectRoot() {
        return Stubs.locateProjectRoot();
    }
}
