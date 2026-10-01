package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VerifiedCorePublicApiResolverFactoryTest {

    private static final Path PROJECT_ROOT = EditorSelectorContractTestPaths.projectRoot();
    private final VerifiedCorePublicApiResolverFactory factory = new VerifiedCorePublicApiResolverFactory();

    @Test
    void admitsPinnedCoreArtifactsForAllSupportedProfiles() throws Exception {
        assertAdmitted("5.1.03", "5.1.03");
        assertAdmitted("5.2.03", "5.2.03");
        assertAdmitted("5.3.02", "5.3.02");
        assertAdmitted("5.3.04", "5.3.04");
    }

    @Test
    void rejectsArtifactForTheWrongProfile() throws Exception {
        Path artifact = coreArtifact("5.3.02");
        try (URLClassLoader loader = loader(artifact)) {
            assertThrows(
                    IllegalArgumentException.class, () -> factory.create("5.2.03", record("5.2.03"), artifact, loader));
        }
    }

    @Test
    void rejectsRuntimeClassesFromAnotherCoreArtifact() throws Exception {
        Path reviewed = coreArtifact("5.3.02");
        try (URLClassLoader wrongLoader = loader(coreArtifact("5.2.03"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> factory.create("5.3.02", record("5.3.02"), reviewed, wrongLoader));
        }
    }

    @Test
    void rejectsNewCoreUnderOldProfileAndOldRuntimeClassesUnderNewProfile() throws Exception {
        Path artifact = coreArtifact("5.3.04");
        try (URLClassLoader loader = loader(artifact);
                URLClassLoader oldLoader = loader(coreArtifact("5.3.02"))) {
            assertThrows(
                    IllegalArgumentException.class, () -> factory.create("5.3.02", record("5.3.02"), artifact, loader));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> factory.create("5.3.04", record("5.3.04"), artifact, oldLoader));
        }
    }

    @Test
    void rejectsUnknownProfileBeforeReadingEvidence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(
                        "5.4",
                        Path.of("missing-record"),
                        Path.of("missing-artifact"),
                        ClassLoader.getPlatformClassLoader()));
    }

    private void assertAdmitted(final String profile, final String exactVersion) throws Exception {
        Path artifact = coreArtifact(profile);
        assertTrue(Files.isRegularFile(artifact), "missing local Core evidence artifact " + artifact);
        assertEquals(profile, factory.profileForArtifact(artifact));
        try (URLClassLoader loader = loader(artifact)) {
            VerifiedMemberResolver resolver = factory.create(profile, record(profile), artifact, loader);
            assertEquals(exactVersion, resolver.cubismVersion());
            assertTrue(resolver.isExactCubismVersion(exactVersion));
            assertTrue(resolver.authorizes(
                    "adapter.core-model.readonly",
                    dev.turboism.mapping.verification.selector.CorePublicApiSelectorContract.capabilityIdsFor(profile)
                            .orElseThrow(),
                    dev.turboism.mapping.verification.selector.CorePublicApiSelectorContract.requiredAliasesFor(profile)
                            .orElseThrow()));
            if ("5.1.03".equals(profile)) {
                org.junit.jupiter.api.Assertions.assertFalse(resolver.authorizesFeature(
                        "adapter.core-model.readonly",
                        "cubism.geometry.read",
                        dev.turboism.mapping.verification.selector.CorePublicApiSelectorContract.requiredAliasesFor(
                                        profile)
                                .orElseThrow()));
            }
        }
    }

    private static Path record(final String profile) {
        return PROJECT_ROOT.resolve(
                Path.of("compatibility", "cubism", "verification", "cubism-" + profile + "-core-model-read.json"));
    }

    private static Path coreArtifact(final String profile) {
        return EditorSelectorContractTestPaths.artifact(profile).resolveSibling("Live2DCubismCore.jar");
    }

    private static URLClassLoader loader(final Path artifact) throws Exception {
        return new URLClassLoader(new URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }
}
