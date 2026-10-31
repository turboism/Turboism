package dev.turboism.shell;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.internal.core.CorePluginManagement;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class PendingPluginChangesActionTest {
    private static final CorePluginManagement.PendingChanges REVIEW = new CorePluginManagement.PendingChanges(
            "original-root-bound-snapshot", "PLUGIN_PENDING_READY",
            List.of(new CorePluginManagement.PendingChange("example.plugin", "INSTALL", "2.0.0")));

    @Test
    void decliningConsentKeepsIntentAndAllIoOffEdt() throws Exception {
        final FakePlugins plugins = new FakePlugins();
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger confirmations = new AtomicInteger();
        final var worker = Executors.newSingleThreadExecutor();
        final var action = action(plugins, snapshot -> {
            assertTrue(SwingUtilities.isEventDispatchThread());
            assertSame(REVIEW, snapshot);
            confirmations.incrementAndGet();
            return false;
        }, code -> { if (code.equals("PLUGIN_PENDING_UNCHANGED")) finished.countDown(); }, () -> fail(), worker);
        try {
            SwingUtilities.invokeAndWait(() -> action.button().doClick());
            await(finished);
            assertEquals(1, confirmations.get());
            assertEquals(0, plugins.discards.get());
            SwingUtilities.invokeAndWait(() -> assertTrue(action.button().isEnabled()));
        } finally { action.close(); }
    }

    @Test
    void consentSubmitsExactlyReviewedSnapshotAndSurfacesConflict() throws Exception {
        final FakePlugins plugins = new FakePlugins();
        plugins.discard = token -> {
            assertEquals(REVIEW.snapshot(), token);
            return CorePluginManagement.OperationResult.rejected("PLUGIN_PLAN_CHANGED", "changed");
        };
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger refresh = new AtomicInteger();
        final var worker = Executors.newSingleThreadExecutor();
        final var action = action(plugins, snapshot -> true,
                code -> { if (code.equals("PLUGIN_PLAN_CHANGED")) finished.countDown(); }, refresh::incrementAndGet, worker);
        try {
            SwingUtilities.invokeAndWait(() -> action.button().doClick());
            await(finished);
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals(1, plugins.discards.get());
            assertEquals(1, refresh.get());
        } finally { action.close(); }
    }

    @Test
    void closeDoesNotInterruptAnAlreadyConsentedCommit() throws Exception {
        final FakePlugins plugins = new FakePlugins();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean committed = new AtomicBoolean();
        plugins.discard = token -> {
            entered.countDown();
            await(release);
            committed.set(true);
            return CorePluginManagement.OperationResult.accepted("PLUGIN_PENDING_DISCARDED", "done");
        };
        final var worker = Executors.newSingleThreadExecutor();
        final var action = action(plugins, snapshot -> true, ignored -> {}, () -> fail(), worker);
        try {
            SwingUtilities.invokeAndWait(() -> action.button().doClick());
            await(entered);
            action.close();
            release.countDown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {});
            assertTrue(committed.get());
        } finally { release.countDown(); action.close(); }
    }

    @Test
    void closeDuringReadNeverShowsConsentOrMutatesAndDuplicateClicksAreSuppressed() throws Exception {
        final FakePlugins plugins = new FakePlugins();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        plugins.read = () -> { entered.countDown(); await(release); return REVIEW; };
        final AtomicInteger confirmations = new AtomicInteger();
        final var worker = Executors.newSingleThreadExecutor();
        final var action = action(plugins, snapshot -> { confirmations.incrementAndGet(); return true; },
                ignored -> {}, () -> fail(), worker);
        try {
            SwingUtilities.invokeAndWait(() -> action.button().doClick());
            await(entered);
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(action.button().isEnabled());
                for (var listener : action.button().getActionListeners()) listener.actionPerformed(null);
            });
            action.close();
            release.countDown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals(1, plugins.reads.get());
            assertEquals(0, confirmations.get());
            assertEquals(0, plugins.discards.get());
        } finally { release.countDown(); action.close(); }
    }

    @Test
    void hidingAndReopeningViewDropsOldReadAndOnlyConfirmsNewRequest() throws Exception {
        final FakePlugins plugins = new FakePlugins();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        final AtomicInteger confirmations = new AtomicInteger();
        plugins.read = () -> {
            if (plugins.reads.get() == 1) { entered.countDown(); await(release); }
            return REVIEW;
        };
        final var worker = Executors.newSingleThreadExecutor();
        final var action = action(plugins, snapshot -> { confirmations.incrementAndGet(); return false; },
                code -> { if (code.equals("PLUGIN_PENDING_UNCHANGED")) finished.countDown(); }, () -> fail(), worker);
        try {
            SwingUtilities.invokeAndWait(() -> action.button().doClick());
            await(entered);
            SwingUtilities.invokeAndWait(() -> { action.viewHidden(); action.button().doClick(); });
            release.countDown();
            await(finished);
            assertEquals(2, plugins.reads.get());
            assertEquals(1, confirmations.get());
            assertEquals(0, plugins.discards.get());
        } finally { release.countDown(); action.close(); }
    }

    @Test
    void suspendedFailureIsExplicitlyReviewedBeforeCancellation() throws Exception {
        final FakePlugins plugins = new FakePlugins();
        plugins.read = () -> new CorePluginManagement.PendingChanges(REVIEW.snapshot(),
                "PLUGIN_PENDING_FAILED", REVIEW.changes());
        final CountDownLatch finished = new CountDownLatch(1);
        final var worker = Executors.newSingleThreadExecutor();
        final var action = action(plugins, snapshot -> {
            assertEquals("PLUGIN_PENDING_FAILED", snapshot.code());
            return true;
        }, code -> { if (code.equals("PLUGIN_PENDING_DISCARDED")) finished.countDown(); }, () -> {}, worker);
        try {
            SwingUtilities.invokeAndWait(() -> action.button().doClick());
            await(finished);
            assertEquals(1, plugins.discards.get());
        } finally { action.close(); }
    }

    @Test
    void frameworkRecoveryStatusDoesNotOfferDiscardConsent() throws Exception {
        final FakePlugins plugins = new FakePlugins();
        plugins.read = () -> new CorePluginManagement.PendingChanges(REVIEW.snapshot(),
                "PLUGIN_FRAMEWORK_RECOVERY_PENDING", REVIEW.changes());
        final CountDownLatch finished = new CountDownLatch(1);
        final var worker = Executors.newSingleThreadExecutor();
        final var action = action(plugins, snapshot -> { fail("Recovery must not offer cancellation"); return true; },
                code -> { if (code.equals("PLUGIN_FRAMEWORK_RECOVERY_PENDING")) finished.countDown(); }, () -> fail(), worker);
        try {
            SwingUtilities.invokeAndWait(() -> action.button().doClick());
            await(finished);
            assertEquals(0, plugins.discards.get());
        } finally { action.close(); }
    }

    private static PendingPluginChangesAction action(final FakePlugins plugins,
            final Predicate<CorePluginManagement.PendingChanges> confirm, final Consumer<String> status,
            final Runnable refresh, final ExecutorService worker) throws Exception {
        final AtomicReference<PendingPluginChangesAction> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(new PendingPluginChangesAction("Review", plugins, confirm,
                code -> { assertTrue(SwingUtilities.isEventDispatchThread()); status.accept(code); }, refresh, worker)));
        return result.get();
    }

    private static void await(final CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "worker/EDT timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static final class FakePlugins implements CorePluginManagement {
        final AtomicInteger reads = new AtomicInteger(), discards = new AtomicInteger();
        Supplier<PendingChanges> read = () -> REVIEW;
        Function<String, OperationResult> discard = token -> OperationResult.accepted("PLUGIN_PENDING_DISCARDED", "done");
        @Override public PendingChanges pendingChanges() {
            assertFalse(SwingUtilities.isEventDispatchThread()); reads.incrementAndGet(); return read.get();
        }
        @Override public OperationResult discardPendingChanges(final String token) {
            assertFalse(SwingUtilities.isEventDispatchThread()); discards.incrementAndGet(); return discard.apply(token);
        }
        @Override public List<PluginInfo> plugins() { return List.of(); }
        @Override public OperationResult install() { throw new AssertionError(); }
        @Override public OperationResult uninstall(final String id) { throw new AssertionError(); }
        @Override public OperationResult setEnabled(final String id, final boolean enabled) { throw new AssertionError(); }
    }
}
