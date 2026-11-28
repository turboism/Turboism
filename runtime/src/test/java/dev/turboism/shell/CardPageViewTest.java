package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.internal.TurboismEndpoints;
import dev.turboism.internal.core.UpdateNotesService.ReleaseNotes;
import dev.turboism.test.plugin.FakePluginContext;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class CardPageViewTest {

    @Test
    void htmlEscapesTitleAndContentAndOmitsBlankIntro() {
        final String html = CardPageView.html("<b>x</b>&", "  ", List.of("a & b", "<i>"));

        assertTrue(html.contains("&lt;b&gt;x&lt;/b&gt;&amp;"));
        assertFalse(html.contains("<b>x</b>"), "title text must never inject markup");
        assertTrue(html.contains("<li>a &amp; b</li>"));
        assertTrue(html.contains("<li>&lt;i&gt;</li>"));
        assertFalse(html.contains("class=\"intro\""), "a blank intro renders no paragraph");
    }

    @Test
    void linksAreFocusableControlsThatReportTheirUrl() throws Exception {
        final AtomicReference<String> opened = new AtomicReference<>();
        final CardPageView[] created = new CardPageView[1];
        SwingUtilities.invokeAndWait(() -> created[0] = new CardPageView(
                "Title",
                "Intro",
                List.of("line"),
                List.of(new CardPageView.Link("Docs", "https://docs.turboism.dev")),
                opened::set));

        final CardPageView view = created[0];
        final JButton link = findLink(view);
        assertNotNull(link, "a supplied link renders as one control");
        assertEquals("Docs", link.getText());
        assertTrue(link.isFocusable());
        SwingUtilities.invokeAndWait(link::doClick);

        assertEquals("https://docs.turboism.dev", opened.get());
        assertEquals("Title", view.title());
        assertEquals(List.of("line"), view.bullets());
    }

    @Test
    void titleAndContentAloneProduceAView() throws Exception {
        final CardPageView[] created = new CardPageView[1];
        SwingUtilities.invokeAndWait(() -> created[0] = new CardPageView("Title", List.of("line one")));

        assertEquals("Title", created[0].title());
        assertEquals(List.of("line one"), created[0].bullets());
        assertNull(findLink(created[0]), "a page without links renders no control");
    }

    @Test
    void markdownEscapesRawHtmlFormatsInlineTokensAndFiltersUnsafeLinks() {
        final String html = CardPageView.markdownHtml(
                "Added <script>",
                "Updated **bold** and `raw_*code*`",
                List.of("See [docs](https://docs.turboism.dev) and [local](sdk/review.md)"),
                List.of("Item `a & b` with **emphasis**"));

        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("<strong>bold</strong>"));
        assertTrue(html.contains("<code>raw_*code*</code>"), "asterisks inside backticks stay literal");
        assertTrue(html.contains("<a href=\"https://docs.turboism.dev\">docs</a>"));
        assertFalse(html.contains("href=\"sdk/review.md\""), "relative paths never become active hyperlinks");
        assertTrue(html.contains("local"));
        assertTrue(html.contains("<code>a &amp; b</code>"));
    }

    @Test
    void authorSupportAppearsOnlyOnTheLastPageOfEachFlow() throws Exception {
        final var i18n = FakePluginContext.passThroughLocalization(Locale.ENGLISH);
        final var notes = new ReleaseNotes(
                "0.70.3", Optional.empty(), Map.of("en", "### Added\n\n- Feature\n\n### Fixed\n\n- Bug"));
        final var emptyNotes = new ReleaseNotes("0.70.3", Optional.empty(), Map.of());

        SwingUtilities.invokeAndWait(() -> {
            for (final List<CardPageView> pages : List.of(
                    GuideWindow.buildPages(i18n, ignored -> {}),
                    UpdateNotesWindow.buildPages(i18n, notes, ignored -> {}),
                    UpdateNotesWindow.buildPages(i18n, emptyNotes, ignored -> {}))) {
                final int lastPage = pages.size() - 1;
                for (int index = 0; index < pages.size(); index++) {
                    final CardPageView page = pages.get(index);
                    final BorderLayout layout = (BorderLayout) page.getLayout();
                    final JScrollPane scroll = (JScrollPane) layout.getLayoutComponent(BorderLayout.CENTER);
                    final String html = ((JEditorPane) scroll.getViewport().getView()).getText();
                    assertEquals(index == lastPage, html.contains("card.author-support.title"));
                    assertEquals(index == lastPage, html.contains("card.author-support.body"));
                    assertEquals(index == lastPage, html.contains(TurboismEndpoints.SPONSOR_PAGE_URL));
                }
            }
        });
    }

    private static JButton findLink(final Container container) {
        for (final Component child : container.getComponents()) {
            if (child instanceof JButton button && !button.getText().isBlank()) return button;
            if (child instanceof Container nested) {
                final JButton found = findLink(nested);
                if (found != null) return found;
            }
        }
        return null;
    }
}
