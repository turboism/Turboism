package dev.turboism.adapter;

import dev.turboism.adapter.cubism.RecentPreviewHostFixture;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentPreviewAdapterBundleTest {

    @Test
    void composesTheVerifiedRecentPreviewSliceIntoTheAdapterBundle() throws Exception {
        final Path current = Files.createTempFile("recent-preview-connector", ".cmo3");
        final ClassLoader loader = getClass().getClassLoader();
        final VerifiedMemberResolver project = projectResolver("5.3.02", loader);
        final VerifiedMemberResolver panel = panelResolver("5.3.02", loader);
        final RuntimeHostAdapters base = RuntimeHostAdapters.safeMode();

        final RuntimeHostAdapters bundle =
            RuntimeHostAdapters.withVerifiedRecentPreview(base, project, panel);

        RecentPreviewHostFixture.ProjectHost.setRoot(projectChain(current));
        RecentPreviewHostFixture.PanelHost.setRoot(panelChain(recentMenu()));
        final List<RecentFileSummary> listed = bundle.recentFiles().list();
        assertEquals(1, listed.size(), "the current project must be visible through the bundle");
        assertEquals(current.getFileName().toString(), listed.get(0).displayName());
        assertTrue(listed.get(0).id().value().matches("[0-9a-f]{64}"));
        // Safe-mode slots stay untouched.
        assertSame(base.themeStatus(), bundle.themeStatus());
        assertSame(base.projectWorkspace(), bundle.projectWorkspace());
    }

    @Test
    void unverifiedResolverPairFailsClosed() {
        final ClassLoader loader = getClass().getClassLoader();
        final VerifiedMemberResolver project = projectResolver("5.3.01", loader);
        final VerifiedMemberResolver panel = panelResolver("5.3.02", loader);
        assertThrows(IllegalArgumentException.class,
            () -> RuntimeHostAdapters.withVerifiedRecentPreview(
                RuntimeHostAdapters.safeMode(), project, panel
            ));
    }

    @Test
    void safeModeBundleKeepsAllRecentPreviewSlotsFailingClosed() {
        final RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        assertEquals(List.of(), safe.recentFiles().list());
        assertThrows(java.util.concurrent.CompletionException.class, () -> safe.screenshots()
            .capture(new dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureRequest(
                new dev.turboism.sdk.cubism.recentfile.RecentFileId("0".repeat(64)), 150, 150))
            .toCompletableFuture().join());
        assertThrows(UnsupportedOperationException.class,
            () -> safe.recentPreviews().contribute(summary -> java.util.Optional.empty()));
        safe.recentPreviews().refresh();
    }
}
