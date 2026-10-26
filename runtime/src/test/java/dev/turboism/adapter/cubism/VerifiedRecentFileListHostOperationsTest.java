package dev.turboism.adapter.cubism;

import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.panelChain;
import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.panelResolver;
import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.projectChain;
import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.projectResolver;
import static dev.turboism.adapter.cubism.RecentPreviewHostFixture.recentMenu;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.RecentPreviewHostFixture.PanelHost;
import dev.turboism.adapter.cubism.RecentPreviewHostFixture.ProjectHost;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedRecentFileListHostOperationsTest {

    @Test
    void portsLegacyPathResolutionOrderAndOpaqueStableIdentity() throws Exception {
        Path project = Files.createTempFile("recent-preview", ".cmo3");

        assertEquals(
                project.toAbsolutePath().normalize(),
                RecentMenuChain.firstExistingPath("missing.cmo3", project.toString(), "ignored"));
        assertEquals(project.toAbsolutePath().normalize(), RecentMenuChain.firstExistingPath("Demo | " + project));
        assertEquals(project.toAbsolutePath().normalize(), RecentMenuChain.firstExistingPath("Demo [" + project + "]"));
        assertEquals(
                project.toAbsolutePath().normalize(), RecentMenuChain.firstExistingPath("Demo\t\"" + project + "\""));
        assertEquals(
                project.toAbsolutePath().normalize(), RecentMenuChain.firstExistingPath("Demo\n'" + project + "'"));
        final Path unrelated = Files.createTempFile("recent-preview", ".txt");
        org.junit.jupiter.api.Assertions.assertNull(RecentMenuChain.firstExistingPath(unrelated.toString()));
        org.junit.jupiter.api.Assertions.assertNull(RecentMenuChain.firstExistingPath("missing.cmo3"));
        assertEquals(
                VerifiedRecentFileListHostOperations.idFor(project),
                VerifiedRecentFileListHostOperations.idFor(
                        project.toAbsolutePath().normalize()));
        assertNotEquals(
                VerifiedRecentFileListHostOperations.idFor(project),
                VerifiedRecentFileListHostOperations.idFor(Files.createTempFile("other", ".cmo3")));
        assertTrue(VerifiedRecentFileListHostOperations.idFor(project).value().matches("[0-9a-f]{64}"));
        assertFalse(VerifiedRecentFileListHostOperations.idFor(project).value().contains(project.toString()));
    }

    @Test
    void admitsReviewedVersionPairsAndRejectsUnreviewedOrForeignHosts() {
        final ClassLoader loader = getClass().getClassLoader();
        assertThrows(
                IllegalArgumentException.class,
                () -> new VerifiedRecentFileListHostOperations(
                        projectResolver("5.3.01", loader), panelResolver("5.3.02", loader)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new VerifiedRecentFileListHostOperations(
                        projectResolver("5.2.03", new ClassLoader() {}),
                        panelResolver("5.2.03", new ClassLoader() {})));
        assertThrows(
                IllegalArgumentException.class,
                () -> new VerifiedRecentFileListHostOperations(
                        projectResolver("5.3.02", loader), panelResolver("5.2.03", loader)));
    }

    @Test
    void absoluteTooltipWinsOverAnExistingRelativeName(@TempDir final Path projects) throws Exception {
        final Path shadow = Files.createTempFile(Path.of("."), "recent-preview-shadow-", ".cmo3");
        final Path project = Files.createFile(projects.resolve(shadow.getFileName()));
        try {
            assertEquals(
                    project.toAbsolutePath().normalize(),
                    RecentMenuChain.firstExistingPath(shadow.getFileName().toString(), project.toString()));
        } finally {
            Files.deleteIfExists(shadow);
        }
    }

    @Test
    void relativeNamesStillResolveWhenNoAbsolutePathWasProvided() throws Exception {
        final Path project = Files.createTempFile(Path.of("."), "recent-preview-relative-", ".cmo3");
        try {
            assertEquals(
                    project.toAbsolutePath().normalize(),
                    RecentMenuChain.firstExistingPath(project.getFileName().toString()));
        } finally {
            Files.deleteIfExists(project);
        }
    }

    @Test
    void missingAbsoluteTooltipDoesNotResolveToAnUnrelatedRelativeFile(@TempDir final Path projects) throws Exception {
        final Path shadow = Files.createTempFile(Path.of("."), "recent-preview-shadow-", ".cmo3");
        try {
            assertNull(RecentMenuChain.firstExistingPath(
                    shadow.getFileName().toString(), projects.resolve(shadow.getFileName()).toString()));
        } finally {
            Files.deleteIfExists(shadow);
        }
    }

    @Test
    void menuAndCurrentDocumentShareTheSameIdWhenTheWorkingDirectoryHasAShadowFile(
            @TempDir final Path projects) throws Exception {
        final Path shadow = Files.createTempFile(Path.of("."), "recent-preview-shadow-", ".cmo3");
        final Path project = Files.createFile(projects.resolve(shadow.getFileName()));
        try {
            final javax.swing.JMenu menu = recentMenu();
            final javax.swing.JMenuItem item = new javax.swing.JMenuItem(shadow.getFileName().toString());
            item.setToolTipText(project.toString());
            menu.add(item);
            ProjectHost.setRoot(projectChain(project));
            PanelHost.setRoot(panelChain(menu));
            final ClassLoader loader = getClass().getClassLoader();
            final VerifiedRecentFileListHostOperations operations = new VerifiedRecentFileListHostOperations(
                    projectResolver("5.3.02", loader), panelResolver("5.3.02", loader));

            final List<RecentFileSummary> listed = operations.list();

            assertEquals(1, listed.size());
            assertEquals(operations.current().orElseThrow(), listed.get(0).id());
            assertEquals(project.toAbsolutePath().normalize().toString(), listed.get(0).path().orElseThrow());
        } finally {
            Files.deleteIfExists(shadow);
        }
    }

    @Test
    void listIncludesCurrentProjectAheadOfMenuEntries() throws Exception {
        final Path current = Files.createTempFile("recent-preview-current", ".cmo3");
        final Path recentOnly = Files.createTempFile("recent-preview-recent", ".cmo3");
        final Path alsoCurrent = Files.createTempFile("recent-preview-both", ".cmo3");
        final ClassLoader loader = getClass().getClassLoader();
        final VerifiedRecentFileListHostOperations operations = new VerifiedRecentFileListHostOperations(
                projectResolver("5.2.03", loader), panelResolver("5.2.03", loader));

        ProjectHost.setRoot(projectChain(current));
        PanelHost.setRoot(panelChain(recentMenu(recentOnly, alsoCurrent)));

        final List<RecentFileSummary> listed = operations.list();
        assertEquals(3, listed.size(), "current project must be merged with recent menu entries");
        assertEquals(
                current.getFileName().toString(), listed.get(0).displayName(), "current project must lead the list");
        assertEquals(recentOnly.getFileName().toString(), listed.get(1).displayName());
        assertEquals(alsoCurrent.getFileName().toString(), listed.get(2).displayName());
        for (RecentFileSummary summary : listed) {
            assertTrue(summary.id().value().matches("[0-9a-f]{64}"), "id must stay opaque");
            assertFalse(summary.id().value().contains(current.toString()), "id must never contain the project path");
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
                projectResolver("5.2.03", loader), panelResolver("5.2.03", loader));

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
                panelResolver("5.3.02", getClass().getClassLoader()));
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
                panelResolver("5.2.03", getClass().getClassLoader()));
        ProjectHost.setRoot(projectChain(current));
        PanelHost.setRoot(null);

        assertEquals(
                VerifiedRecentFileListHostOperations.idFor(current),
                operations.current().orElseThrow());
    }

    @Test
    void currentFallsBackToLastActiveDocumentWhenViewFocusIsLost() throws Exception {
        final Path current = Files.createTempFile("recent-preview-unfocused", ".cmo3");
        final Path recentOnly = Files.createTempFile("recent-preview-recent", ".cmo3");
        final VerifiedRecentFileListHostOperations operations = new VerifiedRecentFileListHostOperations(
                projectResolver("5.2.03", getClass().getClassLoader()),
                panelResolver("5.2.03", getClass().getClassLoader()));
        // 5.2 hosts report a null current document once focus sits on the frame
        // chrome; the last activated view context still names the same document.
        ProjectHost.setRoot(projectChain(current, false));
        PanelHost.setRoot(panelChain(recentMenu(recentOnly)));

        assertEquals(
                VerifiedRecentFileListHostOperations.idFor(current),
                operations.current().orElseThrow(),
                "the last activated document view must stand in for a focus-cleared current document");
        final List<RecentFileSummary> listed = operations.list();
        assertEquals(current.getFileName().toString(), listed.get(0).displayName());
    }

    @Test
    void currentStaysEmptyWhenViewFocusIsLostAndHistoryIsEmpty() {
        final VerifiedRecentFileListHostOperations operations = new VerifiedRecentFileListHostOperations(
                projectResolver("5.2.03", getClass().getClassLoader()),
                panelResolver("5.2.03", getClass().getClassLoader()));
        ProjectHost.setRoot(RecentPreviewHostFixture.emptyProjectChain());
        PanelHost.setRoot(null);

        assertTrue(operations.current().isEmpty(), "an empty view-context history must fail closed");
    }
}
