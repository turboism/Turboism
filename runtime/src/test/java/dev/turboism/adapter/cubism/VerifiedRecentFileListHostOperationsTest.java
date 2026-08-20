package dev.turboism.adapter.cubism;

import dev.turboism.adapter.cubism.RecentPreviewHostFixture.ProjectHost;
import dev.turboism.adapter.cubism.RecentPreviewHostFixture.PanelHost;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.panelChain;
import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.panelResolver;
import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.projectChain;
import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.projectResolver;
import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.recentMenu;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedRecentFileListHostOperationsTest {

    @Test
    void portsLegacyPathResolutionOrderAndOpaqueStableIdentity() throws Exception {
        Path project = Files.createTempFile("recent-preview", ".cmo3");

        assertEquals(project.toAbsolutePath().normalize(),
            RecentMenuChain.firstExistingPath(
                "missing.cmo3", project.toString(), "ignored"
            ));
        assertEquals(project.toAbsolutePath().normalize(),
            RecentMenuChain.firstExistingPath("Demo | " + project));
        org.junit.jupiter.api.Assertions.assertNull(
            RecentMenuChain.firstExistingPath("missing.cmo3"));
        assertEquals(
            VerifiedRecentFileListHostOperations.idFor(project),
            VerifiedRecentFileListHostOperations.idFor(project.toAbsolutePath().normalize())
        );
        assertNotEquals(
            VerifiedRecentFileListHostOperations.idFor(project),
            VerifiedRecentFileListHostOperations.idFor(Files.createTempFile("other", ".cmo3"))
        );
        assertTrue(VerifiedRecentFileListHostOperations.idFor(project).value().matches("[0-9a-f]{64}"));
        assertFalse(VerifiedRecentFileListHostOperations.idFor(project).value().contains(project.toString()));
    }

    @Test
    void admitsReviewedVersionPairsAndRejectsUnreviewedOrForeignHosts() {
        final ClassLoader loader = getClass().getClassLoader();
        assertThrows(IllegalArgumentException.class, () -> new VerifiedRecentFileListHostOperations(
            projectResolver("5.3.01", loader), panelResolver("5.3.02", loader)
        ));
        assertThrows(IllegalArgumentException.class, () -> new VerifiedRecentFileListHostOperations(
            projectResolver("5.2.03", new ClassLoader() { }), panelResolver("5.2.03", new ClassLoader() { })
        ));
        assertThrows(IllegalArgumentException.class, () -> new VerifiedRecentFileListHostOperations(
            projectResolver("5.3.02", loader), panelResolver("5.2.03", loader)
        ));
    }

    @Test
    void listIncludesCurrentProjectAheadOfMenuEntries() throws Exception {
        final Path current = Files.createTempFile("recent-preview-current", ".cmo3");
        final Path recentOnly = Files.createTempFile("recent-preview-recent", ".cmo3");
        final Path alsoCurrent = Files.createTempFile("recent-preview-both", ".cmo3");
        final ClassLoader loader = getClass().getClassLoader();
        final VerifiedRecentFileListHostOperations operations = new VerifiedRecentFileListHostOperations(
            projectResolver("5.2.03", loader), panelResolver("5.2.03", loader)
        );

        ProjectHost.setRoot(projectChain(current));
        PanelHost.setRoot(panelChain(recentMenu(recentOnly, alsoCurrent)));

        final List<RecentFileSummary> listed = operations.list();
        assertEquals(3, listed.size(), "current project must be merged with recent menu entries");
        assertEquals(current.getFileName().toString(), listed.get(0).displayName(),
            "current project must lead the list");
        assertEquals(recentOnly.getFileName().toString(), listed.get(1).displayName());
        assertEquals(alsoCurrent.getFileName().toString(), listed.get(2).displayName());
        for (RecentFileSummary summary : listed) {
            assertTrue(summary.id().value().matches("[0-9a-f]{64}"), "id must stay opaque");
            assertFalse(summary.id().value().contains(current.toString()),
                "id must never contain the project path");
            assertTrue(summary.path().isPresent(), "existing files expose their absolute path");
            assertTrue(summary.lastModified().isPresent(), "existing files expose their edit time");
        }
    }

    @Test
    void listDeduplicatesCurrentProjectAgainstRecentMenuEntries() throws Exception {
        final Path current = Files.createTempFile("recent-preview-dedup", ".cmo3");
        final Path recentOnly = Files.createTempFile("recent-preview-recent", ".cmo3");
        final ClassLoader loader = getClass().getClassLoader();
        final VerifiedRecentFileListHostOperations operations = new VerifiedRecentFileListHostOperations(
            projectResolver("5.2.03", loader), panelResolver("5.2.03", loader)
        );

        ProjectHost.setRoot(projectChain(current));
        PanelHost.setRoot(panelChain(recentMenu(current, recentOnly)));

        final List<RecentFileSummary> listed = operations.list();
        assertEquals(2, listed.size(), "current project present in the menu must be deduplicated");
        assertEquals(current.getFileName().toString(), listed.get(0).displayName());
        assertEquals(recentOnly.getFileName().toString(), listed.get(1).displayName());
    }

    @Test
    void listSkipsMissingFilesAndReturnsEmptyWithoutAHostChain() {
        final VerifiedRecentFileListHostOperations operations = new VerifiedRecentFileListHostOperations(
            projectResolver("5.3.02", getClass().getClassLoader()),
            panelResolver("5.3.02", getClass().getClassLoader())
        );
        ProjectHost.setRoot(null);
        PanelHost.setRoot(null);
        assertEquals(List.of(), operations.list());
        assertTrue(operations.current().isEmpty());
    }

    @Test
    void currentUsesTheCurrentDocumentFile() throws Exception {
        final Path current = Files.createTempFile("recent-preview-current", ".cmo3");
        final VerifiedRecentFileListHostOperations operations = new VerifiedRecentFileListHostOperations(
            projectResolver("5.2.03", getClass().getClassLoader()),
            panelResolver("5.2.03", getClass().getClassLoader())
        );
        ProjectHost.setRoot(projectChain(current));
        PanelHost.setRoot(null);

        assertEquals(
            VerifiedRecentFileListHostOperations.idFor(current),
            operations.current().orElseThrow()
        );
    }

}
