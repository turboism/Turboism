package dev.turboism.ui.appearance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.util.Collections;
import java.util.List;
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
 * Focused tests for the one-shot L&F readiness repair: the repair runs
 * {@code FlatLaf.updateUI()} once on the EDT after the host's FlatLaf
 * look-and-feel and UI defaults are ready, polling sleeps stay on the daemon
 * worker, and every path (success, timeout, updateUI failure) is fail-open and
 * never retried.
 */
class LafReadinessRepairTest {

    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        final Thread thread = new Thread(r, "laf-repair-test-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final List<String> logLines =
        Collections.synchronizedList(new java.util.ArrayList<>());

    @AfterEach
    void tearDown() throws Exception {
        com.formdev.flatlaf.FlatLaf.reset();
        UIManager.getDefaults().remove("PanelUI");
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
    void repairAppliesUpdateUiOnceWhenFlatLafAndUiDefaultsAreReady() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            installLookAndFeel(new TestCubismLightTheme());
            UIManager.put("PanelUI", "javax.swing.plaf.metal.MetalPanelUI");
        });
        final LafReadinessRepair repair = newRepair(5_000L);
        invokeRun(repair);
        assertEquals(1, com.formdev.flatlaf.FlatLaf.updateUiCalls(),
            "updateUI must be invoked once on a ready host");
        assertTrue(logLines.stream().anyMatch(line -> line.contains("FlatLaf.updateUI applied")),
            "the repair must log its action");
    }

    @Test
    void repairIsOneShotAndIdempotent() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            installLookAndFeel(new TestCubismLightTheme());
            UIManager.put("PanelUI", "javax.swing.plaf.metal.MetalPanelUI");
        });
        final LafReadinessRepair repair = newRepair(5_000L);
        invokeRun(repair);
        invokeRun(repair);
        assertEquals(1, com.formdev.flatlaf.FlatLaf.updateUiCalls(),
            "a second invocation must not repeat the repair");
    }

    @Test
    void repairTimesOutFailOpenAndNeverRetries() throws Exception {
        SwingUtilities.invokeAndWait(() ->
            installLookAndFeel(new javax.swing.plaf.metal.MetalLookAndFeel()));
        final LafReadinessRepair repair = newRepair(200L);
        invokeRun(repair);
        assertEquals(0, com.formdev.flatlaf.FlatLaf.updateUiCalls(),
            "no updateUI may run when FlatLaf never becomes ready");
        assertTrue(logLines.stream().anyMatch(line -> line.contains("not ready within")),
            "the timeout must be logged");
        invokeRun(repair);
        assertEquals(0, com.formdev.flatlaf.FlatLaf.updateUiCalls(),
            "the repair must not retry after a timeout");
    }

    @Test
    void repairFailsOpenWhenUpdateUiThrows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            installLookAndFeel(new TestCubismLightTheme());
            UIManager.put("PanelUI", "javax.swing.plaf.metal.MetalPanelUI");
        });
        com.formdev.flatlaf.FlatLaf.throwOnUpdateUi(true);
        final LafReadinessRepair repair = newRepair(5_000L);
        invokeRun(repair);
        assertTrue(logLines.stream().anyMatch(line -> line.contains("failed safely")),
            "an updateUI failure must be logged and swallowed");
        com.formdev.flatlaf.FlatLaf.throwOnUpdateUi(false);
        invokeRun(repair);
        assertEquals(0, com.formdev.flatlaf.FlatLaf.updateUiCalls(),
            "the repair must not retry after a failure");
    }

    @Test
    void readinessPollingDispatchesUIManagerReadsToTheEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            installLookAndFeel(new TestCubismLightTheme());
            UIManager.put("PanelUI", "javax.swing.plaf.metal.MetalPanelUI");
        });
        final LafReadinessRepair repair = newRepair(5_000L);
        try (EdtHold hold = new EdtHold()) {
            final Future<Boolean> poll = workers.submit(() -> repair.waitForFlatLaf(5_000L));
            // While the EDT is held, an EDT-dispatched poll cannot observe the
            // installed look-and-feel; a direct UIManager read would return at once.
            Thread.sleep(400L);
            assertFalse(poll.isDone(), "readiness polling must run UIManager reads on the EDT");
            hold.release();
            assertTrue(poll.get(5, TimeUnit.SECONDS), "poll must find the installed look-and-feel");
        }
    }

    @Test
    void pollingSleepStaysOnTheDaemonWorkerSoTheEdtStaysResponsive() throws Exception {
        final LafReadinessRepair repair = newRepair(5_000L);
        final AtomicReference<Thread> edtThread = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> edtThread.set(Thread.currentThread()));
        final AtomicReference<Thread> pollThread = new AtomicReference<>();
        final CountDownLatch pollStarted = new CountDownLatch(1);
        final Future<Boolean> poll = workers.submit(() -> {
            pollThread.set(Thread.currentThread());
            pollStarted.countDown();
            return repair.waitForFlatLaf(5_000L);
        });
        assertTrue(pollStarted.await(2, TimeUnit.SECONDS), "poll worker must start");
        assertNotSame(edtThread.get(), pollThread.get(), "poll must run on a non-EDT worker");
        assertTrue(awaitPollingSleep(pollThread.get(), 2_000L),
            "polling sleep must be observed on the poll worker's own stack");
        final CountDownLatch edtProbe = new CountDownLatch(1);
        SwingUtilities.invokeLater(edtProbe::countDown);
        assertTrue(edtProbe.await(2, TimeUnit.SECONDS),
            "EDT must remain responsive while the poll is active");
        assertFalse(poll.isDone(), "poll must remain active while the probe fires");
        poll.cancel(true);
    }

    /**
     * Bounded state sampling: waits until the worker is observed sleeping in
     * the readiness poll (TIMED_WAITING with {@code Thread.sleep} on the
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
            polling |= LafReadinessRepair.class.getName().equals(element.getClassName())
                && "waitForFlatLaf".equals(element.getMethodName());
        }
        return sleeping && polling;
    }

    private LafReadinessRepair newRepair(final long timeoutMillis) {
        return new LafReadinessRepair(
            LafReadinessRepairTest.class.getClassLoader(),
            logLines::add,
            timeoutMillis
        );
    }

    /** Invokes the private bootstrap run path; no production seam is changed. */
    private static void invokeRun(final LafReadinessRepair repair) throws Exception {
        final java.lang.reflect.Method run = LafReadinessRepair.class.getDeclaredMethod("run");
        run.setAccessible(true);
        run.invoke(repair);
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
     * Look-and-feel whose class name trips the FlatLaf detector, so the poll
     * can succeed without a real FlatLaf on the test classpath.
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
            return "Stub look and feel for L&F readiness repair tests";
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
