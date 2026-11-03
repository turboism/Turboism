package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.AWTEvent;
import java.awt.Canvas;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Run under a desktop or xvfb-run to exercise real AWT dispatch and disposal. */
class StartupLoadingWindowTest {

    @BeforeEach
    void requireDisplay() {
        assumeFalse(GraphicsEnvironment.isHeadless());
    }

    @Test
    void successfulStartupDisposesTheNonModalWindow() throws Exception {
        try (StartupLoadingWindow loading = StartupLoadingWindow.open()) {
            final Dialog window = awaitWindow();
            EventQueue.invokeAndWait(() -> {
                assertTrue(window.isUndecorated());
                assertFalse(window.isModal());
                assertFalse(window.getFocusableWindowState());
                if (window.isAlwaysOnTopSupported()) assertTrue(window.isAlwaysOnTop());
            });
            loading.phase(StartupLoadingWindow.Phase.PLUGINS);
            awaitDescription(window, "插件", "plugins");
        }
        assertNoWindow();
    }

    @Test
    void animationRepaintsTheHeavyweightCanvas() throws Exception {
        try (StartupLoadingWindow loading = StartupLoadingWindow.open()) {
            final Dialog window = awaitWindow();
            final CountDownLatch paints = new CountDownLatch(2);
            final AWTEventListener listener = event -> {
                if (event.getSource() == window.getComponents()[0]) paints.countDown();
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.PAINT_EVENT_MASK);
            try {
                loading.phase(StartupLoadingWindow.Phase.PLUGINS);
                assertTrue(paints.await(5, TimeUnit.SECONDS), "Canvas must repaint repeatedly for the loading spinner");
            } finally {
                Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
            }
        }
        assertNoWindow();
    }

    @Test
    void hostDetectionTimeoutRemainsVisibleUntilDismissed() throws Exception {
        try (StartupLoadingWindow loading = StartupLoadingWindow.open()) {
            final Dialog window = awaitWindow();
            loading.phase(StartupLoadingWindow.Phase.TIMED_OUT);
            loading.phase(StartupLoadingWindow.Phase.INITIALIZING);
            awaitDescription(window, "超时", "timed out");
            EventQueue.invokeAndWait(() -> assertTrue(window.isShowing()));
        }
        assertNoWindow();
    }

