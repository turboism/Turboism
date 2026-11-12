package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.net.HttpLinks;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class GuideWindowTest {

    private static final PluginLocalization I18N = new PluginLocalization() {
        @Override
        public Locale locale() {
            return Locale.ENGLISH;
        }

        @Override
        public String text(final String key) {
            return key;
        }

        @Override
        public String format(final String key, final Object... arguments) {
            return text(key);
        }

        @Override
        public boolean contains(final String key) {
            return true;
        }
    };

    @Test
    void pageModelExposesThreePagesWithSafeLinksOnly() {
        final List<GuideWindow.Page> pages = GuideWindow.pages();

        assertEquals(3, pages.size());
        for (final GuideWindow.Page page : pages) {
            assertFalse(page.titleKey().isBlank());
            assertFalse(page.introKey().isBlank());
            assertEquals(3, page.bulletKeys().size());
            assertFalse(page.links().isEmpty(), "every page offers an official channel");
            for (final GuideWindow.Link link : page.links()) {
                assertTrue(HttpLinks.isAllowed(link.url()), "guide links must be http(s): " + link.url());
            }
        }
    }

    @Test
    void navigationClampsAtBothEndsAndRecordsOneDismissal() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "showing the card needs an AWT display");
        final AtomicInteger dismissals = new AtomicInteger();
        final AtomicReference<GuideWindow> created = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> created.set(new GuideWindow(I18N, dismissals::incrementAndGet)));
        final GuideWindow guide = created.get();
        try {
            SwingUtilities.invokeAndWait(guide::show);
            assertTrue(guide.isShowing());
            assertEquals(0, guide.pageIndex());

            SwingUtilities.invokeAndWait(guide::previous);
            assertEquals(0, guide.pageIndex(), "the first page has no previous page");

            SwingUtilities.invokeAndWait(guide::next);
            assertEquals(1, guide.pageIndex());
            SwingUtilities.invokeAndWait(guide::next);
            assertEquals(2, guide.pageIndex());

            SwingUtilities.invokeAndWait(guide::next);
            assertFalse(guide.isShowing(), "advancing past the last page finishes the guide");
            assertEquals(1, dismissals.get());

            SwingUtilities.invokeAndWait(guide::close);
            assertEquals(1, dismissals.get(), "a repeated dismissal is recorded once");
        } finally {
            SwingUtilities.invokeAndWait(guide::dispose);
        }
    }
}
