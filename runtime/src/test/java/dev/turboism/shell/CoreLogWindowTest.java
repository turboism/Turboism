package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.runtime.RuntimeLogReader;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.StyleConstants;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class CoreLogWindowTest {

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
            return key;
        }

        @Override
        public boolean contains(final String key) {
            return true;
        }
    };

    private static final List<String> LINES = List.of(
            "2026-01-01T00:00:00Z [INFO] [runtime] startup",
            "java.lang.IllegalStateException: disk fixture",
            "2026-01-01T00:00:01Z [WARN] [storage] disk nearly full",
            "2026-01-01T00:00:02Z [ERROR] [network] disconnected");

    @Test
    void filtersCompleteLogEntriesByKeywordAndMinimumLevel() {
        assertEquals(
                "2026-01-01T00:00:01Z [WARN] [storage] disk nearly full", CoreLogWindow.filter(LINES, "disk", "WARN"));
        assertEquals(
                "2026-01-01T00:00:00Z [INFO] [runtime] startup\n" + "java.lang.IllegalStateException: disk fixture",
                CoreLogWindow.filter(LINES, "fixture", "INFO"));
    }

    @Test
    void colorsEachEntryByLevelAndCarriesColorIntoContinuationLines() {
        final JTextPane output = new JTextPane();
        output.setBackground(Color.WHITE);
        output.setForeground(Color.BLACK);
        final String content = String.join(
                "\n",
                "2026-01-01T00:00:00Z [TRACE] [probe] trace message",
                "2026-01-01T00:00:01Z [DEBUG] [probe] debug message",
                "2026-01-01T00:00:02Z [INFO] [probe] info message",
                "2026-01-01T00:00:03Z [WARN] [probe] warn message",
                "2026-01-01T00:00:04Z [ERROR] [probe] error message",
                "caused by fixture",
                "2026-01-01T00:00:05Z [FATAL] [probe] fatal message");

        CoreLogWindow.render(output, content);

        assertEquals(new Color(0x5F6368), colorAt(output, "trace message"));
        assertEquals(new Color(0x0057B8), colorAt(output, "debug message"));
        assertEquals(Color.BLACK, colorAt(output, "info message"));
        assertEquals(new Color(0x8A5A00), colorAt(output, "warn message"));
        assertEquals(new Color(0xB00020), colorAt(output, "error message"));
        assertEquals(new Color(0xB00020), colorAt(output, "caused by fixture"));
        assertEquals(new Color(0xB00020), colorAt(output, "fatal message"));

        output.setBackground(new Color(0x202124));
        output.setForeground(Color.WHITE);
        CoreLogWindow.render(output, content);
        assertEquals(new Color(0xFFD166), colorAt(output, "warn message"));
        assertEquals(new Color(0xFF6B6B), colorAt(output, "error message"));
    }

    /**
     * The one-second refresh must stop at {@link CoreLogWindow#close()}, not only inside the task
     * it posts to the host event queue: teardown can run while that queue is wedged, and a refresh
     * timer left scheduled keeps reading the log for the rest of the host session.
     */
    @Test
    void closeStopsTheRefreshTimerWhileTheHostEventQueueIsBusy() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "showing the log window requires a display");
        final CoreLogWindow window = new CoreLogWindow(I18N, RuntimeLogReader.unavailable());
        final CountDownLatch release = new CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(window::show);
            assertTrue(window.refreshTimerRunning(), "showing the log window schedules the one-second refresh");

            final CountDownLatch held = new CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                held.countDown();
                awaitQuietly(release);
            });
            assertTrue(held.await(10, TimeUnit.SECONDS), "the host event queue did not pick up the blocking task");

            window.close();
            assertFalse(
                    window.refreshTimerRunning(),
                    "close must stop the refresh timer without waiting for the queued disposal");
        } finally {
            release.countDown();
            window.close();
            SwingUtilities.invokeAndWait(() -> {});
        }
    }

    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Color colorAt(final JTextPane output, final String marker) {
        final int offset = output.getText().indexOf(marker);
        return StyleConstants.getForeground(
                output.getStyledDocument().getCharacterElement(offset).getAttributes());
    }
}
