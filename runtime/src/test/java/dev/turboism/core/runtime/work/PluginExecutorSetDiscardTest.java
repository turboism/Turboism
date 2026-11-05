package dev.turboism.core.runtime.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.runtime.contract.PluginTask;
import dev.turboism.test.concurrent.ManualScheduledExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A failed plugin generation is fenced and its event owner closed, so nothing its pools could
 * still deliver is admissible. Reclaiming that generation must not pay a graceful-drain grace:
 * startup latency regressed by seconds when a plugin whose event lane was still busy failed,
 * because the load path waited for the pool shutdown bound before reporting the verdict.
 */
class PluginExecutorSetDiscardTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.failed";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void discardReturnsWithoutWaitingForABusyEventLane() throws Exception {
        final PluginExecutorSet set = new PluginExecutorSet(
                PLUGIN_ID,
                PluginWorkExecutorConfiguration.of(500, 1, 1, 50.0f),
                ignored -> {},
                CLOCK,
                new ManualScheduledExecutor());
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        assertTrue(set.events()
                .submit(task("event.subscribe"), () -> {
                    running.countDown();
                    await(release);
                })
                .accepted());
        assertTrue(running.await(1, TimeUnit.SECONDS));

        final long startedNanos = System.nanoTime();
        set.discard();
        final long elapsedMillis =
                Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();

        assertTrue(elapsedMillis < 1000L, "discard must not wait for the running drain, took " + elapsedMillis + "ms");
        assertEquals(
                PluginWorkStatus.RUNTIME_UNAVAILABLE,
                set.events().submit(task("event.subscribe"), () -> {}).rejectionStatus());
        release.countDown();
    }

    @Test
    void discardIsIdempotentAndRefusesFurtherTaskWork() {
        final PluginExecutorSet set = new PluginExecutorSet(
                PLUGIN_ID,
                PluginWorkExecutorConfiguration.of(500, 1, 1, 50.0f),
                ignored -> {},
                CLOCK,
                new ManualScheduledExecutor());

        set.discard();
        set.discard();

        assertEquals(
                PluginWorkStatus.RUNTIME_UNAVAILABLE,
                set.tasks().submit(task("action.handle"), () -> {}).rejectionStatus());
    }

    private static PluginTask task(final String type) {
        return new PluginTask(type, PLUGIN_ID, "payload for " + type, "none");
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
