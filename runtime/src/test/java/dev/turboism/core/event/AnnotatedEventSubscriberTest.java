package dev.turboism.core.event;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.event.EventPriority;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.TurboismEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotatedEventSubscriberTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-23T00:00:00Z"),
        ZoneOffset.UTC
    );

    @Test
    void brokerInvokesAnnotatedEntrypointsInPriorityThenCanonicalOrder() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final List<String> calls = new CopyOnWriteArrayList<>();
        final CountDownLatch delivered = new CountDownLatch(3);
        final Subscriber entrypoint = new Subscriber(calls, delivered);
        broker.registerAnnotated(
            "dev.example.subscriber",
            new EntrypointSubscriberCatalog().inspect(List.of(entrypoint))
        );

        broker.publish("turboism.core", new TestEvent("value"));

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("highest", "alpha", "zeta"), calls);
        scheduler.shutdown();
    }

    private static RuntimeScheduler scheduler() {
        final List<PluginWorkBudgetEvent> diagnostics = new CopyOnWriteArrayList<>();
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 8, diagnostics::add, CLOCK),
            new NoOpSidecarDispatcher(),
            diagnostics::add
        );
    }

    public static final class Subscriber {
        private final List<String> calls;
        private final CountDownLatch delivered;

        Subscriber(final List<String> calls, final CountDownLatch delivered) {
            this.calls = calls;
            this.delivered = delivered;
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void highest(final TestEvent event) {
            record("highest");
        }

        @SubscribeEvent
        public void zeta(final TestEvent event) {
            record("zeta");
        }

        @SubscribeEvent
        public void alpha(final TestEvent event) {
            record("alpha");
        }

        private void record(final String value) {
            calls.add(value);
            delivered.countDown();
        }
    }

    public record TestEvent(String value) implements TurboismEvent {
    }

    private static final class NoOpSidecarDispatcher implements SidecarDispatcher {
        @Override
        public CompletionStage<SidecarResult> dispatch(
            final PluginTask task,
            final Runnable callback
        ) {
            return CompletableFuture.completedFuture(SidecarResult.success(""));
        }
    }
}
