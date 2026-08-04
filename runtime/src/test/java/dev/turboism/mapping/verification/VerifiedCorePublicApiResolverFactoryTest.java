package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedCorePublicApiResolverFactoryTest {

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path LEGACY_EVIDENCE = PROJECT_ROOT.resolveSibling(
        "turboism-legacy"
    ).resolve("cubism-ref");
    private final VerifiedCorePublicApiResolverFactory factory =
        new VerifiedCorePublicApiResolverFactory();

    @Test
    void admitsPinnedCoreArtifactsForBothSupportedProfiles() throws Exception {
        assertAdmitted("5.2", "5.2.0");
        assertAdmitted("5.3.02", "5.3.2");
    }

    @Test
    void rejectsArtifactForTheWrongProfile() throws Exception {
        Path artifact = coreArtifact("5.3.02");
        try (URLClassLoader loader = loader(artifact)) {
            assertThrows(IllegalArgumentException.class, () -> factory.create(
                "5.2", record("5.2"), artifact, loader
            ));
        }
    }

    @Test
    void rejectsRuntimeClassesFromAnotherCoreArtifact() throws Exception {
        Path reviewed = coreArtifact("5.3.02");
        try (URLClassLoader wrongLoader = loader(coreArtifact("5.2"))) {
            assertThrows(IllegalArgumentException.class, () -> factory.create(
                "5.3.02", record("5.3.02"), reviewed, wrongLoader
            ));
        }
    }

    @Test
    void rejectsUnknownProfileBeforeReadingEvidence() {
        assertThrows(IllegalArgumentException.class, () -> factory.create(
            "5.4", Path.of("missing-record"), Path.of("missing-artifact"),
            ClassLoader.getPlatformClassLoader()
        ));
    }

    private void assertAdmitted(
        final String profile,
        final String exactVersion
    ) throws Exception {
        Path artifact = coreArtifact(profile);
        assertTrue(Files.isRegularFile(artifact), "missing local Core evidence artifact " + artifact);
        try (URLClassLoader loader = loader(artifact)) {
            VerifiedMemberResolver resolver = factory.create(
                profile, record(profile), artifact, loader
            );
            assertEquals(exactVersion, resolver.cubismVersion());
            assertTrue(resolver.isExactCubismVersion(exactVersion));
            assertTrue(resolver.authorizes(
                "adapter.core-model.readonly",
                java.util.Set.of("cubism.geometry.read"),
                dev.turboism.mapping.verification.CorePublicApiSelectorContract
                    .requiredAliasesFor(profile)
                    .orElseThrow()
            ));
        }
    }

    private static Path record(final String profile) {
        return PROJECT_ROOT.resolve(Path.of(
            "cubism-ref", "verification",
            "cubism-" + profile + "-core-model-read.json"
        ));
    }

    private static Path coreArtifact(final String profile) {
        return LEGACY_EVIDENCE.resolve(
            "Cubism-" + profile + "/jars/Live2DCubismCore.jar"
        );
    }

    private static URLClassLoader loader(final Path artifact) throws Exception {
        return new URLClassLoader(
            new URL[]{artifact.toUri().toURL()},
            ClassLoader.getPlatformClassLoader()
        );
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
}
