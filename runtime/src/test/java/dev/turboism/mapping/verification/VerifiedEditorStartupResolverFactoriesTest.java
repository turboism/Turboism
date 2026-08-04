package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedEditorStartupResolverFactoriesTest {

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path LEGACY_EVIDENCE = locateLegacyEvidence();

    @Test
    void createsEveryStartupResolverForBothExactEditorProfiles() throws Exception {
        assertAdmitted("5.2", "5.2.0");
        assertAdmitted("5.3.02", "5.3.02");
    }

    @Test
    void rejectsEveryCrossVersionStartupPairing() throws Exception {
        final Path artifact = editorArtifact("5.3.02");
        try (URLClassLoader loader = loader(artifact)) {
            assertThrows(IllegalArgumentException.class, () ->
                new VerifiedProjectWorkspaceResolverFactory().create(
                    record("5.2", "project-workspace"), artifact, loader
                )
            );
            assertThrows(IllegalArgumentException.class, () ->
                new VerifiedEditorModelResolverFactory().create(
                    record("5.2", "editor-model"), artifact, loader
                )
            );
            assertThrows(IllegalArgumentException.class, () ->
                new VerifiedMainToolbarResolverFactory().create(
                    record("5.2", "ui-main-toolbar"), artifact, loader
                )
            );
            assertThrows(IllegalArgumentException.class, () ->
                new VerifiedEmbeddedPanelResolverFactory().create(
                    record("5.2", "ui-embedded-panel"), artifact, loader
                )
            );
            assertThrows(IllegalArgumentException.class, () ->
                new VerifiedTopMenuResolverFactory().create(
                    record("5.2", "ui-top-menu"), artifact, loader
                )
            );
            assertThrows(IllegalArgumentException.class, () ->
                new VerifiedBoundingBoxOverlayButtonResolverFactory().create(
                    record("5.2", "ui-bounding-box-overlay"), artifact, loader
                )
            );
        }
    }

    private static void assertAdmitted(
        final String profile,
        final String exactVersion
    ) throws Exception {
        final Path artifact = editorArtifact(profile);
        try (URLClassLoader loader = loader(artifact)) {
            assertEquals(
                exactVersion,
                new VerifiedProjectWorkspaceResolverFactory().create(
                    record(profile, "project-workspace"), artifact, loader
                ).cubismVersion()
            );
            assertEquals(
                exactVersion,
                new VerifiedEditorModelResolverFactory().create(
                    record(profile, "editor-model"), artifact, loader
                ).cubismVersion()
            );
            assertEquals(
                exactVersion,
                new VerifiedMainToolbarResolverFactory().create(
                    record(profile, "ui-main-toolbar"), artifact, loader
                ).cubismVersion()
            );
            assertEquals(
                profile.equals("5.2") ? "5.2.03" : exactVersion,
                new VerifiedEmbeddedPanelResolverFactory().create(
                    record(profile, "ui-embedded-panel"), artifact, loader
                ).cubismVersion()
            );
            final StaticVerificationReport topMenuReport = new StaticVerificationCli().verify(
                record(profile, "ui-top-menu"),
                artifact
            );
            assertEquals(
                true,
                topMenuReport.allSelectorsVerified(),
                () -> topMenuReport.results().toString()
            );
            assertEquals(
                profile.equals("5.2") ? "5.2.03" : exactVersion,
                new VerifiedTopMenuResolverFactory().create(
                    record(profile, "ui-top-menu"), artifact, loader
                ).cubismVersion()
            );
            final StaticVerificationReport overlayReport = new StaticVerificationCli().verify(
                record(profile, "ui-bounding-box-overlay"),
                artifact
            );
            assertEquals(
                true,
                overlayReport.allSelectorsVerified(),
                () -> overlayReport.results().toString()
            );
            assertEquals(
                profile.equals("5.2") ? "5.2.0" : exactVersion,
                new VerifiedBoundingBoxOverlayButtonResolverFactory().create(
                    record(profile, "ui-bounding-box-overlay"), artifact, loader
                ).cubismVersion()
            );
        }
    }

    private static Path record(final String profile, final String slice) {
        return PROJECT_ROOT.resolve(Path.of(
            "cubism-ref", "verification",
            "cubism-" + profile + '-' + slice + ".json"
        ));
    }

    private static Path editorArtifact(final String profile) {
        return LEGACY_EVIDENCE.resolve(
            "Cubism-" + profile + "/jars/Live2D_Cubism.jar"
        );
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
