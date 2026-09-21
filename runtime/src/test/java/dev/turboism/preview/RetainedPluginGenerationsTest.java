package dev.turboism.preview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetainedPluginGenerationsTest {

    @TempDir
    Path temporary;

    private static final PluginLifecyclePolicy POLICY = new PluginLifecyclePolicy(
        1,
        8,
        Duration.ofSeconds(5),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofMillis(20),
        Duration.ofMillis(30)
    );

    @Test
    void retainedGenerationReclaimsOnlyAfterWorkerExits() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        final PreviewLog log = new PreviewLog(temporary.resolve("logs/t.log"));
        final RetainedPluginGenerations retention =
            new RetainedPluginGenerations(lane, POLICY, log);
        try {
            final CompletableFuture<Void> worker = new CompletableFuture<>();
            final AtomicBoolean reclaimed = new AtomicBoolean();
            retention.retain(new RetainedPluginGenerations.RetainedGeneration(
                "p",
                worker,
                null,
                null,
                () -> {
                    reclaimed.set(true);
                    return true;
                }
            ));
            Thread.sleep(200);
            assertFalse(
                reclaimed.get(),
                "reclaim must not run while the lifecycle worker is still in flight"
            );
            assertEquals(1, retention.retainedCount());
            worker.complete(null);
            awaitTrue(reclaimed::get);
            awaitTrue(() -> retention.retainedCount() == 0);
        } finally {
            retention.retire(lane::shutdown);
            log.close();
        }
    }

    @Test
    void retainedGenerationWaitsForAdmittedCallsToDrain() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        final PreviewLog log = new PreviewLog(temporary.resolve("logs/t2.log"));
        final RetainedPluginGenerations retention =
            new RetainedPluginGenerations(lane, POLICY, log);
        try {
            final PluginGenerationGuard guard = new PluginGenerationGuard("p");
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final GuardedServiceFixtureHolder service = new GuardedServiceFixtureHolder(
                entered, release
            );
            final dev.turboism.sdk.plugin.GuardedServiceFixture guarded =
                guard.wrapForTesting(
                    service,
                    dev.turboism.sdk.plugin.GuardedServiceFixture.class
                );
            final Thread caller = new Thread(() -> guarded.mutate("x"));
            caller.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            final AtomicBoolean reclaimed = new AtomicBoolean();
            retention.retain(new RetainedPluginGenerations.RetainedGeneration(
                "p",
                CompletableFuture.completedFuture(null),
                null,
                guard,
                () -> {
                    reclaimed.set(true);
                    return true;
                }
            ));
            Thread.sleep(200);
            assertFalse(reclaimed.get(), "disposal must wait for admitted SDK calls to drain");
            release.countDown();
            caller.join(5_000);
            awaitTrue(reclaimed::get);
        } finally {
            retention.retire(lane::shutdown);
            log.close();
        }
    }

    @Test
    void unquiescedEventOwnerBlocksReclaimUntilQuiescence() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        final PreviewLog log = new PreviewLog(temporary.resolve("logs/t3.log"));
        final RetainedPluginGenerations retention =
            new RetainedPluginGenerations(lane, POLICY, log);
        final dev.turboism.core.runtime.RuntimeScheduler scheduler = scheduler();
        try {
            final dev.turboism.core.event.RuntimeEventBroker broker =
                new dev.turboism.core.event.RuntimeEventBroker(scheduler);
            final dev.turboism.core.event.RuntimeEventBroker.Owner owner =
                broker.admit("p");
            final AtomicBoolean reclaimed = new AtomicBoolean();
            retention.retain(new RetainedPluginGenerations.RetainedGeneration(
                "p",
                CompletableFuture.completedFuture(null),
                owner,
                null,
                () -> {
                    reclaimed.set(true);
                    return true;
                }
            ));
            Thread.sleep(200);
            assertFalse(
                reclaimed.get(),
                "an owner that never began closing must stay retained"
            );
            owner.beginClosing();
            awaitTrue(reclaimed::get);
            awaitTrue(() -> retention.retainedCount() == 0);
        } finally {
            retention.retire(lane::shutdown);
            scheduler.shutdown();
            log.close();
        }
    }

    @Test
    void failedReclaimIsRetriedAndDrainCallbackFiresOnceEmpty() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        final PreviewLog log = new PreviewLog(temporary.resolve("logs/t4.log"));
        final RetainedPluginGenerations retention =
            new RetainedPluginGenerations(lane, POLICY, log);
        try {
            final AtomicInteger attempts = new AtomicInteger();
            retention.retain(new RetainedPluginGenerations.RetainedGeneration(
                "p",
                CompletableFuture.completedFuture(null),
                null,
                null,
                () -> attempts.incrementAndGet() >= 2
            ));
            awaitTrue(() -> attempts.get() >= 2);
            awaitTrue(() -> retention.retainedCount() == 0);

            final CountDownLatch drained = new CountDownLatch(1);
            retention.retire(() -> drained.countDown());
            assertTrue(drained.await(5, TimeUnit.SECONDS));
        } finally {
            lane.shutdown();
            log.close();
        }
    }

    private static void awaitTrue(
        final java.util.function.BooleanSupplier condition
    ) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition did not become true in time");
            }
            Thread.sleep(10);
        }
    }

    private static dev.turboism.core.runtime.RuntimeScheduler scheduler() {
        return new dev.turboism.core.runtime.RuntimeScheduler(
            new dev.turboism.core.runtime.DefaultWorkBudgetPolicy(),
            new dev.turboism.core.runtime.work.PluginWorkExecutorRegistry(
                1, 4, ignored -> { }, java.time.Clock.systemUTC()
            ),
            dev.turboism.core.runtime.sidecar.SidecarDispatcher.noop(),
            ignored -> { }
        );
    }

    private static final class GuardedServiceFixtureHolder
        implements dev.turboism.sdk.plugin.GuardedServiceFixture {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private GuardedServiceFixtureHolder(
            final CountDownLatch entered,
            final CountDownLatch release
        ) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public String mutate(final String value) {
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return value;
        }

        @Override
        public GuardedServiceFixtureHolder child() {
            return this;
        }

        @Override
        public void close() {
        }
    }
}
