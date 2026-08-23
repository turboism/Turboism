package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.event.EntrypointSubscriberCatalog;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.event.CubismOperationOrigin;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.cubism.CubismOperationLifecycleEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticOperationEventContractTest {
    @Test
    void publishesBeforeChangedOnlyOnAndAlwaysAfter() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        try {
            final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
            final SemanticOperationLifecycleCoordinator coordinator =
                new SemanticOperationLifecycleCoordinator();
            coordinator.attachEventBroker(broker);
            final RuntimeEventBroker.Owner owner = broker.admit("semantic-events");
            final CountDownLatch completion = new CountDownLatch(5);
            final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            owner.registerAnnotated(new EntrypointSubscriberCatalog().inspect(List.of(
                new Subscriber(events, completion)
            )));
            owner.activate();
            final int[] state = {0};

            coordinator.runComparing(
                CubismOperation.OPEN_DOCUMENT,
                CubismOperationOrigin.HOST_UI,
                Optional.of("DocumentA"),
                () -> state[0],
                () -> state[0]++
            );
            coordinator.runComparing(
                CubismOperation.SAVE_DOCUMENT,
                CubismOperationOrigin.TURBOISM_API,
                Optional.of("DocumentA"),
                () -> state[0],
                () -> { }
            );

            assertTrue(completion.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(
                "before:OPEN_DOCUMENT", "on:OPEN_DOCUMENT", "after:OPEN_DOCUMENT:true",
                "before:SAVE_DOCUMENT", "after:SAVE_DOCUMENT:false"
            ), events);
        } finally {
            scheduler.shutdown();
        }
    }

    public static final class Subscriber {
        private final List<String> events;
        private final CountDownLatch completion;

        private Subscriber(final List<String> events, final CountDownLatch completion) {
            this.events = events;
            this.completion = completion;
        }

        @SubscribeEvent
        public void before(final CubismOperationLifecycleEvent.Before event) {
            events.add("before:" + event.operation().operation());
            completion.countDown();
        }

        @SubscribeEvent
        public void on(final CubismOperationLifecycleEvent.On event) {
            events.add("on:" + event.operation().operation());
            completion.countDown();
        }

        @SubscribeEvent
        public void after(final CubismOperationLifecycleEvent.After event) {
            events.add(
                "after:" + event.operation().operation() + ":" + event.confirmed()
            );
            completion.countDown();
        }
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 8, ignored -> { }, Clock.systemUTC()),
            new NoOpSidecarDispatcher(),
            ignored -> { }
        );
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
