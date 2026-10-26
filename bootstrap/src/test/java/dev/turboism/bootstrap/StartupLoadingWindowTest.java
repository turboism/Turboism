package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    private static void assertNoWindow() throws Exception {
        EventQueue.invokeAndWait(() -> assertFalse(Arrays.stream(Window.getWindows())
                .anyMatch(window -> window.isDisplayable() && "turboism-startup-loading".equals(window.getName()))));
    }
}
