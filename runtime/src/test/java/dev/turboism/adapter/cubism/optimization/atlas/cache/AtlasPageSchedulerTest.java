package dev.turboism.adapter.cubism.optimization.atlas.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AtlasPageSchedulerTest {

    @Test
    void serialRunsEveryPageInListOrder() {
        final List<String> order = new ArrayList<>();
        final List<Runnable> pages = List.of(() -> order.add("a"), () -> order.add("b"), () -> order.add("c"));
        AtlasPageScheduler.serial().runPages(pages);
        assertEquals(List.of("a", "b", "c"), order);
    }

    @Test
    void serialPropagatesFailureAndStops() {
        final List<String> order = new ArrayList<>();
        final IllegalStateException boom = new IllegalStateException("page failed");
        final List<Runnable> pages = List.of(
                () -> order.add("a"),
                () -> {
                    throw boom;
                },
                () -> order.add("c"));
        final IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> AtlasPageScheduler.serial().runPages(pages));
        assertEquals(boom, thrown);
        assertEquals(List.of("a"), order);
    }

    @Test
    void forPolicySelectsExpectedScheduler() {
        assertInstanceOf(
                SerialAtlasPageScheduler.class, AtlasPageScheduler.forPolicy(AtlasPageScheduler.Policy.SERIAL));
        assertInstanceOf(
                ParallelAtlasPageScheduler.class,
                AtlasPageScheduler.forPolicy(AtlasPageScheduler.Policy.PARALLEL_UNIFORM));
    }

    @Test
    void parallelRunsAllPagesConcurrentlyAndHandlesDegenerateInputs() {
        AtlasPageScheduler.parallel().runPages(null);
        AtlasPageScheduler.parallel().runPages(List.of());

        final Thread caller = Thread.currentThread();
        final Thread[] singleThread = new Thread[1];
        AtlasPageScheduler.parallel().runPages(List.of(() -> singleThread[0] = Thread.currentThread()));
        assertEquals(caller, singleThread[0]);

        final int pageCount = 4;
        final CountDownLatch entered = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger completed = new AtomicInteger();
        final ExecutorService pool = Executors.newFixedThreadPool(pageCount);
        try {
            final ParallelAtlasPageScheduler scheduler = new ParallelAtlasPageScheduler(pool);
            final List<Runnable> pages = new ArrayList<>(pageCount);
            for (int i = 0; i < pageCount; i++) {
                pages.add(() -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    completed.incrementAndGet();
                });
            }
            final Thread coordinator = new Thread(() -> scheduler.runPages(pages));
            coordinator.start();
            assertTrue(awaitLatch(entered, 5, TimeUnit.SECONDS), "At least two page tasks should run concurrently");
            release.countDown();
            coordinator.join(TimeUnit.SECONDS.toMillis(5));
            assertEquals(pageCount, completed.get());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelPropagatesFirstFailure() {
        final IllegalStateException boom = new IllegalStateException("worker boom");
        final List<Integer> ok = Collections.synchronizedList(new ArrayList<>());
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final ParallelAtlasPageScheduler scheduler = new ParallelAtlasPageScheduler(pool);
            final List<Runnable> pages = List.of(
                    () -> ok.add(0),
                    () -> {
                        throw boom;
                    },
                    () -> ok.add(2));
            final IllegalStateException thrown =
                    assertThrows(IllegalStateException.class, () -> scheduler.runPages(pages));
            assertEquals(boom, thrown);
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean awaitLatch(final CountDownLatch latch, final long timeout, final TimeUnit unit) {
        try {
            return latch.await(timeout, unit);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    void uniformHeuristicAdmitsEvenlySizedTiles() {
        assertTrue(AtlasPageScheduler.uniformTileAreas(new long[] {100, 110, 95, 105}, 4.0));
    }

    @Test
    void uniformHeuristicRejectsSingleDominatingTile() {
        // mean≈3417, tolerance 4 → bound 13667; the 20000px tile dominates and is rejected.
        assertFalse(AtlasPageScheduler.uniformTileAreas(new long[] {100, 100, 100, 100, 100, 20_000}, 4.0));
    }

    @Test
    void uniformHeuristicRejectsDegenerateInput() {
        assertFalse(AtlasPageScheduler.uniformTileAreas(new long[0], 4.0));
        assertFalse(AtlasPageScheduler.uniformTileAreas(null, 4.0));
        assertFalse(AtlasPageScheduler.uniformTileAreas(new long[] {0, 0}, 4.0));
    }
}
