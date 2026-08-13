package dev.turboism.ui.appearance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.awt.Color;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the early-theme startup EDT correction: UIManager access
 * in early-theme readiness polling and native off-canvas capture is dispatched
 * to the EDT, while polling sleeps stay on the daemon/background thread.
 */
class EarlyThemeAppearanceBootstrapTest {

    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        final Thread thread = new Thread(r, "early-theme-test-worker");
        thread.setDaemon(true);
        return thread;
    });

    @AfterEach
    void tearDown() throws Exception {
        UIManager.getDefaults().remove("CubismCommon.gl.viewArea.background");
        UIManager.getDefaults().remove("Turboism.native.CubismCommon.gl.viewArea.background");
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(new javax.swing.plaf.metal.MetalLookAndFeel());
            } catch (UnsupportedLookAndFeelException exception) {
                throw new IllegalStateException(exception);
            }
        });
        workers.shutdownNow();
    }

    @Test
    void waitForFlatLafDispatchesLookAndFeelPollingToTheEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> installLookAndFeel(new TestCubismLightTheme()));
        final EarlyThemeAppearanceBootstrap bootstrap = newBootstrap();
        try (EdtHold hold = new EdtHold()) {
            final Future<Boolean> poll = workers.submit(bootstrap::waitForFlatLaf);
            // While the EDT is held, an EDT-dispatched poll cannot observe the
            // installed look-and-feel; a direct UIManager read would return at once.
            Thread.sleep(400L);
            assertFalse(poll.isDone(), "look-and-feel polling must run UIManager reads on the EDT");
            hold.release();
            assertTrue(poll.get(5, TimeUnit.SECONDS), "poll must find the installed look-and-feel");
        }
    }

    @Test
    void captureNativeOffCanvasBackgroundDispatchesUIManagerAccessToTheEdt() throws Exception {
        final Color nativeBackground = new Color(222, 223, 224);
        SwingUtilities.invokeAndWait(() ->
            UIManager.put("CubismCommon.gl.viewArea.background", nativeBackground));
        try (EdtHold hold = new EdtHold()) {
            final Future<?> capture = workers.submit(
                () -> SwingFlatLafHostOperations.captureNativeOffCanvasBackground());
            Thread.sleep(400L);
            assertFalse(capture.isDone(), "off-canvas capture must run UIManager access on the EDT");
            hold.release();
            capture.get(5, TimeUnit.SECONDS);
        }
        assertEquals(nativeBackground,
            UIManager.get("Turboism.native.CubismCommon.gl.viewArea.background"));
    }

    @Test
    void runWithoutUsableSelectionCompletesWhileEdtIsHeld(@TempDir Path home) throws Exception {
        final EarlyThemeAppearanceBootstrap bootstrap = new EarlyThemeAppearanceBootstrap(
            home,
            EarlyThemeAppearanceBootstrapTest.class.getClassLoader(),
            () -> {
            }
        );
        try (EdtHold hold = new EdtHold()) {
            final Future<?> completed = workers.submit((java.util.concurrent.Callable<Object>) () -> {
                invokeRun(bootstrap);
                return null;
            });
            // A no-selection home must return before any Swing/EDT/UIManager
            // dependency: with the EDT held, an early waitForFlatLaf() would
            // block the worker in invokeAndWait past the bounded timeout.
            completed.get(5, TimeUnit.SECONDS);
            assertTrue(completed.isDone(), "run must finish while the EDT is held");
        }
    }

    /** Invokes the private bootstrap run path; no production seam is changed. */
    private static void invokeRun(final EarlyThemeAppearanceBootstrap bootstrap) throws Exception {
        final java.lang.reflect.Method run = EarlyThemeAppearanceBootstrap.class.getDeclaredMethod("run");
        run.setAccessible(true);
        run.invoke(bootstrap);
    }
    @Test
    void pollingSleepStaysOffTheEdtSoHostInitializationIsNotBlocked() throws Exception {
        final EarlyThemeAppearanceBootstrap bootstrap = newBootstrap();
        final AtomicReference<Thread> edtThread = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> edtThread.set(Thread.currentThread()));
        final AtomicReference<Thread> pollThread = new AtomicReference<>();
        final CountDownLatch pollStarted = new CountDownLatch(1);
        final Future<Boolean> poll = workers.submit(() -> {
            pollThread.set(Thread.currentThread());
            pollStarted.countDown();
            return bootstrap.waitForFlatLaf();
        });
        assertTrue(pollStarted.await(2, TimeUnit.SECONDS), "poll worker must start");
        final Thread worker = pollThread.get();
        assertNotSame(edtThread.get(), worker, "poll must run on a non-EDT worker");
        // Deterministic sleep evidence: sample the exact worker until its state
        // and stack show the active polling sleep (TIMED_WAITING with
        // Thread.sleep under waitForFlatLaf). A sleep moved into the EDT lambda
        // would put the 100 ms sleep on the EDT; this worker would never own it
        // and the bounded observation would expire.
        assertTrue(awaitPollingSleep(worker, 2_000L),
            "polling sleep must be observed on the poll worker's own stack");
        final CountDownLatch edtProbe = new CountDownLatch(1);
        SwingUtilities.invokeLater(edtProbe::countDown);
        // The EDT stays responsive while the poll sleeps on its worker.
        assertTrue(edtProbe.await(2, TimeUnit.SECONDS),
            "EDT must remain responsive while the poll is active");
        assertFalse(poll.isDone(), "poll must remain active while the probe fires");
        poll.cancel(true);
    }

    /**
     * Bounded state sampling: waits until the worker is observed sleeping in
     * the early-theme poll (TIMED_WAITING with {@code Thread.sleep} on the
     * {@code waitForFlatLaf} stack). No latency threshold is asserted.
     */
    private static boolean awaitPollingSleep(final Thread worker, final long timeoutMillis)
        throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (worker.getState() == Thread.State.TIMED_WAITING
                && isPollingSleep(worker.getStackTrace())) {
                return true;
            }
            Thread.sleep(5L);
        }
        return false;
    }

    private static boolean isPollingSleep(final StackTraceElement[] stack) {
        boolean sleeping = false;
        boolean polling = false;
        for (StackTraceElement element : stack) {
            sleeping |= "java.lang.Thread".equals(element.getClassName())
                && "sleep".equals(element.getMethodName());
            polling |= EarlyThemeAppearanceBootstrap.class.getName().equals(element.getClassName())
                && "waitForFlatLaf".equals(element.getMethodName());
        }
        return sleeping && polling;
    }

    private static EarlyThemeAppearanceBootstrap newBootstrap() {
        return new EarlyThemeAppearanceBootstrap(
            java.nio.file.Path.of("."),
            EarlyThemeAppearanceBootstrapTest.class.getClassLoader(),
            () -> {
            }
        );
    }

    private static void installLookAndFeel(final javax.swing.LookAndFeel lookAndFeel) {
        try {
            UIManager.setLookAndFeel(lookAndFeel);
        } catch (UnsupportedLookAndFeelException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Occupies the EDT until {@link #release()}, so EDT-dispatched work can be observed. */
    private static final class EdtHold implements AutoCloseable {
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private EdtHold() throws Exception {
            SwingUtilities.invokeLater(() -> {
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(held.await(2, TimeUnit.SECONDS), "EDT hold did not start");
        }

        void release() {
            release.countDown();
        }

        @Override
        public void close() {
            release.countDown();
        }
    }

    /**
     * Look-and-feel whose class name trips the early-theme FlatLaf detector,
     * so the poll can succeed without a real FlatLaf on the test classpath.
     */
    private static final class TestCubismLightTheme extends javax.swing.LookAndFeel {
        @Override
        public String getName() {
            return "TestCubismLightTheme";
        }

        @Override
        public String getID() {
            return "TestCubismLightTheme";
        }

        @Override
        public String getDescription() {
            return "Stub look and feel for early-theme EDT dispatch tests";
        }

        @Override
        public boolean isNativeLookAndFeel() {
            return false;
        }

        @Override
        public boolean isSupportedLookAndFeel() {
            return true;
        }

        @Override
        public UIDefaults getDefaults() {
            return new UIDefaults();
        }
    }
}
