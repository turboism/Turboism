package dev.turboism.ui;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeUiSchedulerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);
    private static final Duration SHORT_DELAY = Duration.ofMillis(150);

    @Test
    void runOnUiThreadReturnsImmediatelyAndSchedulesAsynchronously() throws InterruptedException {
        // Given
        RuntimeScheduler runtimeScheduler = runtimeScheduler();
        RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(runtimeScheduler, "dev.turboism.plugin.demo");
        CountDownLatch releaseWork = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);

        // When
        Registration registration = uiScheduler.runOnUiThread(() -> {
            await(releaseWork);
            completed.countDown();
        });

        // Then
        assertEquals(1, completed.getCount());
        releaseWork.countDown();
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        registration.close();
        runtimeScheduler.shutdown();
    }

    @Test
    void registrationCanCancelPendingUiWork() throws InterruptedException {
        // Given
        RuntimeScheduler runtimeScheduler = runtimeScheduler();
        RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(runtimeScheduler, "dev.turboism.plugin.demo");
        AtomicInteger executions = new AtomicInteger();

        // When
        Registration registration = uiScheduler.runOnUiThreadLater(executions::incrementAndGet, SHORT_DELAY);
        registration.close();

        // Then
        TimeUnit.MILLISECONDS.sleep(SHORT_DELAY.toMillis() * 2);
        assertEquals(0, executions.get());
        runtimeScheduler.shutdown();
    }

    private static RuntimeScheduler runtimeScheduler() {
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 4, events::add, CLOCK),
            new NoopSidecarDispatcher(),
            events::add
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class NoopSidecarDispatcher implements SidecarDispatcher {

        @Override
        public CompletionStage<SidecarResult> dispatch(PluginTask task, Runnable callback) {
            return CompletableFuture.completedFuture(SidecarResult.success(""));
        }
    }
}
