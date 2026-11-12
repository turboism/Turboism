package dev.turboism.runtime.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The contracts every runtime lane depends on: daemon threads, attributable names, bounded queues
 * that reject instead of blocking, and timer cancellation that frees its slot.
 */
class RuntimeThreadsTest {

    private static final long AWAIT_SECONDS = 5L;

    @Test
    void namesAndMarksLaneThreadsAsDaemon() throws Exception {
        final ThreadPoolExecutor lane = RuntimeThreads.serialLane(RuntimeThreads.daemonThreads("turboism-fixture"));
        try {
            final AtomicReference<Thread> worker = new AtomicReference<>();
            final CountDownLatch ran = new CountDownLatch(1);
            lane.execute(() -> {
                worker.set(Thread.currentThread());
                ran.countDown();
            });
            assertTrue(ran.await(AWAIT_SECONDS, TimeUnit.SECONDS), "lane worker never ran");
            assertTrue(worker.get().isDaemon(), "runtime lane threads must never pin JVM shutdown");
            assertTrue(
                    worker.get().getName().startsWith("turboism-fixture-"),
                    worker.get().getName());
        } finally {
            lane.shutdownNow();
        }
    }

    @Test
    void runsThePreambleBeforeEveryTask() throws Exception {
        final AtomicReference<String> observed = new AtomicReference<>("");
        final ThreadPoolExecutor lane = RuntimeThreads.serialLane(RuntimeThreads.daemonThreads(
                "turboism-fixture", () -> observed.set(Thread.currentThread().getName())));
        try {
            final CountDownLatch ran = new CountDownLatch(1);
            lane.execute(ran::countDown);
            assertTrue(ran.await(AWAIT_SECONDS, TimeUnit.SECONDS), "lane worker never ran");
            assertTrue(observed.get().startsWith("turboism-fixture-"), observed.get());
        } finally {
            lane.shutdownNow();
        }
    }

    @Test
    void rejectsWorkOnceTheBoundedQueueIsFull() throws Exception {
        final ThreadPoolExecutor lane =
                RuntimeThreads.serialLane(1, RuntimeThreads.daemonThreadsWithFixedName("turboism-fixture"));
        final CountDownLatch release = new CountDownLatch(1);
        try {
            lane.execute(() -> awaitQuietly(release));
            lane.execute(() -> {});
            assertThrows(RejectedExecutionException.class, () -> lane.execute(() -> {}));
        } finally {
            release.countDown();
            lane.shutdownNow();
        }
    }

    @Test
    void evictsCancelledTimersSoADebounceCannotHoldItsSlot() throws Exception {
        final ScheduledThreadPoolExecutor timer =
                RuntimeThreads.serialTimer(RuntimeThreads.daemonThreadsWithFixedName("turboism-fixture"));
        try {
            timer.schedule(() -> {}, 1L, TimeUnit.HOURS).cancel(false);
            assertTrue(timer.getQueue().isEmpty(), "a cancelled timer must leave the queue at once");
        } finally {
            timer.shutdownNow();
        }
    }

    @Test
    void keepsCancelledTimersQueuedWhenEvictionIsNotRequested() {
        final ScheduledThreadPoolExecutor timer =
                RuntimeThreads.scheduledLane(1, RuntimeThreads.daemonThreadsWithFixedName("turboism-fixture"), false);
        try {
            timer.schedule(() -> {}, 1L, TimeUnit.HOURS).cancel(false);
            assertFalse(timer.getQueue().isEmpty(), "the default policy keeps cancelled tasks queued");
        } finally {
            timer.shutdownNow();
        }
    }

    @Test
    void sanitizesNameFragmentsThatWouldBreakAThreadDump() {
        assertEquals("plugin_a-1.0", RuntimeThreads.sanitizeNameFragment("plugin a-1.0"));
        assertEquals("a_b_c", RuntimeThreads.sanitizeNameFragment("a/b\\c"));
    }

    /**
     * The production agent is bootstrap-loaded through {@code Boot-Class-Path}, so its classes
     * report a null defining loader. Publishing that null as a lane's context loader made the
     * real Cubism host fail at startup, so the resolver must always answer with a usable loader.
     */
    @Test
    void resolvesAUsableContextLoaderForABootstrapLoadedOwner() {
        final ClassLoader resolved = RuntimeThreads.resolveContextClassLoader(null);
        assertEquals(ClassLoader.getSystemClassLoader(), resolved);
        assertTrue(
                resolved.getResource("dev/turboism/runtime/exec/RuntimeThreads.class") != null,
                "the resolved loader must still see runtime classes");
    }

    @Test
    void keepsTheDefiningLoaderOfAnOrdinarilyLoadedOwner() {
        assertEquals(
                RuntimeThreadsTest.class.getClassLoader(), RuntimeThreads.contextClassLoader(RuntimeThreadsTest.class));
    }

    @Test
    void rejectsANullOwnerInsteadOfSilentlySubstitutingALoader() {
        assertThrows(NullPointerException.class, () -> RuntimeThreads.contextClassLoader(null));
    }

    @Test
    void rejectsNonPositiveLaneBounds() {
        assertThrows(
                IllegalArgumentException.class, () -> RuntimeThreads.serialLane(0, runnable -> new Thread(runnable)));
        assertThrows(
                IllegalArgumentException.class, () -> RuntimeThreads.fixedLane(0, 1, runnable -> new Thread(runnable)));
        assertThrows(
                IllegalArgumentException.class,
                () -> RuntimeThreads.scheduledLane(0, runnable -> new Thread(runnable)));
    }

    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            latch.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