    @Test
    void failureStopsProgressAndUserCanDismissWithoutStoppingBootstrap() throws Exception {
        try (StartupLoadingWindow loading = StartupLoadingWindow.open()) {
            final Dialog window = awaitWindow();
            loading.phase(StartupLoadingWindow.Phase.FAILED);
            loading.phase(StartupLoadingWindow.Phase.PLUGINS);
            awaitDescription(window, "失败", "failed");
            EventQueue.invokeAndWait(() -> window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING)));
            loading.phase(StartupLoadingWindow.Phase.INTERFACE);
            assertNoWindow();
        }
    }

    @Test
    void completionBeforeTheEdtCanShowTheWindowDoesNotResurrectIt() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        EventQueue.invokeLater(() -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try (StartupLoadingWindow loading = StartupLoadingWindow.open()) {
                loading.phase(StartupLoadingWindow.Phase.INTERFACE);
            }
        } finally {
            release.countDown();
        }
        assertNoWindow();
    }

    @Test
    void progressPhasesKeepTheWindowSizeStable() throws Exception {
        try (StartupLoadingWindow loading = StartupLoadingWindow.open()) {
            final Dialog window = awaitWindow();
            final AtomicReference<Dimension> initial = new AtomicReference<>();
            EventQueue.invokeAndWait(() -> initial.set(window.getSize()));
            for (final StartupLoadingWindow.Phase phase : List.of(
                    StartupLoadingWindow.Phase.INITIALIZING,
                    StartupLoadingWindow.Phase.PLUGINS,
                    StartupLoadingWindow.Phase.INTERFACE)) {
                loading.phase(phase);
                Thread.sleep(120L);
                final AtomicReference<Dimension> current = new AtomicReference<>();
                EventQueue.invokeAndWait(() -> current.set(window.getSize()));
                assertEquals(initial.get(), current.get(), "Progress phases must not resize the loading window");
            }
        }
        assertNoWindow();
    }

    @Test
    void terminalMessageGrowsTheWindowWithoutMovingItsCenter() throws Exception {
        try (StartupLoadingWindow loading = StartupLoadingWindow.open()) {
            final Dialog window = awaitWindow();
            // Placement is a request to the window server and may land after the map, so sample the
            // window only once it reports the centred bounds the constructor asks for.
            awaitCentered(window);
            final AtomicReference<Rectangle> before = new AtomicReference<>();
            EventQueue.invokeAndWait(() -> before.set(window.getBounds()));
            loading.phase(StartupLoadingWindow.Phase.FAILED);
            awaitDescription(window, "失败", "failed");
            final AtomicReference<Rectangle> after = new AtomicReference<>();
            EventQueue.invokeAndWait(() -> after.set(window.getBounds()));
            assertTrue(after.get().width > before.get().width, "The failure message needs more room");
            assertEquals(
                    before.get().getCenterX(),
                    after.get().getCenterX(),
                    1.0,
                    "A resized terminal message must keep the window centered");
        }
        assertNoWindow();
    }

    @Test
    void animationRepaintsOnlyTheSpinnerAtAFullFrameRate() throws Exception {
        try (StartupLoadingWindow loading = StartupLoadingWindow.open()) {
            final Dialog window = awaitWindow();
            // Let the appearance fade settle so both samples render at full opacity.
            Thread.sleep(300L);
            final AtomicReference<Canvas> found = new AtomicReference<>();
            EventQueue.invokeAndWait(() -> found.set((Canvas) window.getComponents()[0]));
            final Canvas canvas = found.get();
            final int paints = countPaints(canvas, 1000L);
            assertTrue(paints >= 20, "The spinner must present at least ~20 frames per second, saw " + paints);
            final BufferedImage first = paint(canvas);
            Thread.sleep(120L);
            final BufferedImage second = paint(canvas);
            assertTrue(
                    first.getWidth() == second.getWidth() && first.getHeight() == second.getHeight(),
                    "Both samples must cover the same window area");
            // Every pixel right of the spinner column is text and must stay untouched by
            // the animation; an unpainted or erased frame would change those pixels too.
            final int textColumn = 18 + 22 + 14;
            int spinnerChanges = 0;
            int textChanges = 0;
            for (int y = 0; y < first.getHeight(); y++) {
                for (int x = 0; x < first.getWidth(); x++) {
                    if (first.getRGB(x, y) == second.getRGB(x, y)) continue;
                    if (x < textColumn) {
                        spinnerChanges++;
                    } else {
                        textChanges++;
                    }
                }
            }
            assertTrue(spinnerChanges > 0, "The spinner must advance between frames");
            assertEquals(0, textChanges, "The animation must not disturb the text or clear the window");
        }
        assertNoWindow();
    }

    private static BufferedImage paint(final Canvas canvas) throws Exception {
        final AtomicReference<BufferedImage> rendered = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            final BufferedImage image = new BufferedImage(
                    Math.max(1, canvas.getWidth()), Math.max(1, canvas.getHeight()), BufferedImage.TYPE_INT_RGB);
            final Graphics2D graphics = image.createGraphics();
            try {
                canvas.paint(graphics);
            } finally {
                graphics.dispose();
            }
            rendered.set(image);
        });
        return rendered.get();
    }

    private static int countPaints(final Canvas canvas, final long millis) throws Exception {
        final AtomicInteger paints = new AtomicInteger();
        final AWTEventListener listener = event -> {
            if (event.getSource() == canvas) paints.incrementAndGet();
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.PAINT_EVENT_MASK);
        try {
            Thread.sleep(millis);
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
        }
        return paints.get();
    }

    private static Dialog awaitWindow() throws Exception {
        final AtomicReference<Dialog> found = new AtomicReference<>();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (found.get() == null && System.nanoTime() < deadline) {
            EventQueue.invokeAndWait(() -> Arrays.stream(Window.getWindows())
                    .filter(window -> window.isShowing() && "turboism-startup-loading".equals(window.getName()))
                    .findFirst()
                    .ifPresent(window -> found.set((Dialog) window)));
            if (found.get() == null) Thread.sleep(20);
        }
        assertTrue(found.get() != null, "Loading window did not appear");
        return found.get();
    }

    private static void awaitDescription(final Dialog window, final String chinese, final String english)
            throws Exception {
        final AtomicReference<String> description = new AtomicReference<>("");
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            EventQueue.invokeAndWait(
                    () -> description.set(window.getAccessibleContext().getAccessibleDescription()));
            final String value = description.get();
            if (value != null && (value.contains(chinese) || value.contains(english))) return;
            Thread.sleep(20);
        }
        assertEquals(english, description.get(), "Expected startup phase was not rendered");
    }

    /**
     * Waits, bounded, for the window server to apply the requested placement.
     *
     * <p>Setting a location before the window is mapped is only a request: without a window
     * manager the map can happen first and the window stays at the origin until something
     * re-places it. Sampling the pre-resize bounds before that settles compared an unplaced
     * window with a placed one.</p>
     */
    private static void awaitCentered(final Dialog window) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        final AtomicReference<String> observed = new AtomicReference<>("");
        while (System.nanoTime() < deadline) {
            final AtomicReference<Rectangle> bounds = new AtomicReference<>();
            final AtomicReference<Rectangle> screen = new AtomicReference<>();
            EventQueue.invokeAndWait(() -> {
                bounds.set(window.getBounds());
                screen.set(window.getGraphicsConfiguration().getBounds());
            });
            final double expectedCenterX = screen.get().getCenterX();
            if (Math.abs(bounds.get().getCenterX() - expectedCenterX) <= 1.0) return;
            observed.set(bounds.get() + " on a screen of " + screen.get());
            Thread.sleep(20);
        }
        fail("The loading window never settled on the requested centre: " + observed.get());
    }

    private static void assertNoWindow() throws Exception {
        EventQueue.invokeAndWait(() -> assertFalse(Arrays.stream(Window.getWindows())
                .anyMatch(window -> window.isDisplayable() && "turboism-startup-loading".equals(window.getName()))));
    }
}
