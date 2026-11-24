package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.internal.TurboismEndpoints;
import dev.turboism.internal.core.UpdateNotesService.ReleaseNotes;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.test.plugin.FakePluginContext;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class UpdateNotesWindowTest {

    private static final PluginLocalization I18N = FakePluginContext.passThroughLocalization(Locale.ENGLISH);

    @Test
    void splitsMarkdownSectionsIntoPagesAndExtractsSafeLinks() {
        final String markdown = """
                Summary compared to **0.70.2**.

                ### Added

                - First feature with `code` support.
                - Second item referencing [internal review](sdk/review.md).

                ### Changed

                - Refined behavior line.

                ### Fixed

                - Resolved crash.

                [Compare changes](https://github.com/turboism/Turboism/compare/v0.70.2...v0.70.3)
                """;
        final ReleaseNotes notes = new ReleaseNotes("0.70.3", Optional.of("0.70.2"), Map.of("en", markdown));
        final List<String> opened = new ArrayList<>();

        final List<CardPageView> pages = UpdateNotesWindow.buildPages(I18N, notes, opened::add);

        assertEquals(3, pages.size());
        assertEquals("Added", pages.get(0).title());
        assertEquals(2, pages.get(0).bullets().size());
        assertEquals("Changed", pages.get(1).title());
        assertEquals("Fixed", pages.get(2).title());
        assertEquals(1, pages.get(2).bullets().size());

        final UpdateNotesWindow.ParsedNotes parsed = UpdateNotesWindow.parseMarkdown(markdown, "Fallback");
        assertEquals(1, parsed.links().size());
        assertEquals(
                "https://github.com/turboism/Turboism/compare/v0.70.2...v0.70.3",
                parsed.links().get(0).url());
    }

    @Test
    void emptyNotesProduceSingleFallbackPageWithReleasesLink() {
        final ReleaseNotes notes = new ReleaseNotes("0.70.3", Optional.empty(), Map.of());

        final List<CardPageView> pages = UpdateNotesWindow.buildPages(I18N, notes, ignored -> {});

        assertEquals(1, pages.size());
        assertTrue(pages.get(0).bullets().isEmpty());
        final UpdateNotesWindow.ParsedNotes emptyParsed = UpdateNotesWindow.parseMarkdown("", "Release Notes");
        assertTrue(emptyParsed.sections().isEmpty());
        assertTrue(HttpLinksAllowedCheck());
    }

    @Test
    void navigationClampsAndDismissalRunsOnceOnUserActionOnly() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "showing the card needs an AWT display");
        final ReleaseNotes notes = new ReleaseNotes(
                "0.70.3", Optional.of("0.70.2"), Map.of("en", "### Added\n\n- Feature one\n\n### Fixed\n\n- Bug two"));
        final AtomicInteger dismissals = new AtomicInteger();
        final AtomicReference<UpdateNotesWindow> created = new AtomicReference<>();
        SwingUtilities.invokeAndWait(
                () -> created.set(new UpdateNotesWindow(I18N, notes, dismissals::incrementAndGet)));
        final UpdateNotesWindow window = created.get();
        try {
            SwingUtilities.invokeAndWait(window::show);
            assertTrue(window.isShowing());
            assertEquals("0.70.3", window.version());
            assertEquals(0, window.pageIndex());

            SwingUtilities.invokeAndWait(window::previous);
            assertEquals(0, window.pageIndex());

            SwingUtilities.invokeAndWait(window::next);
            assertEquals(1, window.pageIndex());

            SwingUtilities.invokeAndWait(window::next);
            assertFalse(window.isShowing(), "advancing past the last page closes the card");
            assertEquals(1, dismissals.get());

            SwingUtilities.invokeAndWait(window::close);
            assertEquals(1, dismissals.get(), "repeated close only records dismissal once");
        } finally {
            SwingUtilities.invokeAndWait(window::dispose);
        }
    }

    private static boolean HttpLinksAllowedCheck() {
        return dev.turboism.core.net.HttpLinks.isAllowed(TurboismEndpoints.GITHUB_RELEASES_URL);
    }
}
