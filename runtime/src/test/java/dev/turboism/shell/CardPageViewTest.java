package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
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
