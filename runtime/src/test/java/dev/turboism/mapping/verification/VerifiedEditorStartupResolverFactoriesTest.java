package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.test.Stubs;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VerifiedEditorStartupResolverFactoriesTest {

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path LEGACY_EVIDENCE = locateLegacyEvidence();

    @Test
    void createsEveryStartupResolverForBothExactEditorProfiles() throws Exception {
        assumeTrue(
                LEGACY_EVIDENCE != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");
        assertAdmitted("5.2.03", "5.2.03");
        assertAdmitted("5.3.02", "5.3.02");
    }

    @Test
    void rejectsEveryCrossVersionStartupPairing() throws Exception {
        assumeTrue(
                LEGACY_EVIDENCE != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");
        final Path artifact = editorArtifact("5.3.02");
        try (URLClassLoader loader = loader(artifact)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SliceRegistry.PROJECT_WORKSPACE
                            .resolverFactory()
                            .create(record("5.2.03", "project-workspace"), artifact, loader));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SliceRegistry.EDITOR_MODEL
                            .resolverFactory()
                            .create(record("5.2.03", "editor-model"), artifact, loader));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SliceRegistry.MAIN_TOOLBAR
                            .resolverFactory()
                            .create(record("5.2.03", "ui-main-toolbar"), artifact, loader));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SliceRegistry.EMBEDDED_PANEL
                            .resolverFactory()
                            .create(record("5.2.03", "ui-embedded-panel"), artifact, loader));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SliceRegistry.TOP_MENU
                            .resolverFactory()
                            .create(record("5.2.03", "ui-top-menu"), artifact, loader));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SliceRegistry.BOUNDING_BOX_OVERLAY_BUTTON
                            .resolverFactory()
                            .create(record("5.2.03", "ui-bounding-box-overlay"), artifact, loader));
        }
    }

    private static void assertAdmitted(final String profile, final String exactVersion) throws Exception {
        final Path artifact = editorArtifact(profile);
        try (URLClassLoader loader = loader(artifact)) {
            assertEquals(
                    exactVersion,
                    SliceRegistry.PROJECT_WORKSPACE
                            .resolverFactory()
                            .create(record(profile, "project-workspace"), artifact, loader)
                            .cubismVersion());
            assertEquals(
                    exactVersion,
                    SliceRegistry.EDITOR_MODEL
                            .resolverFactory()
                            .create(record(profile, "editor-model"), artifact, loader)
                            .cubismVersion());
            assertEquals(
                    exactVersion,
                    SliceRegistry.MAIN_TOOLBAR
                            .resolverFactory()
                            .create(record(profile, "ui-main-toolbar"), artifact, loader)
                            .cubismVersion());
            assertEquals(
                    profile.equals("5.2.03") ? "5.2.03" : exactVersion,
                    SliceRegistry.EMBEDDED_PANEL
                            .resolverFactory()
                            .create(record(profile, "ui-embedded-panel"), artifact, loader)
                            .cubismVersion());
            final StaticVerificationReport topMenuReport =
                    new StaticVerificationCli().verify(record(profile, "ui-top-menu"), artifact);
            assertEquals(
                    true,
                    topMenuReport.allSelectorsVerified(),
                    () -> topMenuReport.results().toString());
            assertEquals(
                    profile.equals("5.2.03") ? "5.2.03" : exactVersion,
                    SliceRegistry.TOP_MENU
                            .resolverFactory()
                            .create(record(profile, "ui-top-menu"), artifact, loader)
                            .cubismVersion());
            final StaticVerificationReport overlayReport =
                    new StaticVerificationCli().verify(record(profile, "ui-bounding-box-overlay"), artifact);
            assertEquals(
                    true,
                    overlayReport.allSelectorsVerified(),
                    () -> overlayReport.results().toString());
            assertEquals(
                    profile.equals("5.2.03") ? "5.2.03" : exactVersion,
                    SliceRegistry.BOUNDING_BOX_OVERLAY_BUTTON
                            .resolverFactory()
                            .create(record(profile, "ui-bounding-box-overlay"), artifact, loader)
                            .cubismVersion());
        }
    }

    private static Path record(final String profile, final String slice) {
        return PROJECT_ROOT.resolve(
                Path.of("build", "generated", "verification", "cubism-" + profile + '-' + slice + ".json"));
    }

    private static Path editorArtifact(final String profile) {
        assumeTrue(
                LEGACY_EVIDENCE != null,
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");

        // The legacy evidence repository is an external checkout whose directory names predate
        // this project's exact-version naming: it still ships Cubism-5.2, not Cubism-5.2.03.
        // Map the profile onto the directory rather than renaming someone else's tree.
        final String directory = "5.2.03".equals(profile) ? "5.2" : profile;
        return LEGACY_EVIDENCE.resolve("Cubism-" + directory + "/jars/Live2D_Cubism.jar");
    }

    private static URLClassLoader loader(final Path artifact) throws Exception {
        return Stubs.jarClassLoader(artifact);
    }

    private static Path locateProjectRoot() {
        return Stubs.locateProjectRoot();
    }

    private static Path locateLegacyEvidence() {
        for (Path current = PROJECT_ROOT; current != null; current = current.getParent()) {
            final Path candidate = current.resolve("turboism-legacy/cubism-ref");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
