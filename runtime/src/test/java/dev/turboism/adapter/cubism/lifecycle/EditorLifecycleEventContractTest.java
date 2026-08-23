package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.event.EntrypointSubscriberCatalog;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.cubism.EditorExitEvent;
import dev.turboism.sdk.event.cubism.EditorStartupEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorLifecycleEventContractTest {
    @Test
    void publishesStartupAndAcceptedExitStates() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        try {
            final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
            final EditorLifecycleCoordinator coordinator = new EditorLifecycleCoordinator();
            coordinator.attachEventBroker(broker);
            final RuntimeEventBroker.Owner owner = broker.admit("editor-events");
            final CountDownLatch completion = new CountDownLatch(6);
            final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            owner.registerAnnotated(new EntrypointSubscriberCatalog().inspect(List.of(
                new Subscriber(events, completion)
            )));
            owner.activate();

            coordinator.publishStartup("5.3.02");
            final EditorLifecycleCoordinator.ExitInvocation exit =
                coordinator.beginExit("5.3.02");
            coordinator.completeExit(exit, true, null);

            assertTrue(completion.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(
                "startup-before", "startup-on", "startup-after",
                "exit-before", "exit-on", "exit-after:true"
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

        @SubscribeEvent public void startupBefore(final EditorStartupEvent.Before event) {
            events.add("startup-before"); completion.countDown();
        }
        @SubscribeEvent public void startupOn(final EditorStartupEvent.On event) {
            events.add("startup-on"); completion.countDown();
        }
        @SubscribeEvent public void startupAfter(final EditorStartupEvent.After event) {
            events.add("startup-after"); completion.countDown();
        }
        @SubscribeEvent public void exitBefore(final EditorExitEvent.Before event) {
            events.add("exit-before"); completion.countDown();
        }
        @SubscribeEvent public void exitOn(final EditorExitEvent.On event) {
            events.add("exit-on"); completion.countDown();
        }
        @SubscribeEvent public void exitAfter(final EditorExitEvent.After event) {
            events.add("exit-after:" + event.result().accepted()); completion.countDown();
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
